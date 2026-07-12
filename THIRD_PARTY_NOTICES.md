# Third-Party Notices

Caustica's project-owned code is licensed under `LGPL-3.0-or-later`. This file
documents third-party components and license boundaries that are not changed by
Caustica's license.

## NVIDIA DLSS / NGX SDK

Caustica can build and distribute release artifacts that include NVIDIA DLSS/NGX
SDK runtime components, including DLSS Ray Reconstruction and Frame Generation
libraries. These NVIDIA components are proprietary third-party software and are
not licensed under the LGPL.

The NVIDIA SDK components remain subject to the NVIDIA RTX SDKs license:

<https://github.com/NVIDIA/DLSS/blob/main/LICENSE.txt>

The LGPL license grant for Caustica does not grant rights to NVIDIA SDK
components. Redistribution and use of those components must comply with
NVIDIA's license terms.

This software contains source code provided by NVIDIA Corporation.

Bundled NVIDIA SDK runtime libraries may include files matching:

- `caustica/natives/windows-x64/nvngx_dlssd.dll`
- `caustica/natives/windows-x64/nvngx_dlssg.dll`
- `caustica/natives/linux-x64/libnvidia-ngx-dlssd.so*`
- `caustica/natives/linux-x64/libnvidia-ngx-dlssg.so*`

Caustica's `ngxshim` native library is project-owned glue code and follows
Caustica's project license unless otherwise noted.

## AMD FidelityFX SDK (FSR 3 / FSR 4)

Caustica can build and distribute release artifacts that include AMD FidelityFX
SDK runtime components (FSR 3, FSR 4.1 INT8, and FSR Frame Generation). These
AMD components are proprietary third-party software licensed under the AMD
FidelityFX SDK EULA and are not licensed under the LGPL.

The AMD SDK components remain subject to the AMD FidelityFX SDK license:

<https://gpuopen.com/fidelityfx-sdk-license/>

The LGPL license grant for Caustica does not grant rights to AMD SDK
components. Redistribution and use of those components must comply with
AMD's license terms.

Bundled AMD SDK runtime libraries may include files matching:

- `caustica/natives/windows-x64/amd_fidelityfx_loader.dll`
- `caustica/natives/windows-x64/amd_fidelityfx_upscaler.dll`
- `caustica/natives/windows-x64/amd_fidelityfx_framegeneration.dll`
- `caustica/natives/linux-x64/libamd_fidelityfx_loader.so`
- `caustica/natives/linux-x64/libamd_fidelityfx_upscaler.so`
- `caustica/natives/linux-x64/libamd_fidelityfx_framegeneration.so`

FSR 3 / FSR 4 share the same set of DLLs; the AMD modular loader picks the
right model per device. Caustica's FFX Java bindings are project-owned glue
code and follow Caustica's project license unless otherwise noted.

## Intel XeSS SDK

Caustica can build and distribute release artifacts that include Intel XeSS
SDK runtime components (XeSS upscaler + XeSS-FG on SDK 2.1+). These Intel
components are proprietary third-party software licensed under the Intel
Software License and are not licensed under the LGPL.

The Intel SDK components remain subject to the Intel XeSS SDK license:

<https://github.com/intel/xess/blob/main/LICENSE.txt>

The LGPL license grant for Caustica does not grant rights to Intel SDK
components. Redistribution and use of those components must comply with
Intel's license terms.

Bundled Intel SDK runtime libraries may include files matching:

- `caustica/natives/windows-x64/libxess.dll`
- `caustica/natives/linux-x64/libxess.so`

XeSS is a cross-vendor SDK (XMX path on Intel Arc / Xe-LPG, DP4a fallback
on NVIDIA / AMD SM 6.4+). Caustica's XeSS Java bindings are project-owned
glue code and follow Caustica's project license unless otherwise noted.
