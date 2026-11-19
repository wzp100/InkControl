# 模式、显示算法与迟滞

[English](algorithms.en.md) · [Wiki](README.md)

## 选择矩阵

每个列表均有 `default`，表示不接管当前算法。所有新接管项默认关闭；灰度与黑白不混选，视频独立保存。算法选择不会改变模式、温区或波形计数。

| 场景 | 外部模式 | 支持的非默认算法 ID | 沿用默认 | 独立迟滞 |
| --- | --- | --- | --- | --- |
| 清晰 `clear` | 2 | `stock`、`nearest`、`bayer16` | 既有清晰分支 | 无 |
| 阅读 `reading` | 3，兼容 4/5 | `stock`、`nearest`、`bayer16` | 既有阅读与锁屏策略 | 无 |
| 快速 `fast` | 6 | `stock`、`bayer`、`blue`、`threshold` | Bayer | 默认关闭 |
| 极速 `extreme` | 9 | `stock`、`bayer`、`blue`、`threshold` | Bayer | 默认关闭 |
| 局部刷新 `partial` | 1（A2 8/8 或 DU 1/1） | `stock`、`bayer`、`blue`、`threshold` | Bayer；阅读选 `stock` 时跟随交回原厂 | 默认关闭 |
| 视频 `video` | `0x400` + 实际 A2 | `blue`、`bayer`、`stock`、`threshold` | 蓝噪声 | 默认开启；stock 不提供此开关 |

`nearest` 是 16 级点量化，`bayer16` 是 16 级 Bayer；`blue` 是蓝噪声；`threshold` 是直接黑白阈值。

局部刷新是系统滚动、切页时使用的外部模式 1，不属于原厂四个模式。0.3.0-rc2 之前它没有自己的选项，固定使用 Bayer，只有「阅读 = 原厂」会把它一起交回原厂；这也是快速模式选了蓝噪声、滚动时仍出现方格的原因。现在它单独保存算法和迟滞；保持现状时行为与旧版逐位一致。

## 「原厂」在各场景的含义

像素结果都与原厂一致，执行路径不同（规则在 `einkalg::stockFor`）：

| 场景 | 选 `stock` 后 | 代价 |
| --- | --- | --- |
| 清晰 | GPU 逐位复刻原厂 16 级误差扩散 | 无；全刷扫过等波形动画照常 |
| 阅读、快速、极速、局部刷新 | 该外部入口整波交回原厂 CPU 路径 | 本模式的波形动画不生效 |
| 视频 | GPU 复刻原厂视频误差扩散 | 逐行依赖，明显慢于蓝噪声 |

总开关 `display.alg.stock` 优先于以上全部：开启后所有场景都不接管像素量化，各场景的选择只保留在配置里。

## 视频何时激活

生产策略在 `native/service_algorithm_policy.hpp`，按顺序判断：

1. reset、transition 或 blank 场景直接走既有处理，不进入新的模式接管。
2. `mode & 0x400` 非零，且 `(selected & 255) == 8`、`internal == 8`，优先进入视频组。
3. 普通模式还需外部低字节与内部实际选择匹配：局部刷新为外部 1 且 8/8 或 1/1，清晰为 2/2，阅读为 5/5，快速/极速为 8/8。

视频不是“画面变化很多”就自动触发；框架的视频／相机状态会影响标记，真正执行以 HWC 模式与遥测为准。标记生命周期清理是独立修复项，不能用播放界面截图证明视频路径已启用。

## 迟滞与配置

迟滞参考旧帧，让接近阈值的黑白像素更稳定。只对 Bayer、蓝噪声、固定阈值开放，选项为 `default/on/off`。stock 视频算法有自身历史处理，不受此附加开关控制；灰度不显示该选项。稳定性、细节和翻转成本需要实屏评价。

功能 ID：`display.alg.<scope>`、`display.hyst.<scope>`；后端属性：`debug.eink.alg.<scope>`、`debug.eink.hyst.<scope>`，依赖 `display.gpu`，热应用。旧「实验室 → 视频抖动算法」`experiment.video` 已下线：App 读到旧的 `stock` 选择时迁移为 `display.alg.video=stock`（新页面已明确选择时以新选择为准）；后端在 App 重写配置前继续读取旧键，升级过程行为不变。关闭接管显式恢复 `default`，不留下旧属性覆盖。

## 已知结论

- 普通 Bayer 迟滞曾在亮度等于“阈值减 12”的边界受浮点优化影响，现用整数比较；视频旧公式保留以守住默认。
- `stock` 视频在匹配分支也能运行于 GPU，但逐行误差扩散明显慢于 blue。慢不能直接判为 CPU 回退。
- rc11 独立 Mali 与软件 GLES 各 59 场景零差异；不证明完整 HWC、历史 stock 全状态或面板长稳通过。

源码：`DisplayAlgorithms.java`、`FeatureCatalog.java`、`service_algorithm_policy.hpp`、`service_gpu.cpp`。来源：`R01`、`R02`、`R03`。
