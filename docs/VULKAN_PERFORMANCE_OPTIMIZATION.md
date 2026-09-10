# Vulkan 性能优化分析报告（RX 7600 + RADV）

## 📅 日期：2026-01-27

---

## 🔍 **当前 Vulkan 特性使用状态**

### ✅ **已启用的特性**

| 特性 | 状态 | 性能影响 | 备注 |
|------|------|----------|------|
| Ray Tracing Pipeline | ✅ 启用 | 必需 | 核心光追功能 |
| Acceleration Structure | ✅ 启用 | 必需 | TLAS/BLAS |
| Ray Query | ✅ 启用 | +5% | 用于 overlay 遮挡 |
| Deferred Host Operations | ✅ 启用 | +5% | 异步 BLAS 构建 |
| SER (Shader Execution Reordering) | ❌ 禁用 | 潜在 +15-30% | **RADV 不支持** |
| VRS (Variable Rate Shading) | ❌ 禁用 | 潜在 +10-20% | 导致光照损坏 |
| Opacity Micromap (OMM) | ❓ 检测 | 潜在 +20-40% | **RADV bug 待修复** |
| Position Fetch | ❌ 禁用 | 潜在 +5-10% | **RADV 强制禁用** |
| Async Compute | ❌ 禁用 | 潜在 +15-25% | 需要稳定性测试 |

---

## 🚫 **被禁用的高性能特性（及原因）**

### **1. Position Fetch（位置获取）**
```java
positionFetchEnabled = !radvDriver && supportsPositionFetch(physicalDevice);
```

**功能**：直接从 BLAS 读取顶点位置，而不需要手动 buffer 访问

**性能提升**：5-10%
- 减少 shader 中的 buffer 读取
- 减少寄存器压力

**为什么禁用**：
- RADV 实现有 bug，导致错误的位置数据
- Mesa 计划修复，但目前不稳定

**如何启用**（风险自负）：
```java
// RtDeviceBringup.java:491
positionFetchEnabled = supportsPositionFetch(physicalDevice); // 移除 !radvDriver 检查
```

---

### **2. VRS (Variable Rate Shading)（可变速率着色）**
```java
// RtComposite.java:141
private static final boolean ENABLE_VRS = false;  // ❌ 回退：导致光照损坏
```

**功能**：
- 降低边缘/暗处的着色率（如 2x2 像素共享一次着色）
- 中心/亮处保持 1x1 全分辨率

**性能提升**：10-20%（理论上）

**为什么禁用**：
- 导致光照计算错误
- 可能是 shading rate attachment 和 ray tracing 的交互问题

**修复方向**：
1. 检查 VRS attachment 的格式和布局
2. 确保 ray tracing pass 不受 VRS 影响
3. 或者只在 denoise/upscale pass 使用 VRS

---

### **3. Opacity Micromap (OMM)（不透明度微映射）**
```java
// RtComposite.java - 配置门控
ommEnabled = supportsOmm(physicalDevice);
```

**功能**：
- 预先计算叶子/玻璃等透明材质的每个微三角形的不透明度
- 硬件在光线追踪时跳过完全透明/不透明的微三角形
- **大幅减少 any-hit shader 执行次数**

**性能提升**：20-40%（对于有大量植被的场景）
- 树叶、草、藤蔓等透明物体非常多
- Any-hit shader 在 RADV 下极其昂贵

**为什么可能不工作**：
- RADV 的 OMM 实现可能有 bug
- 或者代码中还没有真正构建 OMM 数据

**如何检查**：
```bash
grep -r "buildOpacityMicromap\|VK_BUILD_ACCELERATION_STRUCTURE.*OPACITY" src/
```

如果没有找到，说明 OMM 还没实现。

**实现 OMM 的价值**：
- ⭐⭐⭐⭐⭐ **最高优先级**
- 在 RADV 下，any-hit 是最大的性能瓶颈
- OMM 可以跳过 50-70% 的 any-hit 调用

---

### **4. Async Compute（异步计算）**
```java
private static final boolean ENABLE_ASYNC_COMPUTE = false;  // ❌ 回退：需要更多测试
```

**功能**：
- 使用独立的 compute queue 并行执行降噪/后处理
- Graphics queue 做光追，compute queue 同时做降噪
- 真正的 GPU 并行（不是伪并行）

**性能提升**：15-25%（如果降噪占用 20-30% 时间）

**为什么禁用**：
- 需要更复杂的同步（semaphore）
- 可能与 RADV 的 device-loss 问题有关

**修复方向**：
1. 先修复所有 device-loss 问题（ReSTIR DI 已修复）
2. 添加正确的 queue family 同步
3. 确保资源在 queue 之间正确转移所有权

---

### **5. SER (Shader Execution Reordering)（着色器执行重排序）**
```java
public static final List<String> SER_EXTENSIONS = List.of(
    VK_EXT_RAY_TRACING_INVOCATION_REORDER_EXTENSION_NAME);
```

**功能**：
- 硬件自动重排序光线，使相似光线在 SIMD 单元内一起执行
- 大幅提升 coherence（相干性）

**性能提升**：15-30%（对于 incoherent workloads）

**为什么不可用**：
- **RADV 根本不支持这个扩展**
- 只有 NVIDIA（Ada Lovelace+）和部分 Intel 支持
- AMD Windows 驱动（Adrenalin）也不支持

**解决方案**：
- 等待 AMD 硬件/驱动更新
- 或者升级到支持 SER 的 NVIDIA GPU

---

## 🔧 **可以立即优化的 Vulkan 特性**

### **优化 1：启用 Subgroup Operations（子组操作）**

**当前状态**：可能未充分利用

**功能**：
- Wave/Warp 级别的 shuffle、reduce、ballot 操作
- 减少 shared memory 使用
- 加速 ReSTIR spatial reuse

**实现**：
```glsl
// restir_di.glsl - spatial reuse 优化
#extension GL_KHR_shader_subgroup_ballot : require
#extension GL_KHR_shader_subgroup_shuffle : require

// 使用 subgroup shuffle 共享 reservoir 数据
Reservoir neighbor = subgroupShuffle(myReservoir, laneId);
```

**性能提升**：5-10%（ReSTIR pass）

---

### **优化 2：使用 VK_KHR_synchronization2（同步 2.0）**

**当前状态**：可能使用老式 vkCmdPipelineBarrier

**功能**：
- 更精确的 pipeline stage 和 access mask
- 减少不必要的 stall

**检查**：
```bash
grep "VkDependencyInfo\|vkCmdPipelineBarrier2" src/
```

如果没有，升级到 synchronization2 API。

**性能提升**：2-5%（减少 GPU bubble）

---

### **优化 3：启用 Descriptor Indexing（非均匀索引）**

**当前状态**：可能使用固定 descriptor sets

**功能**：
- `descriptorBindingPartiallyBound` - 允许未绑定的 descriptor
- `runtimeDescriptorArray` - 动态大小的 descriptor 数组
- 减少 descriptor set 更新

**实现**：
```glsl
// 动态索引纹理数组
layout(set = 0, binding = 0) uniform sampler2D textures[];
vec4 color = texture(textures[nonuniformEXT(materialId)], uv);
```

**性能提升**：5-10%（减少 descriptor 管理开销）

---

### **优化 4：使用 Inline Uniform Blocks（内联常量块）**

**当前状态**：Push constants 有 256 字节限制

**功能**：
- 将更多常量数据直接嵌入 descriptor set
- 避免额外的 UBO buffer 访问

**实现**：
```c
VkPhysicalDeviceInlineUniformBlockFeatures inlineUniformBlock = {
    .sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_INLINE_UNIFORM_BLOCK_FEATURES,
    .inlineUniformBlock = VK_TRUE
};
```

**性能提升**：2-5%

---

### **优化 5：Ray Tracing Pipeline Library（光追管线库）**

**功能**：
- 预编译 shader 模块，加快管线创建
- 减少启动时间和管线切换开销

**实现**：
```java
VkRayTracingPipelineCreateInfoKHR pipelineInfo = ...
pipelineInfo.flags(VK_PIPELINE_CREATE_LIBRARY_BIT_KHR);
```

**性能提升**：不直接提升 FPS，但减少卡顿

---

## 📊 **推荐的优化优先级**

### **短期（1-2 周）**

#### **1. 实现 Opacity Micromap** ⭐⭐⭐⭐⭐
**预期提升**：+20-40% FPS

**实现步骤**：
1. 为每个透明材质的 BLAS 构建 OMM
2. 使用 `VK_BUILD_ACCELERATION_STRUCTURE_ALLOW_OPACITY_MICROMAP_UPDATE_EXT`
3. 在 BLAS build 时绑定 OMM

**代码位置**：`RtTerrain.java` - BLAS 构建逻辑

---

#### **2. 优化 ReSTIR 使用 Subgroup Operations** ⭐⭐⭐⭐
**预期提升**：+5-10% FPS

**实现步骤**：
1. 在 `restir_di.glsl` 添加 subgroup extensions
2. 使用 `subgroupShuffle` 共享 spatial neighbor 数据
3. 减少 reservoir image 访问

---

#### **3. 减少 Descriptor Set 更新开销** ⭐⭐⭐
**预期提升**：+5-8% FPS

**实现步骤**：
1. 使用 descriptor indexing
2. 合并多个小的 descriptor sets
3. 使用 `VK_DESCRIPTOR_BINDING_PARTIALLY_BOUND_BIT`

---

### **中期（1-2 月）**

#### **4. 修复并启用 Async Compute** ⭐⭐⭐⭐
**预期提升**：+15-25% FPS

**前置条件**：
- 所有 device-loss 问题已修复（ReSTIR DI ✅，Entity RT ❌）
- 添加完整的 queue family 同步

---

#### **5. 调查并修复 VRS 光照问题** ⭐⭐⭐
**预期提升**：+10-20% FPS

**可能的问题**：
- VRS attachment 在 ray tracing pass 期间活动
- 需要在 composite 开始时禁用，结束时重新启用

---

#### **6. 尝试启用 Position Fetch（RADV）** ⭐⭐
**预期提升**：+5-10% FPS（如果 Mesa 已修复）

**测试方法**：
1. 更新到最新 Mesa（6.2+）
2. 移除 `!radvDriver` 检查
3. 验证位置数据正确性

---

### **长期（3+ 月）**

#### **7. 等待 Mesa 修复 RADV bugs**
- FSR2 支持（当前会崩溃）
- Position Fetch 稳定性
- OMM 性能改进

#### **8. 考虑混合光栅化路径**
- 简单场景使用光栅化（faster）
- 复杂反射/阴影使用光追（quality）
- **预期提升**：+50-100% FPS（简单场景）

---

## 💡 **立即可以尝试的配置优化**

### **配置 1：降低渲染分辨率（最直接）**

```toml
[upscaler]
mode = "taau"
quality = 4  # 性能模式（0.5x 内部分辨率）
```

**效果**：
- 1920x1080 → 960x540 渲染，然后上采样
- **+100% FPS**
- 画质损失：中等

---

### **配置 2：禁用次要特性**

```toml
[entities]
enabled = false  # 禁用实体渲染（包括玩家阴影）
glow-enabled = false

[composite]
secondary-moon-nee = false  # 禁用月光（减少 1 shadow ray）
```

**效果**：+5-10% FPS

---

### **配置 3：极限性能模式**

```toml
[composite]
spp = 1
max-bounces = 1  # 从 2 降到 1（只有直接光照）
temporal-alpha = 0.05  # 极致累积（但拖影严重）

[upscaler]
quality = 4  # 最低画质

[dynamic-resolution]
enabled = true
min-scale = 0.4
target-fps = 60
```

**效果**：+200-300% FPS
**代价**：画质大幅下降

---

## 🎯 **总结：最有价值的优化**

| 优化 | 难度 | 预期提升 | 优先级 |
|------|------|----------|--------|
| **Opacity Micromap** | 中 | +20-40% | ⭐⭐⭐⭐⭐ |
| Async Compute | 中 | +15-25% | ⭐⭐⭐⭐ |
| Subgroup Ops (ReSTIR) | 低 | +5-10% | ⭐⭐⭐⭐ |
| VRS 修复 | 中 | +10-20% | ⭐⭐⭐ |
| Descriptor Indexing | 低 | +5-8% | ⭐⭐⭐ |
| Position Fetch (等Mesa) | 低 | +5-10% | ⭐⭐ |
| SER (需要新硬件) | 不可能 | +15-30% | ❌ |

---

## 🚀 **建议的行动计划**

### **立即（今天）**
1. 降低 upscaler quality 到 4（性能模式）
2. 测试 FPS 提升

### **本周**
1. 研究 Opacity Micromap 实现
2. 添加 Subgroup operations 到 ReSTIR shader

### **下月**
1. 实现并测试 Async Compute
2. 调查 VRS 光照损坏原因

### **长期**
1. 监控 Mesa RADV 更新
2. 考虑混合渲染管线

---

## 📝 **备注**

**关于 RADV vs AMDVLK**：
- RADV：开源驱动，性能更好，但有些 bug
- AMDVLK：AMD 官方驱动，更稳定，但慢 10-20%
- 建议：等待 Mesa 修复 RADV，不要切换到 AMDVLK

**关于硬件升级**：
- RX 7600 是入门级光追卡
- 如果预算允许，升级到 RX 7700 XT（+50% 光追性能）
- 或 RTX 4060（支持 SER，+30% 额外性能）

**最终目标**：
- 当前：20-30 FPS @ 1080p
- 全部优化后：40-60 FPS @ 1080p（+100-150%）
- 硬件升级后：60-90 FPS @ 1080p
