# Source, dependency, and redistribution evidence

The build uses recorded source inputs and preserves notices. A successful
compile or Maven license declaration is not a legal opinion or proof of every
possible downstream distribution obligation.

## Native libraries

Primary `libMNN.so`, App JNI, `libmnn_tts.so`, and the auxiliary
`libsherpa-mnn-jni.so` are built from the locked MNN repository. There is no
opaque Sherpa binary download or old-engine fallback. `apk-audit.json` records
what is actually packaged: native hashes, source/build identities, required JNI
exports, ELF/ZIP alignment, and dynamic/MNN-symbol dependency checks.

Sherpa's exact repository tree, configuration, linked static archive hashes,
MNN input hash, and JNI output hash are in `sherpa-provenance.json`. Its six
source dependency archives and SHA-256 values are in
`sherpa-source-inputs.json`. The exact original archives are supplied in
`sherpa-sources/`, including Eigen's source; preserve this source bundle or an
equivalent compliant source-availability mechanism when redistributing the APK.
The original OpenFST build-script patch is applied by the locked upstream
FetchContent recipe and identified in the report; the original archive remains
unchanged in the source bundle.

The required source notices are copied without paraphrase to
`sherpa-notices/`. They include:

- Sherpa LICENSE/NOTICE in the general `notices/` collection, preserving the
  Xiaomi/MNN attribution and sherpa-onnx derivation
- Apache-2.0 licenses for kaldi-native-fbank, kaldi-decoder, kaldifst, and
  simple-sentencepiece; OpenFST COPYING and AUTHORS
- Complete license-bearing source files for Ooura FFT (`fftsg.cc`), Darts
  (`darts.h`, BSD-2-Clause), and ThreadPool (`threadpool.h`, zlib-style)
- Eigen COPYING.README, COPYING.MPL2, and COPYING.BSD; compilation enforces
  `EIGEN_MPL2_ONLY` and the exact Eigen source archive accompanies the bundle

The selected Sherpa build includes the ASR functions MnnLlmChat uses. Unused
Sherpa TTS/speaker-diarization and demo/network/Python/test features are off,
so their optional dependencies (including eSpeak) are not fetched or linked.
The App's separate `mnn_tts` module and its existing enabled TTS features remain
unchanged. Final device ASR/TTS behavior must still be tested with the real
user-selected models; symbol checks do not substitute for inference.

## JVM and remaining source notices

`dependency-inventory.json` lists the actual resolved release-runtime artifacts
and hashes, with available POM license declarations. Original external JAR/AAR
bytes are recorded; local project classes are not misreported as Maven modules.
`local-project-inventory.json` separately identifies the selected local Android
runtime classes JARs, hashes, exact source commits/trees, and variant attributes.
Its classes-only coverage is explicit; native/resource evidence is in the source
identities and final APK audit. `notices/` and
`notice-inventory.json` preserve available source notices and notices embedded
in AAR/JAR artifacts. Upstream packaging excludes some META-INF license files;
the companion bundle preserves notices it can find. Include applicable notices
with any redistribution, and review transitive/embedded requirements rather
than treating a POM as complete clearance.

### Netty public TLS capability-test data

Netty `io.netty:netty-handler:4.1.119.Final` contains a public, fixed
`SslUtils.PROBING_KEY`, also inlined into `OpenSsl` and `JdkSslServerContext`.
The reviewed same-version source uses it for certificate-callback and wrapping
trust-manager capability probes. App server credentials are separate inputs.
The key is already public test data and must never be used as a production
credential. Its exact 1699-byte constant SHA-256 is
`67153216753676a99069a64f0986ef7015efed5eca8d545261c6c1aa9e9117c8`.

`build-lock.json` pins the official Maven binary and source URLs/hashes.
`public-test-fixture-provenance.json` records verification of both archives and
three binary constant pools; the APK audit additionally cross-checks the actual
Gradle runtime binary. The scanner allows only that exact complete DEX string
and reports it as `public_upstream_tls_capability_test_fixture`. It does not
exempt other keys, other strings in the same DEX, the library, or changed bytes.
No key body is copied into diagnostic reports. This scoped classification does
not alter Netty's license/NOTICE requirements or establish legal clearance.

Header-only libraries can carry additional notices in source headers. The
collector includes available relevant header evidence; it does not claim a
comprehensive legal assessment of all native/JVM code. Firebase plugins and
collection are disabled, but upstream still declares Firebase dependencies;
inspect the actual inventory and merged manifest before describing them.

QNN is off. No proprietary QNN asset, model weights, model license, user API
key, signing key, or user conversation is supplied by this workflow.
