#!/usr/bin/env bash
# Restore only the existing app certificate. Never create a substitute update key in CI.
set -euo pipefail
umask 077
: "${KEYSTORE_BASE64:?Set the private KEYSTORE_BASE64 repository secret to the existing app signing key}"
expected=76f5e06f47c5eb4030c74fc0ab6034bf34f7bacea5b946f1e4e91b410e609fa1
mkdir -p build
stage="$(mktemp build/signing-key.XXXXXX)"
trap 'rm -f "$stage"' EXIT
if ! printf '%s' "$KEYSTORE_BASE64" | base64 --decode > "$stage"; then
  echo 'The signing secret is not valid base64. No APK was signed.' >&2
  exit 1
fi
if ! actual="$(keytool -exportcert -keystore "$stage" -storepass android -alias androiddebugkey 2>/dev/null | sha256sum | cut -d' ' -f1)"; then
  echo 'The signing secret is not the existing Audio City development keystore.' >&2
  exit 1
fi
if [ "$actual" != "$expected" ]; then
  echo 'Signing certificate mismatch. Refusing to build an update with a different key.' >&2
  exit 1
fi
chmod 600 "$stage"
mv -f "$stage" build/development.keystore
printf '%s\n' 'Restored and verified the existing Audio City signing certificate.'
