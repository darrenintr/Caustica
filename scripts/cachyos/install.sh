#!/usr/bin/env bash
# install.sh — one-shot setup for CachyOS / Arch.
#
# What this does (idempotent, safe to re-run):
#   1. Installs vendor-neutral runtime helpers (GameMode, ananicy-cpp, Vulkan tools).
#   2. Enables ananicy-cpp service (the daemon that reads our rules).
#   3. Installs the Caustica ananicy rule into /etc/ananicy.d/50-caustica.rules.
#   4. Optimises a few kernel sysctls (THP defrag, vm.swappiness, NR-hugepages).
#   5. Optionally builds the JAR (delegates to ./build.sh) — pass --build.
#   6. Symlinks run-caustica.sh into ~/.local/bin/caustica-launch so the
#      user can just `caustica-launch prism-launcher` from anywhere.
#
# Usage:
#   ./scripts/cachyos/install.sh                  # runtime setup only
#   ./scripts/cachyos/install.sh --build          # also build the JAR
#   ./scripts/cachyos/install.sh --skip-sysctl    # kernel sysctls are no-op
#   sudo ./scripts/cachyos/install.sh --build     # sudo needed for ananicy + sysctl
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"

DO_BUILD=0
APPLY_SYSCTL=1

while [[ $# -gt 0 ]]; do
    case "$1" in
        --build)        DO_BUILD=1; shift ;;
        --skip-sysctl)  APPLY_SYSCTL=0; shift ;;
        -h|--help)
            sed -n '3,18p' "$0"; exit 0 ;;
        *) echo "unknown flag: $1" >&2; exit 2 ;;
    esac
done

log()  { printf '\033[1;34m[install]\033[0m %s\n' "$*"; }
warn() { printf '\033[1;33m[install]\033[0m %s\n' "$*" >&2; }
die()  { printf '\033[1;31m[install]\033[0m %s\n' "$*" >&2; exit 1; }

[[ $EUID -eq 0 ]] || die "run as root: sudo $0 $*"

# ---------- 1. runtime deps ----------
log "installing runtime deps (pacman)"
pacman -S --needed --noconfirm \
    gamemode lib32-gamemode \
    numactl \
    vulkan-icd-loader vulkan-tools \
    ananicy-cpp ananicy-cpp-games 2>/dev/null || \
    warn "ananicy-cpp not in repos — install from AUR if you want auto-priority"

# ---------- 2. ananicy service ----------
if systemctl list-unit-files ananicy-cpp.service &>/dev/null; then
    log "enabling ananicy-cpp service"
    systemctl enable --now ananicy-cpp.service
else
    warn "ananicy-cpp.service unit not found; is ananicy-cpp installed? Ananicy rule will not apply until the daemon is running."
fi

# ---------- 3. ananicy rule ----------
log "installing ananicy rule -> /etc/ananicy.d/50-caustica.rules"
install -m 0644 "$SCRIPT_DIR/ananicy.d/50-caustica.rules" /etc/ananicy.d/50-caustica.rules
systemctl reload ananicy-cpp.service 2>/dev/null || true

# ---------- 4. sysctls ----------
if (( APPLY_SYSCTL )); then
    log "applying kernel sysctls (CachyOS-friendly)"
    install -m 0755 -d /etc/sysctl.d

    cat > /etc/sysctl.d/99-caustica.conf <<'EOF'
# Caustica-friendly kernel tuning for CachyOS / linux-cachyos.
# Effective immediately via sysctl --system; persists across reboots.

# Don't over-defrag hugepages — the renderer prefers THP-on-fault, not
# defrag-anything (which can stall allocation for hundreds of ms).
vm.transparent_hugepage = madvise

# Aggressively prefer RAM over swap. CachyOS default is already 10; we
# push lower to keep the 8 GiB Java heap resident. 1 means "only swap to
# avoid OOM kill" — appropriate when the user has plenty of RAM.
vm.swappiness = 10

# MGLRU (Multi-Gen LRU, kernel 6.1+) gives much better working-set
# estimation; the cachyos kernel enables it. We don't disable — but we
# also don't tune it from here. The auto-tuned values are correct for
# game workloads.

# Inotify watches — Java + Fabric + many mods can chew through the
# default 8192 quickly. Lifting to 524288 matches /proc/sys/fs/inotify/max_user_watches
# behavior on stock Arch.
fs.inotify.max_user_watches = 524288
EOF

    sysctl --system
    log "sysctls applied"
else
    log "skipping sysctls (--skip-sysctl)"
fi

# ---------- 5. optional build ----------
if (( DO_BUILD )); then
    log "delegating to build.sh"
    bash "$SCRIPT_DIR/build.sh"
fi

# ---------- 6. launcher symlink ----------
USER_BIN="/usr/local/bin/caustica-launch"
install -m 0755 "$SCRIPT_DIR/run-caustica.sh" "$USER_BIN"
log "launcher installed: $USER_BIN"
log "usage: caustica-launch <your-minecraft-launcher-cmd>"

log "done"
