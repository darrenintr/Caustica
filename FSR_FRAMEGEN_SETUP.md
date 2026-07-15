# FSR 帧生成设置指南

## 当前状态

FSR 3/4 帧生成的代码框架已完整实现，包括：
- `FsrFrameGen.java` - 帧生成核心实现
- `FsrRuntime.java` - FSR 库加载器
- `FrameGenSelector.java` - 自动选择器
- 配置系统集成

## 限制

**Linux + Vulkan**: AMD 未发布 FSR 3/4 帧生成的 Vulkan 原生库。
- 打包的 `libamd_fidelityfx_framegeneration.so` 是 15KB stub
- 真正的库仅适用于 Windows + DX12

## 未来启用步骤

当 AMD 发布 Linux Vulkan 版本时：

### 1. 获取 FSR SDK
```bash
# 下载 AMD FidelityFX SDK 2.1+
git clone https://github.com/GPUOpen-Effects/FidelityFX-SDK.git
```

### 2. 设置环境变量
```bash
export FFX_SDK="/path/to/FidelityFX-SDK"
```

### 3. 重新构建 mod
```bash
export JAVA_HOME=/usr/lib/jvm/zulu-25
bash gradlew jar
```

### 4. 配置启用
编辑 `caustica.toml`:
```toml
[framegen]
    mode = "AUTO"  # 或 "FSR_3" / "FSR_4"
    multi-frame-count = 1
```

### 5. 运行时检查
游戏日志会显示：
```
[Caustica] FSR Frame Generation probe OK
[Caustica] Frame generation active: FSR 3 (multi-frame: 1)
```

## 替代方案

在 Linux 上，考虑使用：
- **DLSS Frame Generation** (NVIDIA RTX 40+ 系列)
- **XeSS Frame Generation** (Intel Arc)
- 或等待 AMD 发布 Vulkan 支持
