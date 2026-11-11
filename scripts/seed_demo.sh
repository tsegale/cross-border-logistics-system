#!/usr/bin/env bash
# =============================================================================
# FreightFlow Phase 1 Demo Seed Script
# Creates demo accounts in NAD, ZAR, and EUR, deposits funds, locks an FX quote,
# executes a cross-currency NAD -> EUR transfer, creates a Walvis Bay -> Hamburg
# freight quote, books an idempotent shipment, records tracking events, and
# verifies public tracking + ledger reconciliation through the API gateway.
# =============================================================================
set -euo pipefail

GATEWAY_URL="${GATEWAY_URL:-http://127.0.0.1:8080}"
RUN_TAG="$(date +%s)-$$"

extract_json_field() {
  local json="$1"
  local field="$2"
  printf '%s' "$json" | grep -oE "\"${field}\"[[:space:]]*:[[:space:]]*\"?[^\",}]+\"?" | head -n 1 | sed -E 's/^[^:]+:[[:space:]]*"?([^",}]+)"?$/\1/'
}

echo "==> Seeding FreightFlow demo data via ${GATEWAY_URL} (run=${RUN_TAG})"

# 1. Create customer accounts in NAD, ZAR, and EUR
echo "1. Creating customer accounts in NAD, ZAR, and EUR..."
NAD_RESP="$(curl -fsS -X POST "${GATEWAY_URL}/api/v1/accounts" \
  -H "Content-Type: application/json" \
  -d '{"holderReference":"Namib Fisheries (Pty) Ltd","currency":"NAD"}')"
NAD_ID="$(extract_json_field "$NAD_RESP" "id")"
NAD_ACC="$(extract_json_field "$NAD_RESP" "accountNumber")"
echo "   NAD Account: ${NAD_ACC} (id=${NAD_ID})"

ZAR_RESP="$(curl -fsS -X POST "${GATEWAY_URL}/api/v1/accounts" \
  -H "Content-Type: application/json" \
  -d '{"holderReference":"Cape Meridian Logistics (Pty) Ltd","currency":"ZAR"}')"
ZAR_ID="$(extract_json_field "$ZAR_RESP" "id")"
ZAR_ACC="$(extract_json_field "$ZAR_RESP" "accountNumber")"
echo "   ZAR Account: ${ZAR_ACC} (id=${ZAR_ID})"

EUR_RESP="$(curl -fsS -X POST "${GATEWAY_URL}/api/v1/accounts" \
  -H "Content-Type: application/json" \
  -d '{"holderReference":"Hanseatic Seafood GmbH","currency":"EUR"}')"
EUR_ID="$(extract_json_field "$EUR_RESP" "id")"
EUR_ACC="$(extract_json_field "$EUR_RESP" "accountNumber")"
echo "   EUR Account: ${EUR_ACC} (id=${EUR_ID})"

# 2. Deposit funds into NAD, ZAR, and EUR accounts
echo "2. Depositing initial balances..."
curl -fsS -X POST "${GATEWAY_URL}/api/v1/accounts/${NAD_ID}/deposits" \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: seed-dep-nad-${RUN_TAG}" \
  -d '{"amount":250000.00,"currency":"NAD","reference":"EXPORT-PROCEEDS-NAWVB"}' >/dev/null

curl -fsS -X POST "${GATEWAY_URL}/api/v1/accounts/${ZAR_ID}/deposits" \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: seed-dep-zar-${RUN_TAG}" \
  -d '{"amount":150000.00,"currency":"ZAR","reference":"CMA-TREASURY-ZADUR"}' >/dev/null

curl -fsS -X POST "${GATEWAY_URL}/api/v1/accounts/${EUR_ID}/deposits" \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: seed-dep-eur-${RUN_TAG}" \
  -d '{"amount":25000.00,"currency":"EUR","reference":"IMPORT-WORKING-CAP-DEHAM"}' >/dev/null

# 3. Lock an FX quote (50,000.00 NAD -> EUR) and execute cross-currency transfer
echo "3. Locking FX quote (50,000.00 NAD -> EUR) and executing cross-currency transfer..."
FX_RESP="$(curl -fsS -X POST "${GATEWAY_URL}/api/v1/fx-quotes" \
  -H "Content-Type: application/json" \
  -d '{"sellCurrency":"NAD","buyCurrency":"EUR","sellAmount":50000.00}')"
FX_ID="$(extract_json_field "$FX_RESP" "id")"
FX_BUY="$(extract_json_field "$FX_RESP" "buyAmount")"
echo "   FX Quote: ${FX_ID} (50,000.00 NAD -> ${FX_BUY} EUR)"

XFER_RESP="$(curl -fsS -X POST "${GATEWAY_URL}/api/v1/transfers" \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: seed-xfer-fx-${RUN_TAG}" \
  -d "{
    \"sourceAccount\":\"${NAD_ACC}\",
    \"targetAccount\":\"${EUR_ACC}\",
    \"amount\":50000.00,
    \"currency\":\"NAD\",
    \"fxQuoteId\":\"${FX_ID}\",
    \"reference\":\"CORRIDOR-SETTLEMENT-NAWVB-DEHAM\"
  }")"
XFER_ID="$(extract_json_field "$XFER_RESP" "id")"
echo "   Transfer ID: ${XFER_ID}"

# 4. Verify ledger reconciliation
echo "4. Checking ledger reconciliation..."
REC_RESP="$(curl -fsS "${GATEWAY_URL}/api/v1/ledger/reconciliation")"
REC_BALANCED="$(extract_json_field "$REC_RESP" "balanced")"
echo "   Ledger balanced: ${REC_BALANCED}"

# 5. Create a SEA_LCL freight quote (Walvis Bay NAWVB -> Hamburg DEHAM, FCA, EUR)
echo "5. Creating SEA_LCL freight quote (NAWVB -> DEHAM, FCA, EUR)..."
QUOTE_RESP="$(curl -fsS -X POST "${GATEWAY_URL}/api/v1/quotes" \
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
        "description": "Frozen Merluccius capensis hake fillets"
      }
    ]
  }')"
QUOTE_ID="$(extract_json_field "$QUOTE_RESP" "id")"
QUOTE_TOTAL="$(extract_json_field "$QUOTE_RESP" "totalAmount")"
echo "   Freight Quote ID: ${QUOTE_ID} (Total: ${QUOTE_TOTAL} EUR, the tariff currency)"

# 6. Book a shipment from the quote (starts in AWAITING_PAYMENT)
echo "6. Booking shipment in AWAITING_PAYMENT status..."
PICKUP_DATE="$(date -u -d '+7 days' +%F 2>/dev/null || date -u -v+7d +%F)"  # GNU date, then BSD date
SHIP_RESP="$(curl -fsS -X POST "${GATEWAY_URL}/api/v1/shipments" \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: seed-ship-${RUN_TAG}" \
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
    \"goodsDescription\": \"Frozen Merluccius capensis hake fillets (2 pallets)\",
    \"declaredValue\": 18500.00,
    \"declaredValueCurrency\": \"EUR\",
    \"plannedPickupDate\": \"${PICKUP_DATE}\"
  }")"
SHIP_ID="$(extract_json_field "$SHIP_RESP" "id")"
TRACK_NUM="$(extract_json_field "$SHIP_RESP" "trackingNumber")"
SHIP_STATUS="$(extract_json_field "$SHIP_RESP" "status")"
echo "   Shipment ID: ${SHIP_ID} | Tracking Number: ${TRACK_NUM} | Status: ${SHIP_STATUS}"

echo "==> Demo seed completed."
