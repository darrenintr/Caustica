#!/bin/bash
# XeSS + SVGF 集成构建脚本

set -e

PROJECT_ROOT="/run/media/darren/1TB/Windows data/project/Caustica"
cd "$PROJECT_ROOT"

echo "=========================================="
echo "Caustica 非Nvidia重构 - 构建脚本"
echo "=========================================="
echo ""

# Step 1: 编译 SVGF shaders
echo "[1/4] 编译 SVGF shaders..."
SHADER_DIR="$PROJECT_ROOT/shaders/denoise/svgf"
OUTPUT_DIR="$PROJECT_ROOT/src/main/resources/caustica/rt/denoise"

mkdir -p "$OUTPUT_DIR"

if command -v glslangValidator &> /dev/null; then
    for shader in "$SHADER_DIR"/*.comp; do
        if [ -f "$shader" ]; then
            basename=$(basename "$shader" .comp)
            echo "  编译: $basename.comp → $basename.comp.spv"
            glslangValidator -V \
                --target-env vulkan1.3 \
                -o "$OUTPUT_DIR/${basename}.comp.spv" \
                "$shader"
        fi
    done
    echo "  ✓ SVGF shaders 编译完成"
else
    echo "  ⚠ glslangValidator 未找到，跳过 shader 编译"
    echo "  请安装: sudo pacman -S vulkan-tools"
fi

echo ""

# Step 2: 编译 XeSS native library (如果 SDK 可用)
echo "[2/4] 编译 XeSS native library..."
XESS_SDK="$PROJECT_ROOT/third_party/xess-sdk"
XESS_BUILD="$PROJECT_ROOT/native/xess_vk/build"

if [ -d "$XESS_SDK" ]; then
    echo "  检测到 XeSS SDK: $XESS_SDK"
    mkdir -p "$XESS_BUILD"
    cd "$XESS_BUILD"

    cmake .. \
        -DCMAKE_BUILD_TYPE=Release \
        -DXESS_SDK="$XESS_SDK"

    make -j$(nproc)
    make install

    echo "  ✓ XeSS native library 编译完成"
else
    echo "  ⚠ XeSS SDK 未找到: $XESS_SDK"
    echo "  构建 stub 实现（无实际 XeSS 功能）"
    mkdir -p "$XESS_BUILD"
    cd "$XESS_BUILD"

    cmake .. -DCMAKE_BUILD_TYPE=Release
    make -j$(nproc)
    make install

    echo "  ✓ XeSS stub library 编译完成"
fi

cd "$PROJECT_ROOT"
echo ""

# Step 3: 编译 Java 代码
echo "[3/4] 编译 Caustica (Gradle)..."
./gradlew build -x test --no-daemon

echo "  ✓ Java 代码编译完成"
echo ""

# Step 4: 部署到测试实例
echo "[4/4] 部署到测试实例..."
DEPLOY_DIR="/home/darren/.local/share/PrismLauncher/instances/26.2(1)/minecraft/mods"

if [ -d "$DEPLOY_DIR" ]; then
    JAR_FILE=$(find build/libs -name "caustica-*.jar" -type f | head -n 1)

    if [ -f "$JAR_FILE" ]; then
        cp -f "$JAR_FILE" "$DEPLOY_DIR/"
        echo "  ✓ 已部署: $(basename $JAR_FILE)"
    else
        echo "  ⚠ JAR 文件未找到"
    fi
else
    echo "  ⚠ 部署目录不存在: $DEPLOY_DIR"
fi

echo ""
echo "=========================================="
echo "构建完成！"
echo "=========================================="
echo ""
echo "新增功能:"
echo "  • XeSS DP4a 超分辨率 (跨平台 ML upscaler)"
echo "  • SVGF 降噪器 (纯 shader 实现)"
echo ""
echo "配置方法 (caustica.toml):"
echo "  [rt.upscaler]"
echo "      mode = \"xess\"    # 或 \"auto\" 自动选择"
echo ""
echo "  [rt.denoiser]"
echo "      mode = \"svgf\"    # SVGF 降噪"
echo ""
echo "测试建议:"
echo "  1. 启动游戏，检查日志中的 XeSS/SVGF 初始化信息"
echo "  2. 对比 TAAU vs XeSS 画质差异 (XeSS 应该更清晰)"
echo "  3. 测试 SVGF vs Bilateral 降噪效果"
echo ""
