# Third-Party Notices

Caustica's project-owned code is licensed under `LGPL-3.0-or-later`. This file
documents third-party components and license boundaries that are not changed by
Caustica's license.

## NVIDIA Real-Time Denoisers (NRD)

Caustica includes an optional NRD-based Vulkan denoise provider and may bundle a
native library such as:

- `caustica/natives/windows-x64/nrd_caustica.dll`
- `caustica/natives/linux-x64/libnrd_caustica.so`

NRD is third-party software from NVIDIA Corporation and remains subject to the
NVIDIA RTX SDKs license distributed with the NRD source tree. The local pinned
source/build copy records that license at `build/vendor/NRD/LICENSE.txt` when
present. Caustica's LGPL license does not replace or extend the rights granted
by that license.

Required attribution for source-derived portions:

> This software contains source code provided by NVIDIA Corporation.

NRD is used as a cross-vendor Vulkan denoiser. This attribution does not imply
that Caustica includes NGX, DLSS Ray Reconstruction, DLSS Frame Generation,
Reflex, CUDA, or another NVIDIA-specific runtime; those runtime paths are not
part of the current renderer.

## AMD FidelityFX

Caustica includes FidelityFX-derived shader/native components for its optional
FFX denoise and classic FSR2 Vulkan providers. Release or development artifacts
may include files such as:

- `caustica/natives/windows-x64/ffx_denoiser_caustica.dll`
- `caustica/natives/linux-x64/libffx_denoiser_caustica.so`
- `caustica/natives/windows-x64/ffx_fsr2_caustica.dll`
- `caustica/natives/linux-x64/libffx_fsr2_caustica.so`

The regular source/resource tree also currently carries FidelityFX modular-loader
binaries used by development probes:

- `caustica/natives/linux-x64/libamd_fidelityfx_loader.so`
- `caustica/natives/linux-x64/libamd_fidelityfx_upscaler.so`
- `caustica/natives/linux-x64/libamd_fidelityfx_framegeneration.so`

Their presence does not advertise an active FSR3, FSR4, or vendor
frame-generation provider in the current Java renderer.

The AMD-provided components remain subject to the license and third-party
notices shipped with the FidelityFX SDK. In a local SDK checkout these are:

- `third_party/FidelityFX-SDK/docs/license.md`
- `third_party/FidelityFX-SDK/3rdpartynotice.md`

The FidelityFX license permits specified binary redistribution subject to its
conditions, including retaining its copyright, permission, disclaimer, and
notice text. Consult the complete SDK license rather than this summary before
redistributing AMD binaries.

## Project-owned native glue

Caustica's JNI bridges, provider adapters, Vulkan resource plumbing, and other
project-authored native glue follow Caustica's project license unless a source
file states otherwise. Linking or packaging them with a third-party SDK does not
relicense that SDK under the LGPL.

## No XeSS or NGX bundle

The current build does not fetch or bundle Intel XeSS or NVIDIA NGX/DLSS SDK
runtimes. Legacy config spellings may still parse so older configuration files
can fall back safely; parsing an old name does not indicate that its SDK is
present.
