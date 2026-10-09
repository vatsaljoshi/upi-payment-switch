#!/usr/bin/env bash
# Walks through every payment path against a running stack (docker compose up).
set -euo pipefail
SWITCH=${SWITCH_URL:-http://localhost:8080}
BANK=${BANK_URL:-http://localhost:9090}
VPAS="alice@switchbank bob@otherbank carol@otherbank"

step()   { printf '\n\033[1m== %s ==\033[0m\n' "$1"; }
newkey() { echo "demo-$(date +%s)-$RANDOM$RANDOM"; }
pay()    { # key payer payee amount
  curl -s -w '  [HTTP %{http_code}]\n' -X POST "$SWITCH/api/v1/payments" \
    -H 'Content-Type: application/json' -H "Idempotency-Key: $1" \
    -d "{\"payerVpa\":\"$2\",\"payeeVpa\":\"$3\",\"amount\":$4}"
}
faults() { curl -s -X POST "$BANK/admin/faults" -H 'Content-Type: application/json' -d "$1" >/dev/null; }
balances() {
  local total=0 b
  for v in $VPAS; do
    b=$(curl -s "$BANK/accounts/$v" | grep -o '"balance":[0-9.]*' | cut -d: -f2)
    printf '  %-18s %12s\n' "$v" "$b"
    total=$(awk -v a="$total" -v b="$b" 'BEGIN { printf "%.2f", a + b }')
  done
  printf '  %-18s %12s\n' "TOTAL" "$total"
}

faults '{"creditFailures":0,"creditDeclines":0,"latencyMs":0}'

step "Balances before"
balances

step "1) Happy path: alice pays bob 100 -> 200 SUCCESS"
KEY=$(newkey)
pay "$KEY" alice@switchbank bob@otherbank 100.00

step "2) Client retries with the same Idempotency-Key -> same txn, no second debit"
pay "$KEY" alice@switchbank bob@otherbank 100.00

step "3) Same key, different amount -> 409 Conflict"
pay "$KEY" alice@switchbank bob@otherbank 999.00

step "4) Payee bank declines the credit -> 422 REVERSED (alice refunded)"
faults '{"creditDeclines":1}'
pay "$(newkey)" alice@switchbank bob@otherbank 42.00

step "5) Insufficient funds (carol has 0) -> 422 FAILED, no money moves"
pay "$(newkey)" carol@otherbank bob@otherbank 10.00

step "6) Payee bank unreachable -> 202 CREDIT_PENDING (NOT reversed: the credit may have landed)"
faults '{"creditFailures":3}'
RESP=$(pay "$(newkey)" alice@switchbank bob@otherbank 7.00)
echo "$RESP"
TXN=$(echo "$RESP" | grep -o '"txnId":"[^"]*"' | cut -d'"' -f4)
echo "  waiting 25s for the recovery sweeper to re-send the credit..."
sleep 25
curl -s -w '  [HTTP %{http_code}]\n' "$SWITCH/api/v1/payments/$TXN"

step "Balances after -> TOTAL must be unchanged (money moved, never created or lost)"
balances
