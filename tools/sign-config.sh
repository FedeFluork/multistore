#!/usr/bin/env bash
#
# Signs a remote configuration document (`parsers.json` and `index.json`).
#
#   tools/sign-config.sh <payload.json> <private-key.pem> [output.json]
#
# It signs the **bytes of the payload file**, not a JSON object. The difference is not academic:
# signing a structure forces whoever verifies it to re-serialise it in exactly the same way — same
# key order, same spacing, same number formatting — and any discrepancy makes a valid signature come
# out invalid, or, worse, lets two different documents produce the same canonical bytes. Here the
# payload travels base64-encoded inside the envelope: the bytes verified and the bytes interpreted
# are the same bytes, by construction.
#
# It is the same reasoning by which `SessionInstaller` computes the SHA-256 **while** writing the
# bytes into the session rather than over the staged file.
set -euo pipefail

payload="${1:?usage: sign-config.sh <payload.json> <key.pem> [output.json]}"
key="${2:?usage: sign-config.sh <payload.json> <key.pem> [output.json]}"
out="${3:--}"

[ -f "$payload" ] || { echo "payload does not exist: $payload" >&2; exit 1; }
[ -f "$key" ]     || { echo "key does not exist: $key" >&2; exit 1; }

# Ed25519 signs the whole message, not a digest: `-rawin` is mandatory, and without it OpenSSL
# rejects the key rather than silently signing something wrong.
signature=$(openssl pkeyutl -sign -inkey "$key" -rawin -in "$payload" | base64 | tr -d '\n')
encoded=$(base64 < "$payload" | tr -d '\n')

envelope=$(printf '{"algorithm":"ed25519","payload":"%s","signature":"%s"}\n' "$encoded" "$signature")

if [ "$out" = "-" ]; then
  printf '%s' "$envelope"
else
  printf '%s' "$envelope" > "$out"
  echo "signed: $out ($(wc -c < "$out" | tr -d ' ') bytes)" >&2
fi
