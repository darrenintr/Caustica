#!/usr/bin/env python3
"""Analyze rt-probe/probe.csv and flag out-of-range stages.

Usage:
    python3 scripts/analyze_probe.py <gameDir>/rt-probe/probe.csv

Reads the per-stage plate moments recorded by RtProbe and prints a diagnosis:
which stage is starved / blown out / NaN-poisoned / frozen, with concrete
thresholds. Feed it the CSV from a "looks wrong" session plus a screenshot
taken at the same time.

Two CSV sections: beauty/history plates (raw/firefly/denoise/up/motion/disocc)
and lighting plates (diffuse/reflection/unshadowed/emission/transmission/
shadowHit/viewZ/exposure/NRD-out) plus sky/light scalars (sunY/dayFactor/
lightRGB, light counts, spp/bounces, camera).

Old 6-plate CSVs are still accepted: lighting columns default to NaN and the
lighting checks are skipped.

Exit 0 = all stages in range, 1 = at least one finding, 2 = bad input.
"""
import csv
import math
import sys

COLS = [
    "frame", "width", "height", "renderW", "renderH", "jitterX", "jitterY",
    "denoiseOn", "denoisePath", "upscalerPath", "upscalerOk",
    "rawMean", "rawVar", "rawMax", "rawNaN",
    "fireflyMean", "fireflyVar", "fireflyMax", "fireflyNaN",
    "denoiseMean", "denoiseVar", "denoiseMax", "denoiseNaN",
    "upMean", "upVar", "upMax", "upNaN",
    "mvMeanPx", "mvVar", "mvMaxPx", "mvNaN",
    "disoccMean", "disoccVar", "disoccMax", "disoccNaN",
]

LIGHT_COLS = [
    "diffMean", "diffVar", "diffMax", "diffNaN",
    "reflMean", "reflVar", "reflMax", "reflNaN",
    "unshadowMean", "unshadowVar", "unshadowMax", "unshadowNaN",
    "emissMean", "emissVar", "emissMax", "emissNaN",
    "transmMean", "transmVar", "transmMax", "transmNaN",
    "shadowHitMean", "shadowHitVar", "shadowHitMax", "shadowHitNaN",
    "viewZMean", "viewZVar", "viewZMax", "viewZNaN",
    "exposMean", "exposVar", "exposMax", "exposNaN",
    "nrdDiffMean", "nrdDiffVar", "nrdDiffMax", "nrdDiffNaN",
    "nrdSpecMean", "nrdSpecVar", "nrdSpecMax", "nrdSpecNaN",
    "nrdShadowMean", "nrdShadowVar", "nrdShadowMax", "nrdShadowNaN",
]

SCALAR_COLS = [
    "sunY", "dayFactor", "lightR", "lightG", "lightB",
    "blockLightCount", "dynLightCount", "uploadCount", "lightRev",
    "spp", "bounces", "camX", "camY", "camZ",
]


def load(path):
    with open(path, newline="") as f:
        reader = csv.DictReader(f)
        fields = reader.fieldnames or []
        missing = [c for c in COLS if c not in fields]
        if missing:
            print(f"ERROR: missing columns: {missing}", file=sys.stderr)
            sys.exit(2)
        have_light = all(c in fields for c in LIGHT_COLS)
        have_scalars = all(c in fields for c in SCALAR_COLS)
        if not have_light:
            print("note: old 6-plate CSV (no lighting columns) — lighting checks skipped")
        rows = []
        for r in reader:
            try:
                row = {c: (r[c] if c in ("denoisePath",) else float(r[c])) for c in COLS}
                for c in LIGHT_COLS + SCALAR_COLS:
                    row[c] = float(r[c]) if c in fields else float("nan")
                rows.append(row)
            except ValueError as e:
                print(f"WARN: skipping malformed row: {e}", file=sys.stderr)
        return rows, have_light, have_scalars


def pct(vals, q):
    if not vals:
        return float("nan")
    s = sorted(vals)
    return s[min(len(s) - 1, int(q * len(s)))]


def main():
    if len(sys.argv) != 2:
        print(__doc__)
        sys.exit(2)
    rows, have_light, have_scalars = load(sys.argv[1])
    if not rows:
        print("ERROR: no data rows", file=sys.stderr)
        sys.exit(2)
    print(f"probe frames: {len(rows)} (frame {rows[0]['frame']:.0f}..{rows[-1]['frame']:.0f})")
    findings = []

    def col(name):
        return [r[name] for r in rows if math.isfinite(r[name])]

    # --- NaN poisoning (any stage, any frame) ---
    for stage in ("raw", "firefly", "denoise", "up"):
        bad = [r for r in rows if r[f"{stage}NaN"] > 0]
        if bad:
            frames = ",".join(f"{r['frame']:.0f}" for r in bad[:8])
            findings.append(
                f"[NaN] {stage} plate has NaN/Inf pixels on {len(bad)}/{len(rows)} frames "
                f"(e.g. frame {frames}). Upstream of this stage is writing garbage — "
                f"check the previous stage's NaN guard, not the denoiser.")
    if have_light:
        for stage in ("diff", "refl", "unshadow", "emiss", "transm", "nrdDiff", "nrdSpec"):
            key = f"{stage}NaN" if stage != "unshadow" else "unshadowNaN"
            bad = [r for r in rows if math.isfinite(r[key]) and r[key] > 0]
            if bad:
                frames = ",".join(f"{r['frame']:.0f}" for r in bad[:8])
                findings.append(
                    f"[NaN] {stage} lighting plate has NaN/Inf pixels on {len(bad)}/{len(rows)} "
                    f"frames (e.g. frame {frames}). world.rgen is writing garbage into that "
                    f"split channel — check the rgen write site, not the denoiser.")

    # --- Firefly kill effectiveness: rawMax vs fireflyMax ---
    raw_max = pct(col("rawMax"), 0.5)
    ff_max = pct(col("fireflyMax"), 0.5)
    if math.isfinite(raw_max) and math.isfinite(ff_max) and raw_max > 0.5:
        ratio = ff_max / max(raw_max, 1e-6)
        print(f"firefly-kill: median rawMax={raw_max:.2f} fireflyMax={ff_max:.2f} (ratio {ratio:.2f})")
        if ratio > 0.9 and raw_max > 2.0:
            findings.append(
                f"[FIREFLY] firefly-kill is a no-op (ratio {ratio:.2f} with rawMax {raw_max:.2f}): "
                f"SPP=1 spikes reach NRD/TAAU history untouched. Check firefly_kill thresholds.")
    else:
        print(f"firefly-kill: median rawMax={raw_max:.2f} (dim scene, nothing to kill)")

    # --- Denoise energy check: does NRD change anything? ---
    ff_mean = pct(col("fireflyMean"), 0.5)
    dn_mean = pct(col("denoiseMean"), 0.5)
    ff_var = pct(col("fireflyVar"), 0.5)
    dn_var = pct(col("denoiseVar"), 0.5)
    denoise_on = sum(1 for r in rows if r["denoiseOn"] > 0.5)
    print(f"denoise: on {denoise_on}/{len(rows)} frames; "
          f"firefly mean/var={ff_mean:.4f}/{ff_var:.5f} → denoise mean/var={dn_mean:.4f}/{dn_var:.5f}")
    if denoise_on > len(rows) // 2 and math.isfinite(ff_var) and math.isfinite(dn_var):
        if dn_var > ff_var * 0.95 and ff_var > 1e-6:
            findings.append(
                f"[DENOISE-NOP] NRD runs but variance barely moves "
                f"({ff_var:.5f}→{dn_var:.5f}): history is being rejected every frame "
                f"(check disoccMean/mvMax below) or the dispatch silently fails.")
        if abs(dn_mean - ff_mean) / max(ff_mean, 1e-4) > 0.5 and ff_mean > 1e-3:
            findings.append(
                f"[DENOISE-GAIN] NRD shifts mean luma by >50% "
                f"({ff_mean:.4f}→{dn_mean:.4f}): demod/remod floors asymmetric or "
                f"firefly clamp eating real light. Check prepare_nrd_inputs floors.")

    # --- Upscale sanity: mean should track denoise, variance should not explode ---
    up_mean = pct(col("upMean"), 0.5)
    up_var = pct(col("upVar"), 0.5)
    print(f"upscale: denoise mean/var={dn_mean:.4f}/{dn_var:.5f} → up mean/var={up_mean:.4f}/{up_var:.5f}")
    if math.isfinite(dn_mean) and math.isfinite(up_mean) and dn_mean > 1e-3:
        if abs(up_mean - dn_mean) / dn_mean > 0.35:
            findings.append(
                f"[UPSCALE-GAIN] TAAU shifts mean luma by >35% "
                f"({dn_mean:.4f}→{up_mean:.4f}): YCoCg round-trip or blend bug, "
                f"not a denoise problem.")
    if math.isfinite(dn_var) and math.isfinite(up_var) and dn_var > 1e-7:
        if up_var > dn_var * 4.0:
            findings.append(
                f"[UPSCALE-NOISE] TAAU quadruples variance ({dn_var:.5f}→{up_var:.5f}): "
                f"history rejected every frame (alpha≈1) or variance-clip gamma too tight.")

    # --- Motion / disocclusion: is history even allowed? ---
    mv_max = pct(col("mvMaxPx"), 0.5)
    mv_mean = pct(col("mvMeanPx"), 0.5)
    dis_mean = pct(col("disoccMean"), 0.5)
    print(f"motion: median mvMean={mv_mean:.2f}px mvMax={mv_max:.1f}px; disoccMean={dis_mean:.3f}")
    mv_nan = sum(1 for r in rows if r["mvNaN"] > 0)
    if mv_nan:
        findings.append(
            f"[MV-NAN] motion guide has NaN on {mv_nan}/{len(rows)} frames: "
            f"every reproject (NRD + TAAU + motion_disocclusion) is sampling garbage.")
    if math.isfinite(mv_mean) and mv_mean < 2.0 and math.isfinite(dis_mean) and dis_mean > 0.5:
        findings.append(
            f"[DISOC-HIGH] camera nearly static (mvMean {mv_mean:.2f}px) but disocclusion "
            f"mix is {dis_mean:.2f}: motion_disocclusion is over-rejecting (MV-unit bug?) "
            f"and starving BOTH NRD and TAAU of history. Plate will look raw.")
    if math.isfinite(mv_max) and mv_max > 80.0:
        print(f"  note: mvMax {mv_max:.0f}px — fast motion/teleport frames present; "
              f"expect per-frame disocclusion spikes there.")

    # --- Frozen history: variance collapses to ~0 across stages ---
    for stage in ("denoise", "up"):
        v = pct(col(f"{stage}Var"), 0.5)
        if math.isfinite(v) and v < 1e-9:
            means = col(f"{stage}Mean")
            spread = max(means) - min(means) if means else 0.0
            if spread < 1e-6:
                findings.append(
                    f"[FROZEN] {stage} plate variance is 0 across ALL probe frames: "
                    f"the stage is outputting a constant (cleared-but-never-written "
                    f"image, or a stuck history). Check dispatches/barriers.")

    if have_light:
        light_findings(rows, col, findings, have_scalars)

    print()
    if not findings:
        print("OK: all stages in range. If the image still looks wrong, it is a")
        print("tonemap/exposure/display-mapping issue (post-upscale), not NRD/TAAU.")
        return 0
    print(f"{len(findings)} finding(s):")
    for i, f in enumerate(findings, 1):
        print(f"\n{i}. {f}")
    return 1


def light_findings(rows, col, findings, have_scalars):
    def med(name):
        return pct(col(name), 0.5)

    diff_mean, diff_var, diff_max = med("diffMean"), med("diffVar"), med("diffMax")
    refl_mean, refl_var = med("reflMean"), med("reflVar")
    un_mean, un_var, un_max = med("unshadowMean"), med("unshadowVar"), med("unshadowMax")
    emis_mean, emis_max = med("emissMean"), med("emissMax")
    transm_mean = med("transmMean")
    sh_mean, sh_max = med("shadowHitMean"), med("shadowHitMax")
    vz_mean, vz_max, vz_nan = med("viewZMean"), med("viewZMax"), med("viewZNaN")
    ex_mean, ex_max = med("exposMean"), med("exposMax")
    nd_mean, nd_var = med("nrdDiffMean"), med("nrdDiffVar")
    ns_mean, ns_var = med("nrdSpecMean"), med("nrdSpecVar")
    nsh_mean = med("nrdShadowMean")
    print(f"light-split: diffuse mean/var/max={diff_mean:.4f}/{diff_var:.5f}/{diff_max:.2f}; "
          f"reflection mean/var={refl_mean:.4f}/{refl_var:.5f}; "
          f"unshadowed mean/var/max={un_mean:.4f}/{un_var:.5f}/{un_max:.2f}")
    print(f"light-split: emission mean/max={emis_mean:.4f}/{emis_max:.2f}; "
          f"transmission mean={transm_mean:.4f}; shadowHit mean/max={sh_mean:.3f}/{sh_max:.3f}")
    print(f"guides: viewZ mean/max={vz_mean:.1f}/{vz_max:.1f}m; exposure mean/max={ex_mean:.3f}/{ex_max:.3f}")
    print(f"nrd-out: diff mean/var={nd_mean:.4f}/{nd_var:.5f}; "
          f"spec mean/var={ns_mean:.4f}/{ns_var:.5f}; shadow mean={nsh_mean:.4f}")

    # --- Split-energy conservation: does denoise preserve what rgen produced? ---
    if all(math.isfinite(v) for v in (diff_mean, refl_mean, un_mean, emis_mean, nd_mean, ns_mean)):
        rgen_total = diff_mean + refl_mean + emis_mean
        nrd_total = nd_mean + ns_mean
        # unshadowedDirect is the pre-visibility direct term (already inside diffuse
        # once shadowed); exclude it from the conservation sum to avoid double count.
        if rgen_total > 1e-3:
            drift = abs(nrd_total - rgen_total) / rgen_total
            print(f"  split-energy: rgen(diff+refl+emiss)={rgen_total:.4f} "
                  f"vs nrd-out(diff+spec)={nrd_total:.4f} (drift {drift:.1%})")
            if drift > 0.5:
                findings.append(
                    f"[SPLIT-DRIFT] NRD output energy differs from rgen split sum by "
                    f"{drift:.0%} ({rgen_total:.4f}→{nrd_total:.4f}): demod/remod albedo "
                    f"mismatch or a split channel silently zero. Check which of "
                    f"diff/refl/emiss collapsed vs the NRD-out pair.")
        # unshadowed ≈ 0 while diffuse > 0 means the sun path died but GI survived.
        if un_mean < 1e-4 and diff_mean > 1e-3:
            findings.append(
                f"[SUN-STARVED] unshadowedDirect is ~0 ({un_mean:.5f}) but diffuse carries "
                f"energy ({diff_mean:.4f}): the NEE sun path contributes nothing — check "
                f"sunY/lightRGB below (night?) or the shadow-ray miss handler in world.rgen.")

    # --- Emission vs shadow: emissive-heavy scene with fully shadowed sun ---
    if math.isfinite(emis_max) and math.isfinite(sh_mean) and emis_max > 1.0 and sh_mean < 0.05:
        findings.append(
            f"[EMISSIVE-DOM] emission peaks at {emis_max:.1f} while shadowHit mean is "
            f"{sh_mean:.3f}: the frame is lit almost entirely by emissives (cave/night "
            f"interior?). If ReSTIR DI shows few lights below, the reservoir is starved.")

    # --- Transmission without content ---
    if math.isfinite(transm_mean) and transm_mean < 1e-5:
        print("  note: transmission mean is ~0 — no glass/water in view this session, "
              "or the transmission write site never fires.")

    # --- viewZ sanity: max depth bounds the scene, NaN kills NRD reconstruct ---
    if math.isfinite(vz_nan) and vz_nan > 0:
        bad = sum(1 for r in rows if math.isfinite(r["viewZNaN"]) and r["viewZNaN"] > 0)
        findings.append(
            f"[VIEWZ-NAN] viewZ guide has NaN on {bad}/{len(rows)} frames: NRD position "
            f"reconstruction + TAAU depth reject are both compromised. Check the rgen "
            f"viewZ write (miss path must write far, not NaN).")
    if math.isfinite(vz_max) and vz_max > 0 and vz_max < 2.0:
        print(f"  note: viewZ max is only {vz_max:.1f}m — staring at a wall up close? "
              f"Diffuse will look flat regardless of denoiser quality.")

    # --- Exposure sanity ---
    if math.isfinite(ex_mean) and (ex_mean < 0.05 or ex_mean > 20.0):
        findings.append(
            f"[EXPOSURE] exposure scale is {ex_mean:.3f} (sane range ~0.05..20): the "
            f"auto-exposure histogram is stuck or diverged. Image will look "
            f"{'pitch black' if ex_mean < 0.05 else 'blown out'} no matter what NRD does.")

    # --- Sky / light scalar cross-checks ---
    if have_scalars:
        sun_y = pct(col("sunY"), 0.5)
        day = pct(col("dayFactor"), 0.5)
        lr, lg, lb = pct(col("lightR"), 0.5), pct(col("lightG"), 0.5), pct(col("lightB"), 0.5)
        n_block = pct(col("blockLightCount"), 0.5)
        n_dyn = pct(col("dynLightCount"), 0.5)
        n_up = pct(col("uploadCount"), 0.5)
        light_luma = 0.2126 * lr + 0.7152 * lg + 0.0722 * lb if all(
            math.isfinite(v) for v in (lr, lg, lb)) else float("nan")
        print(f"sky: sunY={sun_y:.2f} dayFactor={day:.2f} lightRGB=({lr:.2f},{lg:.2f},{lb:.2f}); "
              f"lights: block={n_block:.0f} dyn={n_dyn:.0f} uploaded={n_up:.0f}")
        if math.isfinite(sun_y) and sun_y > 0.1 and math.isfinite(light_luma):
            if light_luma < 0.5 and math.isfinite(un_mean) and un_mean < 1e-3:
                findings.append(
                    f"[SKY-DIM] sun is up (sunY {sun_y:.2f}) but NEE light luma is only "
                    f"{light_luma:.2f} and unshadowedDirect is empty: the atmosphere "
                    f"transmittance path is under-delivering (check handoff fade/sunPeak), "
                    f"not the denoiser.")
            if math.isfinite(sh_mean) and sh_mean < 0.02 and light_luma > 1.0:
                findings.append(
                    f"[SHADOW-STARVED] strong sun (luma {light_luma:.1f}) but shadowHit mean "
                    f"is {sh_mean:.3f}: every NEE shadow ray reports occluded. Check the "
                    f"shadow-ray t-min/terminator bias or a stale TLAS, not NRD.")
        if math.isfinite(n_up) and math.isfinite(n_block) and math.isfinite(n_dyn):
            if n_up < min(n_block + n_dyn, 2048) - 1 and (n_block + n_dyn) > 0:
                findings.append(
                    f"[LIGHT-DROP] {n_block:.0f} block + {n_dyn:.0f} dyn lights known but only "
                    f"{n_up:.0f} uploaded: the 2048-cap merge or revision gating is dropping "
                    f"lights. Block-light buffer upload changed flag may be stuck.")
            if n_block + n_dyn == 0 and math.isfinite(emis_max) and emis_max > 0.5:
                findings.append(
                    f"[LIGHT-MISS] emission peaks at {emis_max:.1f} but zero lights are "
                    f"tracked: UnifiedLightManager is not harvesting the emissives the "
                    f"path tracer sees (scan radius / dirty-block refresh?). ReSTIR DI "
                    f"has nothing to resample.")


if __name__ == "__main__":
    sys.exit(main())
