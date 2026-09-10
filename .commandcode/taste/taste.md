# Taste

- Prefers vendor-neutral ray tracing that runs on RDNA2/RDNA3/Intel Arc/Steam Deck/Mesa RADV; rejects assuming NVIDIA-only SER, OMM, memory compression, or RTX 40-class divergence tolerance. Confidence: 0.9
- Prefers VRAM/stability-first scaling for path tracing (packed history formats over RGBA16F, quarter-resolution tiled ReSTIR reservoirs, static BLAS with instance-only TLAS updates, entity budgets with raster fallback, ray-type sorted dispatches, always-on geometry cache) over full-resolution reservoirs, multi-bounce GI, ray reconstruction, and frame generation. Confidence: 0.85
- Prefers standardizing on NRD for denoising and TAAU for upscaling and pushing that single pair to extreme quality over maintaining breadth across multiple denoisers/upscalers. Confidence: 0.85
- Prefers quantitative, data-driven diagnosis of visual artifacts (per-stage metrics/stats/CSV over subjective eyeballing) when debugging and testing rendering pipelines. Confidence: 0.85
- Requires a two-step confirm gate when enabling features with heavy side effects (warn about large data / perf cost, safe-off default, explicit "I know what I'm doing" acknowledgement vs cancel). Confidence: 0.8
