#!/usr/bin/env bash
# run-caustica.sh — runtime launcher for Caustica on CachyOS / Arch Linux.
#
# This is NOT a Minecraft launcher. It's a thin wrapper that:
#   1. Sets the env Caustica needs at runtime (LD_LIBRARY_PATH, NVIDIA vars,
#      VK_ICD_FILENAMES, JAVA_TOOL_OPTIONS, etc.).
#   2. Activates gamemode (CPU/GPU frequency policy, governor pinning, etc.).
#   3. Sets per-process priority via nice/ionice + ananicy-cpp rule (see
#      ananicy.d/50-caustica.rules).
#   4. Optionally binds the process to a NUMA node closest to the GPU.
#   5. execs whatever command you pass in (your launcher, java -jar ..., etc.).
#
# Usage:
#   ./scripts/cachyos/run-caustica.sh prism-launcher
#   ./scripts/cachyos/run-caustica.sh java -jar ~/.minecraft/launcher/minecraft.jar
#   ./scripts/cachyos/run-caustica.sh /opt/multimc/MultiMC
#   ./scripts/cachyos/run-caustica.sh -n 0 prism-launcher     # NUMA-bind to node 0
#   ./scripts/cachyos/run-caustica.sh --no-gamemode java -jar launcher.jar
#
# All JDK 25 system properties (caustica.*) match CausticaConfig.java.
set -euo pipefail

# ---------- defaults ----------
USE_GAMEMODE=1
USE_NUMA=auto    # auto | off | <node>
USE_ANANICY=1
JAVA_HEAP=8G
PRIORITY_NICE=-5
PRIORITY_IONICE=best-effort:1

# ---------- arg parsing ----------
while [[ $# -gt 0 ]]; do
    case "$1" in
        --no-gamemode) USE_GAMEMODE=0; shift ;;
        --no-ananicy)  USE_ANANICY=0;  shift ;;
        -n|--numa)     USE_NUMA="$2";  shift 2 ;;
        -Xmx)          JAVA_HEAP="$2"; shift 2 ;;
        -h|--help)
            sed -n '3,18p' "$0"; exit 0 ;;
        --) shift; break ;;
        -*) echo "unknown flag: $1" >&2; exit 2 ;;
        *)  break ;;
    esac
done

[[ $# -gt 0 ]] || { echo "usage: $0 [--no-gamemode] [--no-ananicy] [-n auto|off|<node>] [-Xmx <size>] <command> [args...]" >&2; exit 2; }

log()  { printf '\033[1;34m[run]\033[0m %s\n' "$*"; }
warn() { printf '\033[1;33m[run]\033[0m %s\n' "$*" >&2; }

# ---------- env: NVIDIA / Vulkan ----------
# Closed driver puts libnvidia-ngx.so.1 / libGLX_nvidia.so.0 in /usr/lib on
# CachyOS; LD_LIBRARY_PATH lets dlopen() find them when Caustica loads NGX.
NVIDIA_LIB_DIRS=(
    /usr/lib
    /usr/lib/nvidia
    /usr/lib32
    /usr/lib/opengl/nvidia
)
NVIDIA_LIB=""
for d in "${NVIDIA_LIB_DIRS[@]}"; do
    if [[ -e "$d/libnvidia-ngx.so.1" ]]; then
        NVIDIA_LIB="$d"
        break
    fi
done
if [[ -n "$NVIDIA_LIB" ]]; then
    export LD_LIBRARY_PATH="$NVIDIA_LIB${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}"
    log "LD_LIBRARY_PATH += $NVIDIA_LIB (libnvidia-ngx.so.1)"
else
    warn "libnvidia-ngx.so.1 not found in standard paths — DLSS will not load."
    warn "Install nvidia-utils (and lib32-nvidia-utils for 32-bit helpers):"
    warn "    sudo pacman -S nvidia-dkms nvidia-utils lib32-nvidia-utils"
fi

# Threaded OpenGL optimisations + persistent shader cache
export __GL_THREADED_OPTIMIZATIONS=1
export __GL_SHADER_DISK_CACHE=1
export __GL_SHADER_DISK_CACHE_PATH="${XDG_CACHE_HOME:-$HOME/.cache}/caustica/gl-cache"
export __GL_SHADER_DISK_CACHE_SIZE=1073741824   # 1 GiB
mkdir -p "$__GL_SHADER_DISK_CACHE_PATH"

# Mesa Vulkan override (AMD/Intel): tell the loader which ICD to use first
if [[ -z "${VK_ICD_FILENAMES:-}" ]]; then
    if [[ -n "$NVIDIA_LIB" && -e /usr/share/vulkan/icd.d/nvidia_icd.json ]]; then
        export VK_ICD_FILENAMES=/usr/share/vulkan/icd.d/nvidia_icd.json
    fi
fi

# Sync RT scheduler so Vulkan / NGX / Mesa can rely on tighter wakeups
export __VK_LAYER_NV_optimus="NVIDIA,AMD"   # harmless if env doesn't read it
# (some Mesa builds read MESA_VK_DEVICE_SELECT to avoid selecting the wrong GPU)
export MESA_VK_DEVICE_SELECT=list

# ---------- env: JVM / mod flags ----------
# -Xss16m because NGX allocates ~1MB on the stack during init (matches the
#   vmArg in build.gradle:loom.runs.client).
# -XX:+UseTransparentHugePages asks the JVM to back its heap with kernel
#   THP — big win on linux-cachyos where the kernel pre-allocates 2 MiB
#   pages. (Kernel >=4.8 needed; CachyOS kernel is well past that.)
# -XX:+UseLargePages requires vm.nr_hugepages > 0; we don't preallocate
#   here — THP is good enough and doesn't need root tuning.
# -XX:+UseNUMA: 2-socket / multi-CCX systems get better heap placement.
# -XX:+UseCompactObjectHeaders: smaller headers, smaller heap, less GC.
# ZGC is low-pause but uses more memory; we cap the heap at 8 GiB by default.
export JAVA_TOOL_OPTIONS="${JAVA_TOOL_OPTIONS:-} -Xmx${JAVA_HEAP} \
    -Xss16m \
    -XX:+UseCompactObjectHeaders \
    -XX:+AlwaysPreTouch \
    -XX:+UseStringDeduplication \
    -XX:+UseZGC \
    -XX:+UseTransparentHugePages \
    -XX:+UseNUMA \
    -XX:InitiatingHeapOccupancyPercent=45 \
    -XX:ZCollectionInterval=2 \
    -XX:SoftRefLRUPolicyMSPerMB=2000 \
    -Dcaustica.rt.fg=false \
    -Dcaustica.rt.reflex=false \
    -Dcaustica.rt.frameStats=false \
    -Dcaustica.rt.lodWorld=true"

# A separate env so the user can grep it from a debug log
log "JAVA_TOOL_OPTIONS=$JAVA_TOOL_OPTIONS"

# ---------- env: Wayland / HDR ----------
# Caustica selects GLFW's native Wayland backend for HDR. Make sure the
# launcher inherits the right env so GLSurface picks Wayland, not X11.
if [[ -n "${WAYLAND_DISPLAY:-}" ]]; then
    log "Wayland session detected ($WAYLAND_DISPLAY) — GLFW should pick native wayland backend"
    # Don't force GDK_BACKEND; the launcher's bundled JVM usually defaults
    # to wayland when WAYLAND_DISPLAY is set.
fi

# ---------- gamemode ----------
GAMEMODE_BIN="$(command -v gamemoderun || true)"
if (( USE_GAMEMODE )) && [[ -n "$GAMEMODE_BIN" ]]; then
    log "gamemode active"
    GAMEMODE_WRAPPER=("$GAMEMODE_BIN")
else
    (( USE_GAMEMODE )) && warn "gamemoderun not in PATH — skipping (pacman -S gamemode lib32-gamemode)"
    GAMEMODE_WRAPPER=()
fi

# ---------- ananicy-cpp ----------
# If ananicy-cpp daemon is running, the rule file installed by install.sh
# auto-classifies our process tree. Manual nice/ionice here is the
# belt-and-braces fallback for systems without ananicy.
if (( USE_ANANICY )) && ! pgrep -x ananicy >/dev/null 2>&1; then
    warn "ananicy-cpp not running — applying manual nice/ionice ($PRIORITY_NICE / $PRIORITY_IONICE)"
    nice -n "$PRIORITY_NICE" -t ionice -c best-effort -n 1 -- \
        "${GAMEMODE_WRAPPER[@]}" "$@"
    exit $?
fi

# ---------- NUMA ----------
NUMA_WRAPPER=()
if [[ "$USE_NUMA" != "off" ]]; then
    if command -v numactl >/dev/null 2>&1; then
        # If user didn't override, bind to the node the GPU lives on.
        # For single-GPU systems there's only one node, so this is a no-op.
        if [[ "$USE_NUMA" == "auto" ]]; then
            # A simple heuristic: nvidia-smi reports GPU's PCI bus; the
            # NUMA node for that bus is in /sys/bus/pci/devices/.../numa_node.
            GPU_NODE=""
            if command -v nvidia-smi >/dev/null 2>&1; then
                GPU_BUSID="$(nvidia-smi --query-gpu=pci.bus_id --format=csv,noheader,nounits 2>/dev/null | head -1 | tr -d ' ')"
                if [[ -n "$GPU_BUSID" ]]; then
                    # bus_id is like "0000:01:00.0" -> "0000:01:00.0" maps to /sys/bus/pci/devices/...
                    GPU_NODE="$(cat "/sys/bus/pci/devices/${GPU_BUSID}/numa_node" 2>/dev/null || true)"
                fi
            fi
            if [[ "$GPU_NODE" =~ ^[0-9]+$ ]]; then
                log "binding to NUMA node $GPU_NODE (GPU lives there)"
                NUMA_WRAPPER=(numactl --cpunodebind="$GPU_NODE" --membind="$GPU_NODE")
            else
                log "could not detect GPU NUMA node — running without numactl"
            fi
        else
            log "binding to NUMA node $USE_NUMA (manual override)"
            NUMA_WRAPPER=(numactl --cpunodebind="$USE_NUMA" --membind="$USE_NUMA")
        fi
    fi
fi

# ---------- launch ----------
log "exec: ${NUMA_WRAPPER[*]:-} ${GAMEMODE_WRAPPER[*]:-} $*"
exec "${NUMA_WRAPPER[@]}" "${GAMEMODE_WRAPPER[@]}" "$@"
