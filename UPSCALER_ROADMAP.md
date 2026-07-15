# Caustica Upscaler 扩展路线图

## 当前状况

### ✅ 已实现
1. **FSR 2.x Classic** (Vulkan) - 开源，完全可用
2. **NRD REBLUR** 降噪 - 工作正常
3. **FFX Denoiser** - 有 shader 但质量有问题

### ❌ 缺失/Stub
1. **FSR 3.1/4.1 Modular** - AMD 未发布 Linux Vulkan 库
2. **DLSS-RR** - 需要 `VK_NVX_binary_import` (NVIDIA 专有)
3. **XeSS** - Intel 有 Vulkan 支持但未集成
4. **Frame Generation** - 所有厂商都缺 Linux Vulkan 支持

---

## 方案：基于 Optiscaler 思路的跨供应商支持

### Optiscaler 是什么？
- GitHub: https://github.com/cdozdil/OptiScaler
- **核心思想**: 用 XeSS/FSR 实现 DLSS API 接口
- **实现**: C++ 代理 DLL，拦截 DLSS 调用转发到其他 SDK

### 适配到 Caustica 的方案

#### 1. XeSS DP4a 支持（最优先）

**为什么优先**：
- Intel XeSS 有**官方 Vulkan 支持**
- DP4a 模式可以在**任何 GPU** 上运行（AMD/NVIDIA/Intel）
- 质量接近 DLSS（比 FSR2 好）

**实现步骤**：

```bash
# 1. 下载 Intel XeSS SDK
cd third_party
wget https://github.com/intel/xess/releases/download/v1.3.0/xess-sdk-1.3.0.zip
unzip xess-sdk-1.3.0.zip

# 2. 编译 Caustica XeSS shim
cd native/xess_vk
cmake -S . -B build -DCMAKE_BUILD_TYPE=Release \
  -DXESS_SDK=../../third_party/xess-sdk
cmake --build build -j

# 3. 拷贝到资源目录
cp build/libxess_caustica.so \
   src/main/resources/caustica/natives/linux-x64/
```

**Java 集成**（已有部分代码）：
- `XeSsUpscaler.java` 已存在
- 需要添加 Vulkan 调用逻辑
- 参考 `Fsr2ClassicUpscaler.java` 的结构

**配置**：
```toml
[upscaler]
    mode = "xess"
    quality = 1

[xess]
    mode = "auto"  # AUTO = XMX on Intel, DP4a on AMD/NVIDIA
```

---

#### 2. NVIDIA DLSS-SR (Upscaler Only)

**问题**：
- DLSS-RR 需要 `VK_NVX_binary_import`（RADV 不支持）
- 但 **DLSS-SR (Super Resolution)** 用标准 Vulkan 扩展

**替代路径**：
1. 使用 NVIDIA NGX SDK 的 **DLSS-SR** 特性（不是 RR）
2. 或者使用 **Streamline** 框架（NVIDIA 跨 API 抽象层）

**Streamline 方案**：
```bash
# 下载 NVIDIA Streamline
https://github.com/NVIDIAGameWorks/Streamline

# 集成到 Caustica
native/streamline/
  - sl_dlss.cpp      # DLSS upscaler
  - sl_reflex.cpp    # Reflex low latency
  - sl_nis.cpp       # NIS sharpening
```

**优点**：
- Streamline 支持 Vulkan
- 不需要 `VK_NVX_*` 私有扩展
- 同时获得 Reflex + NIS

---

#### 3. Frame Generation 支持

**现实**：
- **FSR 3/4 FG**: AMD 无 Linux Vulkan 库
- **DLSS-FG**: NVIDIA 无 Linux Vulkan 支持
- **XeSS-FG**: Intel 刚开始支持，Linux 未知

**短期解决方案**：
❌ 无原生 Frame Generation

**中期方案（实验性）**：
实现**软件插帧**（类似 SVP）：
1. 光流计算（optical flow）
2. 帧插值
3. 性能开销大但可用

```glsl
// optical_flow.comp - 计算相邻帧的运动向量
// frame_interpolate.comp - 基于光流生成中间帧
```

---

#### 4. 跨供应商降噪改进

**问题**：
- NRD 在低 SPP 下质量差
- FFX 有 bug

**新方案**：

##### A. OIDN (Intel Open Image Denoise)
- **开源**，CPU/GPU 都支持
- **质量极佳**（离线渲染级别）
- **Vulkan/SYCL** 后端

```bash
# 集成 OIDN
https://github.com/OpenImageDenoise/oidn

# Caustica 集成
native/oidn/
  oidn_caustica.cpp  # 包装 OIDN API
```

##### B. SVGF (Spatiotemporal Variance-Guided Filtering)
- **论文**开源的降噪算法
- 比 bilateral 好，比 NRD 简单
- 纯 GLSL compute shader

```glsl
// svgf_temporal.comp  - 时间复用
// svgf_variance.comp  - 方差估计
// svgf_atrous.comp    - 空间滤波
```

##### C. ReBLUR (NVIDIA 更新的 NRD)
- NRD 的继任者
- 更好的质量和性能
- 检查是否可以升级

---

## 实现优先级

### Phase 1: 立即可行（1-2 周）
1. ✅ **修复 FSR2 Classic** - 已完成
2. 🔨 **添加 XeSS DP4a 支持** - Intel SDK 可用
3. 🔨 **实现 SVGF 降噪** - 纯 shader，无依赖

### Phase 2: 中期目标（1-2 月）
4. 🔨 **NVIDIA Streamline (DLSS-SR + Reflex + NIS)**
5. 🔨 **Intel OIDN 降噪** - 高质量备选
6. 🔨 **软件帧插值（实验性）**

### Phase 3: 等待外部支持（未知）
7. ⏳ **FSR 3/4 Modular** - 等 AMD 发布 Linux Vulkan
8. ⏳ **原生 Frame Generation** - 等厂商支持

---

## 技术参考

### XeSS 集成参考
- **Doom Eternal**: 有 XeSS 支持
- **Intel XeSS Sample**: https://github.com/intel/xess
- **DP4a Vulkan Extension**: `VK_KHR_shader_integer_dot_product`

### Streamline 集成参考
- **NVIDIA Streamline SDK**: https://github.com/NVIDIAGameWorks/Streamline
- **Cyberpunk 2077**: 使用 Streamline

### OIDN 集成参考
- **Blender Cycles**: 使用 OIDN
- **OIDN Vulkan Backend**: 需要 compute shader 封装

### SVGF 论文实现
- **Paper**: "Spatiotemporal Variance-Guided Filtering" (Schied et al. 2017)
- **Reference**: https://research.nvidia.com/publication/2017-07_spatiotemporal-variance-guided-filtering-real-time-reconstruction

---

## 下一步行动

**我现在可以帮你实现**：

1. **XeSS DP4a 集成** - 完整的 native + Java 代码
2. **SVGF 降噪** - 纯 GLSL shader
3. **Bloom 后处理** - 简单但效果好

**选择哪个？**
