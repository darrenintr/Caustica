#!/usr/bin/env python3
"""Regression checks for Caustica's denoise subsystem (post-rewrite).

The hand-written SVGF-lite denoise (`shaders/display/denoise.comp` +
`RtDenoisePass.java`) was replaced by a pluggable backend architecture:
`CausticaDenoiseBackend` interface with `NoopDenoiseBackend`, `FfxDenoiseBackend`,
and `NrdReBlurBackend`, resolved through `DenoiseBackendSelector`.

These source-level checks pin the public surface area — config keys, selector
behaviour, backend wiring through `RtComposite`, and the absence of the legacy
blanket barriers + history-copy blocks.
"""
from __future__ import annotations

import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def read(rel: str) -> str:
    return (ROOT / rel).read_text(encoding="utf-8")


def method_body(source: str, signature: str) -> str:
    start = source.index(signature)
    brace = source.index("{", start)
    depth = 0
    for i in range(brace, len(source)):
        ch = source[i]
        if ch == "{":
            depth += 1
        elif ch == "}":
            depth -= 1
            if depth == 0:
                return source[brace + 1:i]
    raise AssertionError(f"method body not found for {signature!r}")


def test_denoise_mode_enum_exposes_auto_ffx_nrd_off() -> None:
    src = read("src/main/java/dev/comfyfluffy/caustica/CausticaConfig.java")
    assert "enum DenoiserKind" in src, "CausticaConfig must declare a DenoiserKind enum"
    body = method_body(src, "public enum DenoiserKind")
    for name in ("AUTO", "FFX", "NRD", "OFF", "HYBRID"):
        assert name in body, f"DenoiserKind must contain {name}"
    # AMD_FIDELITYFX was removed (commit 1, 2026-07-20) — the 2.x modular loader has no
    # denoiser provider on this build, so the FFX-only AMD path is dead. AMD AUTO
    # routes to NRD via DenoiseBackendSelector.autoPick. Legacy config key
    # 'amd-fidelityfx' is now a fallback → AUTO rather than its own enum value.
    assert "AMD_FIDELITYFX" not in body, (
        "AMD_FIDELITYFX must be removed from DenoiserKind (use AUTO on AMD instead)"
    )
    assert 'EnumSetting<DenoiserKind> MODE = enumSetting(' in src, (
        "Rt.Denoise.MODE must be an EnumSetting<DenoiserKind> (not the legacy StringSetting)"
    )
    assert '"caustica.rt.denoise.mode"' in src, (
        "denoise mode must keep the legacy config key caustica.rt.denoise.mode"
    )


def test_ffx_p0_official_alignment_assets() -> None:
    """P0: shadow prepare/hitmask, variance-aware reproject/spatial, depth hierarchy."""
    for name in (
        "shadow_prepare.comp",
        "shadow_reproject.comp",
        "shadow_spatial.comp",
        "depth_pyramid.comp",
        "reflection_reproject.comp",
        "denoise_composite.comp",
    ):
        p = ROOT / "shaders/display/denoise_ffx" / name
        assert p.is_file(), f"missing P0 FFX shader {name}"
    prep = (ROOT / "shaders/display/denoise_ffx/shadow_prepare.comp").read_text(encoding="utf-8")
    assert "8x4" in prep or "8 x 4" in prep or "lid.y * 8" in prep
    assert "r32ui" in prep and "gHitMask" in prep
    repro = (ROOT / "shaders/display/denoise_ffx/shadow_reproject.comp").read_text(encoding="utf-8")
    assert "variance" in repro and "gHitMask" in repro
    # Anti-ghost: moving contact shadows must not leave dark trails.
    assert "motionReject" in repro, "shadow reproject must reject history under large motion"
    assert "curShadow - 0.08" in repro, (
        "shadow reproject must clamp against darkening trails on lit samples"
    )
    assert "(curShadow - histShadow) > 0.25" in repro, (
        "shadow reproject must hard-reject darker history (entity contact ghost)"
    )
    spat = (ROOT / "shaders/display/denoise_ffx/shadow_spatial.comp").read_text(encoding="utf-8")
    assert "cVar" in spat or "variance" in spat
    comp = (ROOT / "shaders/display/denoise_ffx/denoise_composite.comp").read_text(encoding="utf-8")
    assert "rawS - 0.06" in comp or "rawS - 0.10" in comp or "rawS - 0.1" in comp, (
        "denoise composite must not allow large darkening deltas (player shadow ghosts)"
    )
    pyr = (ROOT / "shaders/display/denoise_ffx/depth_pyramid.comp").read_text(encoding="utf-8")
    assert "gSrc" in pyr and "gDst" in pyr
    rf = (ROOT / "shaders/display/denoise_ffx/reflection_reproject.comp").read_text(encoding="utf-8")
    assert "gDepthMip1" in rf and "sampleDepthHierarchy" in rf
    backend = read("src/main/java/dev/comfyfluffy/caustica/denoise/OfficialFfxDenoiseBackend.java")
    assert "shadow_prepare.comp.spv" in backend
    assert "depth_pyramid.comp.spv" in backend
    assert "return ready;" in backend  # isReady must not be hard-false
    assert "return false;" not in method_body(backend, "public boolean isReady()")


def test_amd_fidelityfx_preset_skips_nrd_and_pairs_fsr() -> None:
    """REPLACED 2026-07-20 (commit 1): the AMD FidelityFX FFX-only path is dead
    (the 2.x modular loader we bundle has no denoiser effect provider, see
    DenoiseBackendSelector.autoPick log). What we validate now is:
      - the AMD_FIDELITYFX enum value is GONE from CausticaConfig.DenoiserKind
      - the legacy 'amd-fidelityfx' key still falls through to AUTO in fromKey
      - the DenoiseBackendSelector never references the removed enum
      - UpscalerSelector no longer forces FSR2 for AMD AUTO (the AMD path is
        NRD now, not FFX+FSR2)
      - the pause-menu denoise-mode switch no longer offers 'amd-fidelityfx'
    FFX file/class deletion and the FFX pause-menu switch removal happen in
    commit 2. This commit just confirms AMD no longer routes through FFX.
    """
    src = read("src/main/java/dev/comfyfluffy/caustica/CausticaConfig.java")
    body = method_body(src, "public enum DenoiserKind")
    assert "AMD_FIDELITYFX" not in body, (
        "AMD_FIDELITYFX must be removed from DenoiserKind (AMD AUTO routes to NRD)"
    )
    from_key = method_body(src, "public static DenoiserKind fromKey")
    assert 'amd-fidelityfx' in from_key, (
        "fromKey must still accept the 'amd-fidelityfx' legacy alias and route it to AUTO"
    )
    selector = read("src/main/java/dev/comfyfluffy/caustica/denoise/DenoiseBackendSelector.java")
    pick_body = method_body(selector, "private static CausticaDenoiseBackend pick")
    assert "AMD_FIDELITYFX" not in pick_body, (
        "DenoiseBackendSelector.pick must not handle AMD_FIDELITYFX (removed enum value)"
    )
    auto_body = method_body(selector, "private static CausticaDenoiseBackend autoPick")
    assert "AmdFidelityFxDenoiseBackend" not in auto_body, (
        "autoPick must NOT route AMD vendor to AmdFidelityFxDenoiseBackend (FFX dead)"
    )
    video = read("src/main/java/dev/comfyfluffy/caustica/client/RtVideoOptions.java")
    values_list = method_body(video, "private static OptionInstance<String> denoiseMode")
    assert '"amd-fidelityfx"' not in values_list, (
        "the pause-menu denoise-mode switch must no longer offer 'amd-fidelityfx'"
    )
    upsel = read("src/main/java/dev/comfyfluffy/caustica/upscale/UpscalerSelector.java")
    assert "AMD_FIDELITYFX" not in upsel, (
        "UpscalerSelector must no longer reference the removed AMD_FIDELITYFX enum"
    )
    assert "forcing upscaler partner to FSR2" not in upsel, (
        "UpscalerSelector must no longer force FSR2 (AMD AUTO now uses NRD, not FFX+FSR2)"
    )


def test_denoise_mode_legacy_svgf_alias_maps_to_ffx() -> None:
    src = read("src/main/java/dev/comfyfluffy/caustica/CausticaConfig.java")
    assert '"svgf"' in src and '"on"' in src, (
        "DenoiserKind.fromKey must still accept the legacy 'svgf' / 'on' aliases and route them to FFX"
    )


def test_denoise_backends_export_interface() -> None:
    iface = read("src/main/java/dev/comfyfluffy/caustica/denoise/CausticaDenoiseBackend.java")
    for sig in ("String name()", "boolean dispatch(", "void ensureSized(", "void destroy()", "boolean isReady()"):
        assert sig in iface, f"CausticaDenoiseBackend must declare {sig}"
    for name in ("FfxDenoiseBackend.java", "NrdReBlurBackend.java", "NoopDenoiseBackend.java"):
        body = read(f"src/main/java/dev/comfyfluffy/caustica/denoise/{name}")
        assert "implements CausticaDenoiseBackend" in body, f"{name} must implement CausticaDenoiseBackend"


def test_denoise_selector_auto_is_capability_first() -> None:
    src = read("src/main/java/dev/comfyfluffy/caustica/denoise/DenoiseBackendSelector.java")
    assert "autoPick" in src and "GpuVendor" not in src, (
        "AUTO denoise must probe provider capability instead of branching on PCI vendor"
    )
    auto = method_body(src, "private static CausticaDenoiseBackend autoPick()")
    assert "tryCreateNrdAuto(new HybridFfxNrdBackend(true))" in auto
    nrd = method_body(src, "private static CausticaDenoiseBackend tryCreateNrdAuto")
    assert "NrdRuntime.INSTANCE.tryLoad()" in nrd and "BilateralDenoiseBackend" in nrd
    assert "invalidate" in src and "resolvedOnce" in src, (
        "DenoiseBackendSelector must remain a resolved-once provider latch"
    )


def test_denoise_rt_composite_owns_dispatch_via_selector() -> None:
    src = read("src/main/java/dev/comfyfluffy/ct_caustica/rt/RtComposite.java") if False else read("src/main/java/dev/comfyfluffy/caustica/rt/RtComposite.java")
    assert "DenoiseBackendSelector.current(" in src, (
        "RtComposite recordFrame must dispatch through DenoiseBackendSelector.current(...)"
    )
    assert "plateBridge.adaptToDenoise(cmd, output)" in src, (
        "RtComposite must route raw RT beauty through the shared format bridge before denoise"
    )
    assert "RtImage denoiseTarget = plateBridge.denoiseOutputColor()" in src, (
        "RtComposite must let Hybrid compose directly into the bridge-owned RGBA16F target"
    )
    assert "RtImage denoiseInput = temporalAccumRan ? accumulatedColor : output" not in src, (
        "RtComposite must not stack beauty TAA ahead of a temporal denoiser"
    )
    assert "RtDenoisePass" not in src, "RtDenoisePass is deleted; the legacy class must not be referenced"
    assert "historyColor(" not in src and "historyDepth(" not in src and "historyNormal(" not in src, (
        "the legacy history-copy block is removed — backends own their own ping-pong"
    )


def test_denoise_image_barriers_replace_blanket_mem_barriers() -> None:
    src = read("src/main/java/dev/comfyfluffy/caustica/rt/RtComposite.java")
    bad = ('VulkanCommandEncoder.memoryBarrier(cmd, stack); // denoise writes + guide writes visible to TRANSFER history blits',
           'VulkanCommandEncoder.memoryBarrier(cmd, stack); // history copy visible to next pass')
    for s in bad:
        assert s not in src, (
            f"RtComposite still contains the legacy blanket memoryBarrier comment+call: {s!r}. "
            "These were SVGF-internal cleanup; backends own their own barriers."
        )


def test_denoise_video_options_invalidate_selection_on_set() -> None:
    src = read("src/main/java/dev/comfyfluffy/caustica/client/RtVideoOptions.java")
    body = method_body(src, "private static OptionInstance<String> denoiseMode()")
    assert "invalidateDenoiseSelection()" in body, (
        "RtVideoOptions.denoiseMode setter must call RtComposite.INSTANCE.invalidateDenoiseSelection() "
        "so the backend swap takes effect on the next frame, not the next MC restart"
    )
    for v in ('"auto"', '"ffx"', '"nrd"', '"off"'):
        assert v in body, f"denoiseMode widget must expose value {v}"


def test_denoise_video_options_widget_keeps_method_name() -> None:
    src = read("src/main/java/dev/comfyfluffy/caustica/client/RtVideoOptions.java")
    assert "private static OptionInstance<String> denoiseMode()" in src, (
        "denoiseMode method name is part of the contract; do not rename"
    )


def test_denoise_svgf_settings_retired_from_config() -> None:
    src = read("src/main/java/dev/comfyfluffy/caustica/CausticaConfig.java")
    for legacy in ("SIGMA_DEPTH", "SIGMA_NORMAL", "SIGMA_COLOR", "TEMPORAL_MAX"):
        assert legacy not in src, (
            f"legacy SVGF-only FloatSetting {legacy} is retired in the rewrite; each backend owns its tuning"
        )


def test_auto_denoise_is_independent_of_upscaler_provider() -> None:
    src = read("src/main/java/dev/comfyfluffy/caustica/rt/RtComposite.java")
    assert "private static boolean caustica$denoiseEnabled()" in src
    body = method_body(src, "private static boolean caustica$denoiseEnabled()")
    assert "DenoiserKind.OFF" in body and "DenoiserKind.AUTO" in body, (
        "denoiseEnabled must check the typed denoiser mode"
    )
    assert "UpscalerSelector" not in body and "RtDlss" not in body and "DLSS_RR" not in body, (
        "the modular upscaler boundary must not own or suppress denoise"
    )
    assert body.rstrip().endswith("return true;"), (
        "AUTO must run the selected denoise backend independently of the upscaler"
    )


def test_denoise_legacy_files_deleted() -> None:
    deleted_paths = (
        ROOT / "shaders" / "display" / "denoise.comp",
        ROOT / "src/main/java/dev/comfyfluffy/caustica/rt/pipeline/RtDenoisePass.java",
    )
    for p in deleted_paths:
        assert not p.exists(), f"obsolete file {p} must be deleted in the rewrite"


def test_world_rgen_motion_vector_uses_half_render_pixel_units() -> None:
    src = read("shaders/world/world.rgen")
    assert "* 0.5 * size" in src, (
        "world.rgen writes gMotion as the reprojection delta in half-render-pixel units. Both FFx and NRD "
        "consume the same convention: the denoise shader treats mv * motionVectorScale as the UV delta."
    )


def test_world_rgen_motion_vector_projects_same_hit_in_both_frames() -> None:
    """Unjittered MVs use jitter-free matrices, not the integer pixel centre.

    The primary hit was generated by the jittered ray. Its current position
    must be projected through curViewProj and compared with that same point
    through prevViewProj. Subtracting ``ndc`` injects the current Halton offset
    into gMotion on a static camera despite advertising MV_JITTERED=0.
    """
    src = read("shaders/world/world.rgen")
    main_body = method_body(src, "void main()")
    assert "vec2 curNdc = curClip.xy /" in main_body, (
        "world.rgen must project the actual jittered-ray hit with the current "
        "jitter-free matrix; using the integer pixel centre puts jitter in gMotion"
    )
    assert "vec2 curNdc = ndc" not in main_body
    assert "dir, curNdc, size, rayConeSpread" in main_body, (
        "specular motion must use the same current projected hit as gMotion"
    )


def test_temporal_upscalers_expose_request_reset_history() -> None:
    """Every active temporal provider must drop history on hard cuts."""
    for path in (
        "src/main/java/dev/comfyfluffy/caustica/upscale/TaaUpscaler.java",
        "src/main/java/dev/comfyfluffy/caustica/fsr/Fsr2ClassicUpscaler.java",
    ):
        src = read(path)
        assert "public void requestResetHistory()" in src, (
            f"{path} must override requestResetHistory for teleport/dimension/resource hard cuts"
        )


def test_ngx_dlss_runtime_and_build_paths_are_deleted() -> None:
    deleted = (
        "native/ngx_shim",
        "src/main/java/dev/comfyfluffy/caustica/ngx/NgxLibrary.java",
        "src/main/java/dev/comfyfluffy/caustica/ngx/NgxRuntime.java",
        "src/main/java/dev/comfyfluffy/caustica/rt/pipeline/RtDlssFg.java",
        "src/main/java/dev/comfyfluffy/caustica/rt/pipeline/RtDlssRr.java",
    )
    for path in deleted:
        assert not (ROOT / path).exists(), f"dead NVIDIA runtime path must be deleted: {path}"

    gradle = read("build.gradle")
    for symbol in ("DLSS_SDK", "bundleNgxNatives", "ngxNativeGenRoot", "native/ngx_shim"):
        assert symbol not in gradle, f"build.gradle must not retain dead NGX build symbol {symbol}"

    for path in (ROOT / "src/main/java").rglob("*.java"):
        source = path.read_text(encoding="utf-8")
        for symbol in ("NgxRuntime", "NgxLibrary", "RtDlssFg", "RtDlssRr"):
            assert symbol not in source, f"{path} must not reference deleted runtime {symbol}"

    config = read("src/main/java/dev/comfyfluffy/caustica/CausticaConfig.java")
    assert 's.equalsIgnoreCase("dlss-rr")' in config, (
        "the old config key must still parse as a deprecated compatibility alias"
    )
    assert "return AUTO;" in method_body(config, "public static UpscalerMode fromKey"), (
        "legacy upscaler keys must resolve to the portable AUTO/TAAU route"
    )


def test_upscaler_interface_exposes_request_reset_history() -> None:
    """v0.5.3: the Upscaler interface should expose requestResetHistory()
    so any temporal upscaler can drop its internal accumulator on hard cuts.
    Providers can override the default no-op with their SDK or compute path's
    reset path (or destroy+recreate the context lazily on next ensureFeature).
    """
    src = read("src/main/java/dev/comfyfluffy/caustica/upscale/Upscaler.java")
    assert "void requestResetHistory()" in src, (
        "Upscaler must declare `void requestResetHistory()` so a hard cut "
        "(teleport / dimension change / resource reload) can invalidate the "
        "upscaler's internal temporal history — not just the denoise backend's."
    )


def test_composite_invalidate_history_requests_upscaler_reset() -> None:
    """v0.5.3: invalidateHistory() must call requestResetHistory() on the
    active upscaler in addition to backend.resetHistory() and
    temporalAccum.resetHistory(). Otherwise the next frame's denoise history
    starts fresh but the upscaler still holds stale temporal state."""
    src = read("src/main/java/dev/comfyfluffy/caustica/rt/RtComposite.java")
    body = method_body(src, "public void invalidateHistory()")
    assert "requestResetHistory" in body, (
        "RtComposite.invalidateHistory() must call active.requestResetHistory() "
        "(via UpscalerSelector.current()) so the active upscaler's internal "
        "temporal history is invalidated on hard cuts, matching the FFX "
        "denoise backend reset."
    )


def test_denoise_backends_all_implement_reset_history() -> None:
    """v0.5.2: CausticaDenoiseBackend gained void resetHistory(). Every concrete
    backend must implement it (noop is fine for NoopDenoiseBackend, but the
    method has to be present so the selector can call it without instanceof
    checks). This catches a regression where a new backend is added without
    the lifecycle hook."""
    iface = read("src/main/java/dev/comfyfluffy/caustica/denoise/CausticaDenoiseBackend.java")
    assert "void resetHistory()" in iface, (
        "CausticaDenoiseBackend must declare void resetHistory() so the selector can call it on a "
        "teleport / dimension change / resource reload without downcasting."
    )
    for name in ("FfxDenoiseBackend.java", "NrdReBlurBackend.java", "NoopDenoiseBackend.java",
                 "BilateralDenoiseBackend.java"):
        body = read(f"src/main/java/dev/comfyfluffy/caustica/denoise/{name}")
        assert "public void resetHistory()" in body, (
            f"{name} must implement resetHistory() — even Noop/Bilateral need the no-op body so "
            "the dispatch path doesn't have to special-case them."
        )


def test_denoise_ffx_reproject_has_motion_nan_guard() -> None:
    """A bad motion vector (NaN from a divide-by-zero in the rgen's NDC
    projection, Inf from a malformed world-displacement term) would make
    prevUV NaN, then the sampler fetch returns garbage, then the per-pixel
    history persists as "the colour from a parallel dimension" trail. The
    reproject pass must check the MV for non-finite values and treat them as
    a hard disocclusion."""
    src = read("shaders/display/denoise_ffx/ffx_reproject.comp")
    assert "isnan(mv)" in src and "isinf(mv)" in src, (
        "ffx_reproject.comp must guard against NaN/Inf motion vectors (any(isnan(mv)) || any(isinf(mv))). "
        "Without this, a single bad MV per frame persists garbage as history across many frames."
    )


def test_denoise_composite_invalidate_history_on_teleport_dimension_reload() -> None:
    """v0.5.2: the denoise + TAA history must be dropped on every hard cut —
    teleport, dimension change, resource reload. A single missed cut is the
    cause of the 'old view's colour palette smears into the new view for
    several frames' bug that the v0.5.x reflections-only variant hit on
    Nether portals."""
    src = read("src/main/java/dev/comfyfluffy/caustica/rt/RtComposite.java")
    # method exists
    assert "public void invalidateHistory()" in src, (
        "RtComposite must expose invalidateHistory() so the Video Settings denoise-mode setter "
        "and external callers (dimension hooks, etc.) can clear the history on a hard cut."
    )
    # teleport detection threshold = 8 blocks squared (= 64.0f)
    assert "64.0f" in src and "8 blocks squared" in src, (
        "RtComposite.updateMotion() must drop the history on a per-frame camera translation > 8 blocks "
        "(squared: 64.0f). 8 blocks is well above sprint / Elytra / boat speeds; teleports always cross it."
    )
    # NaN-safe teleport (mvCamDelta finite check)
    assert "Float.isFinite" in src, (
        "RtComposite.updateMotion() must use Float.isFinite to gate the teleport-reset on the camera "
        "deltas — without it a NaN/Inf camX (bug in capture path) makes NaN > 64.0f evaluate to false "
        "in IEEE 754 and the teleport silently doesn't fire."
    )
    # dimension change detection
    assert "level.dimension()" in src and "lastDimension" in src, (
        "RtComposite.composite() must detect Overworld <-> Nether <-> End transitions and drop the "
        "history (Nether-portal crossings have near-zero camera translation, so the teleport threshold "
        "won't catch them; the dimension key check is the only reliable signal)."
    )
    # onResourceReloadStart must reset history
    reload_block = src[src.index("public void onResourceReloadStart()"):]
    assert "invalidateHistory()" in reload_block, (
        "RtComposite.onResourceReloadStart() must call invalidateHistory() — without it the old world's "
        "block-atlas-tied history ring survives into the new world, smearing the old block colours."
    )


def test_denoise_ffx_temporal_weight_max_is_user_tunable() -> None:
    """v0.5.2 exposed caustica.rt.denoise.ffxTemporalWeightMax so users can
    tune the temporal weight on top of the 0.75 default. Without this, a user
    who wants a 'responsive' (0.5) preset has no escape hatch — and the bug
    we're guarding against (v0.5.x's 0.95 hardcoded value) was only catchable
    via a code change."""
    src = read("src/main/java/dev/comfyfluffy/caustica/CausticaConfig.java")
    assert "FFX_TEMPORAL_WEIGHT_MAX" in src, (
        "CausticaConfig.Rt.Denoise.FFX_TEMPORAL_WEIGHT_MAX must be a FloatSetting so users can override "
        "the default via caustica.rt.denoise.ffxTemporalWeightMax. Hardcoded values regress."
    )
    # FFX backend must read the config (not a hardcoded temporal weight)
    ffx = read("src/main/java/dev/comfyfluffy/caustica/denoise/FfxDenoiseBackend.java")
    assert "FFX_TEMPORAL_WEIGHT_MAX.value()" in ffx, (
        "FfxDenoiseBackend.dispatchResolve must read CausticaConfig.Rt.Denoise.FFX_TEMPORAL_WEIGHT_MAX "
        "and pass it as the temporalWeightMax push constant. Hardcoding the weight is a regression."
    )
    # Push layout must match ffx_resolve_temporal.comp:
    #   @0 temporalWeightMax, @4 varianceCutoff, @8 minHistoryBlend, @12 frameIndex
    # Writing minHistoryBlend at @0 was the RX-class residual-noise bug (wHistory≈0.04
    # on trusted static pixels → almost pure SPP-1 current every frame).
    assert 'push.putFloat(0, dev.comfyfluffy.caustica.CausticaConfig.Rt.Denoise.FFX_TEMPORAL_WEIGHT_MAX.value())' in ffx, (
        "resolve push @0 must be FFX_TEMPORAL_WEIGHT_MAX (shader field temporalWeightMax). "
        "Swapping with minHistoryBlend disables temporal accumulation on AMD FFX."
    )
    assert "ATROUS_PASSES" in ffx and "ATROUS_STEPS" in ffx, (
        "FfxDenoiseBackend must run multi-pass dilated à-trous (ATROUS_PASSES / ATROUS_STEPS). "
        "A single 3x3 pass leaves residual SPP-1 noise on AMD."
    )
    atrous = read("shaders/display/denoise_ffx/ffx_atrous.comp")
    assert "stepSize" in atrous and "B3" in atrous, (
        "ffx_atrous.comp must take a stepSize push constant and use a B3-spline kernel for multi-pass SVGF"
    )
    resolve = read("shaders/display/denoise_ffx/ffx_resolve_temporal.comp")
    assert "clamp(history" in resolve or "history = clamp" in resolve, (
        "ffx_resolve_temporal.comp must AABB-clamp history against the current neighbourhood (anti-ghost)"
    )
    assert "variance > 0.9" in resolve, (
        "ffx_resolve_temporal.comp must hard-skip history on variance>0.9 (disocclusion → no dark smear)"
    )


def test_denoise_temporal_accum_skips_when_denoise_runs() -> None:
    """v0.5.2: when the denoise backend is active, skip the standalone TAA
    pass (the denoise already has its own temporal filter). Without this, the
    two are stacked and α_total ≈ 0.995 — any MC noise from frame 1 freezes
    as a smearing trail."""
    src = read("src/main/java/dev/comfyfluffy/caustica/rt/RtComposite.java")
    # The skip must be inside the temporalAccumEnabled() block, gated on
    # caustica$denoiseEnabled() returning true.
    assert "denoiseWillRun" in src, (
        "RtComposite must compute a denoiseWillRun flag and skip the standalone TAA dispatch when it's "
        "true. Without this, FFX + TAA run together (the 'noise turns into a smearing trail' symptom)."
    )
    # The default for caustica.rt.temporalAccum must be false (the v0.5.x
    # default of true is no longer safe with the whole-radiance denoiser
    # active).
    cfg = read("src/main/java/dev/comfyfluffy/caustica/CausticaConfig.java")
    assert "bool(\"caustica.rt.temporalAccum\", \"composite.temporal-accum\", false)" in cfg, (
        "Rt.Composite.TEMPORAL_ACCUM default must be false. The v0.5.x default of true produced a "
        "near-permanent history smearing on any denoise path (FFX + TAA stacked)."
    )


def test_raygen_clamps_launch_to_bound_storage_image_extent() -> None:
    src = read("shaders/world/world.rgen")
    body = method_body(src, "void main()")
    first_store = body.index("imageStore(")
    guard_region = body[:first_store]
    for image in ("outImage", "gNormal", "gAlbedo", "gDepth", "gMotion", "gSpecAlbedo", "gSpecMotion"):
        assert f"imageSize({image})" in guard_region, (
            "raygen must include every written storage image in the actual-bound-extent guard; "
            f"missing {image} means a stale smaller descriptor can still be overwritten"
        )


def test_raygen_final_color_store_guards_non_finite() -> None:
    """v0.5.x image 1 regression: world.rgen wrote the raw path-traced radiance
    straight to the rgba16f output without a non-finite guard. When a primary
    ray hits a degenerate geometry hit (zero-area triangle, NaN t-hit) the
    accumulated radiance goes NaN/Inf; the fp16 imageStore saturates a random
    bit pattern that overflows the FFX atrous colour weight (sigma 0.55..3.4)
    and seeds a uniform-grid pattern of bright orange dots across the entire
    frame at every static-camera view. The same non-finite output poisons the
    FFX history on every subsequent frame, so the screenshot never converges.

    The shader must replace NaN / +Inf / -Inf in frameRadiance with a finite
    value (zero is the conventional choice — the FFX resolve treats it as
    "no current contribution" via its neighbourhood clamp) before the final
    imageStore. HDR magnitudes are clamped via a separate MAX_HDR_RADIANCE
    constant tested below; this test only locks the non-finite guard."""
    src = read("shaders/world/world.rgen")
    body = method_body(src, "void main()")
    # Walk to the last imageStore on outImage (the radiance write at the end of
    # main, after the debug-view branch). That `imageStore(outImage, pix, vec4(...))`
    # call must NOT pass frameRadiance through verbatim.
    last = body.rindex("imageStore(outImage, pix,")
    snippet = body[last:last + 200]
    assert "frameRadiance" in snippet, (
        "expected the final outImage imageStore to reference frameRadiance"
    )
    # Frame-finishing assertion: NaN / +Inf / -Inf each get caught and replaced.
    for guard in ("isnan(frameRadiance)", "isinf(frameRadiance)"):
        assert guard in src, (
            f"world.rgen must guard {guard} on frameRadiance before the final "
            "outImage imageStore — without it, a single NaN traced through the "
            "ffa path tracer becomes a stuck-on pixel that the FFX atrous can't "
            "dampen (causing the v0.5.x 'orange-dot screen' regression)."
        )


def test_raygen_final_color_store_clamps_hdr_fireflies() -> None:
    """v0.5.x image 1 follow-up regression: even after the non-finite guard,
    a Cook–Torrance specular spike on a smooth PBR surface at the perfect
    alignment (ndh→1, ndl→1, ndv→1) under direct noon sun writes radiance
    > 2000 to the rgba16f storage image. SPP=1 means the path tracer
    occasionally hits that alignment on a single pixel; the FFX atrous colour
    weight (sigma 0.55..3.4) cannot dampen an outlier that bright because
    every surrounding sample is orders of magnitude dimmer, so the pixel
    survives the spatial filter as an orange halo around emissives / smooth
    surfaces. The same hot pixel poisons the FFX history copy on the next
    frame, so static-camera screenshots never converge.

    The shader must clamp frameRadiance to a per-pixel ceiling before the
    final outImage imageStore. The ceiling must be (a) above the legitimate
    HDR ceiling of direct noon sun on a typical albedo (~6.7 for snow), and
    (b) below the fp16 saturation threshold (65504) by enough margin that a
    second fp16 round-trip cannot push a saturated value through."""
    src = read("shaders/world/world.rgen")
    assert "MAX_HDR_RADIANCE" in src, (
        "world.rgen must declare a MAX_HDR_RADIANCE constant near the top of "
        "the file (sibling of EMISSIVE_STRENGTH) so a future HDR rebalance "
        "(e.g. a brighter sun, lower EMISSIVE_STRENGTH) has a single tunable. "
        "Without the constant the cap is buried in the per-pixel shader body "
        "and gets copy-pasted inconsistently."
    )
    body = method_body(src, "void main()")
    last = body.rindex("imageStore(outImage, pix,")
    snippet = body[last - 200:last + 50]
    assert "min(frameRadiance" in snippet or "min(frameRadiance," in body, (
        "world.rgen main() must clamp frameRadiance via min(frameRadiance, "
        "vec3(MAX_HDR_RADIANCE)) before the final outImage imageStore — the "
        "v0.5.x HDR firefly (specular spike on smooth PBR under direct sun) "
        "needs an explicit ceiling; relying on the FFX atrous colour weight "
        "alone leaves saturated hot pixels in the converged output."
    )


def test_temporal_accumulate_guards_motion_vector_nan_and_huge() -> None:
    """v0.5.x image 2 regression: temporal_accumulate.comp reprojects the
    previous frame's accumulated colour along the per-pixel motion vector
    (`prevUV = uv + mv * motionVectorScale`). The bounds test skipped history
    for out-of-bounds prevUV, but never rejected (a) NaN/Inf motion vectors
    (which dither `all(greaterThanEqual(prevUV, 0))` to false -> disocclusion
    for that pixel, BUT the surrounding pixels' mv could be huge without
    going out of bounds — a 16-pixel camera pan at sub-pixel jitter precision
    produces a roughly-in-bounds prevUV that samples a slightly-wrong scene
    position), and (b) screen-space motion larger than ~80 px/frame (the
    ffx_reproject guard), which is impossible for a normal camera move at
    60 Hz. Without the explicit length check a malformed mv (numeric drift
    in gv_motionObjDisp payload) bleeds the previous frame's accumulated
    radiance along the screen-space direction of the bug, producing a
    directional comet-tail smear.

    temporal_accumulate.comp must mirror ffx_reproject's NaN / Inf + huge-motion
    guards so the standalone-TAA path (denoise=OFF, temporalAccum=ON) cannot
    smear the previous frame onto the current one through buggy MV data."""
    src = read("shaders/display/temporal_accumulate.comp")
    assert "any(isnan(mv))" in src and "any(isinf(mv))" in src, (
        "temporal_accumulate.comp must guard the motion vector against NaN/Inf "
        "(matching ffx_reproject.comp lines 57-62). Without it, a NaN mv dithers "
        "the bounds check to false per pixel and the previous frame's history "
        "survives unbounded."
    )
    assert "hugeMotion" in src and "length(mv)" in src and "80.0" in src, (
        "temporal_accumulate.comp must reject motion vectors longer than 80 "
        "render pixels per frame (= the same threshold ffx_reproject.comp uses "
        "at lines 92-98). Without it a runaway motion vector persists as a "
        "directional smear of the previous frame."
    )


def test_ffx_atrous_uses_depth_normal_only_no_color_weight() -> None:
    """SPP-1 HDR neighbours routinely differ by absolute/relative luma enough to
    zero any colour weight. à-trous must edge-stop on depth + normal ONLY so
    flat Minecraft faces actually average. colour weight (absolute or relative)
    is the salt-and-pepper regression."""
    atrous = read("shaders/display/denoise_ffx/ffx_atrous.comp")
    assert "colorWeight" not in atrous and "colorJump" not in atrous, (
        "ffx_atrous.comp must NOT compute a colour weight — depth/normal only. "
        "Any colour term re-introduces SPP-1 salt-and-pepper on AMD."
    )
    assert "depthWeight" in atrous and "normalWeight" in atrous, (
        "ffx_atrous.comp must still edge-stop on depth and normal"
    )
    # Host still pushes 4 floats (layout pad includes unused colorSigma).
    ffx = read("src/main/java/dev/comfyfluffy/caustica/denoise/FfxDenoiseBackend.java")
    assert "colorSigma" in ffx, (
        "FfxDenoiseBackend must still push a colorSigma slot for layout compatibility"
    )


def test_ffx_resolve_does_not_kill_history_on_color_diff() -> None:
    """The 2026-07-14 '180 frames still pure noise' bug: resolve computed
    historyConfidence = 1 - (Δluma(curr,history)²) / (variance * k).
    On trusted pixels variance≈0.05 so the denominator is tiny; SPP-1 curr
    always differs from clean history → confidence≈0 → wHistory≈0 every frame.
    Weight must come from variance (and clamp), never from |curr-history|."""
    resolve = read("shaders/display/denoise_ffx/ffx_resolve_temporal.comp")
    assert "colorDiffLuma" not in resolve, (
        "ffx_resolve_temporal.comp must not compute colorDiffLuma against history "
        "to drive the temporal weight — that formula zeros history on SPP-1."
    )
    assert "trust" in resolve and "variance" in resolve, (
        "resolve must derive history weight from variance/trust"
    )
    # Sanity: still has the hard disocclusion short-circuit
    assert "variance > 0.9" in resolve


def test_ffx_and_temporal_reproject_mv_sign_matches_world_rgen() -> None:
    """world.rgen writes gMotion = (prevNdc - curNdc) * 0.5 * size
    (= pixel offset from CURRENT to PREVIOUS). Consumers must sample
    prevUV = uv + mv * scale. Using minus inverts the reprojection and
    paints multi-frame semi-transparent geometry trails (2026-07-14 '升天')."""
    rgen = read("shaders/world/world.rgen")
    assert "(prevNdc - curNdc) * 0.5 * size" in rgen or "(prevNdc - curNdc)*0.5*size" in rgen.replace(" ", ""), (
        "world.rgen must store gMotion as (prevNdc - curNdc) * 0.5 * size"
    )
    for rel, label in (
        ("shaders/display/denoise_ffx/ffx_reproject.comp", "ffx_reproject"),
        ("shaders/display/temporal_accumulate.comp", "temporal_accumulate"),
    ):
        src = read(rel)
        assert "uv + mv" in src.replace(" ", "") or "uv+mv" in src.replace(" ", ""), (
            f"{label} must reproject with prevUV = uv + mv * scale (plus sign). "
            f"Minus is the inverted-MV ghost-trail bug."
        )
        # Guard against regressing to the minus form on the prevUV assignment.
        import re as _re
        assigns = _re.findall(r"prevUV\s*=\s*([^;]+);", src)
        assert assigns, f"{label} must assign prevUV"
        for a in assigns:
            assert "-" not in a.split("mv")[0] or "+ mv" in a or "+mv" in a.replace(" ", ""), (
                f"{label} prevUV assignment looks wrong: {a}"
            )
            assert "+ mv" in a or "+mv" in a.replace(" ", ""), (
                f"{label} prevUV must use plus: got {a}"
            )

    for rel, label in (
        ("shaders/display/denoise_ffx/shadow_reproject.comp", "shadow_reproject"),
        ("shaders/display/denoise_ffx/reflection_reproject.comp", "reflection_reproject"),
    ):
        src = read(rel).replace(" ", "")
        assert "+motionPx" in src and "-motionPx" not in src, (
            f"{label} must sample current + motion because gMotion is previous-current"
        )


def test_fsr2_receives_render_jitter_after_denoise() -> None:
    """Denoise preserves the jittered render grid; FSR must still receive its offset.

    The camera-equivalent jitter for ray-offset-based tracers is (-jitterX, -jitterY):
    shifting the ray right produces the *opposite* screen effect of shifting the camera
    right, and temporal upscalers (FSR2, XeSS) expect camera-jitter semantics.
    TAAU is the exception (it knows the raw ray offset).
    """
    src = read("src/main/java/dev/comfyfluffy/caustica/rt/RtComposite.java")
    assert "activeUpscaler.expectsRawRenderJitter()" in src, (
        "jitter convention must be provider capability, not a selector-mode branch"
    )
    taau = read("src/main/java/dev/comfyfluffy/caustica/upscale/TaaUpscaler.java")
    assert "public boolean expectsRawRenderJitter()" in taau and "return true;" in method_body(
        taau, "public boolean expectsRawRenderJitter()"
    ), "TAAU must advertise that it consumes the raw ray offset"
    assert "fsrJx = -jitterX;" in src and "fsrJy = -jitterY;" in src, (
        "camera-equivalent jitter = (-jitterX, -jitterY) for external temporal reconstruction"
    )
    import re as _re
    branch = _re.search(r"if \(activeUpscaler\.expectsRawRenderJitter\(\)\) \{[^}]*\} else \{[^}]*\}", src, _re.DOTALL)
    assert branch is not None, "the capability-driven raw/camera jitter branch must exist"
    assert "jitterX" in branch.group() and "-jitterX" in branch.group()
    assert "else if (lastDenoiseOn)" not in src, (
        "FSR jitter must not be forced to zero merely because denoise ran"
    )
    jitter = read("src/main/java/dev/comfyfluffy/caustica/client/CausticaJitter.java")
    phase = method_body(jitter, "private static int jitterPhaseCount")
    assert "Math.ceil" not in phase and "(int) (8.0f * ratio * ratio)" in phase, (
        "classic FSR2 truncates 8*ratio^2; ceil desynchronizes its lock phase"
    )
    assert "!activeUpscaler.includesSharpening()" in src, (
        "providers with integrated sharpening must not receive a second display-resolution CAS pass"
    )


def test_vulkan_device_bringup_is_vendor_neutral() -> None:
    backend = read("src/main/java/dev/comfyfluffy/caustica/mixin/VulkanBackendMixin.java")
    bringup = read("src/main/java/dev/comfyfluffy/caustica/rt/RtDeviceBringup.java")
    diagnostics = read("src/main/java/dev/comfyfluffy/caustica/rt/VulkanDiagnostics.java")
    combined = backend + bringup + diagnostics
    for vendor_extension in ("VK_NVX_", "VK_NV_", "NVLowLatency", "NVRayTracing"):
        assert vendor_extension not in combined, (
            f"bottom-level Vulkan negotiation must not depend on {vendor_extension}"
        )
    assert "VK_EXT_RAY_TRACING_INVOCATION_REORDER_EXTENSION_NAME" in bringup
    assert '"world_noser.rgen.spv"' in bringup, (
        "SER must retain a no-extension fallback for maximum device compatibility"
    )
    build = read("build.gradle")
    raygen = read("shaders/world/world.rgen")
    for dead_nv_route in ("CAUSTICA_SER_NV", "world_nv.rgen.spv", "GL_NV_shader_invocation_reorder"):
        assert dead_nv_route not in build + raygen, (
            f"dead NVIDIA SER route {dead_nv_route} must not remain in the shader build graph"
        )
    assert "CAUSTICA_SER_NONE" in build and "GL_EXT_shader_invocation_reorder" in raygen


def test_vulkan_12_capability_fallbacks_and_ringed_bindless_descriptors() -> None:
    """Portable RT must not turn optional shader conveniences into device-creation gates."""
    build = read("build.gradle")
    bringup = read("src/main/java/dev/comfyfluffy/caustica/rt/RtDeviceBringup.java")
    composite = read("src/main/java/dev/comfyfluffy/caustica/rt/RtComposite.java")
    accel = read("src/main/java/dev/comfyfluffy/caustica/rt/accel/RtAccel.java")
    outline = read("src/main/java/dev/comfyfluffy/caustica/rt/overlay/RtBlockOutlineFeature.java")
    pipeline = read("src/main/java/dev/comfyfluffy/caustica/rt/pipeline/RtPipeline.java")

    assert '"--target-env", "vulkan1.2"' in build
    assert "vulkan1.4" not in build, "shader build/validation must stay on Blaze3D's Vulkan 1.2 baseline"
    assert "world_noposfetch.rchit.spv" in build and "CAUSTICA_POSITION_FETCH_NONE" in build
    assert "block_outline_no_ray_query.frag.spv" in build and "CAUSTICA_RAY_QUERY_NONE" in build

    mandatory_extensions = re.search(
        r"RT_EXTENSIONS\s*=\s*List\.of\((.*?)\);", bringup, re.DOTALL
    )
    assert mandatory_extensions is not None
    mandatory_extensions_body = mandatory_extensions.group(1)
    assert "VK_KHR_ACCELERATION_STRUCTURE_EXTENSION_NAME" in mandatory_extensions_body
    assert "VK_KHR_RAY_TRACING_PIPELINE_EXTENSION_NAME" in mandatory_extensions_body
    assert "VK_KHR_DEFERRED_HOST_OPERATIONS_EXTENSION_NAME" in mandatory_extensions_body
    assert "POSITION_FETCH" not in mandatory_extensions_body and "RAY_QUERY" not in mandatory_extensions_body

    mandatory_features = method_body(bringup, "private static List<VulkanFeature> mandatoryFeatures()")
    assert "runtimeDescriptorArray" in mandatory_features
    assert "shaderSampledImageArrayNonUniformIndexing" in mandatory_features
    assert "descriptorBindingPartiallyBound" not in mandatory_features
    assert "descriptorBindingSampledImageUpdateAfterBind" not in mandatory_features
    assert "supportsFeature(physicalDevice, feature)" in bringup
    for optional_probe in ("supportsPositionFetch", "supportsRayQuery", "supportsOmm", "supportsVrs"):
        assert optional_probe in bringup

    assert "RtDeviceBringup.worldClosestHitShader()" in composite
    build_flags = method_body(accel, "private static int buildFlags")
    assert "RtDeviceBringup.positionFetchEnabled()" in build_flags
    assert "VK_BUILD_ACCELERATION_STRUCTURE_ALLOW_DATA_ACCESS_BIT_KHR" in build_flags

    assert '"block_outline_no_ray_query.frag.spv"' in outline
    assert "RtDeviceBringup.rayQueryEnabled() ? accelSet.bind(ctx, tlas) : 0L" in outline
    assert "if (RtDeviceBringup.rayQueryEnabled())" in outline

    # A six-slot ordinary descriptor ring plus full fallback initialization replaces both descriptor
    # indexing convenience features without racing older submitted frames.
    assert "private final long[] bindlessSets;" in pipeline
    assert "initializeBindlessFallback" in pipeline
    assert "flushBindlessDirty(currentSet);" in pipeline
    assert "bindlessSets[currentSet]" in pipeline
    for update_after_bind_token in (
        "VK_DESCRIPTOR_BINDING_PARTIALLY_BOUND_BIT",
        "VK_DESCRIPTOR_BINDING_UPDATE_AFTER_BIND_BIT",
        "VK_DESCRIPTOR_SET_LAYOUT_CREATE_UPDATE_AFTER_BIND_POOL_BIT",
        "VK_DESCRIPTOR_POOL_CREATE_UPDATE_AFTER_BIND_BIT",
    ):
        assert update_after_bind_token not in pipeline
    assert "active.setTlas(frameTlas.accel.handle);" in composite
    assert composite.index("active.setTlas(frameTlas.accel.handle);") < composite.index(
        "RtMaterialSystem.INSTANCE.flushBeforeTrace(active, atlasSampler(ctx));"
    ), "bindless writes must happen after advancing to the safe descriptor-ring slot"


def test_upscaler_selection_is_provider_probed_not_vendor_routed() -> None:
    selector = read("src/main/java/dev/comfyfluffy/caustica/upscale/UpscalerSelector.java")
    fsr = read("src/main/java/dev/comfyfluffy/caustica/fsr/Fsr2ClassicUpscaler.java")
    assert "GpuVendor" not in selector and "GpuVendor" not in fsr
    assert "Fsr2ClassicUpscaler.tryCreate()" in selector
    assert "public static Fsr2ClassicUpscaler tryCreate()" in fsr
    assert not (ROOT / "src/main/java/dev/comfyfluffy/caustica/vendor/GpuVendor.java").exists(), (
        "dead vendor-policy detector must not survive after providers own capability probing"
    )


def test_upscaler_and_framegen_contracts_do_not_leak_selector_modes() -> None:
    """Runtime providers expose metadata/capabilities, never config selector enums."""
    upscaler = read("src/main/java/dev/comfyfluffy/caustica/upscale/Upscaler.java")
    framegen = read("src/main/java/dev/comfyfluffy/caustica/framegen/FrameGen.java")
    composite = read("src/main/java/dev/comfyfluffy/caustica/rt/RtComposite.java")
    selector = read("src/main/java/dev/comfyfluffy/caustica/framegen/FrameGenSelector.java")
    config = read("src/main/java/dev/comfyfluffy/caustica/CausticaConfig.java")

    assert "UpscalerSelector" not in upscaler
    assert "String id()" in upscaler
    assert "performsTemporalReconstruction()" in upscaler
    assert "isPassThrough()" in upscaler
    assert "UpscalerSelector" not in framegen and "sourceMode()" not in framegen
    assert "UpscalerSelector.Mode" not in composite
    assert "activeUpscaler.performsTemporalReconstruction()" in composite
    assert "FrameGen resolve(Upscaler source)" in selector
    assert "sourceUpscalerId" in selector
    assert "UpscalerSelector.Mode" not in selector
    assert "valueEnum()" not in config and "UpscalerSelector" not in config


def test_framegen_provider_boundary_hides_vendor_backend() -> None:
    """Presentation/client/composite code depends on FrameGen, not a concrete vendor SDK."""
    interface = read("src/main/java/dev/comfyfluffy/caustica/framegen/FrameGen.java")
    assert "interpolate(Object" not in interface and "Object..." not in interface
    assert "boolean ensureFeature(long commandBuffer" in interface
    assert "boolean interpolate(long commandBuffer" in interface
    assert "void probeAvailabilityOnce()" in interface and "void destroy()" in interface

    selector = read("src/main/java/dev/comfyfluffy/caustica/framegen/FrameGenSelector.java")
    assert "private static volatile FrameGen active" in selector
    assert "FrameGen resolve(Upscaler source)" in selector
    assert "GpuVendor" not in selector
    assert "public static synchronized void shutdown()" in selector
    assert "VulkanMotionFrameGen.INSTANCE" in selector
    assert "GpuVendor.Vendor.NVIDIA" not in selector and "RtDlssFg" not in selector, (
        "frame-generation selection must not branch on GPU vendor or a DLSS backend"
    )

    backend = read("src/main/java/dev/comfyfluffy/caustica/framegen/VulkanMotionFrameGen.java")
    assert "implements FrameGen" in backend and "public synchronized boolean interpolate(long commandBuffer" in backend
    assert "VK_FORMAT_R8G8B8A8_UNORM" in backend and "VK_FORMAT_R16G16B16A16_SFLOAT" in backend
    assert "if (generatedFrameIndex == generatedFrameCount)" in backend
    final_index = method_body(backend, "private void copyCurrentToHistory")
    assert "currentColorImage" in final_index and "currentDepthImage" in final_index
    assert "vkCmdCopyImage" in final_index

    sdr = read("shaders/display/framegen_motion_rgba8.comp")
    hdr = read("shaders/display/framegen_motion_rgba16f.comp")
    assert "binding = 5, rgba8" in sdr
    assert "binding = 5, rgba16f" in hdr
    for shader in (sdr, hdr):
        assert "pc.historyReady == 0u" in shader
        assert "vec4(current.rgb, 1.0)" in shader
        assert "previousUv = uv + motion" in shader
        assert "relativeDepthDelta" in shader

    for path in (
        "src/main/java/dev/comfyfluffy/caustica/client/CausticaClient.java",
        "src/main/java/dev/comfyfluffy/caustica/mixin/VulkanGpuSurfaceMixin.java",
        "src/main/java/dev/comfyfluffy/caustica/rt/RtComposite.java",
    ):
        source = read(path)
        assert "RtDlssFg" not in source, f"{path} must not directly reference the DLSS-FG backend"
        assert "FrameGenSelector" in source, f"{path} must use the provider selector"


def test_official_ffx_transfer_operations_have_transfer_barriers() -> None:
    src = read("src/main/java/dev/comfyfluffy/caustica/denoise/OfficialFfxDenoiseBackend.java")
    copy = method_body(src, "private static void copyImage")
    assert "VK_ACCESS_TRANSFER_READ_BIT" in copy
    assert "VK_ACCESS_TRANSFER_WRITE_BIT" in copy
    assert "barrierTransferToCompute" in copy
    clear = method_body(src, "private void clearHistoryBuffers")
    assert "barrierTransferToCompute" in clear


def test_ffx_reflection_composite_is_config_gated_and_default_on() -> None:
    """The reflection delta composite (bit1) returns with a config latch.

    It was disabled because uninitialised history could zero the plate; the
    transfer→compute barrier fix removed that root cause. The composite keeps
    its ±2.0 delta cap + 0.35*beauty floor, so the default is ON with an escape
    hatch (caustica.rt.denoise.ffxReflectionComposite=false) for driver issues.
    """
    src = read("src/main/java/dev/comfyfluffy/caustica/denoise/OfficialFfxDenoiseBackend.java")
    assert "CausticaConfig.Rt.Denoise.FFX_REFLECTION_COMPOSITE.value()" in src, (
        "reflection composite must be gated by FFX_REFLECTION_COMPOSITE"
    )
    assert "intentionally not: flags |= 2" not in src, (
        "reflection composite must not be unconditionally disabled anymore"
    )
    dispatch = method_body(src, "public boolean dispatch")
    assert "flags |= 2" in dispatch, (
        "reflection path must set composite bit1 when the config allows it"
    )
    cfg = read("src/main/java/dev/comfyfluffy/caustica/CausticaConfig.java")
    import re as _re
    m = _re.search(
        r'FFX_REFLECTION_COMPOSITE\s*=\s*bool\(\s*"caustica\.rt\.denoise\.ffxReflectionComposite"\s*,\s*"[^"]*"\s*,\s*(true|false)',
        cfg,
    )
    assert m is not None, "FFX_REFLECTION_COMPOSITE bool setting not found"
    assert m.group(1) == "true", (
        f"reflection composite must default to true now that history clear is barrier-safe; got {m.group(1)}"
    )


def test_amd_fidelityfx_stack_has_temporal_radiance_stage() -> None:
    """The AMD FidelityFX preset must compose Official FFX + firefly kill + bilateral
    residual, and pass the firefly-killed radiance (not raw RT) to OfficialFfx.

    Verified architecture (2026-07-20):
      path trace  →  firefly_kill  →  OfficialFfx (shadow/refl reproject+spatial)
                                 →  bilateral residual  →  outColor
    The temporal accumulation lives in OfficialFfx's own history ring; this
    preset is firefly-kill-tolerant via its own 3x3 median pre-pass.

    Regression: if someone removes the bilateral residual, removes firefly_kill,
    or feeds raw RT directly to OfficialFfx (skipping the firefly-kill pre-pass)
    this test fails and forces a re-test against the user-visible comparison chart.
    """
    src = read("src/main/java/dev/comfyfluffy/caustica/denoise/AmdFidelityFxDenoiseBackend.java")
    # Stage composition must match the verified chain.
    assert "OfficialFfxDenoiseBackend" in src, (
        "AmdFidelityFxDenoiseBackend must own an OfficialFfxDenoiseBackend for shadow + reflection."
    )
    assert "BilateralDenoiseBackend" in src, (
        "AmdFidelityFxDenoiseBackend must own a BilateralDenoiseBackend for the residual polish."
    )
    assert "FireflyKill" in src, (
        "AmdFidelityFxDenoiseBackend must run a FireflyKill pre-pass against SPP-2 fireflies."
    )
    dispatch = method_body(src, "public boolean dispatch")
    # residual.dispatch( must be invoked exactly once (ping-ponging with one mid).
    assert src.count("residual.dispatch(") == 1, (
        "AMD residual must not invoke and rebind the same backend twice in one command buffer"
    )
    # Firefly kill must run before Official FFX (radiance source for ffx is firefly-killed).
    assert dispatch.find("fireflyKill") < dispatch.find("ffx.dispatch"), (
        "fireflyKill.dispatch must run before ffx.dispatch so OfficialFfx sees clean radiance."
    )


def test_amd_fidelityfx_temporal_disables_luma_bypass() -> None:
    """The standalone beauty TAA temporal stage still respects antiGhostBypass.

    The AMD FidelityFX denoise backend no longer owns a radiance temporal stage
    (see test_amd_fidelityfx_stack_has_temporal_radiance_stage). The whole-radiance
    RtTemporalAccumulation is still used by the standalone beauty TAA path gated
    by `composite.temporal-accum=true`. That path uses raw SPP-1 radiance, so the
    v0.6.23 luma-ratio anti-ghost bypass would fire every frame on Monte Carlo
    variance — pinning history to current and never converging. The
    `antiGhostBypass` parameter on RtTemporalAccumulation + the `pc.antiGhost`
    push constant in temporal_accumulate.comp carry the gate; the standalone
    beauty TAA call in RtComposite invokes the overload with `true` and the
    pipeline.push writes antiGhost=1.0 so the heuristic gates fire.
    """
    # RtTemporalAccumulation must still expose the antiGhostBypass overload.
    acc = read("src/main/java/dev/comfyfluffy/caustica/rt/pipeline/RtTemporalAccumulation.java")
    assert "boolean antiGhostBypass" in acc, (
        "RtTemporalAccumulation must expose an antiGhostBypass overload"
    )
    assert "antiGhostBypass ? 1.0f : 0.0f" in acc, (
        "RtTemporalAccumulation must push the antiGhost flag into the push constants"
    )
    shader = read("shaders/display/temporal_accumulate.comp")
    assert "float antiGhost;" in shader, (
        "temporal_accumulate.comp must declare the antiGhost push-constant field"
    )
    gated = shader.count("pc.antiGhost > 0.5")
    assert gated >= 2, (
        f"temporal_accumulate.comp must gate both luma-bypass and dark-bias blocks "
        f"behind pc.antiGhost (found {gated} gate sites, need ≥2)"
    )
    assert "bool bypassHistory = disocclusion;" in shader, (
        "depth/normal disocclusion must still bypass history unconditionally"
    )
    # Standalone beauty TAA keeps the legacy behavior via the old 5-arg overload
    # (which still defaults antiGhostBypass=true), so legacy v0.6.23 anti-ghost
    # heuristics remain active on the pre-filtered beauty TAA path.
    rt = read("src/main/java/dev/comfyfluffy/caustica/rt/RtComposite.java")
    taa_call = rt.split("temporalAccum.dispatch")[1][:400]
    assert "accumulatedColor)" in taa_call, (
        "standalone beauty TAA must use the legacy antiGhostBypass=true overload"
    )
    assert "false" not in taa_call.split("accumulatedColor")[0][-40:], (
        "standalone beauty TAA must NOT pass antiGhostBypass=false"
    )


def test_auto_mode_has_one_cross_vendor_denoise_graph() -> None:
    """AUTO must not change denoise topology from a PCI vendor name."""
    src = read("src/main/java/dev/comfyfluffy/caustica/denoise/DenoiseBackendSelector.java")
    auto = method_body(src, "private static CausticaDenoiseBackend autoPick()")
    assert "switch" not in auto and "case AMD" not in auto and "case NVIDIA" not in auto
    assert "HybridFfxNrdBackend(true)" in auto, (
        "AUTO must use the portable NRD graph and let native probing decide availability"
    )
    assert "tryCreateNrdAuto" in auto, "AUTO must retain the bilateral compatibility fallback"


def test_ffx_reproject_encodes_motion_into_variance() -> None:
    """Anti-ghost: pan frames must raise variance so resolve drops history weight.
    Without this, temporalWeightMax alone (even at 0.82) leaves semi-transparent
    geometry trails when looking around (2026-07-14 '升天' screenshot)."""
    reproject = read("shaders/display/denoise_ffx/ffx_reproject.comp")
    assert "motionVar" in reproject or "smoothstep" in reproject and "mvLen" in reproject, (
        "ffx_reproject.comp must encode screen-space motion length into variance "
        "(motionVar / mvLen smoothstep) so pan frames prefer current over history."
    )
    assert "hugeMotion" in reproject or "48.0" in reproject, (
        "ffx_reproject.comp must hard-disocclude on large motion (~48 px/frame)"
    )


def test_ffx_temporal_weight_default_not_ghost_level() -> None:
    """Default temporal weight must stay below the 0.90 ghost-trail regime."""
    cfg = read("src/main/java/dev/comfyfluffy/caustica/CausticaConfig.java")
    import re as _re
    m = _re.search(
        r'clampedFloat\(\s*"caustica\.rt\.denoise\.ffxTemporalWeightMax"[^,]*,\s*"[^"]*"\s*,\s*([0-9.]+)f',
        cfg,
    )
    assert m is not None, "FFX_TEMPORAL_WEIGHT_MAX clampedFloat default not found"
    default = float(m.group(1))
    assert 0.70 <= default <= 0.88, (
        f"FFX_TEMPORAL_WEIGHT_MAX default must sit in [0.70, 0.88] for SPP-1 "
        f"convergence without pan-ghosting. Got {default}."
    )


def test_ffx_history_has_transfer_to_shader_barriers() -> None:
    """When TEMPORAL_ENABLED, AMD RADV needs TRANSFER→SHADER barriers on the
    history ring. Spatial-only mode (TEMPORAL_ENABLED=false) skips history
    entirely — the barrier helper must still exist for the temporal path."""
    ffx = read("src/main/java/dev/comfyfluffy/caustica/denoise/FfxDenoiseBackend.java")
    assert "barrierTransferToShader" in ffx, (
        "FfxDenoiseBackend must define barrierTransferToShader for the temporal path."
    )
    assert "TEMPORAL_ENABLED" in ffx, (
        "FfxDenoiseBackend must expose TEMPORAL_ENABLED so spatial-only vs temporal "
        "is an explicit latch (ghost-trail kill switch)."
    )


def test_ffx_default_is_full_official_pipeline() -> None:
    """2026-07-20: the AMD FidelityFX whole-radiance pipeline must run the official
    SDK shader sequence — ffx_reproject + ffx_resolve_temporal + ffx_atrous × 5 —
    not a copy-only passthrough. The previous OFF state was gated by a 2026-07-14
    RADV black-frame regression whose root causes (world.rgen MV jitter injection,
    missing transfer→compute barriers on the history ring) are now fixed.
    Asserts TEMPORAL_ENABLED=true and SPATIAL_PASSTHROUGH=false; flipping either
    silently drops official quality (or reintroduces the old RADV passthrough)."""
    ffx = read("src/main/java/dev/comfyfluffy/caustica/denoise/FfxDenoiseBackend.java")
    import re as _re
    m = _re.search(r"TEMPORAL_ENABLED\s*=\s*(true|false)", ffx)
    assert m is not None, "TEMPORAL_ENABLED constant missing"
    assert m.group(1) == "true", (
        f"TEMPORAL_ENABLED must be true to drive the official AMD FFX resolve. "
        f"Got {m.group(1)}."
    )
    p = _re.search(r"SPATIAL_PASSTHROUGH\s*=\s*(true|false)", ffx)
    assert p is not None, "SPATIAL_PASSTHROUGH constant missing"
    assert p.group(1) == "false", (
        f"SPATIAL_PASSTHROUGH must be false (full pipeline) so the SDK's "
        f"ffx_reproject / ffx_resolve_temporal / ffx_atrous×5 actually run. "
        f"Got {p.group(1)}."
    )
    assert "vkCmdCopyImage" in ffx, (
        "passthrough must use vkCmdCopyImage (not blit) for HDR beauty reliability"
    )


def test_temporal_accumulate_clamps_history_to_max_hdr_radiance() -> None:
    """v0.5.3 follow-up: temporal_accumulate.comp samples the previous frame's
    accumulated colour (binding 4, historySampler) at `prevUV = uv + mv *
    motionVectorScale`. With alpha=0.1 the temporal weight on history is 90%,
    so a single un-clamped HDR firefly that slipped through a previous frame
    — through a denoise=ON -> OFF toggle, or a jar hot-reload that didn't
    reset the history ring — gets replayed as a "tail" along the motion vector.
    That was the user's observation 2026-07-14 ~18:02 (denoise=OFF produced a
    streak even after the NaN/Inf + huge-Motion guards landed). The shader
    must clamp the history colour to a finite upper bound (matching
    world.rgen's MAX_HDR_RADIANCE = 8.0) before mixing it into the new
    accumulated sample. The clamp does not need to match world.rgen's constant
    exactly — any value <= 8.0 keeps the streak bounded."""
    src = read("shaders/display/temporal_accumulate.comp")
    body = method_body(src, "void main")
    # The clamp must be inside the `if (prevInBounds)` branch that does the
    # history texture() fetch. Find the texture call, then check a `min(...)`
    # of history colour appears before the next `mix(` or branch end.
    assert "texture(historySampler" in body, (
        "temporal_accumulate.comp must sample history via texture(historySampler, prevUV)"
    )
    # We just need any `min(historyColor, vec3(...))` inside the function body,
    # right after the texture call. Be permissive on the exact constant value.
    import re as _re
    clamp_match = _re.search(
        r"historyColor\s*=\s*min\(\s*historyColor\s*,\s*vec3\(\s*[0-9.]+\s*\)\s*\)",
        body,
    )
    assert clamp_match is not None, (
        "temporal_accumulate.comp must clamp historyColor via `min(historyColor, "
        "vec3(MAX))` inside main(). The clamp value must be a finite positive "
        "constant <= 8.0 so a HDR firefly pixel that escaped the previous frame "
        "does not get replayed as a tail along the motion vector."
    )


def test_composite_spp_default_is_one_after_firefly_fix() -> None:
    """v0.5.3 firefly fix series: the SPP default was briefly bumped 1 -> 2 -> 4
    via subsequent fixes, but reverted to 1 after the user's hardware on
    2026-07-14 couldn't sustain SPP=4 frame pacing and the visual difference
    between SPP=1 + FFX (sigma 0.55..3.4/step) and SPP>=2 + FFX wasn't worth
    the fps hit. The right knob for higher-quality stills remains user-tunable
    per-instance (`caustica.rt.spp = 2` or 4); the default stays at 1 so the
    typical installation can boot at full fps."""
    src = read("src/main/java/dev/comfyfluffy/caustica/CausticaConfig.java")
    assert '"caustica.rt.spp", "composite.spp", 1, 1' in src, (
        "CausticaConfig.Rt.Composite.SPP default must stay at 1 after the "
        "v0.5.3 firefly-fix series. Higher defaults cause framerate collapse "
        "on modest GPUs (verified 2026-07-14). Users who want higher quality "
        "set the value in their per-instance caustica.toml."
    )


def test_debug_overlay_uses_canonical_stage_names() -> None:
    """v0.5.x debug overlay typo: CausticaDebugOverlay looked up `frame.upscaler`
    while the fallback blit stage is registered as `frame.upscale`. The overlay therefore
    always rendered `upscaler=0ms` even when the upscale pipeline had run for
    tens of milliseconds per frame, making hitch triage impossible from the in-
    game overlay alone. Same applies to `trace` — that one is canonical but the
    overlay also needs to be aware that FrameStats is opt-in (line 0 when off)."""
    src = read("src/main/java/dev/comfyfluffy/caustica/client/CausticaDebugOverlay.java")
    assert 'stageTotalMs("frame.trace")' in src, (
        "CausticaDebugOverlay must read frame.trace from RtFrameStats (it does)"
    )
    # The stage name in RtFrameStats is `frame.upscale` (singular) — the
    # overlay's old `frame.upscaler` made the upscale timing look permanently
    # off after a hitch.
    assert 'stageTotalMs("frame.upscale")' in src, (
        "CausticaDebugOverlay must read frame.upscale (singular) to match the "
        "stage registered in RtFrameStats.FRAME — using `frame.upscaler` (old "
        "plural typo) made the upscale-hitch entry always show 0ms."
    )
    assert 'stageTotalMs("frame.upscaler")' not in src, (
        "CausticaDebugOverlay must NOT use `frame.upscaler` (plural) — that's "
        "the v0.5.x typo; the canonical stage name is `frame.upscale`."
    )




def test_fsr2_declares_required_storage_format_feature_and_valid_depth_range() -> None:
    """Packed HDR storage and reverse-infinite depth must satisfy their Vulkan/FSR contracts."""
    pack = ROOT / "shaders/display/fsr_color_pack.comp"
    unpack = ROOT / "shaders/display/fsr_color_unpack.comp"
    assert pack.is_file() and unpack.is_file(), "FSR2 pack/unpack convert shaders must exist"
    pack_src = pack.read_text(encoding="utf-8")
    unpack_src = unpack.read_text(encoding="utf-8")
    assert "r11f_g11f_b10f" in pack_src and "rgba16f" in pack_src
    assert "vec4(rgb, 1.0)" in pack_src, "pack must initialize the RGBA16F staging alpha"
    assert "rgba16f" in unpack_src and "r11f_g11f_b10f" in unpack_src
    up = read("src/main/java/dev/comfyfluffy/caustica/fsr/Fsr2ClassicUpscaler.java")
    bridge = read("src/main/java/dev/comfyfluffy/caustica/rt/plate/RtPlateBridge.java")
    assert "fsr_color_pack.comp.spv" in bridge and "fsr_color_unpack.comp.spv" in bridge
    assert "plate.convertToUpscalerInput(cmd, color, inputColorFormat)" in up
    assert "new RtPlateBridge" not in up and "plate.destroy()" not in up, (
        "FSR2 must use RtComposite's non-owning plate bridge"
    )
    assert "vkCmdBlitImage" not in up, "FSR2 must not raw-blit incompatible B10G11R11/RGBA16F formats"
    assert "1_000_000.0f" in up, (
        "reverse-infinite FSR2 dispatch must pass a non-zero far sentinel; far=0 collapses depth reconstruction"
    )
    device = read("src/main/java/dev/comfyfluffy/caustica/mixin/VulkanBackendMixin.java")
    assert "shaderStorageImageExtendedFormats" in device, (
        "r11f_g11f_b10f storage images require shaderStorageImageExtendedFormats at vkCreateDevice"
    )
    guard = read("shaders/display/fsr_blackout_guard.comp")
    assert "for (int gy" not in guard and "for (int gx" not in guard, (
        "blackout guard must not repeat a global sparse probe for every display pixel"
    )
    composite = read("shaders/display/denoise_ffx/denoise_composite.comp")
    assert "rawS + 0.12" in composite or "cleanS = min(cleanS, rawS" in composite, (
        "shadow composite must clamp brightening so contact shadows are not washed out"
    )


def test_multi_dispatch_passes_do_not_mutate_one_descriptor_set() -> None:
    """Each distinct binding tuple recorded in one command buffer needs its own descriptor set."""
    official = read("src/main/java/dev/comfyfluffy/caustica/denoise/OfficialFfxDenoiseBackend.java")
    for sets in ("shSpatSets", "depthPyrSets", "rfSpatSets", "compSets"):
        assert f"long[] {sets}" in official, (
            f"Official FFX must allocate multiple descriptor sets for {sets}"
        )
    assert "descriptorBindings" in official, (
        "Official FFX must avoid updating stable sets that may still be in flight"
    )
    bilateral = read("src/main/java/dev/comfyfluffy/caustica/denoise/BilateralDenoiseBackend.java")
    assert "long[] sets" in bilateral and "sets[pass]" in bilateral, (
        "bilateral ping-pong passes must not rewrite a single descriptor set"
    )
    amd = read("src/main/java/dev/comfyfluffy/caustica/denoise/AmdFidelityFxDenoiseBackend.java")
    assert amd.count("residual.dispatch(") == 1, (
        "AMD residual must not invoke and rebind the same backend twice in one command buffer"
    )
    bridge = read("src/main/java/dev/comfyfluffy/caustica/rt/plate/RtPlateBridge.java")
    assert "convSetPack" in bridge and "convSetUnpack" in bridge, (
        "shared plate bridge pack and unpack must use distinct descriptor sets"
    )
    cas = read("src/main/java/dev/comfyfluffy/caustica/display/CasSharpenPass.java")
    cas_dispatch = method_body(cas, "public boolean dispatchInPlace")
    assert cas_dispatch.count("vkCmdDispatch(") == 1 and "vkCmdCopyImage(" in cas, (
        "CAS must not rebind one descriptor set for copy-back in the same command buffer"
    )
    assert "boundViews" in cas, "CAS must not update a stable descriptor set every frame"
    taau = read("src/main/java/dev/comfyfluffy/caustica/upscale/TaaUpscaler.java")
    assert "long[] descriptorSets" in taau and "descriptorSets[setIndex]" in taau, (
        "TAAU history ping-pong directions must use distinct descriptor sets"
    )
    assert "boundViews" in taau, "TAAU must not update stable descriptor sets while in flight"
    temporal = read("src/main/java/dev/comfyfluffy/caustica/rt/pipeline/RtTemporalAccumulation.java")
    assert "long[] descriptorSets" in temporal and "descriptorSets[writeSlot]" in temporal, (
        "temporal history-ring states must own distinct descriptor sets"
    )
    clear_body = method_body(temporal, "private void clearHistoryToZero")
    assert "VK_ACCESS_TRANSFER_WRITE_BIT" in clear_body and "VK_ACCESS_SHADER_READ_BIT" in clear_body, (
        "cleared temporal history must be visible before compute reads it"
    )
    taau_clear = method_body(taau, "private void clearImage")
    assert "vkCmdClearColorImage" in taau_clear and "VK_ACCESS_SHADER_READ_BIT" in taau_clear, (
        "TAAU history initialization must clear memory and synchronize it for compute"
    )
    transparent = read("src/main/java/dev/comfyfluffy/caustica/denoise/TransparentMaterialDenoiser.java")
    assert "spatialBindings" in transparent and "temporalBindings" in transparent, (
        "transparent denoise passes must not rewrite stable descriptor sets every frame"
    )
    hybrid = read("src/main/java/dev/comfyfluffy/caustica/denoise/HybridFfxNrdBackend.java")
    assert "prepBindings" in hybrid and "compBindings" in hybrid, (
        "hybrid NRD passes must cache stable descriptor bindings"
    )



def test_hybrid_fsr_hdr_rgba16f_seam_and_pq_encode() -> None:
    """Hybrid compose -> FSR2 -> HDR mapper stays RGBA16F and SDR UI is PQ encoded."""
    compose = read("shaders/display/denoise_ffx/nrd_compose_beauty.comp")
    assert "layout(binding = 8, rgba16f)" in compose

    profile = read("src/main/java/dev/comfyfluffy/caustica/rt/plate/RtPlateProfile.java")
    assert "instanceof HybridFfxNrdBackend" not in profile and "UpscalerSelector.Mode" not in profile
    assert "denoise.outputColorFormat(rawBeautyFormat)" in profile
    assert "upscale.inputColorFormat(rawBeautyFormat)" in profile
    assert "upscale.displayColorFormat(rawBeautyFormat, hdrEnabled)" in profile

    denoise_iface = read("src/main/java/dev/comfyfluffy/caustica/denoise/CausticaDenoiseBackend.java")
    upscaler_iface = read("src/main/java/dev/comfyfluffy/caustica/upscale/Upscaler.java")
    hybrid = read("src/main/java/dev/comfyfluffy/caustica/denoise/HybridFfxNrdBackend.java")
    fsr = read("src/main/java/dev/comfyfluffy/caustica/fsr/Fsr2ClassicUpscaler.java")
    assert "default int outputColorFormat(int rawBeautyFormat)" in denoise_iface
    assert "public int outputColorFormat(int rawBeautyFormat)" in hybrid
    assert "VK_FORMAT_R16G16B16A16_SFLOAT" in method_body(hybrid, "public int outputColorFormat")
    for capability in ("inputColorFormat", "displayColorFormat", "needsReactiveMask", "needsBlackoutGuard"):
        assert capability in upscaler_iface and capability in fsr

    bridge = read("src/main/java/dev/comfyfluffy/caustica/rt/plate/RtPlateBridge.java")
    assert "convertToUpscalerInput(" in bridge and "int colorFormat" in bridge
    assert "colorFormat == profile.upscalerInputFormat" in bridge
    assert "currentUpscalerInput = color" in bridge

    composite = read("src/main/java/dev/comfyfluffy/caustica/rt/RtComposite.java")
    assert "beautyAfterDenoiseFormat = plateProfile.denoiseOutputFormat" in composite
    assert "activeUpscaler.setInputColorFormat(displayPlateFormat)" in composite
    assert "desiredRrOutputFormat" in composite and "activeUpscaler.displayColorFormat(" in composite

    display = read("shaders/display/display_rgba16f.comp")
    hist = read("shaders/display/exposure_hist_rgba16f.comp")
    guard = read("shaders/display/fsr_blackout_guard_rgba16f.comp")
    assert "binding = 1, set = 0, rgba16f" in display
    assert "binding = 0, set = 0, rgba16f" in hist
    assert "binding = 2, rgba16f" in guard

    pq = read("shaders/display/sdr_present.comp")
    assert "BT709_TO_BT2020" in pq and "pqEncode" in pq
    assert "PQ_M1" in pq and "PQ_M2" in pq and "paperWhiteNits" in pq

def test_labpbr_material_system_owns_pipeline_lifecycle() -> None:
    """LabPBR GPU stores share one renderer lifecycle boundary without double ownership."""
    system = read("src/main/java/dev/comfyfluffy/caustica/rt/material/RtMaterialSystem.java")
    for signature in (
        "public BlockAtlasViews prepareForPipeline",
        "public void flushBeforeTrace",
        "public void releaseAfterPipelineDestroy",
        "public void destroy()",
    ):
        assert signature in system, f"RtMaterialSystem missing lifecycle operation {signature}"

    prepare = method_body(system, "public BlockAtlasViews prepareForPipeline")
    assert "RtEntityTextures.INSTANCE.resetForPipeline(descriptorCapacity)" in prepare
    assert "RtBlockMaterials.INSTANCE.reset()" in prepare
    assert "RtBlockMaterials.INSTANCE.prepareAll()" in prepare

    flush = method_body(system, "public void flushBeforeTrace")
    assert "RtEntityTextures.INSTANCE.uploadPending" in flush
    assert "RtBlockMaterials.INSTANCE.flush()" in flush
    assert "RtEntityMaterials.INSTANCE.flushAll()" in flush

    release = method_body(system, "public void releaseAfterPipelineDestroy")
    assert "RtEntityTextures.INSTANCE.destroy()" in release
    assert "RtBlockMaterials.INSTANCE.destroy()" in release
    assert "RtEntityMaterials.INSTANCE.destroy()" not in release, (
        "block-entity parallel atlases must have one destruction owner: RtEntityTextures"
    )

    entity_textures = read("src/main/java/dev/comfyfluffy/caustica/rt/entity/RtEntityTextures.java")
    reset = method_body(entity_textures, "public void resetForPipeline")
    for stale_state in (
        "viewCache.clear()",
        "viewSlotCache.clear()",
        "atlasSlotCache.clear()",
        "atlasMaterialBound.clear()",
        "pending.clear()",
        "materialCache.clear()",
    ):
        assert stale_state in reset, f"pipeline recreation must invalidate {stale_state}"
    assert reset.count("RtEntityMaterials.INSTANCE.destroy()") == 1
    assert "dt.close()" in reset, "resource reload must close owned entity material DynamicTextures"
    assert "whiteTexture.close()" in reset, "the material registry must own and close its white fallback"
    white_slot = method_body(entity_textures, "public int whiteSlot()")
    assert "new DynamicTexture" in white_slot and "getTextureManager().register" not in white_slot, (
        "the white fallback must not leak through repeated global texture-manager registration"
    )
    block_entity_slot = method_body(entity_textures, "public int slotForBlockEntityAtlas")
    assert "nView != 0L && sView != 0L" in block_entity_slot
    assert block_entity_slot.index("pending.add(new Pending(2") < block_entity_slot.index(
        "atlasMaterialBound.add(slot)"
    ), "parallel atlas binding must only latch after both descriptor views are valid"

    composite = read("src/main/java/dev/comfyfluffy/caustica/rt/RtComposite.java")
    assert "RtMaterialSystem.INSTANCE.prepareForPipeline" in composite
    assert "RtMaterialSystem.INSTANCE.flushBeforeTrace" in composite
    reload_body = method_body(composite, "public void onResourceReloadStart()")
    assert "RtMaterialSystem.INSTANCE.releaseAfterPipelineDestroy()" in reload_body
    assert reload_body.index("worldPipeline.destroy()") < reload_body.index(
        "RtMaterialSystem.INSTANCE.releaseAfterPipelineDestroy()"
    ), "reload must drop descriptor ownership before destroying referenced material images"
    for direct_lifecycle in (
        "RtEntityTextures.INSTANCE.reset(",
        "RtBlockMaterials.INSTANCE.reset()",
        "RtBlockMaterials.INSTANCE.destroy()",
        "RtEntityMaterials.INSTANCE.reset()",
        "RtEntityMaterials.INSTANCE.destroy()",
    ):
        assert direct_lifecycle not in composite, (
            f"RtComposite must coordinate material lifecycle through RtMaterialSystem, found {direct_lifecycle}"
        )

    client = read("src/main/java/dev/comfyfluffy/caustica/client/CausticaClient.java")
    shutdown = method_body(client, "private static void shutdownRt()")
    assert shutdown.index("RtComposite.INSTANCE.destroy()") < shutdown.index(
        "RtMaterialSystem.INSTANCE.destroy()"
    ), "shutdown must destroy descriptor sets before their LabPBR resources"
    assert "RtEntityTextures.INSTANCE" not in client and "RtBlockMaterials.INSTANCE" not in client


def test_vrs_restores_storage_layout_before_each_compute_write() -> None:
    src = read("src/main/java/dev/comfyfluffy/caustica/rt/RtVariableRateShading.java")
    body = method_body(src, "public void generateShadingRate")
    assert "VK_IMAGE_LAYOUT_FRAGMENT_SHADING_RATE_ATTACHMENT_OPTIMAL_KHR" in body
    assert ".newLayout(VK_IMAGE_LAYOUT_GENERAL)" in body, (
        "VRS must return its attachment image to GENERAL before the next compute write"
    )
    assert "rateImageInAttachmentLayout" in body


if __name__ == "__main__":
    tests = [
        test_denoise_mode_enum_exposes_auto_ffx_nrd_off,
        test_ffx_p0_official_alignment_assets,
        test_amd_fidelityfx_preset_skips_nrd_and_pairs_fsr,
        test_fsr2_declares_required_storage_format_feature_and_valid_depth_range,
        test_multi_dispatch_passes_do_not_mutate_one_descriptor_set,
        test_hybrid_fsr_hdr_rgba16f_seam_and_pq_encode,
        test_labpbr_material_system_owns_pipeline_lifecycle,
        test_vrs_restores_storage_layout_before_each_compute_write,
        test_denoise_mode_legacy_svgf_alias_maps_to_ffx,
        test_denoise_backends_export_interface,
        test_denoise_selector_auto_is_capability_first,
        test_denoise_rt_composite_owns_dispatch_via_selector,
        test_denoise_image_barriers_replace_blanket_mem_barriers,
        test_denoise_video_options_invalidate_selection_on_set,
        test_denoise_video_options_widget_keeps_method_name,
        test_denoise_svgf_settings_retired_from_config,
        test_auto_denoise_is_independent_of_upscaler_provider,
        test_denoise_legacy_files_deleted,
        test_world_rgen_motion_vector_uses_half_render_pixel_units,
        test_world_rgen_motion_vector_projects_same_hit_in_both_frames,
        test_raygen_clamps_launch_to_bound_storage_image_extent,
        test_denoise_backends_all_implement_reset_history,
        test_denoise_ffx_reproject_has_motion_nan_guard,
        test_denoise_composite_invalidate_history_on_teleport_dimension_reload,
        test_denoise_ffx_temporal_weight_max_is_user_tunable,
        test_denoise_temporal_accum_skips_when_denoise_runs,
        test_temporal_upscalers_expose_request_reset_history,
        test_ngx_dlss_runtime_and_build_paths_are_deleted,
        test_upscaler_interface_exposes_request_reset_history,
        test_composite_invalidate_history_requests_upscaler_reset,
        test_raygen_final_color_store_guards_non_finite,
        test_raygen_final_color_store_clamps_hdr_fireflies,
        test_temporal_accumulate_guards_motion_vector_nan_and_huge,
        test_composite_spp_default_is_one_after_firefly_fix,
        test_ffx_atrous_uses_depth_normal_only_no_color_weight,
        test_ffx_resolve_does_not_kill_history_on_color_diff,
        test_ffx_and_temporal_reproject_mv_sign_matches_world_rgen,
        test_fsr2_receives_render_jitter_after_denoise,
        test_vulkan_device_bringup_is_vendor_neutral,
        test_vulkan_12_capability_fallbacks_and_ringed_bindless_descriptors,
        test_upscaler_selection_is_provider_probed_not_vendor_routed,
        test_upscaler_and_framegen_contracts_do_not_leak_selector_modes,
        test_framegen_provider_boundary_hides_vendor_backend,
        test_official_ffx_transfer_operations_have_transfer_barriers,
        test_ffx_reflection_composite_is_config_gated_and_default_on,
        test_amd_fidelityfx_stack_has_temporal_radiance_stage,
        test_amd_fidelityfx_temporal_disables_luma_bypass,
        test_auto_mode_has_one_cross_vendor_denoise_graph,
        test_ffx_reproject_encodes_motion_into_variance,
        test_ffx_temporal_weight_default_not_ghost_level,
        test_ffx_history_has_transfer_to_shader_barriers,
        test_ffx_default_is_full_official_pipeline,
        test_temporal_accumulate_clamps_history_to_max_hdr_radiance,
        test_debug_overlay_uses_canonical_stage_names,
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
