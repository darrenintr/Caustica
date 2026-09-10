# Caustica 性能优化计划 (2026-Q3)

**目标**: 在保持画质的前提下提升 30-50% 帧率，重点针对 SPP=1-2 的实时路径追踪场景

**当前基线**: 
- SPP=2, FFX Denoiser, DLSS-RR/FSR3 upscale
- ReSTIR DI 已实现（block lights）
- 现有架构：传统 descriptor sets + descriptor pools

---

## 方案 1: Bindless Descriptor Buffer ⭐⭐⭐⭐

### 收益分析
- **CPU 时间**: -5-10% (descriptor 管理开销)
- **架构现代化**: 支持真正的 bindless 纹理访问（无限材质数量）
- **代码简化**: 移除 descriptor pool 管理逻辑
- **长期价值**: 为未来扩展打基础（如程序化材质、虚拟纹理）

### 技术路径

#### Phase 1: 基础设施 (2 天)
**目标**: 建立 descriptor buffer 基础架构

1. **Device 特性启用** (`RtDeviceBringup.java`)
   - 添加 `VK_EXT_descriptor_buffer` 到 `RT_EXTENSIONS`
   - 查询 `VkPhysicalDeviceDescriptorBufferPropertiesEXT`:
     - `descriptorBufferOffsetAlignment` (通常 4 字节)
     - `maxDescriptorBufferBindings` (通常 ≥8)
     - `robustStorageBufferDescriptorSize` / `robustUniformTexelBufferDescriptorSize`
   - 启用 `VkPhysicalDeviceDescriptorBufferFeaturesEXT.descriptorBuffer = VK_TRUE`

2. **Descriptor Buffer 包装类**
   ```java
   // 新文件: src/main/java/dev/comfyfluffy/caustica/rt/descriptor/DescriptorBuffer.java
   public class DescriptorBuffer {
       private final VulkanBackend backend;
       private final long deviceAddr;  // BDA 地址
       private final VkBuffer handle;
       private final long size;
       
       // 创建 descriptor buffer (VK_BUFFER_USAGE_RESOURCE_DESCRIPTOR_BUFFER_BIT_EXT)
       public static DescriptorBuffer create(VulkanBackend backend, long size);
       
       // 写入 descriptor (vkGetDescriptorEXT → memcpy)
       public void writeImageDescriptor(int offset, VkImageView view, VkSampler sampler);
       public void writeBufferDescriptor(int offset, long bufferAddr, long size);
       public void writeAccelStructDescriptor(int offset, long tlasAddr);
       
       // 绑定到 pipeline (vkCmdBindDescriptorBuffersEXT)
       public void bind(VkCommandBuffer cmd, int set);
   }
   ```

3. **Pipeline Layout 改造**
   - 现有 `world.rgen` 的 layout:
     ```glsl
     layout(binding = 0, set = 0) uniform accelerationStructureEXT topLevelAS;
     layout(binding = 1, set = 0, rgba16f) uniform image2D outImage;
     // ... 20+ bindings
     ```
   - Descriptor buffer 后保持不变（shader 代码无需改动）
   - Java 端用 `VK_DESCRIPTOR_SET_LAYOUT_CREATE_DESCRIPTOR_BUFFER_BIT_EXT` 创建 layout

#### Phase 2: Raygen Pipeline 迁移 (2 天)
**目标**: `world.rgen` 从 descriptor sets 迁移到 descriptor buffer

1. **RtPipeline.java 改造**
   - 移除 `vkAllocateDescriptorSets` 相关代码
   - 在 `prepareFrame()` 中：
     ```java
     // 旧: vkUpdateDescriptorSets(writes[])
     // 新: descriptorBuffer.writeImageDescriptor(offset_outImage, outImageView, null)
     //     descriptorBuffer.writeAccelStructDescriptor(offset_tlas, tlasAddr)
     ```
   - 在 `recordRaygenCommands()` 中：
     ```java
     // 旧: vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_RAY_TRACING_KHR, ...)
     // 新: descriptorBuffer.bind(cmd, 0);
     //     vkCmdSetDescriptorBufferOffsetsEXT(cmd, ..., [0])  // 动态 offset = 0
     ```

2. **验证测试**
   - 跑一局 Minecraft，检查画面是否正确
   - 用 RenderDoc 抓帧验证 descriptor buffer 绑定
   - `scripts/test_denoise_regressions.py` 回归测试

#### Phase 3: Compute Pipelines 迁移 (1-2 天)
**目标**: Denoise/Display/Temporal 等 compute shader 迁移

1. **受影响的 Pipeline**
   - `RtTemporalAccumulation` (temporal_accumulate.comp)
   - `FfxDenoiseBackend` (ffx_reproject.comp 等 9 个 pass)
   - `RtDisplayPipeline` (display.comp)
   - `FireflyKill` (firefly_kill.comp)

2. **统一 Descriptor Buffer 策略**
   - 方案 A: 每个 pipeline 独立的 descriptor buffer（内存开销 ~10MB）
   - 方案 B: 共享一个大 buffer，按 offset 划分区域（推荐）
     ```
     [0-4KB: raygen descriptors] [4KB-8KB: temporal] [8KB-12KB: denoise] ...
     ```

3. **迁移顺序**（由简到难）
   - FireflyKill (单一 image input/output)
   - RtDisplayPipeline (3-4 个 image bindings)
   - RtTemporalAccumulation (5-6 个 bindings)
   - FfxDenoiseBackend (最复杂，9 个 pass 共享 descriptor layout)

#### Phase 4: Bindless 材质系统（可选，+1 天）
**目标**: 移除材质数量限制（当前受 descriptor set 容量限制）

1. **Shader 端改造**
   ```glsl
   // 旧: layout(binding = X) uniform sampler2D textures[MAX_TEXTURES];
   // 新: layout(binding = X) uniform sampler2D textures[];  // unbounded array
   //     uint texId = material.textureIndex;
   //     vec4 color = texture(textures[nonuniformEXT(texId)], uv);
   ```

2. **Java 端**
   - 将所有纹理的 descriptor 连续写入 descriptor buffer
   - 传递 `firstTextureIndex` 给 shader（via push constant）

### 风险与缓解
- **风险 1**: AMD RDNA2 驱动对 descriptor buffer 支持不完整
  - 缓解: 保留旧代码路径，运行时根据 `VK_EXT_descriptor_buffer` 可用性选择
- **风险 2**: Descriptor buffer offset 对齐要求导致内存浪费
  - 缓解: 预先查询 `descriptorBufferOffsetAlignment`，padding 到对齐边界
- **风险 3**: Bindless 数组在 Intel Arc 上性能回退
  - 缓解: 先跑基准测试，如果慢就不启用 Phase 4

### 验收标准
- [ ] `world.rgen` 正确渲染（视觉无差异）
- [ ] 所有 denoise pass 正常工作
- [ ] CPU frame time 下降 5-10%（Nsight Systems profile）
- [ ] 内存占用增加 <50MB
- [ ] 支持 NVIDIA RTX 20+, AMD RDNA2+, Intel Arc+

---

## 方案 2: ReSTIR GI 增强 ⭐⭐⭐⭐

### 收益分析
- **噪点降低**: -60% @ SPP=1（相当于当前 SPP=4 的质量）
- **帧率提升**: SPP 从 2→1，理论 +100% raygen time（实际 +40-60% 整体帧率）
- **视觉效果**: 更稳定的 GI，减少闪烁

### 当前状态分析
根据 `shaders/world/restir_reservoir.glsl` 和 `world.rgen:82-86`：
- **已有基础设施**:
  - ReSTIR DI 完整实现（block lights）
  - GI reservoir 存储结构 (32 bytes/pixel, 双 buffer)
  - Temporal/Spatial reuse 框架
- **当前局限**:
  - GI 候选数太少（默认 2）
  - Spatial reuse 可能没有实现或太弱
  - 缺少 visibility reuse（重要！）

### 技术路径

#### Phase 1: Baseline 分析与参数调优 (1 天)

1. **代码审计** (`world.rgen` 中的 ReSTIR GI 部分)
   - 定位 GI 采样循环：搜索 `giEnabled` / `giCandidates` / `ReservoirGI`
   - 确认当前实现：
     - ✅ Temporal reuse（从 `gGiReservoirPrev` 读取）
     - ❓ Spatial reuse（需要检查是否有邻域采样循环）
     - ❓ Visibility reuse（是否复用上一帧的 visibility 判断）

2. **参数基准测试**
   - 测试场景：室内 + 复杂光照（如村庄房间）
   - 记录当前配置的噪点水平：
     ```java
     // CausticaConfig.java 当前默认值
     restirGi.candidates = 2;
     restirGi.maxMTemporal = 6.0;
     restirGi.maxMSpatial = 24.0;
     ```
   - 增加候选数 2→4→8，测量噪点改善 vs 性能损失

#### Phase 2: Spatial Reuse 实现/增强 (2 天)

**目标**: 从周围像素借用 GI 样本，大幅减少方差

1. **Spatial Reuse 算法**（在 `world.rgen` 中实现）
   ```glsl
   // 伪代码（插入到 GI 计算流程中）
   ReservoirGI r = unpackReservoirGI(
       imageLoad(gGiReservoirPrevA, hitPixel),
       imageLoad(gGiReservoirPrevB, hitPixel)
   );
   
   // Spatial reuse: 从 N 个邻域像素合并 reservoir
   const int SPATIAL_NEIGHBORS = 4;  // 可配置
   for (int i = 0; i < SPATIAL_NEIGHBORS; i++) {
       ivec2 neighborPixel = hitPixel + spatialOffset(i, seed);  // Poisson disk
       
       // 读取邻域的 PREVIOUS-FRAME reservoir（避免 race condition）
       vec4 nA = imageLoad(gGiReservoirPrevA, neighborPixel);
       vec4 nB = imageLoad(gGiReservoirPrevB, neighborPixel);
       ReservoirGI neighbor = unpackReservoirGI(nA, nB);
       
       // Jacobian: 法线相似度检查
       vec3 neighborNormal = reconstructNormal(neighborPixel);  // 从 gNormal 读取
       float jacobian = max(0.0, dot(gv_normal, neighborNormal));
       jacobian = jacobian > 0.9 ? 1.0 : 0.0;  // 硬阈值，避免漏光
       
       // MIS combine
       r = combineReservoirsGI(r, neighbor, jacobian, pc.giMaxMSpatial, seed);
   }
   ```

2. **法线/深度 rejection**
   - 读取邻域像素的 `gNormal` 和 `gDepth`
   - Rejection 条件：
     - `dot(N, N_neighbor) < 0.9` → jacobian = 0
     - `abs(depth - depthNeighbor) > 0.1 * depth` → 跳过

3. **Poisson Disk 采样**
   ```glsl
   ivec2 spatialOffset(int i, inout uint seed) {
       // 8-tap Poisson disk，半径 = 8-16 像素
       const vec2 offsets[8] = vec2[](
           vec2(-1, -1), vec2(1, -1), vec2(-1, 1), vec2(1, 1),
           vec2(-2, 0), vec2(2, 0), vec2(0, -2), vec2(0, 2)
       );
       float r = 8.0 + rndf(seed) * 8.0;  // 随机半径 [8, 16]
       return ivec2(offsets[i % 8] * r);
   }
   ```

#### Phase 3: Visibility Reuse 实现 (2 天)

**目标**: 复用上一帧的 visibility 判断，避免重复 trace 阴影

1. **问题分析**
   - 当前 GI 每帧都 trace 二次光线来评估 visibility
   - Visibility reuse: 假设大多数表面是静态的，上一帧的 visibility 仍然有效

2. **实现策略**
   ```glsl
   // 在 ReservoirGI 中添加一个 visibility 字段（需要修改存储格式）
   struct ReservoirGI {
       vec3  dir;
       float wSum;
       float M;
       float age;
       float targetPdf;
       float cachedVisibility;  // 新增：0-1 之间，上一帧的 visibility
   };
   
   // Spatial reuse 时传播 visibility
   if (neighbor.age < 5.0 && jacobian > 0.95) {
       r.cachedVisibility = neighbor.cachedVisibility;  // 借用邻域的 visibility
   }
   
   // 只有当 cachedVisibility 无效时才 trace
   if (r.cachedVisibility < 0.0 || rndf(seed) < 0.1) {  // 10% 概率重新验证
       r.cachedVisibility = traceVisibilityRay(hitPos, r.dir);
   }
   vec3 giRadiance = evalGI(r.dir) * r.cachedVisibility;
   ```

3. **存储格式调整**
   - 当前 `ReservoirGI` 占用 32 bytes (2x rgba32f)
   - 添加 `cachedVisibility` 后仍然 32 bytes（复用 `packReservoirGiB` 的 `.w` 通道）

#### Phase 4: 自适应采样策略 (可选，+1 天)

1. **动态候选数**
   ```glsl
   // 高粗糙度表面 → 多采样（GI 贡献大）
   // 镜面表面 → 少采样（GI 贡献小）
   int adaptiveCandidates = int(pc.giCandidates * gv_rough);
   adaptiveCandidates = max(1, adaptiveCandidates);
   ```

2. **重要性采样改进**
   - 当前：cosine-weighted hemisphere sampling
   - 改进：结合 lightfield 的方向分布（类似 RTXGI 的 probe）

### 性能预测
| 配置 | SPP | GI Candidates | Spatial Neighbors | 噪点水平 | 相对帧率 |
|------|-----|---------------|-------------------|----------|----------|
| 当前 | 2 | 2 | 0? | 100% | 100% |
| 优化 | 1 | 4 | 4 | 40% | 160% |
| 激进 | 1 | 8 | 8 | 25% | 140% |

### 风险与缓解
- **风险 1**: Spatial reuse 导致漏光（light leaking）
  - 缓解: 严格的法线/深度 rejection，jacobian 阈值 >0.9
- **风险 2**: Visibility reuse 产生时间延迟伪影
  - 缓解: 10% 概率重新验证 + 动态光源强制重新 trace
- **风险 3**: 内存占用翻倍（如果增加 visibility 字段）
  - 缓解: 复用 `packReservoirGiB.w`，无额外开销

### 验收标准
- [ ] SPP=1 下噪点水平接近当前 SPP=4
- [ ] 室内场景 GI 稳定，无明显闪烁
- [ ] 帧率提升 40-60%（因 SPP 减半）
- [ ] 无漏光伪影（在墙壁/地板交界处测试）
- [ ] `scripts/test_ffx_quality.py` 回归通过

---

## 方案 3: Wave Intrinsics 优化 ⭐⭐⭐

### 收益分析
- **特定 shader 性能**: +15-25%（ReSTIR spatial merge, denoise variance）
- **硬件利用率**: 减少 warp/wave divergence
- **长期价值**: 中等（局部优化，不改变架构）

### 技术路径

#### Phase 1: Wave Intrinsics 基础 (1 天)

1. **Device 特性检测**
   ```java
   // RtDeviceBringup.java
   VkPhysicalDeviceSubgroupProperties subgroupProps = ...;
   int waveSize = subgroupProps.subgroupSize();  // NVIDIA=32, AMD=64, Intel=16
   boolean supportsWaveOps = (subgroupProps.supportedOperations() & 
       VK_SUBGROUP_FEATURE_ARITHMETIC_BIT) != 0;
   ```

2. **Shader 端启用**
   ```glsl
   #version 460
   #extension GL_KHR_shader_subgroup_arithmetic : require
   #extension GL_KHR_shader_subgroup_ballot : require
   ```

#### Phase 2: ReSTIR Spatial Merge 优化 (1 天)

**目标**: Wave-parallel neighbor sampling

1. **问题分析**
   - 当前 spatial reuse 每像素串行访问 4-8 个邻域
   - Wave intrinsics: 同一 wave 内的 32/64 像素协作采样

2. **优化实现**
   ```glsl
   // 原始代码（串行）
   for (int i = 0; i < SPATIAL_NEIGHBORS; i++) {
       ivec2 neighbor = hitPixel + offset[i];
       ReservoirGI r = imageLoad(gGiReservoirPrev, neighbor);
       combine(myReservoir, r);
   }
   
   // Wave 优化（并行）
   // Step 1: 每个 lane 广播自己的 reservoir 给整个 wave
   uint laneId = gl_SubgroupInvocationID;
   ReservoirGI myR = ...;
   
   // Step 2: Wave shuffle 获取邻域 reservoir（无需 imageLoad）
   for (int i = 0; i < SPATIAL_NEIGHBORS; i++) {
       uint neighborLane = (laneId + shuffle_offsets[i]) % gl_SubgroupSize;
       ReservoirGI neighborR = subgroupShuffle(myR, neighborLane);
       combine(myR, neighborR);
   }
   ```

3. **局限性**
   - 只能在 wave 内采样（32/64 像素），无法跨越大范围
   - 适用于小半径 spatial reuse（<8 像素）

#### Phase 3: Denoise Variance 计算优化 (1 天)

**目标**: Wave-parallel 3x3/5x5 variance 统计

1. **SVGF Variance Pass** (`shaders/denoise/svgf_variance.comp`)
   ```glsl
   // 原始：每像素读取 9 次 (3x3 kernel)
   float variance = 0.0;
   for (int dy = -1; dy <= 1; dy++) {
       for (int dx = -1; dx <= 1; dx++) {
           vec3 color = imageLoad(input, pixel + ivec2(dx, dy)).rgb;
           variance += dot(color - mean, color - mean);
       }
   }
   
   // Wave 优化：subgroup reduce
   vec3 myColor = imageLoad(input, pixel).rgb;
   vec3 waveMean = subgroupAdd(myColor) / float(gl_SubgroupSize);
   float waveVariance = subgroupAdd(dot(myColor - waveMean, myColor - waveMean));
   ```

2. **FFX Denoiser** (`shaders/display/denoise_ffx/ffx_atrous.comp`)
   - À-trous wavelet filter: 5x5 加权平均
   - Wave intrinsics: 并行计算权重和

#### Phase 4: Warp Divergence 分析（可选，+1 天）

1. **Nsight Compute Profiling**
   - 抓取 `world.rgen` 的 warp stall 分布
   - 识别高 divergence 的分支（如材质类型判断）

2. **优化策略**
   ```glsl
   // 原始：高 divergence
   if (material == WATER) { /* 复杂水波 */ }
   else if (material == GLASS) { /* 折射 */ }
   else { /* 标准 Lambertian */ }
   
   // 优化：ballot 预分类
   uvec4 waterMask = subgroupBallot(material == WATER);
   if (subgroupBallotBitCount(waterMask) > 0) {
       // 整个 wave 一起处理水面
   }
   ```

### 性能预测
- ReSTIR spatial merge: +20%（减少 memory latency）
- Denoise variance: +15%（reduce overhead）
- 整体帧率: +3-5%（局部优化）

### 风险与缓解
- **风险 1**: Wave size 差异导致行为不一致
  - 缓解: 限制 shuffle offset < min(32, gl_SubgroupSize)
- **风险 2**: 复杂度增加，可读性下降
  - 缓解: 仅在热点 kernel 应用，添加详细注释
- **风险 3**: Intel Arc 的 wave size=16 性能回退
  - 缓解: 动态选择 wave-parallel vs 标准路径

### 验收标准
- [ ] ReSTIR spatial pass +15-25% (Nsight profile)
- [ ] SVGF variance pass +15% 
- [ ] 无视觉伪影（wave shuffle 边界处理正确）
- [ ] 支持 NVIDIA RTX/AMD RDNA/Intel Arc

---

## 优先级建议

### 如果时间有限（选一个）
**推荐 ReSTIR GI 增强**
- 理由: 收益最高（+40-60% 帧率 + 画质提升），风险可控，代码改动集中在 `world.rgen`

### 如果有 2 周时间（选两个）
**推荐顺序**: 
1. **ReSTIR GI 增强** (5-7 天) → 立即可见的画质和性能提升
2. **Bindless Descriptor Buffer** (5-7 天) → 长期架构改进，为后续优化打基础

**理由**: Wave Intrinsics 虽然工作量最小（3-4 天），但收益是局部的（+3-5%），不如前两者的战略价值。

### 全部实施顺序（3 周）
1. **Week 1**: ReSTIR GI 增强（立即见效）
2. **Week 2**: Bindless Descriptor Buffer（需要稳定测试）
3. **Week 3**: Wave Intrinsics（锦上添花）

---

## 测试与验收流程

### 每个方案完成后必做
1. **功能测试**
   - 启动 Minecraft，进入测试场景（村庄室内/洞穴/开阔平原）
   - 检查画面正确性（无黑屏、花屏、闪烁）
   - 切换不同配置（SPP 1/2/4, denoise on/off）

2. **性能测试**
   ```bash
   # 录制 30 秒固定路径，对比帧率
   # 场景 A: 开阔平原（光照简单）
   # 场景 B: 村庄室内（GI 密集）
   # 场景 C: 洞穴（block lights 密集）
   ```

3. **回归测试**
   ```bash
   cd /run/media/darren/1TB/Windows\ data/project/Caustica
   python3 scripts/test_denoise_regressions.py
   python3 scripts/test_ffx_quality.py
   ```

4. **Profiling**
   - Nsight Systems: 整体 frame timeline
   - Nsight Compute: shader 微观性能
   - RenderDoc: descriptor 绑定验证

### 发布前检查清单
- [ ] 三个优化方案都通过上述测试
- [ ] 在至少 2 款 GPU 上测试（NVIDIA RTX + AMD RDNA）
- [ ] 更新 `CausticaConfig.java` 的默认值
- [ ] 更新 README 性能数据
- [ ] Git commit message 遵循 conventional commits 规范

---

## 附录: 参考资料

### ReSTIR 论文
- [Spatiotemporal reservoir resampling for real-time ray tracing with dynamic direct lighting](https://research.nvidia.com/publication/2020-07_spatiotemporal-reservoir-resampling-real-time-ray-tracing-dynamic-direct) (SIGGRAPH 2020)
- [ReSTIR GI: Path Resampling for Real-Time Path Tracing](https://research.nvidia.com/publication/2021-06_restir-gi-path-resampling-real-time-path-tracing) (HPG 2021)

### Vulkan 规范
- [VK_EXT_descriptor_buffer](https://registry.khronos.org/vulkan/specs/1.3-extensions/man/html/VK_EXT_descriptor_buffer.html)
- [Shader subgroup operations](https://registry.khronos.org/vulkan/specs/1.3-extensions/html/vkspec.html#shaders-subgroup)

### Bindless 实战案例
- Doom Eternal: [The Devil is in the Details](https://advances.realtimerendering.com/s2020/RenderingDoomEternal.pdf)
- Unreal Engine 5: Bindless Shader Model
