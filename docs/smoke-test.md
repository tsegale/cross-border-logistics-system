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

## 3. Deposits (four eyes), FX and a transfer (customer)

A deposit is requested by one operator and posted only when a different operator approves it (ADR 0021).

```bash
CHECKER="Authorization: Bearer $(scripts/token.sh second-operator)"
REQ=$(curl -s -X POST -H "$OPERATOR" -H "Content-Type: application/json" -H "Idempotency-Key: dep-${RUN_ID}" \
  "${GATEWAY}/api/v1/approvals" \
  -d "{\"kind\":\"DEPOSIT\",\"account\":\"${NAD_ACC}\",\"amount\":100000.00,\"currency\":\"NAD\",\"reference\":\"DEP\"}")
APPROVAL=$(echo "$REQ" | python -c 'import json,sys; print(json.load(sys.stdin)["id"])')   # status PENDING
curl -s -X POST -H "$OPERATOR" -H "Idempotency-Key: self-${RUN_ID}" \
  "${GATEWAY}/api/v1/approvals/${APPROVAL}/approval"          # 403 four-eyes-required
curl -s -X POST -H "$CHECKER" -H "Idempotency-Key: approve-${RUN_ID}" \
  "${GATEWAY}/api/v1/approvals/${APPROVAL}/approval"          # 200 APPROVED, postedEntryId
# The EUR account is funded the same way.

QUOTE=$(curl -s -X POST -H "$CUSTOMER" -H "Content-Type: application/json" "${GATEWAY}/api/v1/fx-quotes" \
  -d '{"sellCurrency":"NAD","buyCurrency":"EUR","sellAmount":20000.00}')      # buyAmount = 20000 x rate (the day's ECB rate, ADR 0034)
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

## 5. Emails in German, once (ADR 0026, ADR 0027)

Hanseatic Imports (Hamburg) wants its emails in German. Mailpit is the stack's SMTP server; with `--profile debug` its
inbox is at <http://127.0.0.1:8025> and its API at `/api/v1/...`. Without it the script reads the API inside the network
(`docker compose exec mailpit wget ...`), since nothing but the gateway and Keycloak is published.

```bash
HANSEATIC_CUSTOMER="Authorization: Bearer $(python scripts/keycloak_token.py customer-hanseatic-imports)"
curl -s -X PUT -H "$HANSEATIC_CUSTOMER" -H "Content-Type: application/json"   "${GATEWAY}/api/v1/notification-settings"   -d '{"language":"de","timeZone":"Europe/Berlin","recipients":["ops@hanseatic-imports.example"],
       "kinds":["shipment-booked","shipment-status","payment-failed","payment-captured","payment-released"]}'
# ... an operator opens and (four eyes) funds a EUR account for Hanseatic; the customer books on it ...
curl -s "http://127.0.0.1:8025/api/v1/search?query=to%3Aops%40hanseatic-imports.example"   | python -c 'import json,sys; [print(m["Subject"]) for m in json.load(sys.stdin)["messages"]]'
# Sendung gebucht: FF-...                 (from shipment.booked)
# after the cancellation:
# Reservierung freigegeben: FF-...        (from banking's hold.released)
curl -s -H "$HANSEATIC_CUSTOMER" "${GATEWAY}/api/v1/notifications?limit=5"   # its own history
```

Then the script takes the booking event out of logistics' outbox, exactly as it was published, sends it to
`freightflow.shipment.v1` again with `kafka-console-producer.sh` as `admin`, waits until notification-service counts it
as a duplicate (`freightflow_events_consumed_total{type="shipment.booked",outcome="duplicate"}`) and checks that
Mailpit still holds one "Sendung gebucht" email.

## 6. Duties and import VAT, with and without the EPA proof (ADR 0035)

Two quotes for the same 12,000 kg of Namibian Cape hake fillets (TARIC 0304 74 11 00, EUR 48,000.00 FOB) into
Hamburg under FCA. With an approved exporter's origin declaration, the EU-SADC EPA preference applies: the duty is 0
and the import VAT is 7 %. Without one, the third-country 7.5 % applies, and VAT is charged on it too. A code that is
not in the loaded tariff subset is a 422 `unknown-tariff-code`. The script prints the figures; the README's worked
example has them.

```bash
curl -s -X POST -H "$NAMIB_CUSTOMER" -H "Content-Type: application/json" "${GATEWAY}/api/v1/quotes" -d '{
  "origin":"NAWVB","destination":"DEHAM","mode":"SEA_LCL","incoterm":"FCA","payerRole":"CONSIGNEE",
  "packages":[{"lengthCm":120,"widthCm":100,"heightCm":150,"weightKg":500,"quantity":2}],
  "goods":[{"hsCode":"0304741100","description":"Frozen Cape hake fillets","originCountry":"NA","quantity":1200,
    "netWeightKg":12000,"customsValue":48000.00,"customsValueCurrency":"EUR",
    "originProof":{"type":"ORIGIN_DECLARATION","reference":"INV-2026-0412","issuedOn":"2026-11-24",
                   "approvedExporterNumber":"NA/AE/0042"}}]}' | python -m json.tool
# customs.estimatedDuties 0.00, customs.collectedWithFreight true, amountDueOnBooking = freight + estimate
```

## 7. A shipment on DCSA events from booking to delivery (ADR 0037)

A second shipment is booked and paid. An operator assigns the carrier `carrier-kalahari-freight` and the container
`CSQU3054383`; a wrong check digit (`CSQU3054384`) is a 400 `invalid-container-number`. A notification without a valid
signature is refused at the public endpoint with a 401 `invalid-webhook-signature`. Then the carrier simulator runs
the container's voyage: PICK, GTIN and LOAD at Walvis Bay, the vessel's DEPA and ARRI, then DISC, GTOT and DROP at
Hamburg, one event a second. It pushes each one signed through the gateway, as a real carrier would. The script waits
until the shipment is DELIVERED, with its payment captured at the pickup.

```bash
docker compose exec -T partner-simulator python -c "import urllib.request; urllib.request.urlopen(urllib.request.Request(
  'http://127.0.0.1:8090/_control/carrier/voyages', method='POST',
  data=b'{\"containerNumber\":\"CSQU3054383\",\"origin\":\"NAWVB\",\"destination\":\"DEHAM\",\"intervalSeconds\":1}'))"
curl -s -H "$NAMIB_CUSTOMER" "${GATEWAY}/api/v1/shipments/${DCSA_SHIP}" | python -c 'import json,sys; print(json.load(sys.stdin)["status"])'
# DELIVERED
```
