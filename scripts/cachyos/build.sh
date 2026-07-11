#!/usr/bin/env bash
# build.sh — build Caustica with linux-x64 native bundle on CachyOS / Arch.
#
# What this does, in order:
#   1. Installs build + runtime deps from pacman (JDK 25, Vulkan tools, glslang,
#      spirv-val, cmake/ninja/llvm, gamemode, ananicy-cpp, numactl, etc.).
#   2. Clones the DLSS / FFX / XeSS SDKs into ./third_party/ (matches the
#      DLSS_SDK / FFX_SDK / XESS_SDK env vars ci.yml uses; build.gradle
#      reads those to find the vendor .so files).
#   3. Compiles the native NGX shim (native/ngx_shim) with the system clang.
#   4. Runs ./gradlew build with -PngxPlatforms=linux-x64
#      -PfsrPlatforms=linux-x64 -PxessPlatforms=linux-x64 so the JAR ships
#      caustica/natives/linux-x64/{libngxshim.so, libnvidia-ngx-*.so,
#      libamd_fidelityfx_*.so} inside it.
#
# After this, the JAR at build/libs/caustica-*-linux-x64.jar is the
# standalone Linux build — drop it into ~/.minecraft/mods/ on CachyOS.
#
# Idempotent. Re-run to refresh.
set -euo pipefail

# ---------- config ----------
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
THIRD_PARTY="$REPO_ROOT/third_party"
SHIM_OUT="$REPO_ROOT/build/cmake/ngx_shim/release"
SHIM_CONFIG="release"

# Match ci.yml env block
DLSS_SDK_REF="v310.7.0"
FFX_SDK_REF="fsr3-v3.0.4"
XESS_SDK_REF="v3.0.1"

# ---------- helpers ----------
log()  { printf '\033[1;34m[build]\033[0m %s\n' "$*"; }
warn() { printf '\033[1;33m[build]\033[0m %s\n' "$*" >&2; }
die()  { printf '\033[1;31m[build]\033[0m %s\n' "$*" >&2; exit 1; }

# Run pacman via sudo unless we're already root
pacman_install() {
    if (( EUID == 0 )); then
        pacman -S --needed --noconfirm "$@"
    else
        sudo pacman -S --needed --noconfirm "$@"
    fi
}

need_cmd() {
    command -v "$1" >/dev/null 2>&1 || die "missing required command: $1"
}

# ---------- sanity ----------
need_cmd pacman
need_cmd git

cd "$REPO_ROOT"

# ---------- 1. install deps ----------
# CachyOS's repo (and Arch's) ship everything we need; no AUR round trip
# for the basic stack. (For JDK 25 specifically: jdk25-openjdk is in
# [extra] as of 2025-Q4; if your tree only has jdk21-openjdk, fall back
# to jdk25-temurin from AUR.)
log "installing build + runtime deps (pacman)"
pacman_install \
    jdk25-openjdk \
    cmake ninja \
    glslang spirv-tools \
    vulkan-headers vulkan-tools vulkan-icd-loader \
    clang llvm lld \
    git base-devel \
    gamemode lib32-gamemode \
    numactl \
    nss libxcomposite libxrandr libxkbcommon \
    mesa-demos

# ananicy-cpp is on CachyOS-extra; if you have a vanilla Arch box swap
# to ananicy-cpp-git from AUR.
pacman_install ananicy-cpp ananicy-cpp-games 2>/dev/null || \
    warn "ananicy-cpp not in your repos — install from AUR for the auto-priority benefits"

# ---------- 2. fetch SDKs ----------
log "fetching DLSS / FFX / XeSS SDKs into $THIRD_PARTY"
mkdir -p "$THIRD_PARTY"
export DLSS_SDK="$THIRD_PARTY/DLSS"
export FFX_SDK="$THIRD_PARTY/FidelityFX-SDK"
export XESS_SDK="$THIRD_PARTY/xess"

clone_or_update() {
    local url="$1" ref="$2" dest="$3"
    if [[ -d "$dest/.git" ]]; then
        log "  updating $(basename "$dest") -> $ref"
        git -C "$dest" fetch --depth 1 origin "refs/tags/$ref:refs/tags/$ref" 2>/dev/null || \
            git -C "$dest" fetch --depth 1 origin "refs/heads/$ref:refs/remotes/origin/$ref"
        git -C "$dest" checkout -q "$ref"
    else
        log "  cloning $(basename "$dest") @ $ref"
        git clone --depth 1 --branch "$ref" "$url" "$dest"
    fi
}

clone_or_update "https://github.com/NVIDIA/DLSS.git"                  "$DLSS_SDK_REF" "$DLSS_SDK"
clone_or_update "https://github.com/GPUOpen-LibrariesAndSDKs/FidelityFX-SDK.git" "$FFX_SDK_REF" "$FFX_SDK"
clone_or_update "https://github.com/intel/xess.git"                   "$XESS_SDK_REF" "$XESS_SDK"

# Sanity-check the SDK layouts the build.gradle expects.
[[ -d "$DLSS_SDK/lib/Linux_x86_64" ]] || die "DLSS SDK missing lib/Linux_x86_64 at $DLSS_SDK — wrong ref?"
[[ -d "$FFX_SDK/bin/Linux_x64" ]]    || die "FFX SDK missing bin/Linux_x64 at $FFX_SDK — wrong ref?"

if [[ ! -f "$XESS_SDK/bin/libxess.so" ]]; then
    warn "XeSS SDK has no bin/libxess.so at $XESS_SDK — public releases on github.com/intel/xess"
    warn "ship Windows binaries only. To get libxess.so you need Intel DevZone access."
    warn "build.gradle will skip the XeSS native bundle; the mod will log a warning and continue."
fi

# ---------- 3. native shim ----------
# native/ngx_shim/CMakeLists.txt picks up the system Vulkan headers
# (vulkan-headers) and links the DLSS static lib from $DLSS_SDK/lib/Linux_x86_64.
# The CMakeLists hard-requires VULKAN_SDK; on Arch/CachyOS the `vulkan-headers`
# package puts vulkan/vulkan.h under /usr/include, so VULKAN_SDK=/usr makes
# the shim's ${VULKAN_SDK}/include include path resolve correctly.
log "compiling native NGX shim (linux-x64 / $SHIM_CONFIG)"
mkdir -p "$SHIM_OUT"
export VULKAN_SDK=/usr
cmake -S native/ngx_shim -B "$SHIM_OUT" \
      -G Ninja \
      -DCMAKE_BUILD_TYPE="$SHIM_CONFIG" \
      -DCMAKE_C_COMPILER=clang \
      -DCMAKE_CXX_COMPILER=clang++ \
      -DCMAKE_EXE_LINKER_FLAGS="-fuse-ld=lld" \
      -DCMAKE_SHARED_LINKER_FLAGS="-fuse-ld=lld"
cmake --build "$SHIM_OUT" --config "$SHIM_CONFIG"

# Confirm the shim produced a .so
[[ -f "$SHIM_OUT/libngxshim.so" ]] || die "shim build did not produce $SHIM_OUT/libngxshim.so"

# ---------- 4. gradle build ----------
# JAVA_HOME points gradle at the system JDK 25; without this the wrapper
# would try its own (older) toolchain.
export JAVA_HOME="$(dirname "$(dirname "$(readlink -f "$(command -v javac)")")")"
log "JAVA_HOME=$JAVA_HOME ($(java -version 2>&1 | head -1))"

# VULKAN_SDK is intentionally NOT set. build.gradle's resolveVulkanTool()
# tries ${VULKAN_SDK}/Bin/ (Windows-style capital B) which never matches
# on Linux; the PATH fallback to /usr/bin/{glslangValidator,spirv-val}
# (from the `glslang` + `spirv-tools` packages) is what we want.
unset VULKAN_SDK

log "./gradlew build -PngxPlatforms=linux-x64 -PfsrPlatforms=linux-x64 -PxessPlatforms=linux-x64"
cd "$REPO_ROOT"
./gradlew --no-daemon build \
    -PngxPlatforms=linux-x64 \
    -PfsrPlatforms=linux-x64 \
    -PxessPlatforms=linux-x64

# ---------- done ----------
JAR=$(ls -1 "$REPO_ROOT/build/libs/"caustica-*.jar | head -1)
log "build complete"
log "  jar: $JAR"
log "  bundled natives:"
ls -l "$REPO_ROOT/build/generated/ngx-natives/caustica/natives/linux-x64/" 2>/dev/null | sed 's/^/    /' || true
log "next: drop the JAR into ~/.minecraft/mods/ and use scripts/cachyos/run-caustica.sh to launch"
