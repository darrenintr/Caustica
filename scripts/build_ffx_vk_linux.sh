#!/usr/bin/env bash
# build_ffx_vk_linux.sh — build official FidelityFX components for Caustica on Linux/Vulkan.
#
# Outputs (under build/ffx_vk/ and optionally resources):
#   libffx_denoiser_caustica.so   — probe + create/destroy/dispatchShadows/dispatchReflections
#   libffx_fsr2_caustica.so       — classic FSR2 upscaler (Vulkan) when shaders build succeeds
#
# Prerequisites: cmake, g++/clang++, glslc (vulkan-tools), python3
# FFX_SDK defaults to third_party/FidelityFX-SDK
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
FFX_SDK="${FFX_SDK:-$ROOT/third_party/FidelityFX-SDK}"
OUT="${FFX_VK_OUT:-$ROOT/build/ffx_vk}"
RES="${FFX_VK_RES:-$ROOT/src/main/resources/caustica/natives/linux-x64}"
JOBS="${JOBS:-$(nproc)}"

log()  { printf '\033[1;34m[ffx-vk]\033[0m %s\n' "$*"; }
warn() { printf '\033[1;33m[ffx-vk]\033[0m %s\n' "$*" >&2; }
die()  { printf '\033[1;31m[ffx-vk]\033[0m %s\n' "$*" >&2; exit 1; }

command -v cmake >/dev/null || die "cmake required"
command -v glslc >/dev/null || die "glslc required (vulkan-tools)"
[[ -f "$FFX_SDK/sdk/include/FidelityFX/host/ffx_denoiser.h" ]] || die "FFX_SDK missing ffx_denoiser.h: $FFX_SDK"

mkdir -p "$OUT" "$RES"

# ---------- Denoiser shim (probe always; FULL when CAUSTICA_FFX_DENOISER_LINK_SDK=ON) ----------
log "Building ffx_denoiser_caustica (probe / FULL if libs present)"
cmake -S "$ROOT/native/ffx_denoiser" -B "$OUT/denoiser" \
  -DCMAKE_BUILD_TYPE=Release \
  -DFFX_SDK="$FFX_SDK" \
  -DCAUSTICA_FFX_DENOISER_LINK_SDK="${CAUSTICA_FFX_DENOISER_LINK_SDK:-OFF}"
cmake --build "$OUT/denoiser" -j"$JOBS"
cp -f "$OUT/denoiser"/libffx_denoiser_caustica.so "$OUT/" 2>/dev/null || \
  cp -f "$OUT/denoiser"/libffx_denoiser*.so "$OUT/" 2>/dev/null || true

# ---------- FSR2 classic (optional: needs generated shaderblobs) ----------
if [[ -f "$ROOT/native/ffx_fsr2/CMakeLists.txt" ]]; then
  log "Building ffx_fsr2_caustica (classic FSR2 Vulkan)"
  if cmake -S "$ROOT/native/ffx_fsr2" -B "$OUT/fsr2" \
        -DCMAKE_BUILD_TYPE=Release \
        -DFFX_SDK="$FFX_SDK" 2>"$OUT/fsr2_cmake.err"; then
    if cmake --build "$OUT/fsr2" -j"$JOBS" 2>"$OUT/fsr2_build.err"; then
      cp -f "$OUT/fsr2"/libffx_fsr2_caustica.so "$OUT/" 2>/dev/null || true
      log "FSR2 native OK"
    else
      warn "FSR2 build failed (see $OUT/fsr2_build.err) — usually missing permutation headers from ffx_sc"
    fi
  else
    warn "FSR2 cmake configure failed (see $OUT/fsr2_cmake.err)"
  fi
else
  warn "native/ffx_fsr2 not present yet — denoiser only"
fi

# ---------- Install into mod resources ----------
log "Installing into $RES"
for so in "$OUT"/libffx_denoiser_caustica.so "$OUT"/libffx_fsr2_caustica.so; do
  [[ -f "$so" ]] && cp -f "$so" "$RES/" && chmod +x "$RES/$(basename "$so")"
done

log "Done. Artifacts:"
ls -la "$OUT"/libffx_*.so 2>/dev/null || true
ls -la "$RES"/libffx_*.so 2>/dev/null || true
echo
echo "Next: JAVA_HOME=… bash gradlew jar  && reinstall mod"
echo "Note: modular libamd_fidelityfx_upscaler.so stub cannot provide FSR3-VK;"
echo "      use classic FSR2 when libffx_fsr2_caustica.so is present."
