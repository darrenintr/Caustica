/*
 * D3D12 Hardware Capability Test
 * Tests if Wine can create a hardware-accelerated D3D12 device
 */

#include <windows.h>
#include <d3d12.h>
#include <dxgi1_6.h>
#include <cstdio>

#pragma comment(lib, "d3d12.lib")
#pragma comment(lib, "dxgi.lib")

int main() {
    printf("D3D12 Hardware Capability Test\n");
    printf("================================\n\n");

    // Create DXGI Factory
    IDXGIFactory4* factory = nullptr;
    HRESULT hr = CreateDXGIFactory2(0, IID_PPV_ARGS(&factory));
    if (FAILED(hr)) {
        printf("FAILED: CreateDXGIFactory2 (0x%08X)\n", hr);
        return 1;
    }
    printf("✓ DXGI Factory created\n\n");

    // Enumerate adapters
    printf("Available adapters:\n");
    IDXGIAdapter1* adapter = nullptr;
    for (UINT i = 0; ; ++i) {
        hr = factory->EnumAdapters1(i, &adapter);
        if (hr == DXGI_ERROR_NOT_FOUND) break;
        if (FAILED(hr)) continue;

        DXGI_ADAPTER_DESC1 desc;
        adapter->GetDesc1(&desc);

        wprintf(L"  [%d] %s\n", i, desc.Description);
        printf("      Vendor: 0x%04X, Device: 0x%04X\n", desc.VendorId, desc.DeviceId);
        printf("      VRAM: %zu MB\n", desc.DedicatedVideoMemory / (1024 * 1024));
        printf("      Flags: 0x%X ", desc.Flags);

        if (desc.Flags & DXGI_ADAPTER_FLAG_SOFTWARE) {
            printf("(SOFTWARE)\n");
        } else {
            printf("(HARDWARE)\n");
        }

        // Try to create D3D12 device on this adapter
        ID3D12Device* device = nullptr;

        // Try Feature Level 12_1 first
        hr = D3D12CreateDevice(adapter, D3D_FEATURE_LEVEL_12_1, IID_PPV_ARGS(&device));
        if (SUCCEEDED(hr)) {
            printf("      ✓ D3D12 Device (Feature Level 12_1) - SUCCESS\n");
            device->Release();
        } else {
            printf("      ✗ Feature Level 12_1 failed (0x%08X)\n", hr);

            // Try Feature Level 12_0
            hr = D3D12CreateDevice(adapter, D3D_FEATURE_LEVEL_12_0, IID_PPV_ARGS(&device));
            if (SUCCEEDED(hr)) {
                printf("      ✓ D3D12 Device (Feature Level 12_0) - SUCCESS\n");
                device->Release();
            } else {
                printf("      ✗ Feature Level 12_0 failed (0x%08X)\n", hr);

                // Try Feature Level 11_0
                hr = D3D12CreateDevice(adapter, D3D_FEATURE_LEVEL_11_0, IID_PPV_ARGS(&device));
                if (SUCCEEDED(hr)) {
                    printf("      ✓ D3D12 Device (Feature Level 11_0) - SUCCESS\n");
                    device->Release();
                } else {
                    printf("      ✗ Feature Level 11_0 failed (0x%08X)\n", hr);
                }
            }
        }

        printf("\n");
        adapter->Release();
    }

    // Try WARP (software rasterizer) as fallback
    printf("Testing WARP (software rasterizer):\n");
    ID3D12Device* warpDevice = nullptr;
    hr = D3D12CreateDevice(nullptr, D3D_FEATURE_LEVEL_11_0, IID_PPV_ARGS(&warpDevice));
    if (SUCCEEDED(hr)) {
        printf("  ✓ WARP device created successfully\n");
        warpDevice->Release();
    } else {
        printf("  ✗ WARP creation failed (0x%08X)\n", hr);
    }

    factory->Release();

    printf("\n================================\n");
    printf("Test complete\n");
    return 0;
}
