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

## Direct API answers (834 / 0.8.3-localapi.4)

API sessions explicitly apply `jinja.context.enable_thinking=false` after native
load succeeds, because model `context.json` is also merged during native load.
The override is session-only: it does not write model/custom configuration or
change chat preferences. Request reset keeps the loaded template configuration;
Stop/Start creates a new API session and reapplies the override. The engine,
toolchain, package ID, output-token limits and HTTP contract are unchanged.

This sets the template's thinking policy rather than merely filtering generated
text. It requires a model template that supports `enable_thinking`. App compilation
and the existing API/lifecycle regression suite do not prove real model behavior.
Validate Qwen3.5-2B with the same two short messages, checking the first request,
a second request after reset, and a request after Stop/Start. Record answer text,
thinking detection and elapsed time separately; see `LOCAL_API.md`.

## Background text generation (833 / 0.8.3-localapi.3)

User-started text chat generation is owned by an application-scoped job and
the private `com.alibaba.mnnllm.android.chat.background.ChatGenerationService`,
independently of the chat screen's lifetime. This path is for text-only chat;
it does not extend background support to image, audio, video, or diffusion
requests. Text chat and the OpenAI API retain one-owner runtime arbitration and
share a single model-status notification rather than competing indicators.

The chat service uses `specialUse` with an explicit subtype explanation for
user-started model residency and text chat generation. Model loading, loaded
residency, and active generation share its foreground lifetime. While generating,
its low-importance notification shows status, a job-scoped Stop
action, and a return-to-conversation action. The unified model indicator stays
static while the model is loaded and idle, uses an animation-list icon and
indeterminate progress while busy, and disappears when the model is unloaded.
Completion returns to loaded-idle status when model retention is enabled. API
handoff transfers notification ownership so stale chat updates cannot remove or
overwrite API status. System UI may render notification icon animation
differently across Android versions, devices, and Doze states; phase text and
progress must remain understandable without animation. There is no per-token
notification polling. Stop uses an explicit
immutable service PendingIntent; no new broadcast receiver is introduced. Prompts and
generated text are not notification content. A partial wake lock is held only
during an active generation, with a 30-minute timeout and release on completion,
failure, or service destruction. Idle model retention may retain the foreground
indicator, but must not retain an active-generation wake lock.

Chat replies are saved by the job rather than relying on an attached Activity.
The service is non-sticky and does not replay a generation after process death.
Force-stop, process termination, device shutdown, notification permission or
channel settings, and vendor battery policies still require distinct device
checks; a foreground service is not a guarantee of uninterrupted execution.

## Engine freshness and provenance

`tools/local_api_ci/build-lock.json` pins the reviewed official, non-prerelease
Release: currently `3.6.1`, annotated tag object
`ea44a3ebd5dd6348eea501047b17c43aa3ecccb6`, peeled source commit
`d407447ed56c4121a11ccbd266dc184ca1ead0c2`. The earlier master baseline
`024a946b0b8fcf87c8a418229fadd4cd7858ffba` is a different source revision,
even though its version header also said 3.6.1; it is not relabeled as a Release.

At each job's start, `preflight.py` reads the official `releases/latest` once,
rejects draft/prerelease entries, resolves the tag through any annotated tag
objects, and compares release ID/tag/object/commit with the reviewed lock.
A newly published Release or moved tag stops the build for review and a lock
update. Master commits do not change this policy or this job's frozen source.
The App feature branch and its commit remain independent; upgrading the engine
does not reset/rebase the App or publish a Release.

Preflight creates a fresh detached source checkout outside the App checkout.
`MNN_ENGINE_SOURCE_ROOT` and `MNN_ENGINE_INSTALL_ROOT` identify that release
source and its `project/android/build_64` install directory. Existing source
checkouts are not reused. Restricted source areas are excluded from sparse
materialization. The commit, complete root tree identity, relevant public tree
objects, clean source state, release metadata and separate App SHA are recorded.

Primary `libMNN.so` and Sherpa JNI are compiled from the independent official
release source. The App's own JNI and `mnn_tts` stay on the feature commit and
compile against the same release's headers and installed MNN library. Their
CMake files accept explicit source/install root parameters (or environment
fallback) while retaining upstream relative-path defaults outside this CI.
The Gradle init script passes explicit CMake arguments so changed engine roots
also invalidate AGP's native configuration inputs. No tracked engine directory
in the App checkout is overwritten or silently mixed into this build.

The actual APK audit checks App/TTS CMake caches and compile commands for the
release source/install roots, rejects App-checkout engine headers, and compares
native Build IDs before/after AGP stripping. Hashes, JNI exports and dynamic
symbol closure are recorded. No native stubs or prebuilt Sherpa binaries are
used for the APK. JVM/Robolectric tests do not replace real-device inference.

The auxiliary Sherpa ASR JNI is also built from the same locked repository
source. Six fixed-version source archives are pre-downloaded with exact SHA-256
verification, then consumed by the original FetchContent recipes (including
the original OpenFST patch). No binary CDN is used. The configuration retains
all Sherpa ASR entry points used by the App, while disabling unused Sherpa
TTS, speaker diarization, demos, C API, WebSocket, PortAudio, Python, and tests.
The separate App `mnn_tts` module is unaffected. Sherpa dependencies are static,
with the shared C++ runtime and 16KiB page alignment; Eigen is restricted to its
MPL2-compatible code. Required JNI exports and linked MNN symbols are checked.

`sherpa-source-inputs.json`, `sherpa-provenance.json`, `sherpa-link.txt`, and the
exact `sherpa-sources/` archives plus `sherpa-notices/` make the native inputs
reviewable. Preserve the source/notices bundle when distributing the APK,
especially the exact Eigen source. See `tools/local_api_ci/NOTICE-AUDIT.md` for
the configured dependency/license evidence and remaining review boundaries.

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
`dependency-inventory.json` contains original external Maven JAR/AAR bytes,
selected with a non-lenient artifact view filtered to external modules.
`local-project-inventory.json` separately records local Android runtime classes
JARs selected with `artifactType=android-classes-jar`, their hashes, variant
attributes, Gradle project paths, source commit and source-tree identities.
These classes JARs are not presented as complete published AARs: local resource
and native inputs remain covered by their source commit and the final APK
manifest/native audit. Unknown flat-file/composite artifact kinds fail the
inventory rather than disappearing from the report. Assembly, APK audit, and
inventory are separate diagnostic stages; all must pass before APK upload.

The existing Markwon fork uses its upstream fixed version tag; resolution or
JitPack failures remain real build failures rather than falling back to another
artifact. New dependencies should use fixed versions and audited origins.

The workflow uses runner SDK licenses already present and does not pipe `yes`
to accept new terms. Missing license acceptance stops provisioning and needs
separate review. Do not place user service configuration, credentials, production keys, model
weights, or private conversations in this checkout or public CI artifacts.

## Local commands

Use a full App checkout on the feature branch, with the above SDK packages already
installed and accepted, JDK 17 selected, and enough space for both the native
engine and Android build. Budget roughly 10–12 GiB of free disk for a cold local
toolchain/dependency/build setup; actual use varies. A 6.9 GiB workspace can be
too tight, so inspect existing SDK/cache space first and do not launch a duplicate
full build alongside CI. The non-signing helper tests need only Python 3.
Signing regressions also require the locked SDK packages and JDK tools; they
use resource-only APK fixtures rather than models or native libraries. Missing
tools skip those fixtures locally but fail them in GitHub Actions.

```sh
export ANDROID_SDK_ROOT=/path/to/android-sdk
export REPORT_DIR="$PWD/local-api-ci-report"
# Choose a new, nonexistent absolute directory outside the App checkout.
export MNN_ENGINE_SOURCE_ROOT=/absolute/fresh/mnn-official-release
export MNN_ENGINE_INSTALL_ROOT="$MNN_ENGINE_SOURCE_ROOT/project/android/build_64"
CI_TOOLS=apps/Android/MnnLlmChat/tools/local_api_ci
python3 "$CI_TOOLS/preflight.py" --report-dir "$REPORT_DIR"
python3 -m unittest discover -s "$CI_TOOLS" -p 'test_*.py' -v
python3 "$CI_TOOLS/verify_apk_metadata.py" --sdk "$ANDROID_SDK_ROOT" --report-dir "$REPORT_DIR"
python3 "$CI_TOOLS/verify_pem_scanner.py" --sdk "$ANDROID_SDK_ROOT" --report-dir "$REPORT_DIR"
bash "$CI_TOOLS/build_native.sh"
bash "$CI_TOOLS/build_sherpa.sh"
bash "$CI_TOOLS/run_gradle.sh" :app:testStandardDebugUnitTest --tests 'com.alibaba.mnnllm.api.openai.*'
bash "$CI_TOOLS/run_gradle.sh" :app:testStandardDebugUnitTest
bash "$CI_TOOLS/run_gradle.sh" :app:lintStandardRelease
bash "$CI_TOOLS/run_gradle.sh" :app:assembleStandardRelease :app:localApiDependencyInventory
python3 "$CI_TOOLS/audit_apk.py" \
  --apk apps/Android/MnnLlmChat/app/build/outputs/apk/standard/release/app-standard-release-unsigned.apk \
  --sdk "$ANDROID_SDK_ROOT" --report-dir "$REPORT_DIR"
python3 "$CI_TOOLS/collect_reports.py" --report-dir "$REPORT_DIR"
```

The native entrypoint preserves the supported App CPU/OpenCL/vision/audio/
diffusion feature switches. KleidiAI is explicitly off to retain the previously
validated backend scope rather than inherit the Release's different default.
The obsolete `MNN_CPU_WEIGHT_DEQUANT_GEMM` option was removed by this engine
version; vision/audio macros are derived from the supported OpenCV/audio
switches. Unused upstream `BUILD_PLUGIN`/`LLM_SUPPORT_*` command-line variables
are not passed as if they enabled features.

Both native entrypoints refuse to reuse an existing CMake cache. Start a fresh
release checkout/build directory for a reviewed engine upgrade rather than risk stale
libraries. `ADD_BUILTIN=false`, `ENABLE_FIREBASE=false`, and
`USE_LOCAL_MARKWON=false` are enforced. Existing upstream Firebase runtime
artifacts may still be present; disabling plugins/collection does not mean all
Firebase code was removed. Signing environment variables are explicitly
rejected. The scripts do not upload anything when run locally.

## Evidence and failure interpretation

`test_engine_source.py` uses an actual tiny Git repository to exercise detached
checkout isolation, exact commit identity, dirty-source rejection and reuse
rejection. `verify_engine_roots.py` configures the real App/TTS CMake entrypoints
with temporary routing inputs, checks generated include/link commands and
explicit cache inputs, and rejects invalid roots. It compiles no native library.
CI uses the pinned CMake 3.22.1 for this configure-only fixture; local results
record the actual available CMake version separately in
`engine-root-regression.json`. Actual native compatibility remains a mandatory
subsequent compile and APK-audit gate.

Before native compilation, `verify_pem_scanner.py` verifies the exact locked
Netty binary/source archives and compiles real D8 fixtures. Netty's public
`SslUtils.PROBING_KEY` is a TLS provider capability-test constant, not a user
credential. Only that exact complete DEX string, with verified source/binary
provenance, is classified as public test data. Bare header format strings must
also occupy an entire DEX string identified with bounds-checked string-table
parsing. This is not a full DEX verifier. Every other complete, modified, mixed,
or malformed key-looking payload fails. No class, DEX, or library is exempted.
The tests reject synthetic PKCS8/RSA/EC/CRLF/encrypted-form payloads, mutations,
mixed known/unknown keys, and a token beside the known fixture. Token scanning
also respects DEX string boundaries instead of trusting adjacent length bytes.
`public-test-fixture-provenance.json` and `pem-scanner-regression.json` preserve
only hashes, provenance, classifications, and offsets; no PEM bodies are logged.
The audit separately verifies the actual Gradle runtime Netty JAR hash.

Before native compilation, `verify_apk_metadata.py` builds tiny resource-only
APKs with the locked SDK aapt2. It verifies both required private services,
numeric foreground-service flags,
optimized resource names/ZIP paths, every backup XML configuration, and retained
diagnostics on a missing ZIP entry. Negative fixtures reject a missing or exported
chat service (including an omitted explicit export setting), wrong/missing or
combined chat service flags, a missing chat subtype explanation, combined API
service flags, a permissive referenced XML even when an unrelated correct XML exists,
and a permissive qualified XML variant. Results are in
`apk-metadata-regression.json`; these fixtures do not exercise App/native code.
For local fixture-only runs, `--platform` and `--apkanalyzer` may select an
already-installed SDK platform/tool path; the report records that fixture SDK
and platform hash. Such a run does not change the production SDK 35 lock or
establish that the actual target-35 App APK passed its audit.

Before native compilation, `verify_gradle_init.py` runs real Gradle against an
SDK-free synthetic multi-project fixture with configuration-on-demand enabled.
It verifies explicit inventory task discovery, combined assembly/inventory task
selection, and rejection of mismatched MNN provenance. Its non-empty dependency
graph contains an original external JAR, a transitive external AAR, and a local
project with multiple Android-shaped outgoing artifact variants. The test checks
exact original external hashes, the explicitly selected local classes JAR and
its source tree, producer task execution, and rejection of unprovenanced flat
files. Negative controls reproduce the ambiguous untyped artifact resolution
and the former `projectsEvaluated` task-registration failure. The actual
init script registers tasks in `beforeProject` so task discovery is not delayed
until after Gradle has selected the requested task graph. This fixture produces
no Android APK or native library and is recorded in `gradle-init-regression.json`.

The workflow executes focused local API tests, the full upstream App unit
suite, release lint, unsigned assembly, and APK audit independently where
prerequisites permit. A failure stays a failure: `continue-on-error` is only
used to gather the other diagnostic results, and a final gate rejects any
failed or unexecuted required stage. There is no blanket lint baseline,
exception suppression, skipped compile step, or simulated native replacement.
Use suite names and lint IDs/locations in the compact reports to distinguish
feature regressions from existing upstream failures.

The static APK audit checks the real package/version/min/target SDK, private
special-use API and text-chat foreground services and permission/subtype
declarations, the chat wake-lock permission, disabled
backup (every compiled XML variant followed from the manifest resource ID),
ARM64-only libraries, required JNI libraries, every ELF PT_LOAD page
alignment, ZIP alignment, native dynamic-dependency closure, and obvious
model/key/config material. The scanner is a defense-in-depth check, not a proof
that arbitrary secrets can never exist in binaries (including split, encoded,
or deliberately obfuscated content). Exact public upstream test data is
identified separately as described above, never presented as a production key. CI only uses synthetic
fixture credentials. Test stdout and private model/chat data are not collected.

Artifacts are retained for 14 days. Diagnostic reports upload even on failure;
the APK uploads only after every required test/lint/build/audit gate succeeds.
The evidence bundle includes
`stage-outcomes.json`, official Release/tag/engine and separate App source identities, actual tool versions,
`apk-manifest.xml`, XML-only resource-table metadata, APK resource-entry names,
and the exact manifest-to-resource-ID-to-ZIP-path resolution. These static
metadata diagnostics are retained before interpretation, including on failure;
no unrelated compiled string values or test stdout are copied. The bundle also contains
focused/full test counts, lint locations, dependency hashes/notices, native
build options, and `apk-audit.json`. A passing build does not replace applicable
distribution obligations or real-device validation.

## Signing and device acceptance

CI preserves the audited unsigned standard release APK for `io.github.naza3.mnnchat`
and, after its gates pass, creates a separate signed testing copy. Signing is
outside Gradle; the unsigned-input audit and its recorded hash are unchanged.
The dedicated CI testing key is cached independently and is never passed to
Gradle or uploaded as an artifact. The signed APK is checked with `apksigner`,
16KiB `zipalign`, original ZIP payload comparison and a separate SHA-256.
Only its public certificate information belongs in the reports.

Keep the accompanying source/notices bundle with any APK handed to another
recipient. A testing key does not establish a production signing identity. Cache
loss can create a new test signer, and this key is not known to match an APK
previously signed elsewhere. Android can update an existing installation only
with a matching signer; use the original key to sign the unsigned artifact when
necessary. Do not uninstall a working installation just to work around a signing
mismatch: uninstalling can remove its model and conversation data. The person
shipping future production releases must own and preserve their release key.

Before declaring device acceptance, install a compatible signed arm64 build and
verify loopback-only binding, bearer-key rejection/rotation, API routes and SSE,
stop/restart and request cancellation, one-owner UI/API runtime arbitration,
notification controls, background/locked-screen behavior, and the real selected
model. For text chat, additionally check Home/lock during generation, Activity
recreation and return to the same conversation, saved final and stopped output,
repeated/stale notification Stop actions, completion and startup-failure service
cleanup, notification permission/channel denial, and model/API takeover while a
job is active. Verify that process death does not replay a request, that idle
model retention shows a static indicator with no generation wake lock, that busy
state remains visible when icon animation is unsupported, and that unloading
removes the model indicator.
Test 16KiB devices separately where relevant. User-selected model
provisioning and its license review are separate from CI; no model is bundled
or downloaded by this workflow.
