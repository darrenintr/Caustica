# ReSTIR PT Enhanced Implementation Plan

**Date:** 2026-07-29
**Author:** Claude (autonomous planning pass)
**Status:** Draft → blocks every P0..P2 task below
**Upstream:** Lin, Kettunen, Wyman — *ReSTIR PT Enhanced: Algorithmic Advances for Faster and More Robust ReSTIR Path Tracing*. DOI 10.1145/3804494. I3D 2026 Best Paper (NVIDIA Research).

> 现行 Caustica 已经具备 ReSTIR DI 的最小实现（`shaders/world/restir_di.glsl` + `world.rgen` 内联，bindings 12–15；`shaders/world/restir_reservoir.glsl` 提供 `Reservoir`/`ReservoirGI` 结构）。GI 路径已有方向型 reservoir（`ReservoirGI`，bindings 16–19）。本计划在现有基础上逐步把 Enhanced 五项技术落地，分 P0/P1/P2/P3 四阶段。

## 0. 现有 Caustica 代码地形（动手前必读）

| 模块 | 文件 | 当前状态 |
|---|---|---|
| DI reservoir 包装 | `src/main/java/dev/comfyfluffy/caustica/rt/light/ReservoirImages.java` | 双 RGBA32UI 乒乓 |
| GI reservoir 包装 | `ReservoirImagesGI.java` | 4 张 RGBA32F 乒乓（currentA/B + previousA/B） |
| ReSTIR DI 算法主体 | `shaders/world/world.rgen`（内联）+ `restir_reservoir.glsl` | 距离偏向候选生成 + temporal reuse + 上帧 spatial reuse + firefly clamp |
| ReSTIR GI 算法主体 | `world.rgen` + `restir_reservoir.glsl::combineReservoirsGI` | 已支持 temporal reuse + jacobian 简化 |
| Block-light 上传 | `BlockLightBuffer.java` + `UnifiedLightManager.java` | 静态 + 动态混合，64 块半径扫描 |
| 管线封装 | `RtPipeline.java` + `RtComposite.java` | 单 Descriptor Set 0（bindings 0..26），6 套 ring 自反转 |
| 配置系统 | `CausticaConfig.java` | 已有 `Rt.Gi.*` 与 `Rt.DynamicLights.*`；缺 Enhanced 段 |
| Push 常量结构体（PC） | `world.rgen` `struct WorldPush` 当前到 624 (`hybridPad`) | 余 8 B 可用，下一阶段需要扩展 |

> 说明：`RtPipeline` 的 descriptor 池设计固定了 `GUIDE_COUNT = 24`，物理上对应 binding 3..26。新加资源需要扩 binding 或合并现有槽位。本计划默认**预留 binding 27..32 给 Enhanced 资源**。

## 1. Enhanced 五项核心技术 → Caustica 映射

| §  | 技术 | 在 Caustica 的位置 | 改动规模 |
|----|------|------------------|---------|
| §3 | 配对空间复用纹理 | 当前 DI/GI 都用 9-tap 邻居搜索 | **低**，可作独立 compute pass 复用 |
| §4 | 基于 footprint 的重连标准 | 当前用粗糙度/距离启发式（`world.rgen` 中 `temporalValid`） | 中，需要世界空间 footprint 评估 |
| §5 | 复制地图 | 没有对应物 | 低，独立 pass，additive |
| §6.1 | 统一 DI+GI 单水库 | DI/GI 两条独立 reservoir | **高**，需要结构重写 |
| §6.2–6.4 | RR-PSS 解耦 / 64B 紧凑 / vector-valued weights / forced NEE reconnection | 现框架已支持部分（如 `ReservoirGI.cachedVisibility`），需要扩展到 DI | 中 |

## 2. 共享资源模型（必须先落地）

| 名称 | 格式 | 用途 | 大小 / 1080p |
|---|---|---|---|
| `gPairedReuseTex0..2` | R16G16 UI × 3（512×512 可平铺） | 配对邻居索引（A→B，B→A） | 1.5 MiB ×3 |
| `gPairedShuffle` | R32 UI × 1 | 每帧选 reuse table | 2 KiB |
| `gDupMapPrev` | R8 UNORM render-res | 上帧 duplication map | < 1 MiB |
| `gV2Seed` | R32 UI render-res | packed V2 标识（初值种子 + 接收者 raygen index） | 4 MiB |
| `gVisibilityReuse` | R8 render-res | 上一帧 shadow/visibility 复用，NaN 标记 | < 1 MiB |
| `gUnifiedReservoir` | 一对 RGBA32UI/UINT render-res | Unified DI+GI reservoir（P2 引入） | 16 MiB |

新增 Java 包装：`PairedReuseTextures.java`、`DuplicationMap.java`、`V2SeedImage.java`、`UnifiedReservoirImages.java`。全部使用 `RtContext.createStorageImage`，与现有 `ReservoirImages` 同一生命周期。

## 3. 配置扩展（先落 CausticaConfig）

新增命名空间 `Rt.RestirEnhanced`：

```toml
[restir-enhanced]
# 配对空间复用（§3）
paired-reuse-enabled        = true
paired-reuse-shuffle-period = 4     # frame-shuffle period
# Duplication map（§5）
duplication-map-enabled     = true
duplication-map-radius      = 17
# Footprint-based 重连（§4）
footprint-reconnection      = true   # 替代粗糙度/距离启发式
# Unified reservoir（§6.1，实验）
unified-reservoir           = false
# 高阶优化（§6.2-6.4）
vector-valued-weights       = false
rr-pss-split                = false
stream-compaction           = false
```

Java：`BooleanSetting`/`IntSetting`/`FloatSetting` 同样写法。

## 4. 实施阶段

### P0-1：Paired Reuse Textures（§3，先做这块）

- Java 资源层：`PairedReuseTextures.generate(254)` CPU 一次性生成 3 张 R16G16 自反转配对纹理（随机游走高斯域偏移），再生成 512×512 可平铺版（U 翻面存两份）。每帧只换 `gPairedShuffle`，不重写纹理。
- 描述符：新增 bindings 27, 28, 29 (combined image sampler, linear filter OFF, point sampler)。
- GLSL：`shaders/world/spatial_reuse_paired.glsl`，compute 8×8 tile：
  1. 读 `gReservoirCurr`；
  2. 读配对点 `gPairedReuseTex0` 当前 tile offset 映射到 uv；
  3. MIS 合并、shift + visibility miss ray；
  4. 写 `gReservoirCurr`。
- DI 与 GI 共用此 pass，分两路：先 DI 再 GI（顺序与现有 temporal merge 一致）。
- world.rgen 第 0 bounce 不再手写 spatial，直接消费 `gReservoirCurr`。
- **保留 `world.rgen` 内联算法作为回退**：`CausticaConfig.Rt.RestirEnhanced.pairedReuseEnabled = false` 时回到现行 9-tap。

> 边界处理：`RtPipeline` 需要 `extraStorageImages` 参数 +3，对应 `GUIDE_COUNT = 27`。当前数值常量要先 patch。

### P0-2：Duplication Map（§5）

- 新建 `shaders/world/duplication_map.comp`：
  - 输入：`gV2Seed`（每像素 32-bit 初值种子）。
  - 输出：`gDupMapCurr` R8 UNORM，统计 17×17 邻域内 V2 命中比例。
  - 采样策略：用 LDS-like 8×8 tile + group-shared 缓冲减少带宽。
- temporal merge pass 引入新公式：
  ```glsl
  float D = imageLoad(gDupMapPrev, pix).r;
  float mCap = mix(c_default, c_min, pow(D, alpha));
  ```
  - `c_default = Gi.MAX_M_TEMPORAL`，`c_min = 4`，`alpha = 0.1`。
- Java：`DuplicationMap.java` 持有 curr/prev；每帧 `swap()`。
- 资源：bindings 30–31。

### P1-1：Footprint-Based Reconnection（§4）

- 在 world.rgen 的 `temporalValid`/`spatialValid` 引入 footprint test：
  ```glsl
  vec3 x0 = hitNeighbor;
  vec3 x1 = hitCurrent;
  float cosTheta = max(dot(N_current, x1 - x0), 0.0);
  float Rp = sqrt(length(x0 - x1) * length(x0 - x1) * cosTheta / (4.0 * 3.14159));
  if (Rp > c * maxFootprint) reject;
  ```
  - `c = 0.02`（论文常量）；额外需要一条辅助主光线评估 footprint。
- 应同时驱动 DI 的 shift 决定与 GI 的 reconnection shift。
- 配置开关：开关关闭时退化到现有粗糙度/距离启发式，避免一次性回归大爆炸。

### P1-2：Forced NEE Reconnection + Visibility Reuse

- 利用 `ReservoirGI.cachedVisibility`，把 Caustica 现有的 visibility cache 推广到 DI 通道。
- 增加新结构字段：`Reservoir.cachedVisibility (32-bit)`，与现有 packed `lightIndex` 共存（用 `lightIndex[0xFFFFFFFE]` sentinel 表示 "needing refresh"，位宽 24 bits 足够；4-bit flags 占走 `.x` 高 8 位即可）。
- 在 temporal merge 时若 `cachedVisibility >= 0` 直接读 `gVisibilityReuse`，跳过 shadow ray。
- 代价：8 MiB 新增 surface（render-res × 单通道），4 ms latency 思路 6000 个像素/帧。

### P2-1：Unified DI+GI Single Reservoir（§6.1，**实验性**）

- 新结构体（`restir_reservoir.glsl`）：
  ```glsl
  struct ReservoirDiGi {
      uint  lightIndex;      // 0xFFFFFFFF = miss / no candidate
      uint  matID;           // 0 = BSDF-sampled GI, 1 = NEE-to-emissive DI, 2 = env miss
      uint  v2Seed;          // packed init seed + sample index（用于 dup-map）
      float wSum;
      float M;
      float cachedVisibility; // packed as f16 in upper 16 bits
  };
  ```
  - 64 B / 像素 = 16 B pixel × 4（也可压缩到 32 B）。
- 资源：bindings 32–35（Unified reservoir currA/B + prevA/B），与现有 `ReservoirImagesGI` 并存。
- world.rgen 第 0 bounce 不再做单独的 DI NEE + GI reflection 选择，而是一次 RIS 把 NEE-candidates + BSDF-samples 喂进同一 reservoir。
- 高度关注 MIS 在 DI 与 GI 间的归一化（论文中 path MIS 必须保留）。
- **配置仅在 `experimental` 配置段暴露**，先 lab 跑通再默认开。

### P2-2：Advanced Optimizations（§6.2-6.4）

- **64B 紧凑 reservoir**：通过合并 fields，避免双 reservoir image。
- **Vector-valued weights**：把 `wSum` 从 float 升到 vec3（RGB 独立），给 FireflyKill / FFX AIDenoise 直接彩色去噪提示。
- **Streaming compaction**：wavefront 实现时把 replay 后的活跃路径密实化，跳过 invalid path 的后续 pass。
- **RR-PSS split**：俄罗斯轮盘只在 initial sampling 时跑，replay 阶段禁用。

## 5. 调度顺序（每帧）

```
[RT world.rgen] 已经有 DI/GI candidate generation
        ↓
   spatial_reuse_paired.comp   ← P0-1 启用后替代 9-tap
        ↓
   duplication_map.comp        ← P0-2 输出 gDupMapCurr
        ↓
   temporal_merge_paired.comp ← 新增，读取 gReservoirPrev + gDupMapPrev
        ↓
   visibility_reuse.comp       ← P1-2（可选）
        ↓
   [rerun DI NEE final shade in primary NEE pass]
        ↓
   [denoise NRD/FFX] 不变
        ↓
   [upscale FSR2/TAAU] 不变
```

> `RtPipeline` 必然加 4 个新 compute pass。考虑在 `RtComposite.composite()` 内按顺序 `vkCmdBindPipeline`/`vkCmdDispatch` 串行；不进入 RT pipeline（不引入新 RT shader 槽位）。

## 6. 测试与回归

| 测试 | 工具 | 接受标准 |
|------|------|---------|
| Spatial reuse 加速比 | RenderDoc GPU capture | 同 SPP 下 spatial 阶段 ≤ P0 前 50% |
| Duplication map 噪声 | 128×128 fixed-resolution region, SPP=4 | pixel variance 比 baseline 低 30%+ |
| Footprint 重连质量 | 室内多光源场景截图 | torch 边缘颜色连续，200 帧无闪烁 |
| Unified reservoir 正确性 | 静态场景 200 帧 | `renderedColor - referenceColor` ≤ 1% |
| 平台兼容 | RX7600、RTX4060、Arc A770 | 不出现 GPUVM fault；max delta fps ≤ 5% |

## 7. 风险与缓解

| 风险 | 触发 | 缓解 |
|---|---|---|
| AMD RADV 读到偶发 GPUVM fault | descriptor ring 重新写入触发 stale 缓存 | 严格 `KEEP_FRAMES = 6` 推迟回收（沿用 block-light 模式） |
| 配对纹理 tile 平铺度不足 | 254 不整除 image 尺寸 | 用 512 平铺 + 动态 offset，论文 §3.2 给的方案 |
| Duplication map 17×17 在 4K 下采样带宽爆炸 | 单 pass 12 MiB 读 | 改用 8×8 tile + group-shared 缓存；总延迟目标 <0.2 ms |
| Unified reservoir 数据布局误算 | structure packing 对齐 | 先小分辨率验证，layer-by-layer 推进 |
| Caustica 现有 `cachedVisibility` 仅 GI 路径生效 | DI 路径误用 | 第 1 阶段 DI 不接 cachedVisibility，仅 GI |

## 8. 文件影响清单（最坏情况）

新增：
- `src/main/java/dev/comfyfluffy/caustica/rt/light/PairedReuseTextures.java`
- `src/main/java/dev/comfyfluffy/caustica/rt/light/DuplicationMap.java`
- `src/main/java/dev/comfyfluffy/caustica/rt/light/V2SeedImage.java`
- `src/main/java/dev/comfyfluffy/caustica/rt/light/UnifiedReservoirImages.java`
- `src/main/java/dev/comfyfluffy/caustica/rt/pipeline/RtEnhancedRestirPipeline.java`
- `shaders/world/paired_reuse_generator.comp`
- `shaders/world/spatial_reuse_paired.comp`
- `shaders/world/duplication_map.comp`
- `shaders/world/temporal_merge_paired.comp`
- `shaders/world/visibility_reuse.comp`
- `shaders/world/unified_reservoir_init.comp`
- `shaders/world/unified_reservoir_shade.comp`

改动：
- `src/main/java/dev/comfyfluffy/caustica/CausticaConfig.java`（新增 `Rt.RestirEnhanced` 表）
- `src/main/java/dev/comfyfluffy/caustica/rt/RtComposite.java`（pass 调度 + 新 push 常量字段）
- `src/main/java/dev/comfyfluffy/caustica/rt/pipeline/RtPipeline.java`（bindings 27..35）
- `src/main/java/dev/comfyfluffy/caustica/rt/RtAsyncCompute.java`（高优先级异步 compute 把新 pass 推到 compute queue）
- `src/main/java/dev/comfyfluffy/caustica/rt/accel/RtImage.java`（无）
- `shaders/world/world.rgen`（第一 bounce 不再内联 spatial，可调用外部 pass 写入）
- `shaders/world/restir_reservoir.glsl`（扩展 `Reservoir.v2`/`cachedVisibility` 字段）

## 9. 推荐落地顺序

```
P0-1 paired-reuse        ──►  P0-2 duplication-map    ──►  P1-1 footprint
                                                          │
                                                          ▼
                                                       P1-2 visibility-reuse
                                                          │
                                                          ▼
                                                  P2-1 unified-reservoir
                                                          │
                                                          ▼
                                                  P2-2 advanced opts
                                                          │
                                                          ▼
                                                       P3 后链路回归
```

每完成一个 phase 后跑一次 RenderDoc + JFR snapshot，确认 spatial 时间和 visibility 缓存命中率都符合预期。

## 10. 参考文献

- Lin, D., Kettunen, M., Wyman, C. *ReSTIR PT Enhanced.* Proc. ACM CGIT 9(1), I3D 2026, DOI 10.1145/3804494.
- NVIDIA 项目页：https://research.nvidia.com/labs/rtr/publication/lin2026restirptenhanced/
- Lin et al. *ReSTIR PT.* ACM TOG 41(4), SIGGRAPH 2022 — 现有 Caustica GI 路径沿用此论文
- Bitterli et al. *Spatiotemporal reservoir resampling for real-time ray tracing with dynamic direct lighting.* PACMCGIT 3(2), SIGGRAPH 2020 — 现有 DI 路径沿用此论文
- 参考开源实现：
  - `ML200/RoyalTracer-DX`（DX12/HLSL，最完整 Enhanced 移植）
  - `ML200/RoyalGL`（OpenGL/GLSL wavefront，与 Caustica 同栈）
  - `McNopper/Theia`（Vulkan/Slang GI2 toggle，最接近 Caustica 平台）
  - `Luna5ama/Alpha-Piscium`（Minecraft Iris GLSL，唯一 Minecraft 引用）
  - `DQLin/ReSTIR_PT`（原版 BSD-3 reference impl）
