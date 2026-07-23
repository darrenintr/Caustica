#!/usr/bin/env bash
# Build Caustica on CachyOS / Arch without downloading vendor SDKs.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"

log()  { printf '\033[1;34m[build]\033[0m %s\n' "$*"; }
warn() { printf '\033[1;33m[build]\033[0m %s\n' "$*" >&2; }
die()  { printf '\033[1;31m[build]\033[0m %s\n' "$*" >&2; exit 1; }

pacman_install() {
    if (( EUID == 0 )); then
        pacman -S --needed --noconfirm "$@"
    else
        sudo pacman -S --needed --noconfirm "$@"
    fi
}

command -v pacman >/dev/null 2>&1 || die "this helper targets CachyOS/Arch (pacman not found)"

log "installing Java, Vulkan shader tools, and optional native-build tools"
pacman_install \
    jdk25-openjdk \
    glslang spirv-tools \
    vulkan-headers vulkan-tools vulkan-icd-loader \
    cmake ninja clang llvm lld \
    git base-devel

command -v javac >/dev/null 2>&1 || die "javac not found after package installation"
command -v glslangValidator >/dev/null 2>&1 || die "glslangValidator not found"
command -v spirv-val >/dev/null 2>&1 || die "spirv-val not found"

export JAVA_HOME="$(dirname "$(dirname "$(readlink -f "$(command -v javac)")")")"
export PATH="$JAVA_HOME/bin:$PATH"

log "JAVA_HOME=$JAVA_HOME"
log "building Java, SPIR-V shaders, tests, and the optional portable JNI bridge"
cd "$REPO_ROOT"
bash ./gradlew --no-daemon build

mapfile -t jars < <(find "$REPO_ROOT/build/libs" -maxdepth 1 -type f -name 'caustica-*.jar' \
    ! -name '*-sources.jar' ! -name '*-dev.jar' -print | sort)
if (( ${#jars[@]} == 0 )); then
    die "Gradle succeeded but no regular Caustica JAR was found under build/libs"
fi

log "build complete"
for jar in "${jars[@]}"; do
    log "  jar: $jar"
done
log "no NGX, DLSS, or XeSS SDK was downloaded; optional providers probe bundled natives at runtime"
