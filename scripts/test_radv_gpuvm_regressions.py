#!/usr/bin/env python3
"""Static regression checks for the RADV/NAVI33 RT GPUVM workarounds.

These checks intentionally lock the resource-placement and shader-routing invariants that
stopped the terrain-streaming READ_INVALID/device-loss sequence. They complement the
runtime test: source assertions cannot prove a driver crash is fixed, but they prevent a
future refactor from silently putting BDA/AS inputs back in host-visible memory.
"""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def read(path: str) -> str:
    return (ROOT / path).read_text(encoding="utf-8")


def test_bda_buffers_are_explicitly_aligned_and_stage_to_device_local() -> None:
    context = read("src/main/java/dev/comfyfluffy/caustica/rt/RtContext.java")
    assert "public static final long BDA_REF_ALIGN = 16L;" in context
    assert "Vma.vmaCreateBufferWithAlignment" in context
    assert "public RtBuffer createBdaBuffer" in context
    assert "public RtBuffer uploadDeviceLocal" in context
    assert "createAlignedBuffer(size, deviceUsage, false, label, align)" in context
    assert "VK10.vkCmdCopyBuffer(cmd, staging.handle, device.handle, region)" in context
    assert "VK_ACCESS_ACCELERATION_STRUCTURE_READ_BIT_KHR" in context
    assert "VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR" in context


def test_radv_disables_position_fetch_and_uses_single_geometry_terrain() -> None:
    bringup = read("src/main/java/dev/comfyfluffy/caustica/rt/RtDeviceBringup.java")
    accel = read("src/main/java/dev/comfyfluffy/caustica/rt/accel/RtAccel.java")
    pipeline = read("src/main/java/dev/comfyfluffy/caustica/rt/pipeline/RtPipeline.java")
    any_hit = read("shaders/world/world.rahit")

    assert "radvDriver = driverInfo.contains(\"radv\") || deviceName.contains(\"radv\")" in bringup
    assert "positionFetchEnabled = !radvDriver && supportsPositionFetch(physicalDevice)" in bringup
    assert 'return positionFetchEnabled ? "world.rchit.spv" : "world_noposfetch.rchit.spv"' in bringup
    assert "if (RtDeviceBringup.isRadv())" in accel
    assert '" (radv single-geom)"' in accel
    assert "if (dev.comfyfluffy.caustica.rt.RtDeviceBringup.isRadv())" in pipeline
    assert "if (bucket == 0u)" in any_hit
    assert "pr.tint.w > 1.5" in any_hit and "pr.normal.w >= 1.5" in any_hit


def test_terrain_geometry_and_section_table_stay_device_local() -> None:
    terrain = read("src/main/java/dev/comfyfluffy/caustica/rt/terrain/RtTerrain.java")

    assert "return ctx.uploadDeviceLocal(bytes, usage, MemoryUtil.memAddress(host), bytes, label);" in terrain
    assert "private RtBuffer sectionTableHost;" in terrain
    assert "private RtBuffer sectionTableDevice;" in terrain
    assert "return sectionTableDevice.deviceAddress;" in terrain
    assert "VK10.vkCmdCopyBuffer(cmd, sectionTableHost.handle, sectionTableDevice.handle, region)" in terrain
    assert "sectionTableDevice = ctx.createBdaBuffer" in terrain
    assert "sectionTableHost.mapped + (long) g.slot * SECTION_ENTRY_BYTES" in terrain
    assert "sectionTableDirty = true;" in terrain


def test_world_push_sbt_and_tlas_are_not_host_visible_bda_consumers() -> None:
    composite = read("src/main/java/dev/comfyfluffy/caustica/rt/RtComposite.java")
    pipeline = read("src/main/java/dev/comfyfluffy/caustica/rt/pipeline/RtPipeline.java")
    accel = read("src/main/java/dev/comfyfluffy/caustica/rt/accel/RtAccel.java")

    assert "private RtBuffer[] pushHostRing;" in composite
    assert "private RtBuffer[] pushDeviceRing;" in composite
    assert "pushDeviceRing[i] = ctx.createBdaBuffer" in composite
    assert "VK10.vkCmdCopyBuffer(cmd, pushHost.handle, pushDevice.handle, region)" in composite
    assert "pushDevice.deviceAddress" in composite

    assert "ctx.uploadDeviceLocal(sbtBytes" in pipeline
    assert "ctx.shaderGroupBaseAlignment()" in pipeline

    assert "private final RtBuffer hostInstances;" in accel
    assert "private final RtBuffer deviceInstances;" in accel
    assert "VK10.vkCmdCopyBuffer(cmd, tlas.hostInstances.handle, tlas.deviceInstances.handle, region)" in accel
    assert "tlasBuildInfo(stack, tlas.deviceInstances.deviceAddress)" in accel


def test_initial_empty_tlas_is_not_traced() -> None:
    terrain = read("src/main/java/dev/comfyfluffy/caustica/rt/terrain/RtTerrain.java")
    composite = read("src/main/java/dev/comfyfluffy/caustica/rt/RtComposite.java")

    assert "ready = true;" not in terrain[terrain.index("private void ensureEmptyTableReady"):]
    assert "boolean hasTraceGeometry = !fe.instances().isEmpty();" in composite
    assert "if (hasTraceGeometry)" in composite


if __name__ == "__main__":
    tests = [
        test_bda_buffers_are_explicitly_aligned_and_stage_to_device_local,
        test_radv_disables_position_fetch_and_uses_single_geometry_terrain,
        test_terrain_geometry_and_section_table_stay_device_local,
        test_world_push_sbt_and_tlas_are_not_host_visible_bda_consumers,
        test_initial_empty_tlas_is_not_traced,
    ]
    failures = []
    for test in tests:
        try:
            test()
            print(f"PASS {test.__name__}")
        except Exception as exc:
            failures.append((test.__name__, exc))
            print(f"FAIL {test.__name__}: {exc}")
    if failures:
        raise SystemExit(1)
