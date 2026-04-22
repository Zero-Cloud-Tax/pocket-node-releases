#!/usr/bin/env bash
# Pocket Node Pro License Key Generator
# Usage: ./keygen.sh <serial_number>   (1 to 65535)
# Example: ./keygen.sh 1  →  PN-XXXXXXXXXXXXXXXXXXXXXXXXXXXX0001
#
# IMPORTANT: Keep this script private — it contains the HMAC secret.
# Do not commit to a public repo.

set -euo pipefail

if [[ $# -ne 1 ]]; then
    echo "Usage: $0 <serial_number>"
    exit 1
fi

SERIAL=$(printf '%04X' "$1")
SECRET="pocketnode-pro-secret-2024"

# Compute HMAC-SHA256 of the serial using the secret as the key
HMAC=$(printf '%s' "$SERIAL" \
    | openssl dgst -sha256 -hmac "$SECRET" -hex \
    | awk '{print toupper($NF)}' \
    | cut -c1-28)

KEY="PN-${HMAC}${SERIAL}"
echo "$KEY"
