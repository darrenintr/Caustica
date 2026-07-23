#!/usr/bin/env bash
# Thin, vendor-neutral runtime wrapper for Caustica on CachyOS / Arch.
set -euo pipefail

USE_GAMEMODE=1
USE_ANANICY=1
USE_NUMA=off
JAVA_HEAP=8G

while [[ $# -gt 0 ]]; do
    case "$1" in
        --no-gamemode) USE_GAMEMODE=0; shift ;;
        --no-ananicy)  USE_ANANICY=0; shift ;;
        -n|--numa)     USE_NUMA="${2:?missing NUMA node}"; shift 2 ;;
        -Xmx)          JAVA_HEAP="${2:?missing heap size}"; shift 2 ;;
        -h|--help)
            cat <<'HELP'
usage: run-caustica.sh [options] <launcher> [args...]

Options:
  --no-gamemode       Do not wrap the launcher with gamemoderun
  --no-ananicy        Ignore ananicy-cpp status and use neutral priorities
  -n, --numa NODE     Bind CPU and memory to an explicit NUMA node, or "off"
  -Xmx SIZE           Java heap size appended to JAVA_TOOL_OPTIONS (default: 8G)
HELP
            exit 0 ;;
        --) shift; break ;;
        -*) echo "unknown flag: $1" >&2; exit 2 ;;
        *) break ;;
    esac
done

[[ $# -gt 0 ]] || { echo "no launcher command supplied; use --help" >&2; exit 2; }

log()  { printf '\033[1;34m[run]\033[0m %s\n' "$*"; }
warn() { printf '\033[1;33m[run]\033[0m %s\n' "$*" >&2; }

# Do not set VK_ICD_FILENAMES, vendor driver variables, or vendor SDK search
# paths. Device/ICD selection belongs to the Vulkan loader and Minecraft backend.
export JAVA_TOOL_OPTIONS="${JAVA_TOOL_OPTIONS:-} \
-Xmx${JAVA_HEAP} \
-Xss2M \
-XX:+UseCompactObjectHeaders \
-XX:+AlwaysPreTouch \
-XX:+UseStringDeduplication \
-XX:+UseZGC \
-Dcaustica.rt.fg=false \
-Dcaustica.rt.frameStats=false"
log "JAVA_TOOL_OPTIONS=$JAVA_TOOL_OPTIONS"

if [[ -n "${WAYLAND_DISPLAY:-}" ]]; then
    log "native Wayland session detected ($WAYLAND_DISPLAY); HDR surface support will be probed by Vulkan"
fi

wrappers=()
if (( USE_GAMEMODE )); then
    if command -v gamemoderun >/dev/null 2>&1; then
        wrappers+=(gamemoderun)
    else
        warn "gamemoderun not found; continuing without GameMode"
    fi
fi

if (( USE_ANANICY )) && ! pgrep -x ananicy-cpp >/dev/null 2>&1 && ! pgrep -x ananicy >/dev/null 2>&1; then
    warn "ananicy-cpp is not running; continuing with normal process priority"
fi

if [[ "$USE_NUMA" != "off" ]]; then
    if ! [[ "$USE_NUMA" =~ ^[0-9]+$ ]]; then
        echo "NUMA node must be a non-negative integer or 'off': $USE_NUMA" >&2
        exit 2
    fi
    if command -v numactl >/dev/null 2>&1; then
        wrappers+=(numactl --cpunodebind="$USE_NUMA" --membind="$USE_NUMA")
    else
        warn "numactl not found; ignoring --numa $USE_NUMA"
    fi
fi

log "exec: ${wrappers[*]:-}$([[ ${#wrappers[@]} -gt 0 ]] && printf ' ')$*"
exec "${wrappers[@]}" "$@"
