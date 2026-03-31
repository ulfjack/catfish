#!/bin/sh
# Usage: ./create-root-ca.sh [output-dir]
OUT=${1:-.}
openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:4096 -out "$OUT/ca.key"
openssl req -new -x509 -days 3650 -key "$OUT/ca.key" -out "$OUT/ca.crt" \
  -subj "/CN=Catfish MITM CA/O=Catfish"
echo "Import $OUT/ca.crt into your browser/system trust store."
