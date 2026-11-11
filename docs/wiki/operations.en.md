# Diagnostics and recovery

[简体中文](operations.md) · [Wiki](README.en.md)

## Preserve evidence first

Record the symptom, application/scenario, time, mode, and candidate version. Once device reading is authorized, explicitly select its serial and collect boot_id, HWC PID/starttime, app/module versions, revision, hashes, and diagnostics. Keep raw data in local experiment directories, outside Git.

| Symptom | Inspect first | Avoid assuming |
| --- | --- | --- |
| Slow video | Actual video flag, algorithm, consecutive GPU/CPU wave deltas, errors | Slow stock means CPU |
| Enabled setting has no effect | Dependencies, HOT/REBOOT, boot identity, current acknowledgements | Saved configuration means active |
| Helper loaded but slow | Current helper, first EGL/GL error, input queue, telemetry deltas | A mapped library proves GPU work |
| Lock-screen/lyric thin lines | Input, mode, diffusion, fixed-lyric comparisons, panel feedback | Equal scan bytes prove optical success |
| Diagnostics shows 2 / 3 scopes | Whether the System Framework scope took effect (named `system` in the Vector command line); whether the app was opened once after install; acknowledgements after a restart | Launcher and System UI acknowledgements prove the system framework is injected |
| Hooks disappear after service reload | New process identities and framework injection | A working app proves working hooks |

Persistent evidence lives at `/data/adb/inkcontrol/gpu-diagnostics/<boot_id>/`, started by Magisk. Each boot retains up to three 1MiB GPU logs, a 768KiB first-fault logcat, and bounded metadata. Current plus three recent boots are retained. Minute checks recover logging with PID/starttime identity checks. Later polls do not overwrite the first fault. The old `/data/local/tmp/einkgpu.log` is a historical temporary logger.

Protection may set `/data/adb/inkcontrol/disable_hwc`, error, and ready=0, preventing the next load. Since 0.3.0-rc1 runtime monitoring triggers protection when `gpu_errors` reaches 3 or the helper is not loaded (previously on the first error); if the system does not finish booting within three minutes the module is disabled and the device restarts once. Native code may already be locked into CPU fallback. Copy evidence before clearing anything or restarting. Logging failure must not prevent protection.

The queue fix rechecks state after locking and reselects up to four times. Only pre-publication selection races retry; publication exceptions do not. This does not explain every historical GPU error. Wave and scan deltas are not panel FPS.

## Recovery order

1. Disable the relevant enhancement if possible; preserve selections and evidence. Verify startup changes after a full restart.
2. Use the matching candidate's disable/uninstall restoration and before/written ledgers, preserving later user edits.
3. Match APK, module, input, backup, and hashes to the same experiment. An old rc10 APK can overwrite the queue fix through its bundled backend.
4. After service reload, verify current-process framework injection. Historical Vector reinjection failures recovered with full restart; automatic self-recovery remains unfinished.

Do not blindly unmount layers, overwrite live libraries, reuse an obsolete baseline script, or treat removing protection as a fix. Device actions remain separately authorized and recorded.

Code: `backend/service.sh`, `backend/lib/gpu_diagnostics.sh`, `native/service_queue_retry.hpp`, `platform/RuntimeStatus.java`. Sources: `R03`, `R04`, `R09`.

## Panel switch reports "Switch failed"

After InkControl is force-stopped, this device forbids System UI from starting its ContentProvider. Panel buttons such as auto refresh or sleep on cover then report the target state first and a failure afterwards; the log shows `unable to launch app ... for provider, it's restricted` and `Unknown authority com.inkcontrol.state`. This is unrelated to in-app switch state: configuration lives in the app's private storage and only InkControl's own process can write it and call root.

`PanelHook` starts the transparent `PanelToggleActivity` when the Provider is unavailable, which brings up InkControl's process to complete the switch (it shows for about half a second and focus stays on the current screen), so opening the app manually is not needed. If the failure still appears, check scopes and module state on **Diagnostics**, open InkControl once, and retry.
