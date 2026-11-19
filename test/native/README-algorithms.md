# 分模式算法原生检查

构建输出放在项目 `out/rc11-algorithm-native/`。最终实机通过产物位于其 `attempt2-bayer-hyst/` 子目录，顶层保留修复前产物及哈希。本目录仅保存源码、构建脚本和说明。构建与电脑测试不连接手机。

## 生产接口及约束

`native/service_algorithm_policy.hpp` 是生产使用的纯选择策略。先排除 reset/transition/blank，再判 `mode & 0x400`、`selected & 255 == 8` 和 `internal == 8`，视频优先于所有外部低字节（包括自动0、7、原厂视频8）。其余只接管外部1的局部刷新（A2 8/8 或 DU 1/1）、2的清晰、3/4/5的阅读、6的快速和9的极速；不匹配内部状态/selected 时沿旧行为。

| 属性 | 合法值 | 缺失/非法/default |
| --- | --- | --- |
| `debug.eink.alg.clear`、`.reading` | default / stock / nearest / bayer16 | 保留原来的 stock16 / Tone / Raw 分支；原锁屏 nearest 条件仍生效 |
| `debug.eink.alg.fast`、`.extreme`、`.partial` | default / stock / bayer / blue / threshold | 普通 Bayer，无迟滞；stock 整波交回原厂 CPU；partial 为 default 时跟随阅读的 stock |
| `debug.eink.alg.video` | default / blue / bayer / stock / threshold | 沿旧 video.dither，非法旧值仍回 blue |
| `debug.eink.hyst.fast`、`.extreme`、`.partial`、`.video` | default / on / off | 普通关；视频 blue/bayer/threshold 开；stock 保留原厂 history |

「原厂」的判定在 `einkalg::stockFor`：阅读、快速、极速、局部刷新选 stock 时整波交回原厂 CPU；清晰选 stock 时走 GPU 逐位复刻的 stock16，以保留全刷扫过等波形动画。灰度显式 stock 选择 stock16，nearest 选择16级就近量化，bayer16 选择16级有序抖动；显式选择覆盖旧 lock.dither。`default` 保留原分支，阅读未带0x100时不会强行开启 stock16。没有改波形、扫描计数、LUT或提交点。

黑白输出等级仍只有0和30，灰度等级仍为0、2、…、30。PairY4的偶数字节放 old pair，奇数字节放 new pair，送入每像素REGAL混合时仍使用同一组等级。

普通 blue/threshold/Bayer 都只读取原有 tone 后的亮度，不使用视频 gamma/black。普通 Bayer 关闭迟滞保留原整数严格大于比较；打开后先取 `h=int(round(uHyst*255.0))`，再用 `int(mapped)>int(b*4+2)+(wasWhite?-h:h)` 比较；当前h固定为12。这样保留严格大于的相等边界，避免Mali浮点加乘导致相等点偏白。视频仍使用原来的浮点公式。

迟滞只支持 BW Bayer、blue、threshold：上一帧等级≥16视为白点，阈值减h；否则阈值加h；始终严格大于，偏移后阈值不夹紧。普通 on 为 `h=12/255`，off 为0，default 为0；视频 on/default 沿旧 `debug.eink.video.hyst`（默认12，夹至0..64后除255），off 为0。视频 gamma 默认0.6、black默认20及其原有范围和公式保持不变。stock 不叠加此迟滞；on/off均忽略，原厂RGB/误差history保留，UI应隐藏其独立迟滞开关。

## Android编译及独立运行

在项目根目录运行（PowerShell，NDK r29）：

```powershell
./test/native/build_algorithm_probes.ps1 -Ndk $env:ANDROID_NDK_HOME -OutDir out/rc11-algorithm-native/attempt2-bayer-hyst -ProductionLibrary
```

脚本构建 `algorithm_policy_probe`、`algorithm_gpu_probe`、`queue_retry_probe`，可选构建生产库；启用 `-std=c++17 -O2 -DNDEBUG -Wall -Wextra -Werror`，目标 `aarch64-linux-android29`。`compile-sources.json` 记录输入源码SHA256，只声明编译通过。

GPU probe的等价单条编译命令（编译器路径替换成自己的NDK）：

```powershell
& "$env:ANDROID_NDK_HOME/toolchains/llvm/prebuilt/windows-x86_64/bin/clang++.exe" --target=aarch64-linux-android29 -std=c++17 -O2 -DNDEBUG -Wall -Wextra -Werror -static-libstdc++ test/native/algorithm_gpu_probe.cpp -lEGL -lGLESv3 -o out/rc11-algorithm-native/attempt2-bayer-hyst/algorithm_gpu_probe
```

以下仅供持有设备的操作者手动执行，指定自己的设备序列号。不替换系统库、不加载墨控服务、不修改系统属性；只复制并启动独立程序，GPU probe自己建立离屏EGL context与SSBO，不访问面板/DRM/sysfs。

```powershell
adb -s <serial> push out/rc11-algorithm-native/attempt2-bayer-hyst/algorithm_policy_probe /data/local/tmp/inkcontrol_algorithm_policy_probe
adb -s <serial> push out/rc11-algorithm-native/attempt2-bayer-hyst/algorithm_gpu_probe /data/local/tmp/inkcontrol_algorithm_gpu_probe
adb -s <serial> shell chmod 755 /data/local/tmp/inkcontrol_algorithm_policy_probe /data/local/tmp/inkcontrol_algorithm_gpu_probe
adb -s <serial> shell /data/local/tmp/inkcontrol_algorithm_policy_probe
adb -s <serial> shell /data/local/tmp/inkcontrol_algorithm_gpu_probe | Out-File -Encoding utf8 out/rc11-algorithm-native/attempt2-bayer-hyst/device-gpu-probe.txt
```

如shell进程无EGL设备权限，由操作者改为 `adb -s <serial> shell su -c /data/local/tmp/inkcontrol_algorithm_gpu_probe`。以程序退出码与最后一行JSON共同判定，不仅检查PASS文本。GPU超时、编译错误、GL错误或任何字节不符均返回非零。可加 `--continue` 收集所有字节差异；此时逐组输出CHECK和参数，最终仍在存在差异时返回非零，不放宽判定。

## 覆盖与已执行结果

- `algorithm_policy_probe.cpp`：1,785项检查。包含外部6/9独立配置分派、全部256个低字节的视频优先级及reset/transition/blank排除、非法值和同类限制、旧属性默认、显式灰度覆盖和stock迟滞限制。检查不用assert，NDEBUG下仍执行。
- `algorithm_gpu_probe.cpp`：复用生产 `service_binary_shader.hpp`、`service_image_ops.hpp` 和蓝噪声表。在真实GLES执行后回读与独立参考逐字节比较：普通三算法×开关迟滞×旧等级0/14/16/30，每一组覆盖256个亮度×全部64×64相位；两组非恒等tone检查；12组1440×720旧默认对照；21组灰度分支和打包检查。
- `legacy_binary_shader.hpp`：冻结rc10原shader，来自 `results/2026-10-17_GPU错误排查/source-before/native/service_preprocess.hpp`。不复用新实现生成旧期望；视频blue/bayer分别对照默认与极端合法gamma/black/hyst，普通Bayer也对照。stock视频原核及其history实现未改动，本probe不声称验证原厂stock视频history。
- 灰度stock使用生产容量1440的prefix shader与相同分派入口；测试图256×64，三种tone对比CPU串行stock参考，检查domain proof=0。nearest/bayer16使用独立数值公式，验证0..30等级和PairY4打包。未覆盖原厂REGAL扫描和面板光学。

2026-10-17最终验证：Android严格编译通过；策略1,785项、队列6场景沿既有通过记录；本次整数迟滞修复后，WSL Mesa llvmpipe与目标HLTE203T的Mali-G52均完成59场景、0差异、退出码0。普通blue/threshold的所有覆盖边界均一致；12组旧默认全屏逐位对照及21组灰度检查均通过。

修复前Mali记录保留在 `results/2026-10-17_分模式算法选择/native-device/` 及其 `diagnostic/`：普通Bayer on且旧等级16/30两组各有64个等级字节差异和64个PairY4字节差异，总计256个比较字节差异。普通Bayer迟滞整数化后复测通过，未调整期望值或放宽probe。

最终原始输出、完整输入快照、源码哈希和摘要位于 `results/2026-10-17_分模式算法选择/native-device/attempt2-bayer-hyst/`。全程普通shell、未使用root；boot保持 `8f732057-89fc-41ff-a32a-3973e8e7a9ca`，HWC PID保持3722。仅推送并执行独立测试程序，未替换/挂载系统库、修改App/模块/配置或重启；本轮临时测试程序保留供回查。以上是独立shader验证，不等同于新候选系统库部署或面板光学验收。

最终生产源码已冻结：`native/service_binary_shader.hpp` SHA256为 `97e25d6d7a1f4f25e4c3b1f794c0367b16cf1ac8d8535a4a071e1b6f7c837dc9`。最终build2应使用此版本。
