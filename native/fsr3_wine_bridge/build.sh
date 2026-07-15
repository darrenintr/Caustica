# FSR3 Wine Bridge Build Script

BUILD_DIR="build_wine"
MINGW_COMPILER="x86_64-w64-mingw32-g++"

echo "Building FSR3 Wine Bridge Server..."

# Check if MinGW is installed
if ! command -v $MINGW_COMPILER &> /dev/null; then
    echo "Error: MinGW-w64 not found. Install it with:"
    echo "  sudo pacman -S mingw-w64-gcc"
    exit 1
fi

mkdir -p $BUILD_DIR

# Compile Windows server
echo "Compiling Windows server (runs under Wine)..."
$MINGW_COMPILER -o $BUILD_DIR/fsr3_server.exe \
    fsr3_wine_server_full.cpp \
    -static-libgcc -static-libstdc++ \
    -ld3d12 -ldxgi -luuid \
    -DUNICODE -D_UNICODE

if [ $? -ne 0 ]; then
    echo "Server compilation failed"
    exit 1
fi

# Compile Linux client
echo "Compiling Linux client..."
g++ -o $BUILD_DIR/libfsr3_wine_client.so \
    fsr3_wine_client_full.cpp \
    -shared -fPIC \
    -lvulkan \
    -lrt \
    -std=c++17

if [ $? -ne 0 ]; then
    echo "Client compilation failed"
    exit 1
fi

echo "Build complete!"
echo "  Server: $BUILD_DIR/fsr3_server.exe (run with Wine)"
echo "  Client: $BUILD_DIR/libfsr3_wine_client.so"
echo ""
echo "Test server with:"
echo "  wine $BUILD_DIR/fsr3_server.exe"
