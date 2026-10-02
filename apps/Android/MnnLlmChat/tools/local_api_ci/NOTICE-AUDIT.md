# Dependency and redistribution audit

This is a test-build evidence bundle, not a statement of complete license
clearance. A successful compile, ELF/ZIP alignment check, or Maven POM does not
prove all redistribution obligations have been met.

- `dependency-inventory.json` lists the actual resolved release-runtime
  artifacts and SHA-256 digests. Available POM license declarations are evidence,
  not a substitute for reviewing transitive or embedded components.
- `notices/` and `notice-inventory.json` preserve available source notices and
  notices embedded in resolved AAR/JAR artifacts. Upstream Android packaging
  excludes some META-INF license files; this companion bundle preserves notices
  it can find. Any eventual redistribution must include the required notices.
- Primary `libMNN.so`, App JNI, and `libmnn_tts.so` are compiled from the frozen
  source checkout. `apk-audit.json` describes libraries actually in the APK,
  their hashes, ELF page alignment, and dynamic dependencies.
- Upstream's auxiliary `libsherpa-mnn-jni.so` is pinned by archive and ELF hash.
  The CDN archive has no source commit, build recipe, license text, or static
  dependency manifest. The repository's Sherpa LICENSE/NOTICE are included for
  context, but are not proof that this particular binary has the same complete
  dependency closure. Exact binary provenance and static-dependency notices
  remain an explicit redistribution blocker until independently established or
  the auxiliary library is rebuilt from audited, pinned sources.
- Header-only libraries may carry notices inside source headers. Included
  header excerpts are evidence for review; this collector does not claim to
  identify every compiled third-party component or produce a legal opinion.
- Firebase plugins and runtime collection are disabled by configuration, but
  upstream still declares Firebase runtime dependencies. Inspect the inventory
  and merged manifest; do not assume those components are absent from the APK.
- QNN is disabled. No proprietary QNN SDK/library, model weights, model license,
  user API key, signing key, or user conversation is supplied by this workflow.

Do not label this bundle "redistribution-ready" until the remaining native,
header-only, and JVM transitive notice obligations have been reviewed and met.
