#!/usr/bin/env bash
# Same enabled App features as upstream build.sh, with pinned tools and real JNI.
set -euo pipefail
HERE="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(git -C "$HERE" rev-parse --show-toplevel)"
: "${ANDROID_SDK_ROOT:?Set ANDROID_SDK_ROOT}"
REPORT_DIR="${REPORT_DIR:-$ROOT/local-api-ci-report}"
mkdir -p "$REPORT_DIR"
read_lock() { python3 -c 'import json,sys;print(json.load(open(sys.argv[1]))["toolchain"][sys.argv[2]])' "$HERE/build-lock.json" "$1"; }
export ANDROID_NDK="$ANDROID_SDK_ROOT/ndk/$(read_lock ndk)"
CMAKE="$ANDROID_SDK_ROOT/cmake/$(read_lock cmake)/bin/cmake"
python3 "$HERE/engine_source.py" --report-dir "$REPORT_DIR"
SOURCE="$MNN_ENGINE_SOURCE_ROOT"
BUILD="$MNN_ENGINE_INSTALL_ROOT"
[[ -x "$CMAKE" && -f "$ANDROID_NDK/build/cmake/android.toolchain.cmake" ]]
[[ ! -e "$BUILD/CMakeCache.txt" ]] || { echo 'Refusing to reuse an unverified native build cache' >&2; exit 1; }
"$CMAKE" -S "$SOURCE" -B "$BUILD" \
  -DCMAKE_TOOLCHAIN_FILE="$ANDROID_NDK/build/cmake/android.toolchain.cmake" \
  -DCMAKE_BUILD_TYPE=Release -DANDROID_ABI=arm64-v8a \
  -DANDROID_STL=c++_static -DANDROID_NATIVE_API_LEVEL=android-21 \
  -DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON \
  -DMNN_BUILD_SHARED_LIBS=ON -DMNN_BUILD_FOR_ANDROID_COMMAND=ON \
  -DMNN_BUILD_TEST=OFF -DMNN_BUILD_BENCHMARK=OFF -DMNN_USE_SSE=OFF \
  -DMNN_LOW_MEMORY=ON \
  -DMNN_BUILD_LLM=ON -DMNN_SUPPORT_TRANSFORMER_FUSE=ON \
  -DMNN_ARM82=ON -DMNN_USE_LOGCAT=ON -DMNN_OPENCL=ON -DMNN_KLEIDIAI=OFF \
  -DMNN_BUILD_OPENCV=ON -DMNN_IMGCODECS=ON \
  -DMNN_BUILD_AUDIO=ON -DMNN_BUILD_DIFFUSION=ON \
  -DMNN_SEP_BUILD=OFF -DMNN_QNN=OFF \
  '-DCMAKE_SHARED_LINKER_FLAGS=-Wl,-z,max-page-size=16384' \
  -DCMAKE_INSTALL_PREFIX="$BUILD"
"$CMAKE" --build "$BUILD" --parallel "${NATIVE_JOBS:-2}"
"$CMAKE" --install "$BUILD"
test -s "$BUILD/lib/libMNN.so"
sha256sum "$BUILD/lib/libMNN.so" > "$REPORT_DIR/libMNN.source-build.sha256"
# Only the explicit CMake settings are retained, not complete environment/cache dumps.
grep -E '^(MNN_|LLM_|ANDROID_ABI:|ANDROID_PLATFORM:|ANDROID_STL:|CMAKE_BUILD_TYPE:|CMAKE_(C|CXX)_COMPILER:|CMAKE_SHARED_LINKER_FLAGS:|BUILD_PLUGIN:)' \
  "$BUILD/CMakeCache.txt" > "$REPORT_DIR/native-build-options.txt"
"$ANDROID_NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/clang" --version > "$REPORT_DIR/native-compiler.txt"
