# End-to-end smoke test (`api-gateway` `:8080`)

The authoritative version is the script: every call states the status it expects and the script stops at the first
one that differs. CI runs it against the default stack after `seed_demo.sh`.

```bash
cp .env.example .env
python scripts/generate_secrets.py
docker compose up -d --build --wait
./scripts/smoke_test.sh
```

Since Phase 3 every call is made as a persona of the realm (ADR 0016), with a real Keycloak token. This page walks
through the same flow by hand.

## Prerequisites

```bash
export GATEWAY="http://127.0.0.1:8080"
export RUN_ID="$(date +%s)"
export NAMIB="0193a0e0-0000-7000-8000-00000000a001"
OPERATOR="Authorization: Bearer $(scripts/token.sh operator)"
CUSTOMER="Authorization: Bearer $(scripts/token.sh customer-namib-exports)"
OTHER_CUSTOMER="Authorization: Bearer $(scripts/token.sh customer-hanseatic-imports)"
CARRIER="Authorization: Bearer $(scripts/token.sh carrier-walvis-haulage)"
AUDITOR="Authorization: Bearer $(scripts/token.sh auditor)"
```

Tokens live 5 minutes; fetch them again if a call answers `401`.

## 1. The edge (ADR 0015, ADR 0017)

```bash
curl -s "${GATEWAY}/api/v1/accounts/FF14EUR000000001"
# 401 {"type":"https://freightflow.dev/problems/unauthenticated", ...}  WWW-Authenticate: Bearer

curl -s -H "$CUSTOMER" "${GATEWAY}/api/v1/ledger/reconciliation"
# 403 {"type":"https://freightflow.dev/problems/forbidden", ...}

curl -s -X POST -H "$OPERATOR" "${GATEWAY}/api/v1/holds"
# 404: holds are internal; only logistics-service's own token reaches them, inside the network (ADR 0019)

curl -s http://127.0.0.1:8085/actuator/health    # the gateway's management port
```

## 2. Accounts: the operator opens them, the customer sees its own

```bash
NAD=$(curl -s -X POST -H "$OPERATOR" -H "Content-Type: application/json" "${GATEWAY}/api/v1/accounts" \
  -d "{\"currency\":\"NAD\",\"organizationId\":\"${NAMIB}\"}")
EUR=$(curl -s -X POST -H "$OPERATOR" -H "Content-Type: application/json" "${GATEWAY}/api/v1/accounts" \
  -d "{\"currency\":\"EUR\",\"organizationId\":\"${NAMIB}\"}")
NAD_ID=$(echo "$NAD" | python -c 'import json,sys; print(json.load(sys.stdin)["id"])')
NAD_ACC=$(echo "$NAD" | python -c 'import json,sys; print(json.load(sys.stdin)["accountNumber"])')
EUR_ID=$(echo "$EUR" | python -c 'import json,sys; print(json.load(sys.stdin)["id"])')
EUR_ACC=$(echo "$EUR" | python -c 'import json,sys; print(json.load(sys.stdin)["accountNumber"])')

curl -s -H "$CUSTOMER" "${GATEWAY}/api/v1/accounts/${NAD_ACC}"          # 200, ownerOrganizationId = NAMIB
curl -s -H "$OTHER_CUSTOMER" "${GATEWAY}/api/v1/accounts/${NAD_ACC}"    # 404 account-not-found (ADR 0018)
```

## 3. Deposits (operator), FX and a transfer (customer)

```bash
curl -s -X POST -H "$OPERATOR" -H "Content-Type: application/json" -H "Idempotency-Key: dep-${RUN_ID}" \
  "${GATEWAY}/api/v1/accounts/${NAD_ID}/deposits" -d '{"amount":100000.00,"currency":"NAD","reference":"DEP"}'
curl -s -X POST -H "$OPERATOR" -H "Content-Type: application/json" -H "Idempotency-Key: dep-eur-${RUN_ID}" \
  "${GATEWAY}/api/v1/accounts/${EUR_ID}/deposits" -d '{"amount":5000.00,"currency":"EUR","reference":"DEP"}'

QUOTE=$(curl -s -X POST -H "$CUSTOMER" -H "Content-Type: application/json" "${GATEWAY}/api/v1/fx-quotes" \
  -d '{"sellCurrency":"NAD","buyCurrency":"EUR","sellAmount":20000.00}')      # buyAmount 995.00
FX_ID=$(echo "$QUOTE" | python -c 'import json,sys; print(json.load(sys.stdin)["id"])')
curl -s -X POST -H "$CUSTOMER" -H "Content-Type: application/json" -H "Idempotency-Key: fx-${RUN_ID}" \
  "${GATEWAY}/api/v1/transfers" \
  -d "{\"sourceAccount\":\"${NAD_ACC}\",\"targetAccount\":\"${EUR_ACC}\",\"amount\":20000.00,\"currency\":\"NAD\",\"fxQuoteId\":\"${FX_ID}\",\"reference\":\"FX\"}"

curl -s -H "$AUDITOR" "${GATEWAY}/api/v1/ledger/reconciliation"           # balanced: true
```

The same key from another organisation is another request, never a replay of this one (ADR 0020).

## 4. Book, get paid, pick up

```bash
PICKUP=$(date -u -d '+7 days' +%F 2>/dev/null || date -u -v+7d +%F)
QUOTE_ID=$(curl -s -X POST -H "$CUSTOMER" -H "Content-Type: application/json" "${GATEWAY}/api/v1/quotes" \
  -d '{"origin":"NAWVB","destination":"DEHAM","mode":"SEA_LCL","incoterm":"FCA","payerRole":"CONSIGNEE",
       "packages":[{"lengthCm":120,"widthCm":100,"heightCm":150,"weightKg":500,"quantity":2}]}' \
  | python -c 'import json,sys; print(json.load(sys.stdin)["id"])')                          # 524.75 EUR
SHIP=$(curl -s -X POST -H "$CUSTOMER" -H "Content-Type: application/json" -H "Idempotency-Key: ship-${RUN_ID}" \
  "${GATEWAY}/api/v1/shipments" \
  -d "{\"quoteId\":\"${QUOTE_ID}\",\"shipper\":{\"name\":\"Namib Fisheries\",\"address\":\"Walvis Bay\",\"contact\":\"x\"},
       \"consignee\":{\"name\":\"Hanseatic Seafood\",\"address\":\"Hamburg\",\"contact\":\"y\"},
       \"payerAccountNumber\":\"${EUR_ACC}\",\"goodsDescription\":\"Frozen hake\",\"declaredValue\":18500.00,
       \"plannedPickupDate\":\"${PICKUP}\"}")
SHIP_ID=$(echo "$SHIP" | python -c 'import json,sys; print(json.load(sys.stdin)["id"])')
# 201 AWAITING_PAYMENT; a moment later the relay authorises the hold with logistics' own token: BOOKED
curl -s -H "$CUSTOMER" "${GATEWAY}/api/v1/shipments/${SHIP_ID}"         # status BOOKED, payment AUTHORISED
curl -s -H "$OTHER_CUSTOMER" "${GATEWAY}/api/v1/shipments/${SHIP_ID}"   # 404 shipment-not-found

# Only the assigned carrier reports tracking events (ADR 0018)
curl -s -X PUT -H "$OPERATOR" -H "Content-Type: application/json" \
  "${GATEWAY}/api/v1/shipments/${SHIP_ID}/carrier" -d '{"carrierId":"carrier-walvis-haulage"}'
curl -s -X POST -H "$CARRIER" -H "Content-Type: application/json" \
  "${GATEWAY}/api/v1/shipments/${SHIP_ID}/tracking-events" \
  -d '{"carrierEventId":"VSL-1","eventType":"DEPARTED","targetStatus":"IN_TRANSIT","location":"NAWVB",
       "description":"Vessel departed","occurredAt":"2026-04-21T06:00:00Z"}'
# APPLIED, skippedStatuses [PICKED_UP, AT_ORIGIN_PORT]; the hold is captured

curl -s -H "$AUDITOR" "${GATEWAY}/api/v1/shipments/${SHIP_ID}"          # audit.bookedBy: who booked
TRACK_NO=$(echo "$SHIP" | python -c 'import json,sys; print(json.load(sys.stdin)["trackingNumber"])')
curl -s "${GATEWAY}/api/v1/shipments/track/${TRACK_NO}"                 # public, no token, no customer data
```

The script also covers idempotent replays, payload mismatches, reversals, a cancellation that releases its hold, a
payer that cannot pay (`PAYMENT_FAILED`), and every refusal above.
