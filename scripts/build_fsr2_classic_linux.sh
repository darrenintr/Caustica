#!/usr/bin/env bash
# Build classic FSR2.2 Vulkan (libffx_fsr2_caustica.so) for Caustica.
# Requires: git, cmake, g++, glslc, wine (for FidelityFX_SC.exe — optional if
#           pre-generated permutation headers already exist under the FSR2 tree).
#
# This script expects a checkout of GPUOpen-Effects/FidelityFX-FSR2 at
#   $FSR2_SRC (default: /tmp/FidelityFX-FSR2)
# and uses Caustica's fixed-permutation SPV path when SC fails.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
FSR2_SRC="${FSR2_SRC:-/tmp/FidelityFX-FSR2}"
OUT_LIB="${OUT_LIB:-$ROOT/src/main/resources/caustica/natives/linux-x64}"
BUILD="${BUILD:-/tmp/FidelityFX-FSR2-build/lib}"

if [[ ! -d "$FSR2_SRC/src/ffx-fsr2-api" ]]; then
  echo "Cloning FidelityFX-FSR2..."
  git clone --depth 1 https://github.com/GPUOpen-Effects/FidelityFX-FSR2.git "$FSR2_SRC"
fi

# Expect caustica_fsr2_export.cpp + patched sources already present after first
# successful agent build; re-run cmake/build:
cmake -S "$FSR2_SRC/src/ffx-fsr2-api" -B "$BUILD" -DCMAKE_BUILD_TYPE=Release
cmake --build "$BUILD" -j"$(nproc)"
mkdir -p "$OUT_LIB"
cp -f "$BUILD"/libffx_fsr2_caustica.so "$OUT_LIB/"
chmod +x "$OUT_LIB/libffx_fsr2_caustica.so"
ls -la "$OUT_LIB/libffx_fsr2_caustica.so"
echo "OK — rebuild jar with: bash gradlew jar"
