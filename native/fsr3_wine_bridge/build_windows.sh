# FSR3 Windows Build Script
# Builds the Windows in-process FSR3 integration

BUILD_DIR="build_windows"
MSVC_COMPILER="cl.exe"
CLANG_COMPILER="clang++"

echo "Building FSR3 Windows Integration..."

# Check if MSVC is available (preferred)
if command -v cl.exe &> /dev/null; then
    COMPILER="msvc"
    echo "Using MSVC compiler"
elif command -v clang++ &> /dev/null; then
    COMPILER="clang"
    echo "Using Clang compiler"
else
    echo "Error: No suitable compiler found (need MSVC or Clang)"
    exit 1
fi

mkdir -p $BUILD_DIR

# Compile Windows FSR3 integration
echo "Compiling Windows FSR3 integration..."

if [ "$COMPILER" = "msvc" ]; then
    # MSVC build
    cl.exe /O2 /MD /LD \
        fsr3_windows.cpp \
        /I"$VULKAN_SDK/Include" \
        /I"$DXSDK_DIR/Include" \
        /link \
        /OUT:$BUILD_DIR/fsr3_windows.dll \
        d3d12.lib dxgi.lib vulkan-1.lib \
        /LIBPATH:"$VULKAN_SDK/Lib" \
        /LIBPATH:"$DXSDK_DIR/Lib/x64"
else
    # Clang build (cross-compile or native Windows)
    $CLANG_COMPILER -o $BUILD_DIR/fsr3_windows.dll \
        fsr3_windows.cpp \
        -shared -fPIC \
        -O2 -std=c++17 \
        -I"$VULKAN_SDK/Include" \
        -ld3d12 -ldxgi -lvulkan-1 \
        -L"$VULKAN_SDK/Lib"
fi

if [ $? -ne 0 ]; then
    echo "Windows build failed"
    exit 1
fi

echo "Build complete!"
echo "  Windows: $BUILD_DIR/fsr3_windows.dll"
echo ""
echo "To use:"
echo "  1. Place fsr3_windows.dll in your application directory"
echo "  2. Ensure amd_fidelityfx_upscaler_dx12.dll is available"
echo "  3. Load via JNI or direct C API"
