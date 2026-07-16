/*
 * Test client for FSR3 Wine Bridge
 */

#include <cstdio>
#include <cstdint>
#include <unistd.h>
#include <dlfcn.h>

// Function pointers matching the C API
typedef void* (*CreateFn)(void*, void*, void*);
typedef int (*CreateContextFn)(void*, uint32_t, uint32_t, uint32_t, uint32_t);
typedef int (*DispatchFn)(void*, void*, void*, void*, void*, uint32_t, uint32_t, float, float);
typedef void (*DestroyFn)(void*);

int main() {
    printf("FSR3 Wine Bridge Test Client\n");
    printf("=============================\n\n");

    // Load the client library
    printf("Loading libfsr3_wine_client.so...\n");
    void* lib = dlopen("/tmp/fsr3_test/libfsr3_wine_client.so", RTLD_LAZY);
    if (!lib) {
        printf("ERROR: Failed to load library: %s\n", dlerror());
        return 1;
    }

    // Get function pointers
    auto create = (CreateFn)dlsym(lib, "fsr3_wine_bridge_create");
    auto createContext = (CreateContextFn)dlsym(lib, "fsr3_wine_bridge_create_context");
    auto destroy = (DestroyFn)dlsym(lib, "fsr3_wine_bridge_destroy");

    if (!create || !createContext || !destroy) {
        printf("ERROR: Failed to load functions\n");
        dlclose(lib);
        return 1;
    }

    printf("Functions loaded successfully\n\n");

    // Create bridge (no Vulkan instance for now, just test connection)
    printf("Connecting to Wine server...\n");
    void* bridge = create(nullptr, nullptr, nullptr);
    if (!bridge) {
        printf("ERROR: Failed to create bridge\n");
        printf("Make sure Wine server is running:\n");
        printf("  wine fsr3_server.exe\n");
        dlclose(lib);
        return 1;
    }

    printf("✓ Connected to Wine server!\n\n");

    // Test context creation
    printf("Testing FSR3 context creation (1280x720 -> 1920x1080)...\n");
    int result = createContext(bridge, 1280, 720, 1920, 1080);
    if (result == 0) {
        printf("✓ FSR3 context created successfully!\n\n");
    } else {
        printf("✗ FSR3 context creation failed\n\n");
    }

    // Test dispatch
    printf("Testing FSR3 dispatch (720p -> 1080p, jitter=0.5)...\n");
    auto dispatchFn = (DispatchFn)dlsym(lib, "fsr3_wine_bridge_dispatch");
    if (dispatchFn) {
        result = dispatchFn(bridge,
                         (void*)0, (void*)0, (void*)0, (void*)0,  // Null memory handles for test
                         1280, 720, 0.5f, 0.5f);
        if (result == 0) {
            printf("✓ FSR3 dispatch completed!\n\n");
        } else {
            printf("✗ FSR3 dispatch failed\n\n");
        }
    }

    // Cleanup
    printf("Cleaning up...\n");
    destroy(bridge);
    dlclose(lib);

    printf("\n=============================\n");
    printf("Test completed!\n");
    return 0;
}
