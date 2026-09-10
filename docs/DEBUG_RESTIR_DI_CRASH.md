# ReSTIR DI AMD Crash 调试计划

## 问题描述
```
VK_ERROR_DEVICE_LOST when live light set grows while NRD is active
触发条件：光源数量增加 + NRD 降噪器运行
```

## 假设的根本原因

### 假设 1：Descriptor 更新时序问题 ⭐⭐⭐⭐⭐
**问题**：
- `blockLightBuffer` 在 `UnifiedLightManager.rescanAround()` 中动态增长
- Buffer 重新分配后，descriptor set 被更新（line 1559）
- 但此时可能有 in-flight 的命令仍在访问旧 buffer

**验证方法**：
1. 添加日志记录每次 buffer 重新分配
2. 检查 buffer 更新和 descriptor 绑定之间是否有同步屏障
3. 验证旧 buffer 是否在所有 in-flight 帧完成前被释放

**修复方向**：
```java
// BlockLightBuffer.java - 添加延迟释放
private static final int KEEP_FRAMES = 4;
private final Deque<Long> retiredBuffers = new ArrayDeque<>();

public void updateBuffer(long newBuffer) {
    if (currentBuffer != 0L) {
        retiredBuffers.add(currentBuffer); // 延迟释放
    }
    currentBuffer = newBuffer;
    
    // 只释放 N 帧前的 buffer
    if (retiredBuffers.size() > KEEP_FRAMES) {
        long oldBuffer = retiredBuffers.poll();
        destroyBuffer(oldBuffer);
    }
}
```

### 假设 2：Reservoir Image 同步问题 ⭐⭐⭐⭐
**问题**：
- Trace 阶段写入 `reservoirImages.current()`（binding 10）
- NRD 可能访问相关的 guide buffers
- `swap()` 在帧结束时调用（line 2160）
- 下一帧立即读取 `previous()`，但可能还有写操作未完成

**验证方法**：
1. 检查 trace → denoise 之间是否有 image barrier
2. 验证 `swap()` 后是否有同步点
3. 添加 Vulkan validation layers 检查 WAR hazard

**修复方向**：
```java
// RtComposite.java - 在 denoise 前添加 barrier
if (ENABLE_RESTIR_DI && reservoirImages != null) {
    // Ensure reservoir writes are visible before denoise reads guides
    transitionImageLayout(cmd, reservoirImages.current(),
        VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR,
        VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
        VK_ACCESS_SHADER_WRITE_BIT,
        VK_ACCESS_SHADER_READ_BIT);
}
```

### 假设 3：NRD + ReSTIR 的 Resource Aliasing ⭐⭐⭐
**问题**：
- NRD 内部可能创建临时 buffer/image
- 当光源数量增长时，内存需求突然增加
- 可能触发 Vulkan 内存分配器的重新分配
- RADV 在这种情况下可能有 bug

**验证方法**：
1. 监控 VRAM 使用量（当前 ~2.5GB / 8GB）
2. 在光源增长时记录内存分配事件
3. 尝试预分配足够大的 blockLightBuffer

**修复方向**：
```java
// UnifiedLightManager.java - 预分配策略
private static final int INITIAL_LIGHT_CAPACITY = 256;

public void initialize() {
    // 预分配足够大的 buffer，避免动态增长
    blockLightBuffer.ensureCapacity(INITIAL_LIGHT_CAPACITY);
}
```

### 假设 4：RADV 特定的 Descriptor Indexing Bug ⭐⭐⭐
**问题**：
- ReSTIR shader 可能使用动态索引访问 `blockLightBuffer[lightIndex]`
- RADV 在 descriptor 动态更新 + 动态索引时可能有竞态条件
- 这与你代码中其他 RADV workarounds 类似

**验证方法**：
1. 检查 `restir_di.glsl` 中的 buffer 访问模式
2. 尝试在 AMDVLK 驱动上测试（对比 RADV）
3. 添加边界检查防止越界访问

**修复方向**：
```glsl
// restir_di.glsl - 添加边界检查
uint safeGetLight(uint index, uint maxCount) {
    if (index >= maxCount) {
        return INVALID_LIGHT_INDEX; // 返回无效光源
    }
    return blockLights[index];
}
```

## 调试步骤

### Phase 1: 添加详细日志
```java
// RtComposite.java
if (ENABLE_RESTIR_DI) {
    CausticaMod.LOGGER.info("Frame {}: ReSTIR DI - lights={}, bufferSize={}, reservoirSize={}x{}",
        frameIndex, 
        blockLightBuffer != null ? blockLightBuffer.count() : 0,
        blockLightBuffer != null ? blockLightBuffer.buffer() : 0L,
        renderW, renderH);
    
    if (backend != null) {
        CausticaMod.LOGGER.info("Frame {}: NRD backend={} active", frameIndex, backend.name());
    }
}
```

### Phase 2: 启用 Vulkan Validation Layers
```java
// 在 VulkanDevice 初始化时添加
if (RtDeviceBringup.isRadv() && ENABLE_RESTIR_DI) {
    enableValidationLayers = true;
    enableGPUAssistedValidation = true; // 检测 descriptor 访问越界
}
```

### Phase 3: 渐进式测试
1. **测试 A**：只启用 ReSTIR DI，禁用 NRD
   ```java
   private static final boolean ENABLE_RESTIR_DI = true;
   // 配置文件：denoise.mode = "off"
   ```
   - 如果不崩溃 → 问题在 NRD 交互
   - 如果崩溃 → 问题在 ReSTIR 本身

2. **测试 B**：固定光源数量（不允许增长）
   ```java
   // UnifiedLightManager.java
   if (blockLightBuffer.count() >= 64) {
       return; // 停止添加光源
   }
   ```
   - 如果不崩溃 → 问题在 buffer 动态增长
   - 如果崩溃 → 问题在其他地方

3. **测试 C**：延迟 descriptor 更新
   ```java
   // 每 4 帧才更新一次 descriptor
   if (frameIndex % 4 == 0 && blockLightBuffer.isDirty()) {
       worldPipeline.setExtraStorageBuffer(9, ...);
   }
   ```

### Phase 4: 添加同步屏障
```java
// 在 denoise 前添加全局内存屏障
if (ENABLE_RESTIR_DI && backend != null) {
    try (MemoryStack stack = MemoryStack.stackPush()) {
        VkMemoryBarrier2 barrier = VkMemoryBarrier2.calloc(stack)
            .sType$Default()
            .srcStageMask(VK_PIPELINE_STAGE_2_RAY_TRACING_SHADER_BIT_KHR)
            .srcAccessMask(VK_ACCESS_2_SHADER_WRITE_BIT)
            .dstStageMask(VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT)
            .dstAccessMask(VK_ACCESS_2_SHADER_READ_BIT);
        
        VkDependencyInfo depInfo = VkDependencyInfo.calloc(stack)
            .sType$Default()
            .pMemoryBarriers(VkMemoryBarrier2.create(barrier.address(), 1));
        
        KHRSynchronization2.vkCmdPipelineBarrier2KHR(cmd, depInfo);
    }
}
```

## 预期结果

- **假设 1 正确**：添加延迟释放后崩溃消失
- **假设 2 正确**：添加 barrier 后崩溃消失
- **假设 3 正确**：预分配 buffer 后崩溃消失
- **假设 4 正确**：AMDVLK 不崩溃，但 RADV 崩溃（需要 Mesa bug report）

## 下一步

根据测试结果，我们可以确定：
1. 是否可以通过代码修复（假设 1-3）
2. 是否需要提交 RADV bug report（假设 4）
3. 是否需要完全重构资源管理策略
