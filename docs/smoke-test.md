# Phase 1 End-to-End Smoke Test (`api-gateway` `:8080`)

This walkthrough verifies every Phase 1 endpoint across `banking-service` and `logistics-service` through `api-gateway` (`http://127.0.0.1:8080`).

## Prerequisites

```bash
docker compose up -d --wait --build
export GATEWAY="http://127.0.0.1:8080"
export RUN_ID="$(date +%s)"

json_field() {
  printf '%s' "$1" | grep -oE "\"$2\"[[:space:]]*:[[:space:]]*\"?[^\",}]+\"?" | head -n 1 | sed -E 's/^[^:]+:[[:space:]]*"?([^",}]+)"?$/\1/'
}
```

---

## 1. Gateway Health & OpenAPI Docs

```bash
curl -fsS "${GATEWAY}/actuator/health"
# Expected: {"groups":["liveness","readiness"],"status":"UP"}

curl -fsS -o /dev/null -w "%{http_code}\n" "${GATEWAY}/docs/banking/v3/api-docs"
# Expected: 200

curl -fsS -o /dev/null -w "%{http_code}\n" "${GATEWAY}/docs/logistics/v3/api-docs"
# Expected: 200
```

---

## 2. Accounts & ISO 7064 MOD 97-10 Check Digits (`banking-service`)

### 2.1 Open NAD, ZAR, and EUR Customer Accounts

```bash
NAD_RESP=$(curl -fsS -X POST "${GATEWAY}/api/v1/accounts" \
  -H "Content-Type: application/json" \
  -d "{\"holderReference\":\"CUST-NAM-WALVIS-${RUN_ID}\",\"currency\":\"NAD\"}")
echo "$NAD_RESP"
# Expected 201 Created:
# {
#   "id": "019...",
#   "accountNumber": "FF..NAD.........",
#   "holderReference": "CUST-NAM-WALVIS-...",
#   "type": "CUSTOMER",
#   "currency": "NAD",
#   "status": "ACTIVE",
#   "balance": 0.00,
#   ...
# }

EUR_RESP=$(curl -fsS -X POST "${GATEWAY}/api/v1/accounts" \
  -H "Content-Type: application/json" \
  -d "{\"holderReference\":\"CUST-DEU-HAMBURG-${RUN_ID}\",\"currency\":\"EUR\"}")
echo "$EUR_RESP"
```

### 2.2 Fetch Account by Account Number & Reject Mistyped Check Digits

```bash
NAD_ACC=$(json_field "$NAD_RESP" "accountNumber")
NAD_ID=$(json_field "$NAD_RESP" "id")
EUR_ACC=$(json_field "$EUR_RESP" "accountNumber")
EUR_ID=$(json_field "$EUR_RESP" "id")

curl -fsS "${GATEWAY}/api/v1/accounts/${NAD_ACC}"
# Expected 200 OK with matching account details

# Corrupt the check digits (FF00...) -> 400 invalid-account-number
curl -s -X GET "${GATEWAY}/api/v1/accounts/FF00NAD000001000"
# Expected 400 Bad Request:
# {
#   "type": "https://freightflow.dev/problems/invalid-account-number",
#   "title": "Invalid account number",
#   "status": 400,
#   "detail": "...",
#   "accountNumber": "FF00NAD000001000"
# }
```

---

## 3. Idempotent Deposits, Transfers, FX Quotes & Reversals

### 3.1 Deposit Funds from `CLEARING`

```bash
curl -fsS -X POST "${GATEWAY}/api/v1/accounts/${NAD_ID}/deposits" \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: smoke-dep-nad-${RUN_ID}" \
  -d '{"amount":100000.00,"currency":"NAD","reference":"DEP-WINDHOEK-001"}'
# Expected 201 Created: balanced 2-posting DEPOSIT journal entry (CLEARING-NAD -> customer)
```

### 3.2 Lock an FX Quote (`NAD -> EUR`) and Execute a Cross-Currency Transfer

```bash
FX_RESP=$(curl -fsS -X POST "${GATEWAY}/api/v1/fx-quotes" \
  -H "Content-Type: application/json" \
  -d '{"sellCurrency":"NAD","buyCurrency":"EUR","sellAmount":20000.00}')
echo "$FX_RESP"
# Expected 201 Created with locked rate, buyAmount, and expiresAt

FX_ID=$(json_field "$FX_RESP" "id")

XFER_RESP=$(curl -fsS -X POST "${GATEWAY}/api/v1/transfers" \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: smoke-xfer-fx-${RUN_ID}" \
  -d "{
    \"sourceAccount\":\"${NAD_ACC}\",
    \"targetAccount\":\"${EUR_ACC}\",
    \"amount\":20000.00,
    \"currency\":\"NAD\",
    \"fxQuoteId\":\"${FX_ID}\",
    \"reference\":\"INV-NAWVB-DEHAM-001\"
  }")
echo "$XFER_RESP"
# Expected 201 Created: 4-posting FX_TRANSFER journal entry via FX_POSITION-NAD and FX_POSITION-EUR

XFER_ID=$(json_field "$XFER_RESP" "id")
curl -fsS "${GATEWAY}/api/v1/transfers/${XFER_ID}"
# Expected 200 OK
```

### 3.3 Fetch Journal Entry & Reverse It Once

```bash
JE_ID=$(json_field "$XFER_RESP" "journalEntryId")

curl -fsS "${GATEWAY}/api/v1/journal-entries/${JE_ID}"
# Expected 200 OK showing all 4 postings summing to 0.00 per currency

curl -fsS -X POST "${GATEWAY}/api/v1/journal-entries/${JE_ID}/reversals" \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: smoke-rev-${RUN_ID}" \
  -d '{"reason":"Customer amended invoice amount"}'
# Expected 201 Created: compensating REVERSAL journal entry with reversesEntryId = JE_ID
```

### 3.4 Keyset-Paginated Statement & Ledger Reconciliation

```bash
curl -fsS "${GATEWAY}/api/v1/accounts/${NAD_ID}/postings?size=2"
# Expected 200 OK with items[0..1], runningBalance on each item, and nextCursor

curl -fsS "${GATEWAY}/api/v1/ledger/reconciliation"
# Expected 200 OK:
# {
#   "checkedAt": "2026-...",
#   "accountsChecked": ...,
#   "balanced": true,
#   "discrepancies": [],
#   "trialBalances": [...]
# }
```

---

## 4. Freight Quotes, Shipments & Out-of-Order Tracking (`logistics-service`)

### 4.1 Create a Freight Quote (`SEA_LCL` and `AIR`)

```bash
QUOTE_RESP=$(curl -fsS -X POST "${GATEWAY}/api/v1/quotes" \
  -H "Content-Type: application/json" \
  -d '{
    "origin": "NAWVB",
    "destination": "DEHAM",
    "mode": "SEA_LCL",
    "incoterm": "FCA",
    "payerRole": "CONSIGNEE",
    "packages": [
      {
        "lengthCm": 120,
        "widthCm": 100,
        "heightCm": 150,
        "weightKg": 500,
        "quantity": 2,
        "description": "Frozen hake fillets"
      }
    ]
  }')
echo "$QUOTE_RESP"
# Expected 201 Created:
# volumeCbm = 3.6, actualWeightKg = 1000.0, chargeableUnits = 3.6 (WM_TON),
# FCA asked by the consignee: main carriage, destination handling and import clearance
# baseFreightCharge = 342.00, fuelSurcharge = 42.75, destinationHandlingCharge = 85.00,
# importClearanceCharge = 55.00, totalAmount = 524.75 EUR

QUOTE_ID=$(json_field "$QUOTE_RESP" "id")
curl -fsS "${GATEWAY}/api/v1/quotes/${QUOTE_ID}"
```

### 4.2 Book an Idempotent Shipment (`AWAITING_PAYMENT` + `ETag: "0"`)

```bash
SHIP_BODY=$(curl -fsS -X POST "${GATEWAY}/api/v1/shipments" \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: smoke-ship-${RUN_ID}" \
  -d "{
    \"quoteId\": \"${QUOTE_ID}\",
    \"shipper\": {
      \"name\": \"Namib Fisheries (Pty) Ltd\",
      \"address\": \"Ben Amathila Ave 14, Walvis Bay, Namibia\",
      \"contact\": \"exports@namibfisheries.na\"
    },
    \"consignee\": {
      \"name\": \"Hanseatic Seafood GmbH\",
      \"address\": \"Am Sandtorkai 48, 20457 Hamburg, Germany\",
      \"contact\": \"imports@hanseatic-seafood.de\"
    },
    \"payerAccountNumber\": \"${EUR_ACC}\",
    \"goodsDescription\": \"Frozen hake fillets in 20kg master cartons\",
    \"declaredValue\": 18500.00,
    \"declaredValueCurrency\": \"EUR\"
  }")
echo "$SHIP_BODY"
# Expected 201 Created, ETag: "0", status: "AWAITING_PAYMENT", trackingNumber: "FF-........-."
```

### 4.3 Record In-Order, Duplicate, and Out-of-Order Tracking Events

```bash
SHIP_ID=$(json_field "$SHIP_BODY" "id")
TRACK_NO=$(json_field "$SHIP_BODY" "trackingNumber")

# 1. Advance AWAITING_PAYMENT -> BOOKED -> PICKED_UP
curl -fsS -X POST "${GATEWAY}/api/v1/shipments/${SHIP_ID}/tracking-events" \
  -H "Content-Type: application/json" \
  -H 'If-Match: "0"' \
  -d '{
    "carrierEventId": "SMOKE-EVT-1",
    "eventType": "PAYMENT_CONFIRMED",
    "targetStatus": "BOOKED",
    "location": "NAWVB",
    "description": "Booking confirmed at Walvis Bay",
    "occurredAt": "2026-04-18T09:00:00Z"
  }'

curl -fsS -X POST "${GATEWAY}/api/v1/shipments/${SHIP_ID}/tracking-events" \
  -H "Content-Type: application/json" \
  -d '{
    "carrierEventId": "SMOKE-EVT-2",
    "eventType": "CARGO_PICKED_UP",
    "targetStatus": "PICKED_UP",
    "location": "NAWVB",
    "description": "Picked up from Walvis Bay cold store",
    "occurredAt": "2026-04-19T08:00:00Z"
  }'

# 2. Out-of-order older event (occurredAt 2026-04-18T12:00:00Z < latestEventAt 2026-04-19T08:00:00Z)
# Stored in history with appliedToStatus = false; currentShipmentStatus remains PICKED_UP
curl -fsS -X POST "${GATEWAY}/api/v1/shipments/${SHIP_ID}/tracking-events" \
  -H "Content-Type: application/json" \
  -d '{
    "carrierEventId": "SMOKE-EVT-LATE",
    "eventType": "DOCUMENTS_VERIFIED",
    "targetStatus": "BOOKED",
    "location": "NAWVB",
    "description": "Late export documentation scan",
    "occurredAt": "2026-04-18T12:00:00Z"
  }'
```

### 4.4 Public Tracking View & Keyset Listing

```bash
curl -fsS "${GATEWAY}/api/v1/shipments/track/${TRACK_NO}"
# Expected 200 OK: chronological events with occurredAtUtc + occurredAtLocal (Africa/Windhoek +02:00),
# and no shipper/consignee/payerAccountNumber/declaredValue fields.

curl -fsS "${GATEWAY}/api/v1/shipments?size=5"
# Expected 200 OK: keyset-paginated ShipmentPageResponse
```
