# FSR3 Windows In-Process Integration

## 概述
在 Windows 上，Caustica 可以在**同一进程内**同时使用 Vulkan 和 D3D12，通过 GPU 互操作调用 FSR3。

---

## 架构对比

### Linux 方案（已实现）
```
Caustica (Vulkan, 进程 A)
    ↓ TCP Socket
FSR3 Server (D3D12, 进程 B, Wine)
```

### Windows 方案（推荐）
```
Caustica (单进程)
    Vulkan 线程 ←→ D3D12 线程
         ↓             ↓
    Vulkan 纹理   D3D12 纹理
         ↓             ↓
       GPU 共享内存（同一设备）
```

---

## GPU 互操作 API

### 1. Vulkan → D3D12

**Vulkan 端（导出）**：
```cpp
// 创建支持外部内存的 Vulkan Image
VkExternalMemoryImageCreateInfo externalInfo = {};
externalInfo.sType = VK_STRUCTURE_TYPE_EXTERNAL_MEMORY_IMAGE_CREATE_INFO;
externalInfo.handleTypes = VK_EXTERNAL_MEMORY_HANDLE_TYPE_D3D12_RESOURCE_BIT;

VkImageCreateInfo imageInfo = {};
imageInfo.pNext = &externalInfo;
imageInfo.usage = VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | 
                  VK_IMAGE_USAGE_SAMPLED_BIT;
// ... 其他参数

vkCreateImage(device, &imageInfo, nullptr, &image);

// 分配支持外部内存的设备内存
VkExportMemoryAllocateInfo exportInfo = {};
exportInfo.sType = VK_STRUCTURE_TYPE_EXPORT_MEMORY_ALLOCATE_INFO;
exportInfo.handleTypes = VK_EXTERNAL_MEMORY_HANDLE_TYPE_D3D12_RESOURCE_BIT;

VkMemoryAllocateInfo allocInfo = {};
allocInfo.pNext = &exportInfo;
// ...

vkAllocateMemory(device, &allocInfo, nullptr, &memory);
vkBindImageMemory(device, image, memory, 0);

// 获取 Windows HANDLE
VkMemoryGetWin32HandleInfoKHR handleInfo = {};
handleInfo.sType = VK_STRUCTURE_TYPE_MEMORY_GET_WIN32_HANDLE_INFO_KHR;
handleInfo.memory = memory;
handleInfo.handleType = VK_EXTERNAL_MEMORY_HANDLE_TYPE_D3D12_RESOURCE_BIT;

HANDLE sharedHandle;
vkGetMemoryWin32HandleKHR(device, &handleInfo, &sharedHandle);
```

**D3D12 端（导入）**：
```cpp
// 打开共享句柄
ID3D12Heap* heap = nullptr;
HRESULT hr = d3d12Device->OpenSharedHandle(
    sharedHandle,
    IID_PPV_ARGS(&heap)
);

// 在共享 heap 上创建 D3D12 资源
D3D12_RESOURCE_DESC resourceDesc = {};
resourceDesc.Dimension = D3D12_RESOURCE_DIMENSION_TEXTURE2D;
resourceDesc.Width = width;
resourceDesc.Height = height;
resourceDesc.Format = DXGI_FORMAT_R16G16B16A16_FLOAT;
resourceDesc.MipLevels = 1;
resourceDesc.SampleDesc.Count = 1;
resourceDesc.Flags = D3D12_RESOURCE_FLAG_ALLOW_UNORDERED_ACCESS;

ID3D12Resource* d3d12Resource = nullptr;
hr = d3d12Device->CreatePlacedResource(
    heap,
    0, // heapOffset
    &resourceDesc,
    D3D12_RESOURCE_STATE_COMMON,
    nullptr,
    IID_PPV_ARGS(&d3d12Resource)
);
```

### 2. D3D12 → Vulkan（输出回来）

使用相同的机制反向操作。

---

## 同步机制

### Vulkan Semaphore ↔ D3D12 Fence

**Vulkan 端**：
```cpp
// 创建支持外部同步的 Semaphore
VkExportSemaphoreCreateInfo exportInfo = {};
exportInfo.sType = VK_STRUCTURE_TYPE_EXPORT_SEMAPHORE_CREATE_INFO;
exportInfo.handleTypes = VK_EXTERNAL_SEMAPHORE_HANDLE_TYPE_D3D12_FENCE_BIT;

VkSemaphoreCreateInfo semaphoreInfo = {};
semaphoreInfo.pNext = &exportInfo;

vkCreateSemaphore(device, &semaphoreInfo, nullptr, &semaphore);

// 导出为 Windows HANDLE
VkSemaphoreGetWin32HandleInfoKHR handleInfo = {};
handleInfo.sType = VK_STRUCTURE_TYPE_SEMAPHORE_GET_WIN32_HANDLE_INFO_KHR;
handleInfo.semaphore = semaphore;
handleInfo.handleType = VK_EXTERNAL_SEMAPHORE_HANDLE_TYPE_D3D12_FENCE_BIT;

HANDLE fenceHandle;
vkGetSemaphoreWin32HandleKHR(device, &handleInfo, &fenceHandle);
```

**D3D12 端**：
```cpp
// 打开为 D3D12 Fence
ID3D12Fence* fence = nullptr;
d3d12Device->OpenSharedHandle(
    fenceHandle,
    IID_PPV_ARGS(&fence)
);

// 等待 Vulkan 完成
commandQueue->Wait(fence, signalValue);

// 执行 FSR3
commandList->Close();
commandQueue->ExecuteCommandLists(1, &commandList);

// 通知 Vulkan 完成
commandQueue->Signal(fence, signalValue + 1);
```

---

## 完整工作流程

### 每帧步骤

```cpp
// 1. Vulkan 渲染
vkCmdBeginRenderPass(...);
vkCmdDraw(...);
vkCmdEndRenderPass(...);

// 2. 过渡 Vulkan 纹理到外部使用
VkImageMemoryBarrier barrier = {};
barrier.oldLayout = VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL;
barrier.newLayout = VK_IMAGE_LAYOUT_GENERAL;
barrier.srcQueueFamilyIndex = vulkanQueueFamily;
barrier.dstQueueFamilyIndex = VK_QUEUE_FAMILY_EXTERNAL;
vkCmdPipelineBarrier(..., &barrier);

// 3. 信号 D3D12 可以开始
vkQueueSubmit(..., vulkanSemaphore);

// 4. D3D12 等待 Vulkan 完成
d3d12Queue->Wait(d3d12Fence, vulkanSignalValue);

// 5. 执行 FSR3 upscale
ffxDispatchDescUpscale desc = {};
desc.color = d3d12ColorResource;
desc.output = d3d12OutputResource;
// ...
ffxDispatch(fsr3Context, &desc);

// 6. D3D12 信号完成
d3d12Queue->Signal(d3d12Fence, d3d12SignalValue);

// 7. Vulkan 等待 D3D12 完成
vkWaitSemaphores(..., d3d12Semaphore);

// 8. 过渡回 Vulkan
barrier.oldLayout = VK_IMAGE_LAYOUT_GENERAL;
barrier.newLayout = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
barrier.srcQueueFamilyIndex = VK_QUEUE_FAMILY_EXTERNAL;
barrier.dstQueueFamilyIndex = vulkanQueueFamily;
vkCmdPipelineBarrier(..., &barrier);

// 9. Vulkan 显示
vkCmdBlitImage(...);
vkQueuePresentKHR(...);
```

---

## 性能分析

### 开销估算

| 操作 | 时间 |
|------|------|
| Vulkan → D3D12 队列转换 | ~0.1ms |
| D3D12 等待 Vulkan | ~0.1ms |
| FSR3 计算 | 2-4ms |
| D3D12 → Vulkan 队列转换 | ~0.1ms |
| Vulkan 等待 D3D12 | ~0.1ms |
| **总计** | **2.4-4.4ms** |

**对比**：
- Linux 跨进程方案: 4-7ms
- Windows 进程内方案: 2.4-4.4ms
- **节省**: ~2ms (30-40%)

---

## 实现复杂度

### 需要的代码

1. **Vulkan 外部内存支持** (~200 行)
   - 创建支持外部内存的 Image
   - 导出 Windows HANDLE
   - 队列族转换

2. **D3D12 共享资源导入** (~200 行)
   - 打开共享句柄
   - 创建 Placed Resource
   - 资源状态管理

3. **同步原语** (~150 行)
   - Vulkan Semaphore ↔ D3D12 Fence
   - 信号和等待

4. **FSR3 集成** (~300 行)
   - D3D12 设备初始化
   - FSR3 上下文创建
   - Dispatch 调用

**总计**: ~850 行（比 Linux 方案少 ~1200 行）

---

## JNI 接口

```java
public class FSR3Upscaler {
    // 初始化（传入 Vulkan 句柄）
    private static native long nativeCreate(
        long vkInstance,
        long vkPhysicalDevice, 
        long vkDevice
    );
    
    // 创建 FSR3 上下文
    private static native boolean nativeCreateContext(
        long handle,
        int renderWidth, int renderHeight,
        int displayWidth, int displayHeight
    );
    
    // 上采样（传入 Vulkan Image 句柄）
    private static native boolean nativeDispatch(
        long handle,
        long vkColorImage,
        long vkDepthImage,
        long vkMotionImage,
        long vkOutputImage,
        float jitterX, float jitterY
    );
}
```

---

## 优势总结

| 方面 | Linux 跨进程 | Windows 进程内 |
|------|-------------|----------------|
| **性能** | 70-85% | 95-98% |
| **延迟** | 4-7ms | 2.4-4.4ms |
| **D3D12 支持** | ⚠️ VKD3D | ✅ 原生 |
| **FSR3 可用性** | ❌ 崩溃 | ✅ 正常 |
| **实现复杂度** | 2075 行 | ~850 行 |
| **稳定性** | ⚠️ Wine 依赖 | ✅ 稳定 |

---

## 建议

### 如果 Caustica 需要支持 Windows

**最佳方案**: 实现 Windows 进程内版本

1. **短期**（1-2 周）:
   - 实现 Vulkan External Memory 支持
   - 实现 D3D12 共享资源导入
   - 基础的 FSR3 集成

2. **中期**（1 个月）:
   - 完善同步机制
   - 性能优化
   - 错误处理

3. **长期**:
   - 两个平台共享 Java 接口
   - Linux 使用 FSR2
   - Windows 使用 FSR3

### 代码复用

可以复用当前 Linux 项目的：
- ✅ FFX API 定义（`ffx_api.h`）
- ✅ FSR3 上下文创建逻辑
- ✅ Dispatch 参数管理
- ✅ 错误处理框架

**只需替换**：
- ❌ TCP 通信层 → 进程内直接调用
- ❌ Socket 数据传输 → 共享 GPU 内存

---

## 结论

**Windows 端非常可行！** ✅

- 原生 D3D12 = FSR3 100% 工作
- 进程内互操作 = 更好的性能
- 代码量更少 = 更容易维护

**建议**：
1. Linux: 使用 FSR2（已有）
2. Windows: 实现 FSR3（进程内）
3. 提供统一的 Java 接口

这样可以在两个平台上都提供最佳体验！
