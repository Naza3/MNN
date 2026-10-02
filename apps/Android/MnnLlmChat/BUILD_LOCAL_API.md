# Local API test builds

## Scope

The dedicated `MNN Chat local API test build` workflow runs only in `Naza3/MNN`
on `feature/mnn-chat-local-api`, for changes under this App or its own workflow.
It does not alter `master`, create tags, publish releases, enable inherited
workflows, or receive signing/API secrets. Initial execution uses the feature
branch push event; GitHub's manual dispatch UI requires the workflow to exist
on the default branch, and is not a reason to change that branch.

This is a source-built arm64 App with the existing upstream CPU/OpenCL, vision,
audio, and diffusion build features. QNN is off because its SDK/assets are not
part of this build. Compiling these features is not proof of on-device GPU/NPU
execution, inference quality, background survival, or Android 16KiB runtime
compatibility.

## Engine freshness and provenance

`tools/local_api_ci/build-lock.json` pins the reviewed upstream engine base.
At each job's start, `preflight.py` reads the public upstream `master` SHA once,
requires it to equal that base, verifies ancestry and unchanged engine trees,
and records the separate App feature SHA. If upstream has advanced, the job
fails before building. Synchronize/rebase the feature branch, review upstream
changes, update the engine lock, and rerun the checks. Never silently substitute
an older runtime or download a floating `libMNN.so`. Commits appearing after a
job's initial check do not alter that job's already-frozen source.

The primary MNN library is compiled from this checkout into
`project/android/build_64/lib/libMNN.so`; Gradle then builds the actual App JNI
and `mnn_tts` JNI against it. The APK audit compares native Build IDs before and
after AGP stripping, as well as recording both hashes. No native stubs are used
for the final APK. JVM/Robolectric tests are host tests and do not replace a
real-device JNI/inference smoke test.

Upstream's existing auxiliary Sherpa JNI CDN ZIP is fetched from its fixed
HTTPS URL and checked against the reviewed SHA-256 before extracting exactly
one library. It is not a replacement MNN engine. If the CDN bytes change, the
build fails. Its exact source revision/static-dependency closure is unresolved;
see `tools/local_api_ci/NOTICE-AUDIT.md`. Do not call an artifact fully cleared
for redistribution while that audit remains open.

## Toolchain and dependencies

- JDK 17 (Temurin in CI), with its exact runtime version recorded
- The App's Gradle 8.9 wrapper, pinned wrapper script/JAR hashes and verified
  distribution SHA-256 in a disposable wrapper copy
- AGP 8.7.3; Android SDK 35, SDK 34 for the TTS module; build-tools 35.0.0
- NDK 27.2.12479018; CMake 3.22.1
- Actions are pinned to immutable commit SHAs

The runner image and Maven/JitPack transitive graph are not a fully hermetic
lockfile. The actual runtime dependency coordinates, hashes, available POM
license declarations, tool versions, and runner-image version are recorded.
The existing Markwon fork uses its upstream fixed version tag; resolution or
JitPack failures remain real build failures rather than falling back to another
artifact. New dependencies should use fixed versions and audited origins.

The workflow uses runner SDK licenses already present and does not pipe `yes`
to accept new terms. Missing license acceptance stops provisioning and needs
separate review. Do not place service configuration, credentials, keys, model
weights, or private conversations in this checkout or public CI artifacts.

## Local commands

Use a full checkout on the feature branch, with the above SDK packages already
installed and accepted, JDK 17 selected, and enough space for both the native
engine and Android build. Budget roughly 10–12 GiB of free disk for a cold local
toolchain/dependency/build setup; actual use varies. A 6.9 GiB workspace can be
too tight, so inspect existing SDK/cache space first and do not launch a duplicate
full build alongside CI. The lightweight helper tests need only Python 3.

```sh
export ANDROID_SDK_ROOT=/path/to/android-sdk
export REPORT_DIR="$PWD/local-api-ci-report"
CI_TOOLS=apps/Android/MnnLlmChat/tools/local_api_ci
python3 "$CI_TOOLS/preflight.py" --report-dir "$REPORT_DIR"
python3 -m unittest discover -s "$CI_TOOLS" -p 'test_*.py' -v
python3 "$CI_TOOLS/prepare_prebuilt.py" --report-dir "$REPORT_DIR"
bash "$CI_TOOLS/build_native.sh"
bash "$CI_TOOLS/run_gradle.sh" :app:testStandardDebugUnitTest --tests 'com.alibaba.mnnllm.api.openai.*'
bash "$CI_TOOLS/run_gradle.sh" :app:testStandardDebugUnitTest
bash "$CI_TOOLS/run_gradle.sh" :app:lintStandardRelease
bash "$CI_TOOLS/run_gradle.sh" :app:assembleStandardRelease :app:localApiDependencyInventory
python3 "$CI_TOOLS/audit_apk.py" \
  --apk apps/Android/MnnLlmChat/app/build/outputs/apk/standard/release/app-standard-release-unsigned.apk \
  --sdk "$ANDROID_SDK_ROOT" --report-dir "$REPORT_DIR"
python3 "$CI_TOOLS/collect_reports.py" --report-dir "$REPORT_DIR"
```

The native entrypoint preserves the supported upstream App feature switches.
The obsolete `MNN_CPU_WEIGHT_DEQUANT_GEMM` option was removed by this engine
version; vision/audio macros are derived from the supported OpenCV/audio
switches. Unused upstream `BUILD_PLUGIN`/`LLM_SUPPORT_*` command-line variables
are not passed as if they enabled features.

The native entrypoint refuses to reuse an existing CMake cache. Start a fresh
checkout/build directory for a newly synchronized engine rather than risk stale
libraries. `ADD_BUILTIN=false`, `ENABLE_FIREBASE=false`, and
`USE_LOCAL_MARKWON=false` are enforced. Existing upstream Firebase runtime
artifacts may still be present; disabling plugins/collection does not mean all
Firebase code was removed. Signing environment variables are explicitly
rejected. The scripts do not upload anything when run locally.

## Evidence and failure interpretation

The workflow executes focused local API tests, the full upstream App unit
suite, release lint, unsigned assembly, and APK audit independently where
prerequisites permit. A failure stays a failure: `continue-on-error` is only
used to gather the other diagnostic results, and a final gate rejects any
failed or unexecuted required stage. There is no blanket lint baseline,
exception suppression, skipped compile step, or simulated native replacement.
Use suite names and lint IDs/locations in the compact reports to distinguish
feature regressions from existing upstream failures.

The static APK audit checks the real package/version/min/target SDK, private
special-use foreground service and permission/subtype declaration, disabled
backup, ARM64-only libraries, required JNI libraries, every ELF PT_LOAD page
alignment, ZIP alignment, native dynamic-dependency closure, and obvious
model/key/config material. The scanner is a defense-in-depth check, not a proof
that arbitrary secrets can never exist in binaries. CI only uses synthetic
fixture credentials. Test stdout and private model/chat data are not collected.

Artifacts are retained for 14 days. Diagnostic reports upload even on failure;
the APK uploads only after every required test/lint/build/audit gate succeeds. The evidence bundle includes
`stage-outcomes.json`, engine/App source identities, actual tool versions,
focused/full test counts, lint locations, dependency hashes/notices, native
build options, and `apk-audit.json`. An unresolved license audit must be disclosed when using an artifact; a passing
build does not replace license closure or real-device validation.

## Signing and device acceptance

CI produces an unsigned standard release APK for `io.github.naza3.mnnchat`.
No keystore or private signing material is uploaded. Sign and re-verify the
APK in a controlled local environment before installation; calculate the
signed APK's new SHA-256 and record its signer certificate. The person shipping
future production releases must own and preserve their release key. This
workflow does not promise a production signing identity or compatibility with
the official MNN Chat application's signature.

Before declaring device acceptance, install the locally signed arm64 build and
verify loopback-only binding, bearer-key rejection/rotation, API routes and SSE,
stop/restart and request cancellation, one-owner UI/API runtime arbitration,
notification controls, background/locked-screen behavior, and the real selected
model. Test 16KiB devices separately where relevant. User-selected model
provisioning and its license review are separate from CI; no model is bundled
or downloaded by this workflow.
