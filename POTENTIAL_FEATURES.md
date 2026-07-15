# Caustica 潜在的画质增强技术

## 高优先级（显著改善画质）

### 1. **Bloom / Glow**
- **效果**: 明亮物体产生光晕（岩浆、火把、天空）
- **实现难度**: ⭐⭐ (中等)
- **性能开销**: 低 (~1-2ms)
- **实现方式**: 
  - Threshold + Gaussian Blur (多 Pass 降采样)
  - 或 Kawase Blur (更快)
  - 混合回主画面
- **参考**: UE5 Bloom, Unity Bloom

### 2. **Depth of Field (景深)**
- **效果**: 模拟相机焦距，焦点外模糊
- **实现难度**: ⭐⭐⭐ (中等)
- **性能开销**: 中 (~2-4ms)
- **实现方式**:
  - Gather-based DoF (采样多个深度层)
  - 或 Bokeh DoF (更真实的散景)
- **配置**: 焦距、光圈大小、焦点位置
- **参考**: NVIDIA GameWorks DoF, UE5 Cinematic DoF

### 3. **Volumetric Lighting / God Rays (体积光)**
- **效果**: 光束穿过雾/灰尘（森林、洞穴）
- **实现难度**: ⭐⭐⭐⭐ (较难)
- **性能开销**: 中高 (~3-6ms)
- **实现方式**:
  - Ray marching through volume
  - Temporal reprojection 降低采样数
  - 或 Screen-space rays (更快但不精确)
- **参考**: UE5 Volumetric Fog, NVIDIA Volumetric Lighting

### 4. **Screen Space Reflections (SSR, 屏幕空间反射)**
- **效果**: 补充 RT 反射（水面、光滑地板）
- **实现难度**: ⭐⭐⭐ (中等)
- **性能开销**: 中 (~2-3ms)
- **实现方式**:
  - Hi-Z ray marching
  - 与 RT 反射混合（近处 SSR，远处 RT）
- **注意**: 已有 RT 反射，SSR 主要用于性能模式或填补 RT 缺失的细节
- **参考**: Unreal Engine SSR

### 5. **ACES Tone Mapping (更好的色调映射)**
- **效果**: 更电影化的色彩，更好的高光处理
- **实现难度**: ⭐ (简单)
- **性能开销**: 极低 (<0.1ms)
- **实现方式**:
  - 替换现有的 tone mapping
  - ACES filmic curve
- **配置**: 可选 ACES / Reinhard / Uncharted / Custom
- **参考**: ACES Color Grading

### 6. **Chromatic Aberration (色差)**
- **效果**: 镜头边缘的彩色条纹（可选艺术效果）
- **实现难度**: ⭐ (简单)
- **性能开销**: 极低 (<0.5ms)
- **实现方式**: RGB 分离采样
- **配置**: 强度、径向/横向

### 7. **Film Grain (胶片颗粒)**
- **效果**: 复古电影感
- **实现难度**: ⭐ (简单)
- **性能开销**: 极低 (<0.1ms)
- **实现方式**: 噪声叠加
- **配置**: 颗粒大小、强度

### 8. **Vignette (渐晕)**
- **效果**: 屏幕边缘变暗
- **实现难度**: ⭐ (简单)
- **性能开销**: 极低 (<0.1ms)
- **配置**: 强度、半径

## 中优先级（优化质量或性能）

### 9. **ReSTIR GI (全局光照重采样)**
- **效果**: 更高质量的间接光照（天花板反射、彩色渗透）
- **实现难度**: ⭐⭐⭐⭐⭐ (很难)
- **性能开销**: 高 (~5-10ms)
- **注意**: 已有 ReSTIR DI，GI 是下一步
- **参考**: NVIDIA ReSTIR GI paper

### 10. **Path Guiding (路径引导)**
- **效果**: 更智能的光线采样（更少 SPP 达到相同质量）
- **实现难度**: ⭐⭐⭐⭐⭐ (很难)
- **性能开销**: 中 (训练开销但降低采样数)
- **参考**: Intel Open Path Guiding Library

### 11. **Adaptive Sampling (自适应采样)**
- **效果**: 复杂区域多采样，简单区域少采样
- **实现难度**: ⭐⭐⭐ (中等)
- **性能开销**: 可节省 20-40%
- **实现方式**: 基于 variance 动态调整 SPP

### 12. **Variable Rate Shading (VRS)**
- **效果**: 边缘高精度，中心低精度（配合凝视点追踪）
- **实现难度**: ⭐⭐⭐ (中等)
- **性能开销**: 可节省 10-30%
- **需要**: Vulkan VRS extension
- **参考**: NVIDIA VRS

### 13. **Motion Blur (运动模糊)**
- **效果**: 快速移动时的拖影（相机转动、物体运动）
- **实现难度**: ⭐⭐⭐ (中等)
- **性能开销**: 中 (~2-3ms)
- **实现方式**:
  - Per-pixel velocity + temporal blur
  - 或 Camera motion blur only (更快)
- **配置**: 强度、采样数

### 14. **Subsurface Scattering (次表面散射)**
- **效果**: 皮肤、蜡、雪等半透明材质
- **实现难度**: ⭐⭐⭐⭐ (较难)
- **性能开销**: 高 (~3-5ms)
- **应用**: 玩家皮肤、特定方块
- **参考**: Disney SSS, UE5 SSS

### 15. **Caustics (焦散)**
- **效果**: 光通过水/玻璃的聚焦图案
- **实现难度**: ⭐⭐⭐⭐⭐ (很难)
- **性能开销**: 高
- **注意**: 项目名 "Caustica" 暗示这可能是终极目标！
- **实现方式**: Photon mapping 或 specialized ray tracing
- **参考**: NVIDIA OptiX caustics

## 低优先级（锦上添花）

### 16. **Lens Flare (镜头光晕)**
- **效果**: 看向太阳/强光源时的光斑
- **实现难度**: ⭐⭐ (简单)
- **性能开销**: 低 (~0.5ms)

### 17. **Screen Space Ambient Occlusion (SSAO)**
- **效果**: 补充 RT AO（性能模式）
- **实现难度**: ⭐⭐⭐ (中等)
- **注意**: 已有 RT，SSAO 主要用于回退

### 18. **Color Grading / LUT**
- **效果**: 艺术化色彩调整（暖色调、冷色调）
- **实现难度**: ⭐⭐ (简单)
- **性能开销**: 极低 (<0.5ms)

### 19. **Sharpening Filter (锐化)**
- **效果**: 抵消 TAA/upscaler 模糊
- **实现难度**: ⭐ (简单)
- **性能开销**: 极低 (<0.5ms)
- **注意**: upscaler 已有内置锐化

### 20. **Dithering (抖动)**
- **效果**: 减少色带（HDR → SDR 时）
- **实现难度**: ⭐ (简单)
- **性能开销**: 极低 (<0.1ms)

## 实现建议优先级

### 阶段 1：立即见效（1-2 天）
1. **Bloom** ⭐⭐⭐⭐⭐ - 最明显的画质提升
2. **ACES Tone Mapping** ⭐⭐⭐⭐ - 更好的色彩
3. **Vignette** ⭐⭐⭐ - 增强沉浸感

### 阶段 2：中期项目（1-2 周）
4. **Depth of Field** ⭐⭐⭐⭐ - 电影感
5. **Motion Blur** ⭐⭐⭐ - 平滑运动
6. **Volumetric Lighting** ⭐⭐⭐⭐⭐ - 视觉冲击力

### 阶段 3：高级优化（1+ 月）
7. **ReSTIR GI** ⭐⭐⭐⭐⭐ - 质量跃升
8. **Caustics** ⭐⭐⭐⭐⭐ - 项目终极目标？
9. **Path Guiding** ⭐⭐⭐⭐ - 性能优化

## 技术栈推荐

- **Bloom**: Compute shader (GLSL)
- **DoF**: Compute shader with gather
- **Volumetric**: Ray marching in compute shader
- **ACES**: Fragment shader 或 compute pass
- **ReSTIR GI**: 类似 ReSTIR DI 的架构
- **Caustics**: 需要 photon tracing pass

## 参考资源

- NVIDIA GameWorks: https://github.com/NVIDIAGameWorks
- UE5 Rendering: Unreal Engine documentation
- Shadertoy: 快速原型验证
- Ray Tracing Gems: 理论基础
- SIGGRAPH papers: ReSTIR, Path Guiding
