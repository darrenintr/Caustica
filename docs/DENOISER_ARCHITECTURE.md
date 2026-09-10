# Caustica Denoiser 架构 — 基岩版 RTX 对齐说明

> 写入时间:2026-07-21  
> 适用范围:`migrate/upstream-low-risk` 分支,`c1b18b3` 之后的 `denoise.mode = "auto"` (NRD) 路径

---

## 一句话总结

**Caustica 的 denoise 主路径(`mode = "auto"`)走的是 NVIDIA NRD REBLUR(diffuse + specular 分离)+ SIGMA_SHADOW,设计上对齐 Minecraft Bedrock with RTX。Whole-radiance FFX 自实现路径已被废弃,只保留 `mode = "ffx"` 模式作为 SDK 调用入口,实际跑出来等于 shadow+refl + bilateral residual 的最小可用版本。**

---

## 基岩版 RTX 实际怎么做(NVIDIA NRD README + Q2VKPT/Q2RTX 派生)

按官方 [NRD README](https://github.com/NVIDIAGameWorks/RayTracingDenoiser/blob/1762023dafce0c7b7a3d536610d1ade03ecfecc1/README.md) 描述的契约 + Christoph Schied(Q2VKPT/Q2RTX,基岩版架构源头)的公开演讲,基岩版 RTX 的 denoise 流水线是:

```
trace(primary hit)
  ├─ G-buffer  : viewZ + normal+roughness(oct-packed, R10G10B10A10) + 2.5D motion
  ├─ radiance  : split DIFFUSE / SPECULAR at primary hit (NRD 强制要求, README L173)
  └─ sky/emissive 旁路:不进入 noisy buffer,合成时直接加
↓
SIGMA_SHADOW     (0.35 ms @ 1440p,接触阴影稳定)
↓
REBLUR_DIFFUSE   (≈2.45 ms @ 1440p)
REBLUR_SPECULAR  (≈2.45 ms @ 1440p)
↓
compose(emissive + sky 旁路) → DLSS upscaling
```

**关键设计点:**

1. **radiance 必须在 primary hit 拆成 diffuse + specular**(NRD 契约硬性要求)。混在一起 denoise 会出 colored firefly + glass specular 抹平。
2. **emissive 旁路** — glowstone / 海晶灯 / 火 / 岩浆不进 noisy path,合成时直接贴 NRD 输出的 beauty 上,既免去 firefly 又减掉 NRD 工作量。
3. **sky 旁路** — 同理,REBLUR 看不到 sky 像素,不再花方差估计。
4. **SIGMA 在 REBLUR 之前** — 接触阴影(方块边缘上的硬阴影)先稳定下来,REBLUR 不会反复抹掉。

---

## Caustica 当前路径映射

| 基岩版组件 | Caustica 实现 | 文件 |
|---|---|---|
| Primary hit G-buffer (viewZ / normal / motion) | `world.rgen` + `prepare_nrd_inputs.comp` | `shaders/world/world.rgen`, `shaders/display/denoise_ffx/prepare_nrd_inputs.comp` |
| Diffuse / specular radiance split | `prepare_nrd_inputs.comp` 按材质拆分 | 同上 |
| Emissive 旁路 | `prepare_nrd_inputs.comp` 单独输出 emissive channel,合成时直接加 | 同上 |
| Sky 旁路 | `prepare_nrd_inputs.comp` 用 normal.length() 标记 sky,合成时直接透传 | 同上 |
| SIGMA_SHADOW | `NrdReBlurBackend` NRD dispatch(SIGMA 是同一 NRD 库内的一个 denoiser variant) | `src/main/java/dev/comfyfluffy/caustica/denoise/NrdReBlurBackend.java` |
| REBLUR_DIFFUSE / SPECULAR | 同上 | 同上 |
| DLSS upscale | DLSS-RR 通过 `dlss-rr` 预设;`mode = "off"` 旁路 | `RtComposite.java` |
| FSR/XeSS 替代 | DLSS 不可用时回退到 XeSS DP4a(INT/AMD)或 TAAU | `UpscalerSelector.java` |

**结论:Caustica `mode = "auto"` 的 NRD 路径在结构上就是基岩版做法,无需重写。**

---

## 已被废弃:whole-radiance FFX 自实现路径

`FfxDenoiseBackend.java` 是 whole-radiance 自实现(Q2RTX-style 三层 MV 重投影 + ASVGF 9-tap à-trous)。这条路有两个根本问题:

1. **基岩版不用** — Mojang 选了 NRD 的 D/S 分离方案,而不是 whole-radiance。
2. **dead code** — 自 commit 16287e5(2026-07-20)后,DenoiseBackendSelector 中没有任何代码路径调用 `new FfxDenoiseBackend()`。c1b18b3 重新写了它的两个 shader 文件,但调用者已经消失。
3. **AMD SDK 障碍** — 我们 bundle 的 AMD FFX 2.x modular loader 不带 denoiser effect provider(`ffxQuery` 查不到 denoiser 版本),SDK 路径不可用。

**所以**:这条代码保留但不再被 dispatch,留着作为"曾经走过的路"的历史参考。

---

## 用户配置现状

`caustica.toml` 当前:
```toml
[denoise]
mode = "AMD_FIDELITYFX"   # → 静默映射到 AUTO (NRD)
ffx-temporal-weight-max = 0.82   # dead config(仅 mode="ffx" 时使用)
ffx-reflection-composite = true # dead config
amd-fidelity-fx-residual = false # dead config
```

**现在会触发一条 warning**(commit 加在 `CausticaConfig.DenoiserKind.fromKey`):
```
denoise.mode='amd-fidelityfx' is a legacy AMD-FidelityFX whole-radiance alias; the bundled
AMD FFX 2.x modular loader has no denoiser effect provider, so this mode now resolves to
AUTO (NRD REBLUR + SIGMA — Bedrock-RTX-aligned). To silence this warning, set
denoise.mode='auto' (recommended) or 'off'.
```

**建议把 `denoise.mode = "AMD_FIDELITYFX"` 改成 `denoise.mode = "auto"`** — 行为不变,只是把"以为自己开了 FFX 实际跑 NRD"这件事摆到台面上。

---

## 性能调优(基岩版基准)

基岩版 RTX 在 RTX 3060 @ 1080p 用 SPP=1 跑得动,这是 NRD REBLUR 的设计点("low rpp signal",README L7)。Caustica 当前默认 `spp = 2`(v0.5.3 firefly fix 后),已经接近基岩版数字。

**如果性能是瓶颈**(常见于 RDNA2 / Intel Arc):
- `spp = 1` + 提高 `temporal-alpha`(默认 0.35) → NRD REBLUR 会用更长历史弥补单帧噪声
- `dlss-rr.enabled = true, preset = 0`(Performance) → 在 540p 起渲,放大到目标分辨率
- `temporal-accum = false` 已经是对的,不要打开(会和 NRD 双重 temporal)

**如果画质优先**:
- `spp = 4`(用户当前值)— NRD REBLUR 在 4 spp 下稳定但单帧开销 4x,适合静态场景
- `dlss-rr.enabled = true, quality = 2` (Quality)