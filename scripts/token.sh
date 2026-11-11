#!/usr/bin/env bash
# A real access token for a demo persona (ADR 0016), on stdout.
#   scripts/token.sh customer-namib-exports
#   curl -H "Authorization: Bearer $(scripts/token.sh operator)" http://127.0.0.1:8080/api/v1/ledger/reconciliation
# Personas: customer-namib-exports, customer-hanseatic-imports, operator, auditor (people, code flow with PKCE);
# carrier-walvis-haulage, logistics-service (machine clients, client credentials). --claims prints the claims.
set -euo pipefail
# python3 where it works; on Windows that name can be a store stub that only prints a hint.
PYTHON=python3
"$PYTHON" -c "" 2>/dev/null || PYTHON=python
exec "$PYTHON" "$(dirname "$0")/keycloak_token.py" "$@"
