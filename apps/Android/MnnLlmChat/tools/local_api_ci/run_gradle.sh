#!/usr/bin/env bash
set -euo pipefail
HERE="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
APP="$(cd "$HERE/../.." && pwd)"
ROOT="$(git -C "$HERE" rev-parse --show-toplevel)"
export REPORT_DIR="${REPORT_DIR:-$ROOT/local-api-ci-report}"
# A disposable copy allows SHA-256 verification without editing upstream wrapper files.
WRAPPER="${RUNNER_TEMP:-/tmp}/mnn-local-api-wrapper-$(id -u)"
python3 - "$HERE" "$APP" "$WRAPPER" <<'PY'
import hashlib,json,pathlib,re,shutil,subprocess,sys
here,app,wrapper=map(pathlib.Path,sys.argv[1:])
tc=json.loads((here/'build-lock.json').read_text())['toolchain']
java=subprocess.run(['java','-XshowSettings:properties','-version'],capture_output=True,text=True,check=True)
major=re.search(r'java.specification.version\s*=\s*(\S+)',java.stderr)
if not major or major.group(1)!=tc['java_major']:
    raise SystemExit('Select the pinned JDK '+tc['java_major']+' before invoking Gradle')
for name,key in [('gradlew','wrapper_script_sha256'),('gradle/wrapper/gradle-wrapper.jar','wrapper_jar_sha256')]:
    if hashlib.sha256((app/name).read_bytes()).hexdigest()!=tc[key]:
        raise SystemExit(f'Unreviewed Gradle wrapper change: {name}')
    destination=wrapper/name
    destination.parent.mkdir(parents=True,exist_ok=True)
    shutil.copyfile(app/name,destination)
properties=(app/'gradle/wrapper/gradle-wrapper.properties').read_text()
expected='https\\://services.gradle.org/distributions/gradle-'+tc['gradle']+'-bin.zip'
if 'distributionUrl='+expected not in properties:
    raise SystemExit('Unreviewed Gradle distribution URL')
properties='\n'.join(line for line in properties.splitlines() if not line.startswith('distributionSha256Sum='))
(wrapper/'gradle/wrapper/gradle-wrapper.properties').write_text(properties+'\ndistributionSha256Sum='+tc['gradle_distribution_sha256']+'\n')
PY
# CI never uses signing secrets, built-in models, Firebase configuration, or local AAR overrides.
for variable in KEYSTORE_FILE KEYSTORE_PASSWORD KEY_ALIAS KEY_PASSWORD; do
  [[ -z "${!variable:-}" ]] || { echo "Signing environment is forbidden in this CI entrypoint: $variable" >&2; exit 1; }
done
exec bash "$WRAPPER/gradlew" --project-dir "$APP" --no-daemon --console=plain --max-workers=2 \
  -Dorg.gradle.jvmargs='-Xmx4g -XX:MaxMetaspaceSize=1g -Dfile.encoding=UTF-8' \
  -PADD_BUILTIN=false -PENABLE_FIREBASE=false -PUSE_LOCAL_MARKWON=false \
  --init-script "$HERE/ci.init.gradle" "$@"
