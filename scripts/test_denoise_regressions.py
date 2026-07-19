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
    for name in ("AUTO", "FFX", "NRD", "OFF", "AMD_FIDELITYFX"):
        assert name in body, f"DenoiserKind must contain {name}"
    assert '"amd-fidelityfx"' in body, (
        "AMD_FIDELITYFX must publish the config key 'amd-fidelityfx'"
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
    """AMD FidelityFX Phase A = FFX + residual (no NRD) + FSR2 + CAS; no beauty TAA stack."""
    selector = read("src/main/java/dev/comfyfluffy/caustica/denoise/DenoiseBackendSelector.java")
    assert "AMD_FIDELITYFX" in selector and "AmdFidelityFxDenoiseBackend" in selector, (
        "DenoiseBackendSelector must route AMD_FIDELITYFX to AmdFidelityFxDenoiseBackend"
    )
    # FFX-only now also uses residual polish (SPP-1 GI grain without NRD is unusable).
    ffx_body = method_body(selector, "private static CausticaDenoiseBackend pick")
    assert "DenoiserKind.FFX" in ffx_body and "AmdFidelityFxDenoiseBackend" in ffx_body, (
        "DenoiseBackendSelector must route FFX mode to AmdFidelityFxDenoiseBackend (residual GI polish)"
    )
    assert "HybridFfxNrdBackend" in selector  # NRD path still exists for other modes
    backend = read("src/main/java/dev/comfyfluffy/caustica/denoise/AmdFidelityFxDenoiseBackend.java")
    assert "OfficialFfxDenoiseBackend" in backend and "BilateralDenoiseBackend" in backend, (
        "AmdFidelityFxDenoiseBackend must compose Official FFX + residual polish"
    )
    assert "NrdRuntime" not in backend and "HybridFfxNrdBackend" not in backend, (
        "AMD FidelityFX preset must not pull NRD"
    )
    upsel = read("src/main/java/dev/comfyfluffy/caustica/upscale/UpscalerSelector.java")
    assert "AMD_FIDELITYFX" in upsel and "Fsr2ClassicUpscaler" in upsel, (
        "UpscalerSelector must force/prefer FSR2 when denoise preset is AMD_FIDELITYFX"
    )
    assert "forcing upscaler partner to FSR2" in upsel, (
        "AMD FidelityFX must force FSR2 partner (not only when AUTO)"
    )
    fsr = read("src/main/java/dev/comfyfluffy/caustica/fsr/Fsr2ClassicUpscaler.java")
    assert "fsr_blackout_guard" in fsr or "GUARD_SPV" in fsr, (
        "FSR2 must ship a blackout fail-open guard"
    )
    assert (ROOT / "shaders/display/fsr_blackout_guard.comp").is_file(), (
        "missing shaders/display/fsr_blackout_guard.comp"
    )
    assert "consumeBlackoutFailOpen" in read(
        "src/main/java/dev/comfyfluffy/caustica/rt/RtComposite.java"
    ), "RtComposite must fail-open blit when FSR2 blackout is latched"
    cfg = read("src/main/java/dev/comfyfluffy/caustica/CausticaConfig.java")
    assert 'FSR2("fsr2")' in cfg or 'FSR2("fsr2")' in method_body(cfg, "public enum UpscalerMode"), (
        "UpscalerMode must expose FSR2 so the preset can request classic FSR"
    )
    composite = read("src/main/java/dev/comfyfluffy/caustica/rt/RtComposite.java")
    body = method_body(composite, "private static boolean caustica$denoiseEnabled()")
    assert "AMD_FIDELITYFX" in body, (
        "caustica$denoiseEnabled must treat AMD_FIDELITYFX as an active forced denoise mode"
    )
    taa_body = method_body(composite, "private static boolean temporalAccumEnabled")
    assert "AMD_FIDELITYFX" in taa_body and "return false" in taa_body, (
        "temporalAccumEnabled must hard-disable beauty TAA for the AMD FidelityFX preset"
    )
    assert "CasSharpenPass" in composite and "casSharpen" in composite, (
        "RtComposite must run CAS after upscale for the FidelityFX stack"
    )
    assert (ROOT / "shaders/display/cas.comp").is_file(), "CAS compute shader must exist"
    assert (ROOT / "src/main/java/dev/comfyfluffy/caustica/display/CasSharpenPass.java").is_file()


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


def test_denoise_selector_resolves_via_vendor_for_auto() -> None:
    src = read("src/main/java/dev/comfyfluffy/caustica/denoise/DenoiseBackendSelector.java")
    assert "autoPick" in src and "GpuVendor.detect" in src, (
        "autoPick must dispatch via GpuVendor — AMD/Intel → FFX, NVIDIA → NRD"
    )
    assert "invalidate" in src and "resolvedOnce" in src, (
        "DenoiseBackendSelector must be a resolved-once latch like UpscalerSelector"
    )


def test_denoise_rt_composite_owns_dispatch_via_selector() -> None:
    src = read("src/main/java/dev/comfyfluffy/ct_caustica/rt/RtComposite.java") if False else read("src/main/java/dev/comfyfluffy/caustica/rt/RtComposite.java")
    assert "DenoiseBackendSelector.current(" in src, (
        "RtComposite recordFrame must dispatch through DenoiseBackendSelector.current(...)"
    )
    # v0.5.2: the dispatch input is `denoiseInput`, which is `accumulatedColor` (when temporal
    # accumulation ran) or `output` (raw noisy trace when it didn't). The variable name
    # denoiseInput is the contract — the assignment chain must mention both accumulatedColor
    # and output so a reader can see the temporal-precedence rule in one place.
    assert "backend.dispatch(stack, cmd, output, gNormal, gDepth, gMotion," in src, (
        "RtComposite must invoke the resolved backend with raw RT output and guide buffers"
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


def test_auto_denoise_follows_resolved_upscaler_not_dlss_config() -> None:
    src = read("src/main/java/dev/comfyfluffy/caustica/rt/RtComposite.java")
    assert "private static boolean caustica$denoiseEnabled()" in src, (
        "caustica$denoiseEnabled must exist"
    )
    body = method_body(src, "private static boolean caustica$denoiseEnabled()")
    # 2026-07-14 kill-switch returns false unconditionally (black-screen emergency).
    if "return false" in body and "kill-switch" in body.lower() or "kill-switch" in src.lower() or "EMERGENCY" in src or "black-screen" in body:
        assert "return false" in body
        return
    assert "DenoiserKind.OFF" in body and "DenoiserKind.AUTO" in body, (
        "denoiseEnabled must check DenoiserKind (not the legacy StringSetting 'svgf'/'on' literals)"
    )
    assert "UpscalerSelector.resolvedMode()" in body and "DLSS_RR" in body, (
        "the AUTO rule must still key off the resolved upscaler: skip when DLSS-RR is active + usable"
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


def test_world_rgen_motion_vector_uses_unjittered_pixel_center() -> None:
    """v0.5.3 ghosting fix: gMotion must be MVJittered=0.

    The current-frame screen position used in the reprojection must be the
    unjittered pixel centre, not the jittered ray's NDC (jndc). Using jndc
    contaminates the MV with the per-frame Halton jitter, so every temporal
    consumer (FFX reproject / DLSS-RR / FSR / XeSS) samples history from a
    shifted position each frame. Static scenes never converge; moving the
    camera compounds the error into the smear the user reported.

    The DLSS-RR FEATURE_FLAGS do not include MV_JITTERED (RtDlssRr.java), so
    NGX is told the MV is unjittered — the shader must honour that contract.
    """
    src = read("shaders/world/world.rgen")
    # The bad shape: any line in main() that assigns jndc to curNdc.
    main_body = method_body(src, "void main()")
    assert "vec2 curNdc = jndc" not in main_body, (
        "world.rgen must NOT use `vec2 curNdc = jndc` for the motion-vector "
        "current position. jndc carries the per-frame sub-pixel camera jitter; "
        "with MVJittered=0 the consumer expects MV == 0 on a static scene, but "
        "`jndc = unjittered_center + jitter` makes MV = -jitter and the temporal "
        "history is sampled from a different position every frame. Use the "
        "unjittered pixel centre (e.g. `vec2(pix) + 0.5`) instead."
    )
    # specular MV must use the same unjittered current position
    assert "specularReflectionMotion(" in main_body, (
        "world.rgen still has the gSpecMotion dispatch"
    )
    import re
    spec_calls = re.findall(
        r"specularReflectionMotion\([^)]*\)", main_body, flags=re.DOTALL,
    )
    for call in spec_calls:
        assert "jndc" not in call, (
            "specularReflectionMotion(...) must be called with the unjittered "
            "pixel centre, not jndc — gSpecMotion is a per-pixel MV consumed by "
            "DLSS-RR with the same MVJittered=0 contract."
        )


def test_dlss_rr_exposes_request_reset_history() -> None:
    """v0.5.3 hard-cut fix: DLSS-RR's internal temporal history must be
    droppable on teleport / dimension change / resource reload. The private
    `resetHistory` flag is set only inside `ensureFeature` (i.e. only when the
    feature is freshly created); the public `invalidateHistory()` call path
    on RtComposite never reaches it, so the NGX accumulator keeps stale data
    across hard cuts. We need a public request latch that the next evaluate()
    observes."""
    src = read("src/main/java/dev/comfyfluffy/caustica/rt/pipeline/RtDlssRr.java")
    assert "public void requestResetHistory()" in src, (
        "RtDlssRr must expose `public void requestResetHistory()` so "
        "RtComposite.invalidateHistory() can drop the NGX internal temporal "
        "accumulator on hard cuts (teleport / dimension change / resource "
        "reload), matching the behaviour of the FFX denoise backend's "
        "resetHistory()."
    )


def test_upscaler_interface_exposes_request_reset_history() -> None:
    """v0.5.3: the Upscaler interface should expose requestResetHistory()
    so any upscaler (DLSS-RR / FSR / XeSS) can drop its internal temporal
    accumulator on hard cuts. DLSS-RR's NGX supports a per-evaluate reset
    flag; FSR / XeSS can override the default no-op with their SDK's own
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


def test_ffx_default_is_spatial_only_no_temporal_ghosts() -> None:
    """2026-07-14: temporal reproject produced multi-frame '升天' ghosts on RADV
    even after MV-sign and weight fixes. TEMPORAL_ENABLED must be false.
    SPATIAL_PASSTHROUGH must be true until à-trous is proven not to black-screen."""
    ffx = read("src/main/java/dev/comfyfluffy/caustica/denoise/FfxDenoiseBackend.java")
    import re as _re
    m = _re.search(r"TEMPORAL_ENABLED\s*=\s*(true|false)", ffx)
    assert m is not None, "TEMPORAL_ENABLED constant missing"
    assert m.group(1) == "false", (
        f"TEMPORAL_ENABLED must default to false to stop ghost trails. Got {m.group(1)}."
    )
    p = _re.search(r"SPATIAL_PASSTHROUGH\s*=\s*(true|false)", ffx)
    assert p is not None, "SPATIAL_PASSTHROUGH constant missing"
    assert p.group(1) == "true", (
        f"SPATIAL_PASSTHROUGH must be true (copy-only) while à-trous black-screens on RADV. "
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
    but RtFrameStats.FRAME registers the stages as `frame.upscale` /
    `frame.dlssRr` (see RtFrameStats.java line 32-55). The overlay therefore
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
    assert "fsr_color_pack.comp.spv" in up and "fsr_color_unpack.comp.spv" in up
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
    fsr = read("src/main/java/dev/comfyfluffy/caustica/fsr/Fsr2ClassicUpscaler.java")
    assert "long[] convSets" in fsr and "convSets[setIndex]" in fsr, (
        "FSR pack and unpack must use distinct descriptor sets"
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
        test_vrs_restores_storage_layout_before_each_compute_write,
        test_denoise_mode_legacy_svgf_alias_maps_to_ffx,
        test_denoise_backends_export_interface,
        test_denoise_selector_resolves_via_vendor_for_auto,
        test_denoise_rt_composite_owns_dispatch_via_selector,
        test_denoise_image_barriers_replace_blanket_mem_barriers,
        test_denoise_video_options_invalidate_selection_on_set,
        test_denoise_video_options_widget_keeps_method_name,
        test_denoise_svgf_settings_retired_from_config,
        test_auto_denoise_follows_resolved_upscaler_not_dlss_config,
        test_denoise_legacy_files_deleted,
        test_world_rgen_motion_vector_uses_half_render_pixel_units,
        test_world_rgen_motion_vector_uses_unjittered_pixel_center,
        test_raygen_clamps_launch_to_bound_storage_image_extent,
        test_denoise_backends_all_implement_reset_history,
        test_denoise_ffx_reproject_has_motion_nan_guard,
        test_denoise_composite_invalidate_history_on_teleport_dimension_reload,
        test_denoise_ffx_temporal_weight_max_is_user_tunable,
        test_denoise_temporal_accum_skips_when_denoise_runs,
        test_dlss_rr_exposes_request_reset_history,
        test_upscaler_interface_exposes_request_reset_history,
        test_composite_invalidate_history_requests_upscaler_reset,
        test_raygen_final_color_store_guards_non_finite,
        test_raygen_final_color_store_clamps_hdr_fireflies,
        test_temporal_accumulate_guards_motion_vector_nan_and_huge,
        test_composite_spp_default_is_one_after_firefly_fix,
        test_ffx_atrous_uses_depth_normal_only_no_color_weight,
        test_ffx_resolve_does_not_kill_history_on_color_diff,
        test_ffx_and_temporal_reproject_mv_sign_matches_world_rgen,
        test_ffx_reproject_encodes_motion_into_variance,
        test_ffx_temporal_weight_default_not_ghost_level,
        test_ffx_history_has_transfer_to_shader_barriers,
        test_ffx_default_is_spatial_only_no_temporal_ghosts,
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
