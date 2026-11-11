#!/usr/bin/env bash
# =============================================================================
# FreightFlow end-to-end smoke test through the API gateway (docs/smoke-test.md).
# Every call states the HTTP status it expects; the script stops at the first one
# that differs and prints the response. Needs curl and python3 (or python).
#
#   docker compose up -d --build --wait && ./scripts/smoke_test.sh
# =============================================================================
set -euo pipefail

GATEWAY="${GATEWAY_URL:-http://127.0.0.1:8080}"
RUN_ID="$(date +%s)-$$"
PYTHON=""
for candidate in python3 python; do
  # Windows ships a python3 alias that only opens the Store: check the interpreter really runs.
  if command -v "$candidate" >/dev/null 2>&1 && "$candidate" -c "" >/dev/null 2>&1; then
    PYTHON="$candidate"
    break
  fi
done
if [[ -z "$PYTHON" ]]; then
  echo "smoke_test: python3 is required to read JSON responses" >&2
  exit 2
fi

STEP=0
BODY=""
HEADERS=""

# call <expected status> <method> <path> [json body] [extra header ...]
call() {
  local expected="$1" method="$2" path="$3" data="${4:-}"
  shift 4 || shift $#
  local args=(-sS -X "$method" "${GATEWAY}${path}" -o "$TMP_BODY" -D "$TMP_HEADERS" -w '%{http_code}')
  if [[ -n "$data" ]]; then
    args+=(-H 'Content-Type: application/json' --data "$data")
  fi
  for header in "$@"; do
    args+=(-H "$header")
  done
  STEP=$((STEP + 1))
  local status
  status="$(curl "${args[@]}")"
  BODY="$(cat "$TMP_BODY")"
  HEADERS="$(cat "$TMP_HEADERS")"
  if [[ "$status" != "$expected" ]]; then
    echo "FAIL step ${STEP}: ${method} ${path} returned ${status}, expected ${expected}" >&2
    echo "$BODY" >&2
    exit 1
  fi
  echo "ok   ${STEP}: ${method} ${path} -> ${status}"
}

# field <json path like .a.b[0].c> : reads from the last response body
field() {
  "$PYTHON" -c '
import decimal, json, re, sys
value = json.loads(sys.argv[1], parse_float=decimal.Decimal)
try:
    for part in re.findall(r"\.([A-Za-z_][A-Za-z0-9_]*)|\[(\d+)\]", sys.argv[2]):
        value = value[part[0]] if part[0] else value[int(part[1])]
except (KeyError, IndexError, TypeError):
    value = None
print("" if value is None else (json.dumps(value, default=str) if isinstance(value, (dict, list)) else value))
' "$BODY" "$1"
}

# expect <json path> <value> : the last response's field must equal value
expect() {
  local actual
  actual="$(field "$1")"
  if [[ "$actual" != "$2" ]]; then
    echo "FAIL step ${STEP}: $1 is '${actual}', expected '$2'" >&2
    echo "$BODY" >&2
    exit 1
  fi
}

# wait_for <path> <json path> <value> : polls GET <path> (every 0.5 s, up to 30 s) until the field has the value.
# The payment saga is asynchronous (ADR 0012): a booking is paid by the relay a moment later.
wait_for() {
  local path="$1" json_path="$2" wanted="$3" actual=""
  for _ in $(seq 1 60); do
    BODY="$(curl -sS "${GATEWAY}${path}")"
    actual="$(field "$json_path")"
    if [[ "$actual" == "$wanted" ]]; then
      STEP=$((STEP + 1))
      echo "ok   ${STEP}: GET ${path} -> ${json_path} = ${wanted}"
      return 0
    fi
    sleep 0.5
  done
  echo "FAIL: ${path} ${json_path} stayed '${actual}', expected '${wanted}' within 30 s" >&2
  echo "$BODY" >&2
  exit 1
}

# expect_header <name> <value> : the last response must carry the header with this value
expect_header() {
  if ! grep -qi "^$1: $2"$'\r'"\?$" <<<"$HEADERS"; then
    echo "FAIL step ${STEP}: header $1 is not '$2'" >&2
    echo "$HEADERS" >&2
    exit 1
  fi
}

TMP_BODY="$(mktemp)"
TMP_HEADERS="$(mktemp)"
trap 'rm -f "$TMP_BODY" "$TMP_HEADERS"' EXIT

echo "==> FreightFlow smoke test against ${GATEWAY} (run ${RUN_ID})"

# ---------------------------------------------------------------- edge (ADR 0015)
MANAGEMENT="${GATEWAY_MANAGEMENT_URL:-http://127.0.0.1:8085}"
curl -fsS "${MANAGEMENT}/actuator/health/readiness" >/dev/null
STEP=$((STEP + 1)); echo "ok   ${STEP}: gateway readiness on the management port"
call 200 GET /docs/banking/v3/api-docs
expect_header X-Request-Id "[0-9a-f-]\{36\}"
call 200 GET /docs/logistics/v3/api-docs
# Internal endpoints are not reachable through the gateway.
call 404 GET /actuator/health
expect .type https://freightflow.dev/problems/not-found
call 404 POST /api/v1/holds '{"payerAccount":"FF14EUR000000001","amount":1.00,"currency":"EUR","reference":"x"}'   "Idempotency-Key: smoke-internal-${RUN_ID}"
expect .type https://freightflow.dev/problems/not-found
call 404 GET /api/v1/payment-commands

# ---------------------------------------------------------------- accounts
call 201 POST /api/v1/accounts "{\"holderReference\":\"SMOKE-NAD-${RUN_ID}\",\"currency\":\"NAD\"}"
NAD_ID="$(field .id)"; NAD_ACC="$(field .accountNumber)"
call 201 POST /api/v1/accounts "{\"holderReference\":\"SMOKE-EUR-${RUN_ID}\",\"currency\":\"EUR\"}"
EUR_ID="$(field .id)"; EUR_ACC="$(field .accountNumber)"
call 200 GET "/api/v1/accounts/${NAD_ACC}"
expect .currency NAD
call 400 GET /api/v1/accounts/FF00NAD000001000
expect .type https://freightflow.dev/problems/invalid-account-number

# ---------------------------------------------------------------- deposits, FX, transfers, reversals
DEPOSIT="{\"amount\":100000.00,\"currency\":\"NAD\",\"reference\":\"SMOKE-DEP-${RUN_ID}\"}"
call 201 POST "/api/v1/accounts/${NAD_ID}/deposits" "$DEPOSIT" "Idempotency-Key: smoke-dep-${RUN_ID}"
DEPOSIT_ENTRY="$(field .id)"
call 201 POST "/api/v1/accounts/${NAD_ID}/deposits" "$DEPOSIT" "Idempotency-Key: smoke-dep-${RUN_ID}"
expect .id "$DEPOSIT_ENTRY"
call 422 POST "/api/v1/accounts/${NAD_ID}/deposits" "{\"amount\":1.00,\"currency\":\"NAD\",\"reference\":\"OTHER\"}" \
  "Idempotency-Key: smoke-dep-${RUN_ID}"
expect .type https://freightflow.dev/problems/idempotency-key-payload-mismatch
call 201 POST "/api/v1/accounts/${EUR_ID}/deposits" "{\"amount\":5000.00,\"currency\":\"EUR\",\"reference\":\"SMOKE-DEP-EUR\"}" \
  "Idempotency-Key: smoke-dep-eur-${RUN_ID}"

call 200 GET "/api/v1/fx-rates?base=EUR"
expect .base EUR

call 201 POST /api/v1/fx-quotes '{"sellCurrency":"NAD","buyCurrency":"EUR","sellAmount":20000.00}'
FX_ID="$(field .id)"
expect .buyAmount 995.00
call 201 POST /api/v1/transfers \
  "{\"sourceAccount\":\"${NAD_ACC}\",\"targetAccount\":\"${EUR_ACC}\",\"amount\":20000.00,\"currency\":\"NAD\",\"fxQuoteId\":\"${FX_ID}\",\"reference\":\"SMOKE-FX-${RUN_ID}\"}" \
  "Idempotency-Key: smoke-fx-${RUN_ID}"
JE_ID="$(field .journalEntryId)"
call 200 GET "/api/v1/journal-entries/${JE_ID}"
call 201 POST "/api/v1/journal-entries/${JE_ID}/reversals" '{"reason":"Smoke test correction"}' "Idempotency-Key: smoke-rev-${RUN_ID}"
expect .reversesEntryId "$JE_ID"
call 409 POST "/api/v1/journal-entries/${JE_ID}/reversals" '{"reason":"Again"}' "Idempotency-Key: smoke-rev2-${RUN_ID}"
expect .type https://freightflow.dev/problems/journal-entry-already-reversed
call 422 POST /api/v1/transfers \
  "{\"sourceAccount\":\"${EUR_ACC}\",\"targetAccount\":\"${NAD_ACC}\",\"amount\":10.00,\"currency\":\"EUR\",\"reference\":\"NO-QUOTE\"}" \
  "Idempotency-Key: smoke-noquote-${RUN_ID}"

call 200 GET "/api/v1/accounts/${NAD_ID}/postings?size=2"
call 200 GET /api/v1/ledger/reconciliation
expect .balanced True

# ---------------------------------------------------------------- quotes
PACKAGES='[{"lengthCm":120,"widthCm":100,"heightCm":150,"weightKg":500,"quantity":2,"description":"Frozen hake"}]'
call 201 POST /api/v1/quotes \
  "{\"origin\":\"NAWVB\",\"destination\":\"DEHAM\",\"mode\":\"SEA_LCL\",\"incoterm\":\"FCA\",\"payerRole\":\"CONSIGNEE\",\"packages\":${PACKAGES},\"indicativeCurrency\":\"NAD\"}"
QUOTE_ID="$(field .id)"
expect .currency EUR
expect .totalAmount 524.75
expect .indicative.currency NAD
call 422 POST /api/v1/quotes \
  "{\"origin\":\"NAWVB\",\"destination\":\"DEHAM\",\"mode\":\"SEA_LCL\",\"incoterm\":\"EXW\",\"payerRole\":\"SHIPPER\",\"packages\":${PACKAGES}}"
expect .type https://freightflow.dev/problems/nothing-to-quote

# ---------------------------------------------------------------- shipments and tracking
SHIPMENT="{\"quoteId\":\"${QUOTE_ID}\",\"shipper\":{\"name\":\"Namib Fisheries\",\"address\":\"Walvis Bay\",\"contact\":\"exports@example.na\"},\"consignee\":{\"name\":\"Hanseatic Seafood\",\"address\":\"Hamburg\",\"contact\":\"imports@example.de\"},\"payerAccountNumber\":\"${EUR_ACC}\",\"goodsDescription\":\"Frozen hake fillets\",\"declaredValue\":18500.00,\"declaredValueCurrency\":\"EUR\"}"
call 201 POST /api/v1/shipments "$SHIPMENT" "Idempotency-Key: smoke-ship-${RUN_ID}"
SHIP_ID="$(field .id)"; TRACK_NO="$(field .trackingNumber)"
expect .status AWAITING_PAYMENT
expect_header ETag '"0"'
call 201 POST /api/v1/shipments "$SHIPMENT" "Idempotency-Key: smoke-ship-${RUN_ID}"
expect .id "$SHIP_ID"
expect_header Idempotent-Replayed true

event() {
  echo "{\"carrierEventId\":\"$1\",\"eventType\":\"SCAN\",\"targetStatus\":\"$2\",\"location\":\"NAWVB\",\"description\":\"Smoke $2\",\"occurredAt\":\"$3\"}"
}

# ---------------------------------------------------------------- payment saga (ADR 0011, 0012)
# 1. The relay authorises a hold: the shipment is BOOKED and the payer's available balance drops by 524.75.
wait_for "/api/v1/shipments/${SHIP_ID}" .status BOOKED
expect .payment.status AUTHORISED
call 200 GET "/api/v1/accounts/${EUR_ACC}"
expect .ledgerBalance 5000.00
expect .availableBalance 4475.25
# Only the payment books a shipment: a carrier event to BOOKED is stored but rejected.
call 201 POST "/api/v1/shipments/${SHIP_ID}/tracking-events" "$(event SMOKE-0 BOOKED 2026-04-18T08:00:00Z)"
expect .outcome REJECTED

# 2. The pickup scan is missed: the vessel departure skips two milestones, applies, and captures the hold.
call 201 POST "/api/v1/shipments/${SHIP_ID}/tracking-events" "$(event SMOKE-1 IN_TRANSIT 2026-04-21T06:00:00Z)"
expect .outcome APPLIED
expect .skippedStatuses '["PICKED_UP", "AT_ORIGIN_PORT"]'
call 200 POST "/api/v1/shipments/${SHIP_ID}/tracking-events" "$(event SMOKE-1 IN_TRANSIT 2026-04-21T06:00:00Z)"
expect .duplicateIgnored True
call 201 POST "/api/v1/shipments/${SHIP_ID}/tracking-events" "$(event SMOKE-LATE AT_ORIGIN_PORT 2026-04-20T08:30:00Z)"
expect .outcome OUT_OF_ORDER
expect .currentShipmentStatus IN_TRANSIT
wait_for "/api/v1/shipments/${SHIP_ID}" .payment.status CAPTURED
call 200 GET "/api/v1/accounts/${EUR_ACC}"
expect .ledgerBalance 4475.25
expect .availableBalance 4475.25

# 3. A second shipment is booked and cancelled before pickup: its hold is released.
call 201 POST /api/v1/quotes   "{\"origin\":\"NAWVB\",\"destination\":\"DEHAM\",\"mode\":\"SEA_LCL\",\"incoterm\":\"FCA\",\"payerRole\":\"CONSIGNEE\",\"packages\":${PACKAGES}}"
QUOTE2_ID="$(field .id)"
call 201 POST /api/v1/shipments "${SHIPMENT/${QUOTE_ID}/${QUOTE2_ID}}" "Idempotency-Key: smoke-ship2-${RUN_ID}"
SHIP2_ID="$(field .id)"
wait_for "/api/v1/shipments/${SHIP2_ID}" .payment.status AUTHORISED
call 200 GET "/api/v1/accounts/${EUR_ACC}"
expect .availableBalance 3950.50
call 200 POST "/api/v1/shipments/${SHIP2_ID}/cancellation" '{"reason":"Smoke test cancellation"}'
expect .status CANCELLED
wait_for "/api/v1/shipments/${SHIP2_ID}" .payment.status RELEASED
call 200 GET "/api/v1/accounts/${EUR_ACC}"
expect .availableBalance 4475.25

# 4. A shipment whose payer cannot cover the freight fails its payment and is never booked.
call 201 POST /api/v1/accounts "{\"holderReference\":\"SMOKE-POOR-${RUN_ID}\",\"currency\":\"EUR\"}"
POOR_ACC="$(field .accountNumber)"
call 201 POST /api/v1/quotes   "{\"origin\":\"NAWVB\",\"destination\":\"DEHAM\",\"mode\":\"SEA_LCL\",\"incoterm\":\"FCA\",\"payerRole\":\"CONSIGNEE\",\"packages\":${PACKAGES}}"
QUOTE3_ID="$(field .id)"
POOR_SHIPMENT="${SHIPMENT/${QUOTE_ID}/${QUOTE3_ID}}"
call 201 POST /api/v1/shipments "${POOR_SHIPMENT/${EUR_ACC}/${POOR_ACC}}" "Idempotency-Key: smoke-ship3-${RUN_ID}"
SHIP3_ID="$(field .id)"
wait_for "/api/v1/shipments/${SHIP3_ID}" .status PAYMENT_FAILED
expect .payment.failureType https://freightflow.dev/problems/insufficient-funds

call 200 GET /api/v1/ledger/reconciliation
expect .balanced True

call 200 GET "/api/v1/shipments/track/${TRACK_NO}"
expect .status IN_TRANSIT
expect .shipper ""
call 400 GET "/api/v1/shipments/track/FF-00000000-Z"
expect .type https://freightflow.dev/problems/invalid-tracking-number
call 200 GET "/api/v1/shipments?size=5"

echo "==> Smoke test passed: ${STEP} calls"
