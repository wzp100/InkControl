# Research and completed work

[简体中文](research.md) · [Wiki](README.en.md) · [Source register](knowledge-index.json)

Original reports preserve inputs, device state, parameters, and failures. Their findings do not automatically validate the current integrated product.

| Work | Finding | Boundary / follow-up | Source |
| --- | --- | --- | --- |
| Stock display reverse engineering | Grayscale/tone, diffusion, decisions/history, waveform and scan stages identified | Symbol presence differs from actual invocation | R05 |
| HWC queue analysis | Stock already overwrites pending input with the latest target | Historical ~91ms scan lead is not current GPU latency | R06 |
| GPU integration | Exact lock-motion-v6 baseline, shared helper, unified off-by-default configuration and recovery | Research v17 and partition prototypes are not production inputs | R01, R04 |
| Real-app profiling | GPU activity confirmed in real scrolling; measure statistics, sync, submission, scans separately | Does not justify sustained maximum frequency | R07 |
| Pipeline optimization | Uniform-location caching reduces local submission work | Smaller windows / frequency changes lacked stable end-to-end benefit | R08 |
| Lock-screen thin lines | Diffusion spreads local glyph changes; point quantization suppresses spread, with positive user feedback | Lyrics, hour changes, grayscale quality, stability remain scoped | R09 |
| Lock/wake motion | Four effects × two directions, 536 standalone scans, hot updates tested | Does not validate every newer integrated build | R12 |
| Video performance | Default blue is faster than stock; both matched paths can use GPU | Wave counts are not panel FPS | R03 |
| GPU queue race | Queue changes between selection and locking could falsely trigger protection; bounded reselection fixes this path | Historical first slow-path/animation errors remain unexplained | R03 |
| Per-mode algorithms | Four modes and video, type constraints, independent hysteresis, integer boundary fix | Standalone kernels do not prove HWC/panel acceptance | R02 |
| OTA 6.16 | No display-chain changes found in resolvable scope; four drivers have matching symbol CRCs | Missing exact system/vendor/product source prevents full replay; no live driver acceptance | R10 |
| Private repo / cloud / languages | Fixed signing, encrypted inputs, successful rc13 cloud and bilingual evidence | Device signing migration remains separate | R11 |

## Reusable lessons

Latest-target overwrite already exists in stock firmware. Standalone GPU correctness does not establish import, invocation, synchronization, scan consumption, and panel quality together. Service reload can break Vector injection; verify current identities. Global refresh changes do not prove a local-line fix, and equal bytes do not establish equal optical quality. Matching module symbol CRCs support an ABI inference, not runtime stability or power claims.

Open work includes historical GPU fault capture, panel/temperature/stability acceptance, unplugged power baselines, quality after scrolling stops, first-action latency after idle, and framework self-recovery. Current user tasks and the workspace backlog determine priority.
