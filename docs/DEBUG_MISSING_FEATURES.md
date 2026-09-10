# 缺失功能诊断与修复报告

## 📅 日期：2026-07-27

---

## ✅ **已修复的功能**

### 1. **方块光源（火把、海晶灯等）**

**问题**：
- ReSTIR DI 被禁用（`ENABLE_RESTIR_DI = false`）
- 原因：`BlockLightBuffer` 的 retired buffers 永远不释放，导致 VRAM 泄漏
- 最终触发 `VK_ERROR_DEVICE_LOST`

**修复**：
```java
// BlockLightBuffer.java
private static final int KEEP_FRAMES = 4;  // 延迟 4 帧后释放
private long frameCounter = 0;

private void cleanupRetiredBuffers() {
    retiredBuffers.removeIf(retired -> {
        if (frameCounter - retired.retiredAtFrame > KEEP_FRAMES) {
            retired.buffer.destroy();  // ✅ 安全释放
            return true;
        }
        return false;
    });
}
```

**状态**：✅ 已修复并测试通过

---

### 2. **月光强度**

**问题**：
- 月光峰值亮度太低（`moonPeak = 0.20f`）
- 夜晚几乎完全黑暗

**修复**：
```java
// RtComposite.java:2279
float moonPeak = 0.50f * (0.15f + 0.85f * litFraction);  // 从 0.20f 提高到 0.50f (2.5倍)
```

**配置**：
```toml
# caustica.toml
[composite]
secondary-moon-nee = true  # 启用月光 NEE（次要光源采样）
```

**状态**：✅ 已修复，待测试

---

## ⚠️ **待修复的功能**

### 3. **动态手持光源**

**问题**：
- 配置已启用（`dynamic-lights.enabled = true, held-items = true`）
- 代码逻辑正确（`DynamicLightTracker.java`）
- 但手持火把不发光

**可能原因**：

#### **假设 A：物品光照等级检测失败**
```java
// DynamicLightTracker.java:207
private int getItemLightLevel(ItemStack stack) {
    Block block = Block.byItem(stack.getItem());
    if (block != null) {
        BlockState state = block.defaultBlockState();
        return state.getLightEmission();  // 可能返回 0？
    }
    return 0;
}
```

**调试步骤**：
1. 添加日志记录 `getItemLightLevel()` 的返回值
2. 验证 `Block.byItem()` 是否正确映射火把物品到火把方块
3. 检查 Minecraft 版本兼容性（API 可能变化）

#### **假设 B：实体列表为空**
```java
// UnifiedLightManager.java:62
dynamicLights.updateFrame(level.entitiesForRendering());
```

可能 `level.entitiesForRendering()` 不包含玩家？

**调试步骤**：
1. 记录 `entities.size()`
2. 检查玩家实体是否在列表中
3. 尝试使用 `level.players()` 代替

#### **假设 C：更新频率太低**
```java
private static final int DYNAMIC_UPDATE_INTERVAL_FRAMES = 2;  // 每 2 帧更新一次
```

可能视觉上不明显？

**测试**：改为 `= 1`（每帧更新）

---

### 4. **玩家阴影（实体光追）**

**问题**：
- 功能被禁用（`ENABLE_DYNAMIC_ENTITY_RT = false`）
- 原因：与 ReSTIR DI 类似的 AMD device-loss 问题

**注释说明**：
```java
// Stable RT baseline: dynamic entity BLAS capture/refit is temporarily 
// isolated from the terrain-only TLAS while AMD device-loss causes are 
// being reduced to one submission path.
```

**可能的根本原因**：

#### **假设 1：Entity BLAS 资源生命周期问题**
类似 `BlockLightBuffer` 的问题：
- 实体 BLAS 在重建时立即释放旧 BLAS
- 但 in-flight 帧仍在访问
- 触发 `VK_ERROR_DEVICE_LOST`

**修复方向**：
```java
// RtEntities.java - 需要添加延迟释放机制
private static final int KEEP_FRAMES = 4;
private final List<RetiredBlas> retiredBlas = new ArrayList<>();

private static class RetiredBlas {
    final RtBlas blas;
    final long retiredAtFrame;
}
```

#### **假设 2：TLAS 更新与 BLAS 重建的同步问题**
- TLAS 更新时引用了正在重建的 BLAS
- 需要添加 pipeline barrier

**修复方向**：
```java
// 在 TLAS build 前添加 barrier
vkCmdPipelineBarrier(cmd,
    VK_PIPELINE_STAGE_ACCELERATION_STRUCTURE_BUILD_BIT_KHR,
    VK_PIPELINE_STAGE_ACCELERATION_STRUCTURE_BUILD_BIT_KHR,
    ...);
```

#### **假设 3：RADV 特定的 BLAS refit bug**
- RADV 的 BLAS refit 实现可能有问题
- 建议：禁用 refit，每帧完全重建 BLAS

**测试方向**：
```java
// 尝试每帧完全重建而不是 refit
buildBlas(vertices, indices, VK_BUILD_ACCELERATION_STRUCTURE_PREFER_FAST_BUILD_BIT_KHR);
// 而不是：
refitBlas(vertices);
```

---

## 🔧 **下一步行动计划**

### **优先级 1：修复动态手持光源**

1. **添加详细调试日志**（已完成）：
   ```java
   CausticaMod.LOGGER.debug("Detected held light: entity={} light={}", ...);
   CausticaMod.LOGGER.debug("Item {} has light level {}", ...);
   ```

2. **测试日志输出**：
   - 启动游戏，手持火把
   - 查看日志：`grep "Detected held\|Dynamic lights" latest.log`
   - 确认问题在哪一步

3. **根据日志结果修复**：
   - 如果 `light=0` → 修复 `getItemLightLevel()`
   - 如果 `0 lights detected` → 修复实体列表获取
   - 如果检测到但不发光 → 检查 GPU 端

### **优先级 2：启用实体光追（玩家阴影）**

1. **参考 ReSTIR DI 的修复**：
   - 在 `RtEntities.java` 添加延迟释放机制
   - 跟踪每个 BLAS 的退休时间
   - 4 帧后安全释放

2. **渐进式测试**：
   - 先测试静态实体（不移动的实体）
   - 再测试动态实体（玩家、怪物）
   - 最后测试粒子效果

3. **监控崩溃**：
   - 启用 Vulkan validation layers
   - 记录每次 BLAS 创建/销毁
   - 捕获 `VK_ERROR_DEVICE_LOST` 前的最后操作

---

## 📊 **功能状态总览**

| 功能 | 状态 | 原因 | 修复难度 |
|------|------|------|----------|
| 方块光源 | ✅ 已修复 | Buffer 泄漏 | 简单 |
| 月光强度 | ✅ 已修复 | 参数太低 | 简单 |
| 月光 NEE | ✅ 已启用 | 配置关闭 | 简单 |
| 手持光源 | 🔍 调查中 | 未知 | 中等 |
| 玩家阴影 | ❌ 禁用 | BLAS 生命周期 | 中等 |
| ReSTIR GI | ❌ 禁用 | AMD 崩溃 | 困难 |
| VRS | ❌ 禁用 | 破坏光照 | 困难 |
| Async Compute | ❌ 禁用 | 未测试 | 中等 |

---

## 🎯 **预期效果（完全修复后）**

**当前（部分修复）**：
- ✅ 火把/海晶灯照亮附近方块
- ✅ 月亮有模型
- ✅ 月光照亮大地（增强后）
- ❌ 手持火把不发光
- ❌ 没有玩家阴影

**完全修复后**：
- ✅ 所有方块光源正常
- ✅ 手持火把照亮周围
- ✅ 玩家投射阴影
- ✅ 实体投射阴影
- ✅ 月光强度合理
- 🚀 **性能可能提升 10-20%**（如果 Async Compute 也修复）

---

## 📝 **备注**

### **关于地形生成慢**
- **不是 Caustica 的问题**
- Caustica 是纯渲染器，不修改世界生成
- 可能原因：其他 mod、硬件瓶颈、或主观感觉

### **关于破碎地狱门**
- **不是 Caustica 的问题**
- 破碎地狱门（Ruined Portals）是原版 1.16+ 的特性
- 如果在地狱里生成异常多 → 检查其他 mod

### **关于 RADV 性能**
- RX 7600 在 RADV 下的性能瓶颈主要是驱动 bug
- 等待 Mesa 修复或切换到 AMDVLK（但更慢）
- 或者升级 GPU（RX 7700 XT / RTX 4060+）

---

## 🔬 **技术细节：为什么延迟释放有效**

**Vulkan 资源生命周期问题**：
```
Frame N:   Create Buffer A → Submit commands using A
Frame N+1: Create Buffer B → Destroy A immediately ❌
           Submit commands using B
Frame N+2: GPU processes Frame N commands → tries to access A
           → VK_ERROR_DEVICE_LOST (A already destroyed!)
```

**延迟释放解决方案**：
```
Frame N:   Create Buffer A → Submit commands using A
Frame N+1: Create Buffer B → Retire A (keep for 4 frames)
           Submit commands using B
Frame N+2: GPU processes Frame N
Frame N+3: GPU processes Frame N+1
Frame N+4: GPU processes Frame N+2
Frame N+5: GPU processes Frame N+3
           → NOW safe to destroy A ✅
```

**为什么是 4 帧？**
- Vulkan 通常使用 2-3 个 frames-in-flight（交换链图像数量）
- 4 帧保证所有 in-flight 帧都已完成
- 与 TLAS 的 deferred-free 策略一致（`RtComposite.java:146`）

---

## 🚀 **未来优化方向**

1. **ReSTIR GI**：全局光照（需要更深入的调试）
2. **VRS**：可变速率着色（需要修复光照损坏）
3. **Async Compute**：异步计算（需要稳定性测试）
4. **Position Fetch**：等待 Mesa 修复 RADV bug
5. **FSR2**：等待 Mesa 修复 RADV bug

**预期总性能提升**：60-100%（如果所有功能都能稳定启用）
