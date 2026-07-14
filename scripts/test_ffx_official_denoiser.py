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


if __name__ == "__main__":
    tests = [
        test_native_ffx_denoiser_cmake_exists,
        test_shim_exports_probe_create_destroy,
        test_java_ffx_denoiser_runtime_class,
        test_design_and_plan_exist,
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
