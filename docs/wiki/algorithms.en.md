# Modes, algorithms, and hysteresis

[简体中文](algorithms.md) · [Wiki](README.en.md)

Every list includes `default`, which preserves the existing path. New overrides default off. Grayscale and binary choices are separate; video is stored independently. Choosing quantization does not switch modes, temperature bands, or waveform counts.

| Scope | External mode | Non-default IDs | Baseline | Independent hysteresis |
| --- | --- | --- | --- | --- |
| `clear` | 2 | `stock`, `nearest`, `bayer16` | Existing Clear path | None |
| `reading` | 3; compatible 4/5 | `stock`, `nearest`, `bayer16` | Existing Reading/lock policy | None |
| `fast` | 6 | `stock`, `bayer`, `blue`, `threshold` | Bayer | Off by default |
| `extreme` | 9 | `stock`, `bayer`, `blue`, `threshold` | Bayer | Off by default |
| `partial` | 1 (A2 8/8 or DU 1/1) | `stock`, `bayer`, `blue`, `threshold` | Bayer; follows Reading back to stock when Reading is `stock` | Off by default |
| `video` | `0x400` + actual A2 | `blue`, `bayer`, `stock`, `threshold` | Blue noise | On by default; unavailable for stock |

`nearest` is 16-level point quantization; `bayer16` is 16-level Bayer; `blue` is blue noise; `threshold` is direct binary thresholding.

Partial refresh is external mode 1, used by the system while scrolling and switching pages; it is not one of the four stock modes. Before 0.3.0-rc2 it had no option of its own and always used Bayer, and only Reading = stock handed it back to stock. That is why scrolling still showed a grid after choosing blue noise for Fast mode. It now stores its own algorithm and hysteresis; keeping the default is bit-identical to the old behavior.

## What `stock` means per scope

The pixels always match stock; the execution path differs (rule in `einkalg::stockFor`):

| Scope | With `stock` | Cost |
| --- | --- | --- |
| Clear | Bit-exact GPU replica of stock 16-level diffusion | None; the full-refresh sweep and other waveform animations keep working |
| Reading, Fast, Extreme, partial refresh | The whole wave for that external entry returns to the stock CPU path | Waveform animations for that mode stop working |
| Video | GPU replica of stock video diffusion | Row-dependent and much slower than blue noise |

The master switch `display.alg.stock` overrides all of the above: no scope overrides quantization while it is on, and per-scope choices are only kept in the configuration.

## Exact video activation

`native/service_algorithm_policy.hpp` first excludes reset, transition, and blank states. It then prioritizes video when `mode & 0x400` is nonzero, `(selected & 255) == 8`, and `internal == 8`. Ordinary interception additionally requires matching external/internal selections: partial refresh 1 with 8/8 or 1/1, Clear 2/2, Reading 5/5, Fast/Extreme 8/8.

High visual motion alone does not activate video. Framework video/camera state affects the flag; HWC mode and telemetry establish the path actually used. Lifecycle flag cleanup is a separate fix. A playback screenshot is insufficient evidence.

## Hysteresis and persistence

Hysteresis uses the previous frame to stabilize binary pixels near a threshold. It is available for Bayer, blue noise, and direct thresholding, with `default/on/off`. Stock video has its own history behavior, which this additional switch does not control. Grayscale has no such option. Judge stability/detail tradeoffs on the physical panel.

Feature IDs are `display.alg.<scope>` and `display.hyst.<scope>`; backend properties are `debug.eink.alg.<scope>` and `debug.eink.hyst.<scope>`. They depend on `display.gpu` and apply hot. The legacy Lab option `experiment.video` is retired: when the app reads an old `stock` choice it migrates it to `display.alg.video=stock` (an explicit new choice wins), and the backend keeps reading the old key until the app rewrites the configuration, so behavior does not change during the upgrade. Disabling overrides explicitly restores `default`.

An ordinary Bayer hysteresis equality boundary at threshold minus 12 now uses integer comparison; legacy video math remains unchanged. Stock video can run on the GPU but its row-dependent diffusion is much slower than blue. Slowness is not proof of CPU fallback.

rc11 passed 59 standalone comparisons on both target Mali and software GLES. This does not prove integrated HWC behavior, every stock-history state, or panel stability. Sources: `R01`–`R03`; code: `DisplayAlgorithms.java`, `FeatureCatalog.java`, `service_algorithm_policy.hpp`, `service_gpu.cpp`.
