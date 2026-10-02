#!/usr/bin/env bash
# Build the App's ASR JNI from the same locked source tree as MNN.
set -euo pipefail
HERE="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(git -C "$HERE" rev-parse --show-toplevel)"
: "${ANDROID_SDK_ROOT:?Set ANDROID_SDK_ROOT}"
REPORT_DIR="${REPORT_DIR:-$ROOT/local-api-ci-report}"
read_lock() { python3 -c 'import json,sys;print(json.load(open(sys.argv[1]))["toolchain"][sys.argv[2]])' "$HERE/build-lock.json" "$1"; }
NDK="$ANDROID_SDK_ROOT/ndk/$(read_lock ndk)"
CMAKE="$ANDROID_SDK_ROOT/cmake/$(read_lock cmake)/bin/cmake"
SOURCE="$ROOT/apps/frameworks/sherpa-mnn"
# Under App/build so all generated sources/artifacts are outside tracked code.
BUILD="$ROOT/apps/Android/MnnLlmChat/app/build/local-api-sherpa"
MNN="$ROOT/project/android/build_64"
DESTINATION="$ROOT/apps/Android/MnnLlmChat/app/src/main/jniLibs/arm64-v8a/libsherpa-mnn-jni.so"
[[ -x "$CMAKE" && -s "$MNN/lib/libMNN.so" ]]
[[ ! -e "$BUILD/CMakeCache.txt" ]] || { echo 'Refusing to reuse an unverified Sherpa build cache' >&2; exit 1; }
python3 "$HERE/prepare_sherpa_sources.py" --build-dir "$BUILD" --report-dir "$REPORT_DIR"
# Archives are found via the original CMAKE_BINARY_DIR lookup. Do not replace
# FETCHCONTENT_SOURCE_DIR: that would bypass upstream's OpenFST PATCH_COMMAND.
"$CMAKE" -G "Unix Makefiles" -S "$SOURCE" -B "$BUILD" \
  -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
  -DCMAKE_BUILD_TYPE=Release -DCMAKE_POSITION_INDEPENDENT_CODE=ON \
  -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-26 \
  -DANDROID_STL=c++_shared -DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON \
  -DCMAKE_TLS_VERIFY=ON -DFETCHCONTENT_UPDATES_DISCONNECTED=ON \
  -DMNN_LIB_DIR="$MNN" -DBUILD_SHARED_LIBS=OFF \
  -DSHERPA_MNN_ENABLE_JNI=ON -DSHERPA_MNN_ENABLE_TTS=OFF \
  -DSHERPA_MNN_ENABLE_SPEAKER_DIARIZATION=OFF -DSHERPA_MNN_ENABLE_BINARY=OFF \
  -DSHERPA_MNN_ENABLE_C_API=OFF -DSHERPA_MNN_BUILD_C_API_EXAMPLES=OFF \
  -DSHERPA_MNN_ENABLE_WEBSOCKET=OFF -DSHERPA_MNN_ENABLE_PORTAUDIO=OFF \
  -DSHERPA_MNN_ENABLE_PYTHON=OFF -DSHERPA_MNN_ENABLE_TESTS=OFF -DSHERPA_MNN_ENABLE_CHECK=OFF \
  -DCMAKE_CXX_FLAGS=-DEIGEN_MPL2_ONLY \
  '-DCMAKE_SHARED_LINKER_FLAGS=-Wl,-z,max-page-size=16384'
"$CMAKE" --build "$BUILD" --target sherpa-mnn-jni --parallel "${NATIVE_JOBS:-2}"
test -s "$BUILD/lib/libsherpa-mnn-jni.so"
mkdir -p "$(dirname "$DESTINATION")"
cp "$BUILD/lib/libsherpa-mnn-jni.so" "$DESTINATION"
python3 "$HERE/record_sherpa_build.py" --build-dir "$BUILD" --library "$DESTINATION" --report-dir "$REPORT_DIR"
