# FSR3 Integration Build Script - Platform Selector
# Automatically builds for the current platform

PLATFORM=$(uname -s)

case "$PLATFORM" in
    Linux*)
        echo "Detected Linux platform"
        echo "Building Linux version (Wine Bridge)..."
        bash build.sh
        ;;
    MINGW*|MSYS*|CYGWIN*)
        echo "Detected Windows platform"
        echo "Building Windows version (In-Process)..."
        bash build_windows.sh
        ;;
    *)
        echo "Unknown platform: $PLATFORM"
        echo "Supported platforms: Linux, Windows"
        exit 1
        ;;
esac
