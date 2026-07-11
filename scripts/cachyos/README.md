# Caustica on CachyOS / Arch

CachyOS-flavored build + runtime pack for [Caustica](../../). Provides:

- `build.sh` — full build pipeline on CachyOS: deps + DLSS/FFX/XeSS SDK fetch
  + native NGX shim + `./gradlew build -PngxPlatforms=linux-x64 ...`. Produces
  a JAR with all `linux-x64` natives bundled.
- `run-caustica.sh` — runtime launcher: env wiring (NVIDIA, Vulkan, JVM
  flags) + gamemode + ananicy + nice/ionice + NUMA bind. `exec`s whatever
  command you pass it, so it works with Prism / MultiMC / official launcher
  / `java -jar`.
- `install.sh` — one-shot setup: runtime deps + ananicy rule + sysctls +
  optional build. Needs `sudo`.
- `ananicy.d/50-caustica.rules` — ananicy-cpp rule that pins the Minecraft
  JVM (matched by `-Dcaustica.*` cmdline) to the `Game` class.

## Quickstart

```bash
# 1. Build the Linux JAR (skip if pulling a prebuilt from GitHub Actions)
./scripts/cachyos/build.sh

# 2. One-shot setup (deps, ananicy rule, sysctls). Pass --build to also build.
sudo ./scripts/cachyos/install.sh

# 3. Drop the JAR into your Minecraft mods dir
cp build/libs/caustica-*-linux-x64.jar ~/.minecraft/mods/

# 4. Launch through the wrapper
caustica-launch prism-launcher
# or
caustica-launch java -jar ~/.minecraft/launcher/minecraft.jar
# or
caustica-launch /opt/MultiMC/MultiMC
```

## Pulling a prebuilt JAR from GitHub Actions

The repo has a `.github/workflows/build-linux-x64.yml` workflow that produces
the same JAR on a `ubuntu-latest` runner. Download from:

> GitHub → repo → Actions → "Build Linux x64" run → Artifacts → `caustica-linux-x64-jar`

Then continue from step 3 above.

## What "CachyOS-optimized" actually means

A Java mod can't directly use most mainline-kernel features. The kernel
new features that *do* matter for a Vulkan RT renderer are exploited by:

| Layer | Kernel feature | How Caustica benefits |
|-------|----------------|----------------------|
| JVM | `THP` (2 MiB pages, kernel ≥2.6.38) | `-XX:+UseTransparentHugePages` asks the JVM to back its heap with THP — fewer TLB misses on the 8 GiB render heap |
| NVIDIA driver | `mmap(MAP_HUGETLB)`, `MADV_HUGEPAGE` | DLSS frame buffers use hugetlb mappings; the driver picks huge pages when available |
| Vulkan loader | `eventfd`, `signalfd`, `epoll` (≥2.6) | swapchain acquire/present uses epoll instead of poll — lower-latency on MGLRU/MGLRU-affected boxes |
| Mesa `radv` | `userfaultfd` (≥4.3), `FUTEX2` (≥5.16) | some Mesa paths use `userfaultfd-wp` for shader cache mappings |
| scheduler | `EEVDF` (6.6+), `BORE` (CachyOS patch) | ananicy-cpp + nice + `SCHED_BATCH` + `SCHED_IDLE` cgrouping reduce render-thread jitter |

We don't reach into the kernel from Java — that's the JVM's and drivers'
job. The mod-side contribution is the kernel probe (logs kernel version +
scheduler + THP state at startup so you can verify the box is actually
configured the way you think it is) and the runtime wrapper (sets the env
those layers need).

## Files

```
scripts/cachyos/
├── build.sh                       # full CachyOS build
├── run-caustica.sh                # runtime launcher
├── install.sh                     # one-shot setup
├── ananicy.d/
│   ├── 50-caustica.rules          # ananicy-cpp native rule
│   └── 50-caustica.rules.json     # same in JSON, for diff-friendliness
└── README.md                      # this file
```
