#!/bin/bash

# FSR3 Wine Bridge Integration Test Script

set -e

echo "=================================="
echo "FSR3 Wine Bridge Integration Test"
echo "=================================="
echo ""

# Cleanup old processes
echo "Cleaning up old processes..."
pkill -9 -f fsr3_server 2>/dev/null || true
sleep 1

# Prepare test directory
TEST_DIR="/tmp/fsr3_test"
BUILD_DIR="$(dirname "$0")/build_wine"

mkdir -p "$TEST_DIR"
cd "$BUILD_DIR"

echo "Copying files to test directory..."
cp libfsr3_wine_client.so "$TEST_DIR/"
cp test_client "$TEST_DIR/"
chmod +x "$TEST_DIR/test_client"

# Start Wine server in background
echo ""
echo "Starting Wine FSR3 server..."
wine fsr3_server_stub.exe > "$TEST_DIR/server.log" 2>&1 &
SERVER_PID=$!
echo "Server PID: $SERVER_PID"

# Wait for server to be ready
echo "Waiting for server to initialize..."
for i in {1..15}; do
    if grep -q "Server ready" "$TEST_DIR/server.log" 2>/dev/null; then
        echo "✓ Server is ready!"
        break
    fi
    if [ $i -eq 15 ]; then
        echo "✗ Server failed to start within 15 seconds"
        echo ""
        echo "Server log:"
        cat "$TEST_DIR/server.log"
        kill $SERVER_PID 2>/dev/null || true
        exit 1
    fi
    sleep 1
    echo -n "."
done

echo ""
echo ""

# Run test client
echo "Running test client..."
cd "$TEST_DIR"
LD_LIBRARY_PATH="$TEST_DIR" ./test_client

TEST_RESULT=$?

echo ""
echo "=================================="

# Show server log
echo ""
echo "Server log:"
echo "----------"
tail -20 "$TEST_DIR/server.log"

# Cleanup
echo ""
echo "Cleaning up..."
kill $SERVER_PID 2>/dev/null || true
sleep 1

if [ $TEST_RESULT -eq 0 ]; then
    echo ""
    echo "✓ Test PASSED!"
    exit 0
else
    echo ""
    echo "✗ Test FAILED"
    exit 1
fi
