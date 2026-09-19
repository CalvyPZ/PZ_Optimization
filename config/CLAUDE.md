# config/

MangoHud 0.8.4 profiles passed with `harness/run.sh --mangohud-config`.

| File | Use |
|---|---|
| `mangohud-benchmark.conf` | measurement runs: CSV log, `control=mangoapp` |
| `mangohud-showcase.conf` | big HUD (font 64), top-center, offset_y=560 = screen middle, gpu_list=0 |
| `mangohud-showcase-graph.conf` | frame_timing + frame_timing_detailed + `dynamic_frame_timing` (Diego's "variable frame time graph"); graph height is hard-coded in 0.8.4 |
| `mangohud-showcase-stock.conf` / `-opt.conf` | left/right labelled variants for the quad video; right-anchored positions need a NEGATIVE offset_x (-48), left-anchored +48 |
| `mangohud-flip.conf` | swap-probe experiments |

Quirks:
- For this LWJGL/GL game the HUD only appears when `libMangoHud_opengl.so` is LD_PRELOADed
  and, on direct launches, Steam's `ubuntu12_64/gameoverlayrenderer.so` is preloaded ahead of
  it. `MANGOHUD=1` alone does nothing on NVIDIA GL. The dlsym shim (`PZOPT_MANGOHUD_LIB=shim`)
  deadlocks the JNI launcher at start-up. Never use it.
- With `--renderer zink` MangoHud comes through its Vulkan layer (`MANGOHUD=1` only, no GL
  preload; both hooks at once killed the game).
- MangoHud blacklists processes named `java`; probes run under a copied launcher name.
- `LC_NUMERIC=C` is required or the fps_metrics 1 % / 0.1 % rows vanish.
- The CSV is written only if the log is stopped while the game is alive; run.sh handles this.
- `gpu_load` reads 0 for this game; GPU numbers come from `harness/sysmon.sh`.
