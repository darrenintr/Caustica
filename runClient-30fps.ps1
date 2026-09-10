$ErrorActionPreference = "Stop"

# =============================================================================
# runClient-30fps.ps1
# -----------------------------------------------------------------------------
# Caustica dev client launcher with the 30fps-preset baked in. Mirrors
# runClient.ps1, but injects every value from config/caustica-30fps.toml as
# a -Dcaustica.xxx JVM arg and closes Vulkan validation layers (which add
# per-cmd-buffer parsing overhead and have been observed to TDR the AMD
# driver on long compute passes).
#
# Every flag below corresponds 1:1 to a key in config/caustica-30fps.toml.
# If you want a different value, edit either the .ps1 or the .toml -- they
# have to match. The .toml is the human-readable reference; this .ps1 is
# what actually drives the JVM.
# =============================================================================

$candelaRoot = $PSScriptRoot
Push-Location $candelaRoot
try {
    # ---- GC + memory (unchanged from runClient.ps1) ----
    $gc = @(
        "-Xmx8G"
        "-XX:+UseCompactObjectHeaders"
        "-XX:+AlwaysPreTouch"
        "-XX:+UseStringDeduplication"
        "-XX:+UseZGC"
    )

    # ---- Caustica 30fps preset (mirror of config/caustica-30fps.toml) ----
    $caustica = @(
        # Render scale -- the single biggest lever
        "-Dcaustica.rt.upscaler.quality=3"           # 0.50x render (PERFORMANCE)
        "-Dcaustica.rt.upscaler.sharpness=0.7"       # compensate the upscale blur

        # Path tracer -- mostly already at the floor; flip adaptive-spp
        "-Dcaustica.rt.composite.adaptiveSpp=true"   # +1 SPP on water/glass/emissive only

        # ReSTIR PT Enhanced -- spatial correlation tuning, no compute cost
        "-Dcaustica.rt.restirEnhanced.pairedReuseShufflePeriod=16"  # was 4

        # Denoise -- halve NRD history depth (memory + anti-lag)
        "-Dcaustica.rt.denoise.nrdMaxAccumulatedFrames=16"          # was 32

        # Latency hiding
        "-Dcaustica.rt.asyncCompute=true"            # overlap denoise with frame setup

        # Defensive -- keep these OFF regardless of what is in caustica.toml
        "-Dcaustica.rt.gi=false"                     # hemisphere ambient, no ReSTIR GI
        "-Dcaustica.rt.fg=false"                     # no FSR3 frame-gen
        "-Dcaustica.rt.hybrid=true"                  # rough surface raster fast-path

        # Disable debug overlays that we are not using
        "-Dcaustica.rt.frameStats=false"
        "-Dcaustica.rt.debugOverlay=false"
    )

    $env:JAVA_TOOL_OPTIONS = ($gc + $caustica -join " ")

    # ---- Disable Vulkan validation layers ----
    # Validation adds per-vkCmd* parsing overhead (each dispatch is re-checked
    # on the CPU) and has been observed to push the AMD driver past its
    # TDR_TIMEOUT (default 2 s) on long compute passes. Re-enable for
    # debugging, never for perf measurement.
    $env:VK_INSTANCE_LAYERS = ""
    $env:VK_LOADER_DEBUG = "error"
    # AMD-specific: dump_shader.bin / debug_printf spam that the driver
    # sometimes inserts even with validation off. Empty these.
    $env:AMD_DEBUG = ""
    $env:AMD_VULKAN_ICD = ""

    Write-Host "=== 30fps preset active ===" -ForegroundColor Green
    Write-Host "Render scale: 0.50x (PERFORMANCE)"
    Write-Host "ReSTIR shuffle period: 16 frames"
    Write-Host "NRD max accumulated frames: 16"
    Write-Host "Async compute: on"
    Write-Host "Hybrid fast-path: on"
    Write-Host "GI / FrameGen: off"
    Write-Host "Vulkan validation: OFF"
    Write-Host "==========================" -ForegroundColor Green
    Write-Host ""

    .\gradlew.bat --stop
    .\gradlew.bat runClient --args="--renderDebugLabels --graphicsBackend VULKAN"
} finally {
    Pop-Location
}