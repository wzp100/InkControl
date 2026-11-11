# Device and display pipeline

[简体中文](device-and-display.md) · [Wiki](README.en.md)

| Item | Project baseline |
| --- | --- |
| Target | Hisense A5 Pro 经典版 (Classic Edition), monochrome E-ink |
| Device gates | `HLTE203T`, Android 10 / API 29, arm64 |
| Input | 720 × 1440; GPU research uses Mali-G52 |
| Platform | ums512; historical kernel 4.14.133 |
| Firmware gate | Build number `L1732.6.01.02` plus exact HWC hashes |
| Verified system software | `ro.software.version` `L1732.6.14.01.B10` (contributor) and `L1732.6.15.01.B10` (2026-11-11 smoke test); both report build `L1732.6.01.02` and the same stock HWC hash |
| Private inputs | Two stock/motion HWC hashes in `ci/inputs.json` |

These are compatibility gates, not a promise for other firmware or related devices. Preserve original libraries and waveform files.

The pipeline is: **application → SurfaceFlinger → vendor HWC input queue → grayscale/tone mapping → quantization/dither → mode/history → WBF lookup → scan frames → DRM/DPU → monochrome panel**.

The page-turn banded reveal is implemented in the same GPU waveform timing (`native/service_fusion.hpp`), so it requires the GPU display backend; there is no separate page-turn display library.

The GPU helper handles matched image, statistics, waveform, and scan computations. EGL/GLES import and synchronization matter; unsupported or faulty paths may still use the stock CPU consumer. Framework events, quantization, waveform selection, and panel scanning are separate layers.

External low-byte modes: 2 Clear; 3/4/5 Reading family; 6 Fast; 9 Extreme; 0 automatic. Internal selections also gate interception. Video additionally needs `0x400` and actual A2 selection. See [exact routing](algorithms.en.md).

WBF research identifies INIT, DU, GC16, GL16, GLR16, GLD16, A2, and DU4. A table's presence, standalone-kernel output, and a validated live production entry are different claims.

The stock input has one pending slot overwritten by newer frames; latest-target behavior already exists. This differs from the driver scan queue. Historical eight-slot rings had seven usable slots, and an approximately 91ms lead under specific conditions. Do not transfer that old latency directly to current GPU builds.

Keep wave counts, scan counts, submission time, GPU fences, first/last scan timing, and optical latency separate. Multiple scans can belong to one update; wave growth is not panel FPS. USB-connected measurements are not unplugged power baselines.

Sources: `R01`, `R05`–`R07`. See [research](research.en.md) and [glossary](glossary.en.md).
