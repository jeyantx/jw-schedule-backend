#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# Direct test of Catalyst SmartBrowz: HTML -> PDF, no build, no deploy.
# Proves the whole idea (real Chrome, correct Tamil shaping) in one curl.
#
# Usage:
#   export PROJECT_ID=123456789
#   export ACCESS_TOKEN=xxddxx...        # a valid Zoho OAuth token (scope ZohoCatalyst.pdfshot.execute)
#   export API_DOMAIN=https://api.catalyst.zoho.in   # optional; India DC is the default
#   export ENVIRONMENT=Development                    # optional
#   ./scripts/test-smartbrowz.sh
#
# Output: out.pdf  (open it — the Tamil sheet should render perfectly)
# ---------------------------------------------------------------------------
set -euo pipefail

API_DOMAIN="${API_DOMAIN:-https://api.catalyst.zoho.in}"
: "${PROJECT_ID:?set PROJECT_ID}"
: "${ACCESS_TOKEN:?set ACCESS_TOKEN}"

HERE="$(cd "$(dirname "$0")" && pwd)"
HTML_FILE="${1:-$HERE/sample-ta.html}"
OUT="${OUT:-out.pdf}"

echo "POST $API_DOMAIN/baas/v1/project/$PROJECT_ID/smartbrowz/pdf"
echo "HTML: $HTML_FILE"

# Build the JSON body safely (HTML is escaped into a JSON string by jq).
BODY="$(jq -n --arg html "$(cat "$HTML_FILE")" '{
  html: $html,
  output_options: { output_type: "pdf" },
  pdf_options: {
    landscape: true,
    print_background: true,
    format: "A4",
    margin: { top: "0", right: "0", bottom: "0", left: "0" }
  },
  navigation_options: { wait_until: "networkidle0", timeout: 30000 }
}')"

curl -sS -X POST \
  "$API_DOMAIN/baas/v1/project/$PROJECT_ID/smartbrowz/pdf" \
  -H "Authorization: Zoho-oauthtoken $ACCESS_TOKEN" \
  -H "Content-Type: application/json" \
  ${ENVIRONMENT:+-H "Environment: $ENVIRONMENT"} \
  -H "Accept: application/pdf" \
  --data-binary "$BODY" \
  -o "$OUT"

# If the server returned JSON (an error) instead of a PDF, show it.
if head -c 4 "$OUT" | grep -q '%PDF'; then
  echo "OK -> $OUT ($(wc -c < "$OUT") bytes). Open it to check the Tamil rendering."
else
  echo "Did NOT get a PDF. Server said:"
  cat "$OUT"; echo
  exit 1
fi
