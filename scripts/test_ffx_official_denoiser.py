#!/usr/bin/env python3
"""Source regressions for official FidelityFX Denoiser integration (P0+)."""
from __future__ import annotations

from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def test_native_ffx_denoiser_cmake_exists() -> None:
    cmake_path = ROOT / "native/ffx_denoiser/CMakeLists.txt"
    assert cmake_path.exists(), "native/ffx_denoiser/CMakeLists.txt must exist"
    cmake = cmake_path.read_text(encoding="utf-8")
    assert "FFX_DENOISER" in cmake
    assert "ffx_denoiser" in cmake
    assert "caustica_ffx_denoiser" in cmake or "ffx_denoiser_caustica" in cmake


def test_shim_exports_probe_create_destroy() -> None:
    hdr = (ROOT / "native/ffx_denoiser/ffx_denoiser_shim.h").read_text(encoding="utf-8")
    for name in (
        "caustica_ffx_denoiser_probe",
        "caustica_ffx_denoiser_create",
        "caustica_ffx_denoiser_destroy",
    ):
        assert name in hdr, f"shim header must export {name}"


def test_java_ffx_denoiser_runtime_class() -> None:
    runtime = ROOT / "src/main/java/dev/comfyfluffy/caustica/ffx/denoiser/FfxDenoiserRuntime.java"
    lib = ROOT / "src/main/java/dev/comfyfluffy/caustica/ffx/denoiser/FfxDenoiserLibrary.java"
    assert runtime.exists(), "FfxDenoiserRuntime.java must exist"
    assert lib.exists(), "FfxDenoiserLibrary.java must exist"
    rt = runtime.read_text(encoding="utf-8")
    assert "tryLoad" in rt
    assert "caustica_ffx_denoiser_probe" in lib.read_text(encoding="utf-8")


def test_design_and_plan_exist() -> None:
    assert (ROOT / "docs/superpowers/specs/2026-07-14-ffx-official-denoiser-design.md").exists()
    assert (ROOT / "docs/superpowers/plans/2026-07-14-ffx-official-denoiser.md").exists()


def test_rgen_writes_split_lighting_buffers() -> None:
    src = (ROOT / "shaders/world/world.rgen").read_text(encoding="utf-8")
    assert "gShadowHit" in src
    assert "gDiffuse" in src
    assert "gReflection" in src
    assert "imageStore(gShadowHit" in src
    assert "imageStore(gDiffuse" in src
    assert "imageStore(gReflection" in src
    assert "unshadowedDirect" in src
    assert "primaryShadow" in src


def test_material_sky_bindings_after_split_buffers() -> None:
    """GUIDE_COUNT=23 uses bindings 3..25; material/sky samplers must follow without collision."""
    rchit = (ROOT / "shaders/world/world.rchit").read_text(encoding="utf-8")
    rmiss = (ROOT / "shaders/world/world.rmiss").read_text(encoding="utf-8")
    rgen = (ROOT / "shaders/world/world.rgen").read_text(encoding="utf-8")
    assert "binding = 9" in rgen and "gShadowHit" in rgen
    assert "binding = 26" in rchit and "blockSpecAtlas" in rchit
    assert "binding = 27" in rchit and "blockNormalAtlas" in rchit
    assert "binding = 28" in rmiss and "celestialsAtlas" in rmiss
    composite = (ROOT / "src/main/java/dev/comfyfluffy/caustica/rt/RtComposite.java").read_text(
        encoding="utf-8"
    )
    assert "GUIDE_COUNT = 23" in composite


def test_shadow_denoise_composite_shaders_exist() -> None:
    for name in (
        "shadow_reproject.comp",
        "shadow_spatial.comp",
        "reflection_reproject.comp",
        "reflection_spatial.comp",
        "denoise_composite.comp",
    ):
        p = ROOT / "shaders/display/denoise_ffx" / name
        assert p.exists(), f"missing {p}"
    comp = (ROOT / "shaders/display/denoise_ffx/denoise_composite.comp").read_text(encoding="utf-8")
    assert "gBeauty" in comp
    assert "gUnshadowed" in comp
    assert "gSpecRaw" in comp and "gSpecClean" in comp
    # Energy-correct deltas: shadow re-weight + specular replace; never GI×shadow.
    assert "cleanS - rawS" in comp or "(cleanS - rawS)" in comp
    assert "specClean - specRaw" in comp or "(specClean - specRaw)" in comp


def test_official_backend_and_selector() -> None:
    backend = ROOT / "src/main/java/dev/comfyfluffy/caustica/denoise/OfficialFfxDenoiseBackend.java"
    assert backend.exists()
    src = backend.read_text(encoding="utf-8")
    assert "ffx-official" in src
    assert "setSplitBuffers" in src
    assert "denoise_composite.comp" in src
    sel = (ROOT / "src/main/java/dev/comfyfluffy/caustica/denoise/DenoiseBackendSelector.java").read_text(
        encoding="utf-8"
    )
    assert "OfficialFfxDenoiseBackend" in sel
    rt = (ROOT / "src/main/java/dev/comfyfluffy/caustica/rt/RtComposite.java").read_text(encoding="utf-8")
    assert "return false;" not in rt.split("caustica$denoiseEnabled")[1][:400]
    shim = (ROOT / "native/ffx_denoiser/ffx_denoiser_shim.h").read_text(encoding="utf-8")
    assert "caustica_ffx_denoiser_dispatch_shadows" in shim


if __name__ == "__main__":
    tests = [
        test_native_ffx_denoiser_cmake_exists,
        test_shim_exports_probe_create_destroy,
        test_java_ffx_denoiser_runtime_class,
        test_design_and_plan_exist,
        test_rgen_writes_split_lighting_buffers,
        test_material_sky_bindings_after_split_buffers,
        test_shadow_denoise_composite_shaders_exist,
        test_official_backend_and_selector,
    ]
    failed = 0
    for t in tests:
        try:
            t()
            print(f"PASS {t.__name__}")
        except Exception as e:
            failed += 1
            print(f"FAIL {t.__name__}: {e}")
    raise SystemExit(failed)
