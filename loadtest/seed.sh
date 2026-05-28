#!/usr/bin/env bash
#
# Seeds a pool of disbursed loans so the repayment endpoint can be load-tested
# across many loans (avoids serializing on a single loan's row lock).
#
# Output: loanIds.json (a JSON array of loan ids) written next to this script,
# which repayment.js (k6) consumes.
#
# Usage:
#   bash seed.sh
#   NUM_LOANS=20 PRINCIPAL=1000000 bash seed.sh
#
# Env (all optional, defaults shown):
#   BASE_URL=https://localhost:8443   Fineract base, WITHOUT /fineract-provider
#   TENANT=default
#   AUTH=mifos:password               Basic auth as user:pass
#   OFFICE_ID=1
#   NUM_LOANS=100
#   PRINCIPAL=1000000                 big, so loans survive many tiny repayments
#   PRODUCT_ID=                       reuse an existing loan product instead of creating one
#
set -euo pipefail

BASE_URL="${BASE_URL:-https://localhost:8443}"
API="$BASE_URL/fineract-provider/api/v1"
TENANT="${TENANT:-default}"
AUTH="${AUTH:-mifos:password}"
OFFICE_ID="${OFFICE_ID:-1}"
NUM_LOANS="${NUM_LOANS:-100}"
PRINCIPAL="${PRINCIPAL:-1000000}"
PRODUCT_ID="${PRODUCT_ID:-}"

OUT="$(cd "$(dirname "$0")" && pwd)/loanIds.json"

command -v curl >/dev/null || { echo "curl is required" >&2; exit 1; }
command -v jq   >/dev/null || { echo "jq is required (brew install jq)" >&2; exit 1; }

TODAY="$(date '+%d %B %Y')"
PAST="$(date -d '14 days ago' '+%d %B %Y' 2>/dev/null || date -v-14d '+%d %B %Y')"

# api METHOD PATH [JSON_BODY] -> prints response body, aborts on non-2xx.
# -k skips the self-signed cert check; tenant goes in the header.
api() {
  local method="$1" path="$2" reqbody="${3:-}"
  local raw code resp
  raw="$(curl -sk -X "$method" -u "$AUTH" \
      -H 'Content-Type: application/json' \
      -H "Fineract-Platform-TenantId: $TENANT" \
      ${reqbody:+--data "$reqbody"} \
      -w $'\n%{http_code}' "$API$path")"
  code="${raw##*$'\n'}"
  resp="${raw%$'\n'*}"
  if [[ "$code" != 2* ]]; then
    echo "ERROR $method $path -> HTTP $code" >&2
    echo "$resp" >&2
    return 1
  fi
  printf '%s' "$resp"
}

echo "Fineract: $API (tenant=$TENANT)"

# 1. Make sure USD is an enabled org currency (union with what's already enabled).
echo "Ensuring USD currency is enabled..."
selected="$(api GET '/currencies?fields=selectedCurrencyOptions')"
codes="$(jq -c '[(.selectedCurrencyOptions // [])[].code] + ["USD"] | unique' <<<"$selected")"
api PUT '/currencies' "{\"currencies\": $codes}" >/dev/null

# 2. Loan product (accountingRule=1 NONE, so no GL account mapping is needed).
if [[ -z "$PRODUCT_ID" ]]; then
  echo "Creating loan product..."
  sn="LT$(printf '%02d' $((RANDOM % 100)))"
  product_body=$(cat <<JSON
{ "name":"LOADTEST_$(date +%s)","shortName":"$sn","currencyCode":"USD","locale":"en","dateFormat":"dd MMMM yyyy",
  "digitsAfterDecimal":2,"inMultiplesOf":0,"principal":"$PRINCIPAL","minPrincipal":"1000","maxPrincipal":"100000000",
  "numberOfRepayments":"5","repaymentEvery":"1","repaymentFrequencyType":"2",
  "interestRatePerPeriod":"1","interestRateFrequencyType":"2",
  "amortizationType":"1","interestType":"1","interestCalculationPeriodType":"1",
  "inArrearsTolerance":"0","transactionProcessingStrategyCode":"mifos-standard-strategy",
  "accountingRule":"1","loanScheduleType":"CUMULATIVE","loanScheduleProcessingType":"HORIZONTAL",
  "daysInYearType":"1","daysInMonthType":"1","overdueDaysForNPA":"5" }
JSON
)
  PRODUCT_ID="$(api POST '/loanproducts' "$product_body" | jq -r '.resourceId')"
fi
echo "Using loan product id = $PRODUCT_ID"

# 3. For each loan: create client -> loan application -> approve -> disburse.
echo "Creating + disbursing $NUM_LOANS loans..."
ids=()
for ((i = 1; i <= NUM_LOANS; i++)); do
  client_body=$(cat <<JSON
{ "officeId":$OFFICE_ID,"legalFormId":1,"firstname":"Load","lastname":"Test$i","active":true,
  "activationDate":"$PAST","dateFormat":"dd MMMM yyyy","locale":"en" }
JSON
)
  clientId="$(api POST '/clients' "$client_body" | jq -r '.resourceId')"

  loan_body=$(cat <<JSON
{ "clientId":$clientId,"productId":$PRODUCT_ID,"principal":"$PRINCIPAL","loanType":"individual",
  "loanTermFrequency":"5","loanTermFrequencyType":"2","numberOfRepayments":"5","repaymentEvery":"1",
  "repaymentFrequencyType":"2","interestRatePerPeriod":"1","amortizationType":"1","interestType":"1",
  "interestCalculationPeriodType":"1","transactionProcessingStrategyCode":"mifos-standard-strategy",
  "expectedDisbursementDate":"$TODAY","submittedOnDate":"$TODAY","dateFormat":"dd MMMM yyyy","locale":"en" }
JSON
)
  loanId="$(api POST '/loans' "$loan_body" | jq -r '.loanId // .resourceId')"

  api POST "/loans/$loanId?command=approve" \
    "{\"approvedOnDate\":\"$TODAY\",\"expectedDisbursementDate\":\"$TODAY\",\"approvedLoanAmount\":\"$PRINCIPAL\",\"dateFormat\":\"dd MMMM yyyy\",\"locale\":\"en\"}" >/dev/null

  api POST "/loans/$loanId?command=disburse" \
    "{\"actualDisbursementDate\":\"$TODAY\",\"transactionAmount\":\"$PRINCIPAL\",\"dateFormat\":\"dd MMMM yyyy\",\"locale\":\"en\"}" >/dev/null

  ids+=("$loanId")
  printf '\r  %d/%d (loanId=%s)        ' "$i" "$NUM_LOANS" "$loanId"
done
echo

printf '%s\n' "${ids[@]}" | jq -R 'tonumber' | jq -s '.' > "$OUT"
echo "Wrote ${#ids[@]} loan ids -> $OUT"
echo "Now run:  k6 run repayment.js"
