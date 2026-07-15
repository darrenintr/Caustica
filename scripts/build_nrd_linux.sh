#!/usr/bin/env bash
# Build libnrd_caustica.so (NRD REBLUR Vulkan) for Caustica.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
NRD_SRC="${NRD_SRC:-/tmp/NRD}"
NRD_BUILD="${NRD_BUILD:-/tmp/NRD-build}"
OUT_LIB="${OUT_LIB:-$ROOT/src/main/resources/caustica/natives/linux-x64}"
SHIM_BUILD="${SHIM_BUILD:-$ROOT/build/nrd}"

if [[ ! -d "$NRD_SRC/Include" ]]; then
  git clone --depth 1 https://github.com/NVIDIA-RTX/NRD.git "$NRD_SRC"
fi

if [[ ! -f "$NRD_SRC/_Bin/libNRD.a" ]]; then
  cmake -S "$NRD_SRC" -B "$NRD_BUILD" \
    -DNRD_STATIC_LIBRARY=ON -DNRD_NRI=OFF -DNRD_EMBEDS_SPIRV_SHADERS=ON \
    -DNRD_NORMAL_ENCODING=4 -DNRD_ROUGHNESS_ENCODING=1 \
    -DCMAKE_BUILD_TYPE=Release
  cmake --build "$NRD_BUILD" -j"$(nproc)"
fi

SM_BLOB="${NRD_BUILD}/_deps/shadermake-build/libShaderMakeBlob.a"
if [[ ! -f "$SM_BLOB" ]]; then
  echo "ERROR: missing $SM_BLOB (rebuild NRD so ShaderMakeBlob is built)"
  exit 1
fi

cmake -S "$ROOT/native/nrd" -B "$SHIM_BUILD" \
  -DNRD_ROOT="$NRD_SRC" \
  -DSHADERMAKE_BLOB_LIB="$SM_BLOB" \
  -DCMAKE_BUILD_TYPE=Release
cmake --build "$SHIM_BUILD" -j"$(nproc)"

# Fail if ShaderMake symbols still undefined (would crash at load in JVM)
if nm -D "$SHIM_BUILD"/libnrd_caustica.so 2>/dev/null | grep -q ' U .*FindPermutationInBlob'; then
  echo "ERROR: FindPermutationInBlob still undefined in libnrd_caustica.so"
  exit 1
fi

mkdir -p "$OUT_LIB"
cp -f "$SHIM_BUILD"/libnrd_caustica.so "$OUT_LIB/"
chmod +x "$OUT_LIB/libnrd_caustica.so"
ls -la "$OUT_LIB/libnrd_caustica.so"
echo "OK — rebuild jar: JAVA_HOME=… bash gradlew jar"
