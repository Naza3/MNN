#!/usr/bin/env bash
# Sign a separate, already-audited APK with the dedicated cached CI test identity.
# This is not a production signing entrypoint; no signing environment reaches Gradle.
set -euo pipefail
umask 077
if [[ $# -ne 4 ]]; then
  echo 'Usage: sign_test_apk.sh UNSIGNED_APK TEST_KEYSTORE OUTPUT_DIR REPORT_DIR' >&2
  exit 2
fi
HERE="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
: "${ANDROID_SDK_ROOT:?Set ANDROID_SDK_ROOT}"
APK="$1"
TEST_KEYSTORE="$2"
OUTPUT_DIR="$3"
REPORT_DIR="$4"
BUILD_TOOLS="$(python3 -c 'import json,sys;print(json.load(open(sys.argv[1]))["toolchain"]["build_tools"])' "$HERE/build-lock.json")"
APKSIGNER="$ANDROID_SDK_ROOT/build-tools/$BUILD_TOOLS/apksigner"
ZIPALIGN="$ANDROID_SDK_ROOT/build-tools/$BUILD_TOOLS/zipalign"
[[ -x "$APKSIGNER" && -x "$ZIPALIGN" ]]
[[ -s "$APK" && -f "$TEST_KEYSTORE" && -s "$TEST_KEYSTORE" ]]

# Bind this exact unsigned file to its completed audit, not merely an earlier job step.
python3 - "$APK" "$TEST_KEYSTORE" "$OUTPUT_DIR" "$REPORT_DIR" <<'PY'
import hashlib, json, pathlib, sys
apk, key, output, report = map(pathlib.Path, sys.argv[1:])
if key.resolve().is_relative_to(output.resolve()) or key.resolve().is_relative_to(report.resolve()):
    raise SystemExit('Test keystore must remain outside all artifact/report directories')
audit = json.loads((report/'apk-audit.json').read_text())
if (audit.get('static_package_audit') != 'passed' or audit.get('signing') != 'unsigned'
        or audit.get('errors') != []
        or audit.get('apk_sha256') != hashlib.sha256(apk.read_bytes()).hexdigest()):
    raise SystemExit('Input APK does not match a successful unsigned APK audit')
PY
if "$APKSIGNER" verify "$APK" > /dev/null 2>&1; then
  echo 'Refusing to replace an existing APK signing identity' >&2
  exit 1
fi

mkdir -p "$OUTPUT_DIR"
FILENAME='MNN-Chat-API-arm64-ci-test.apk'
[[ ! -e "$OUTPUT_DIR/$FILENAME" && ! -e "$OUTPUT_DIR/$FILENAME.sha256" ]]
WORK="$(mktemp -d "$OUTPUT_DIR/.sign-test-apk.XXXXXX")"
trap 'rm -rf -- "$WORK"' EXIT
# The fixed password protects a development-only key, never a production credential.
# API 26 is the locked minimum; v2/v3 cover every supported Android version.
"$APKSIGNER" sign --ks "$TEST_KEYSTORE" --ks-key-alias mnn-local-api-ci-test \
  --ks-pass pass:android --key-pass pass:android \
  --v1-signing-enabled false --v2-signing-enabled true --v3-signing-enabled true \
  --v4-signing-enabled false --out "$WORK/$FILENAME" "$APK"
"$APKSIGNER" verify --verbose --print-certs "$WORK/$FILENAME" > "$WORK/signature.txt"
"$ZIPALIGN" -c -P 16 -v 4 "$WORK/$FILENAME" > "$WORK/zipalign.txt"
python3 - "$APK" "$WORK/$FILENAME" "$WORK/signature.txt" "$REPORT_DIR" <<'PY'
import hashlib, json, pathlib, re, sys, zipfile
original, signed, certificate, report = map(pathlib.Path, sys.argv[1:])
with zipfile.ZipFile(original) as before, zipfile.ZipFile(signed) as after:
    if before.namelist() != after.namelist():
        raise SystemExit('Signing changed APK archive entries')
    for first, second in zip(before.infolist(), after.infolist()):
        if before.read(first) != after.read(second):
            raise SystemExit('Signing changed APK payload: '+first.filename)
text = certificate.read_text()
fingerprints = re.findall(r'^Signer #\d+ certificate SHA-256 digest: ([0-9a-fA-F]{64})$', text, re.M)
if len(fingerprints) != 1 or any(
        'Verified using '+scheme+' scheme (APK Signature Scheme '+scheme+'): true' not in text
        for scheme in ['v2', 'v3']):
    raise SystemExit('Expected exactly one verified v2/v3 test signing certificate')
sha = hashlib.sha256(signed.read_bytes()).hexdigest()
signed.with_suffix('.apk.sha256').write_text(sha+'  '+signed.name+'\n')
(report/'signed-test-apk.json').write_text(json.dumps({
    'signing': 'dedicated_ci_test_key_not_production',
    'unsigned_apk_sha256': hashlib.sha256(original.read_bytes()).hexdigest(),
    'signed_apk_sha256': sha, 'apk_filename': signed.name,
    'certificate_sha256': fingerprints[0].lower(),
    'verified_signature_schemes': ['v2', 'v3'],
    'zipalign_16k': 'passed', 'archive_payload_unchanged': True,
    'upgrade_compatibility': 'requires matching installed APK certificate',
    'runtime_validation': 'not_run: requires a real Android device and model'
}, indent=2)+'\n')
print('Verified CI test APK SHA-256: '+sha)
print('Public test certificate SHA-256: '+fingerprints[0].lower())
PY
cp "$WORK/signature.txt" "$REPORT_DIR/signed-test-apk-certificate.txt"
cp "$WORK/zipalign.txt" "$REPORT_DIR/signed-test-apk-zipalign.txt"
mv "$WORK/$FILENAME" "$OUTPUT_DIR/$FILENAME"
mv "$WORK/$FILENAME.sha256" "$OUTPUT_DIR/$FILENAME.sha256"
