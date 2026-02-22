#!/usr/bin/env bash
set -euo pipefail

BASE_URL="${1:-http://localhost:8081}"
TOTAL_REQUESTS="${2:-200}"
CONCURRENCY="${3:-20}"

API_KEY_JSON=$(curl -sS -X POST "${BASE_URL}/admin/api-keys" \
  -H 'Content-Type: application/json' \
  -d '{"name":"curl-load"}')

API_KEY=$(printf '%s' "$API_KEY_JSON" | sed -n 's/.*"keyValue":"\([^"]*\)".*/\1/p')
if [[ -z "$API_KEY" ]]; then
  echo "Failed to create API key. Response: $API_KEY_JSON"
  exit 1
fi

TMP_FILE=$(mktemp)
export BASE_URL API_KEY TMP_FILE

run_one() {
  local idx="$1"
  local key="curl__${idx}"
  local time_total
  time_total=$(curl -sS -o /dev/null -w '%{time_total}' -X POST "${BASE_URL}/api/applications" \
    -H "X-API-Key: ${API_KEY}" \
    -H 'Content-Type: application/x-www-form-urlencoded' \
    --data-urlencode "requestKey=${key}" \
    --data-urlencode 'companyName=CurlCo' \
    --data-urlencode 'positionTitle=Load Engineer' \
    --data-urlencode 'dateApplied=2026-02-22' \
    --data-urlencode 'status=APPLIED')
  echo "$time_total" >> "$TMP_FILE"
}

export -f run_one

seq 1 "$TOTAL_REQUESTS" | xargs -n1 -P"$CONCURRENCY" bash -c 'run_one "$@"' _

sort -n "$TMP_FILE" > "${TMP_FILE}.sorted"
COUNT=$(wc -l < "${TMP_FILE}.sorted")

pctl() {
  local p="$1"
  local line=$(( (COUNT * p + 99) / 100 ))
  sed -n "${line}p" "${TMP_FILE}.sorted"
}

AVG=$(awk '{sum+=$1} END {if (NR>0) printf "%.4f", sum/NR; else print "0"}' "$TMP_FILE")
P50=$(pctl 50)
P95=$(pctl 95)
P99=$(pctl 99)

cat <<REPORT
Concurrency test complete
- Total requests: $TOTAL_REQUESTS
- Concurrency: $CONCURRENCY
- Avg latency (s): $AVG
- P50 latency (s): $P50
- P95 latency (s): $P95
- P99 latency (s): $P99
REPORT

rm -f "$TMP_FILE" "${TMP_FILE}.sorted"
