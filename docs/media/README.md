# 界面截图 / UI screenshots

来源：**真机截屏**（`HLTE203T`，Android 10，1-bit 墨水屏）。`ui-settings` / `ui-shortcuts` 是 App 版本 **0.2.2-r19**，`ui-home` / `ui-pageanim` 是 **0.2.6-r26**，`ui-panel` / `ui-panel-buttons` 是 **0.2.7-r27**，`ui-pageanim-readers` 是 **0.2.5-r23**。`ui-settings` / `ui-shortcuts` 用 2×2 均值缩到 360×720，其余按原比例缩到 472 宽（`ui-home` 472×944、`ui-panel-buttons` 472×638、两张 `ui-pageanim-*.png` 472×944），除缩放外未做任何编辑（没有增强或重绘）；`ui-panel` 另按 360 宽缩放并裁掉面板以上的桌面区域，只保留面板本身（360×289），`ui-panel-buttons` 另裁掉右侧滚动条。

Source: **physical-device screenshots** (`HLTE203T`, Android 10, 720×1440, 1-bit panel). `ui-settings` / `ui-shortcuts` are app version **0.2.2-r19**; `ui-home` / `ui-pageanim` are **0.2.6-r26**; `ui-panel` / `ui-panel-buttons` are **0.2.7-r27**; `ui-pageanim-readers` is **0.2.5-r23**. `ui-settings` / `ui-shortcuts` were downscaled to 360×720 with a 2×2 box filter; the rest are scaled to 472 px wide (`ui-home` 472×944, `ui-panel-buttons` 472×638, both `ui-pageanim-*.png` 472×944), nothing else edited; `ui-panel` is scaled to 360 px wide and cropped to the panel itself (360×289), `ui-panel-buttons` also drops the scrollbar on the right edge.

- `ui-home.png`：功能首页——页头的 `墨控 v0.2.6-r26` / 关于 / 就绪，下面是各分类卡片，每张一行标题加一句说明。
- `ui-settings.png`：「模块设置」首页——安装、诊断、功能列表排序、桌面快捷方式、实验室、名词解释（列表顶部，末项在标签栏后继续）。
- `ui-shortcuts.png`：「模块设置 → 桌面快捷方式」——长按桌面图标时列出的四项及其顺序。
- `ui-panel.png`：**E-Ink 快捷面板**（SystemUI 的 `InkPopWindow`，720×1440 截屏的下半部分，裁成 360×289）——亮度、当前显示模式，底部五格依次是 WiFi、蓝牙、自动刷新、翻页动画、截屏便签；底色半透明，能透出后面的桌面图标。
- `ui-panel-buttons.png`：「系统增强 → 快捷面板修改」直达的**快捷面板按钮**页——`按钮顺序` 标题与操作说明、五行「序号 · 动作」（WiFi / 蓝牙 / 自动刷新 / 翻页动画 / 截屏便签，各带左侧拖动把手与「更换」），以及页内的「点完收起面板」「截屏便签对齐尺寸」开关；页顶的总开关行与页底的「恢复默认顺序」不在这张裁切里。
- `ui-pageanim.png`：**翻页动画**页——总开关与「已生效」回执、`01 选择翻页风格` 四个下拉（横向展开 / 12 阶段（均衡）/ 12 块 / 250 毫秒）、`02 翻页操作` 的前两项（反转动画方向、点击与横向滑动翻页）；再往下还有实体音量键翻页、音量键遵从翻页直觉与 `03 目标阅读器`。
- `ui-pageanim-readers.png`：`03 目标阅读器` 的「选择应用 · 目标阅读器」弹窗——搜索框输入「微信」后过滤出「微信读书」并勾选，底部为「取消」「保存」。

这些是屏幕位图，**不是面板翻拍**：能说明界面版式，不能说明灰阶观感、残影、刷新波形或可读性验收。当前截图均为中文界面。`ui-pageanim` 截于 0.2.6-r26，画面里的「翻页间隔」与两个手势开关已在 0.3.0 移除。

These are framebuffer captures, **not panel photographs**: they show layout, not grayscale quality, ghosting, refresh waveforms, or readability acceptance. The current screenshots show the Chinese UI. `ui-pageanim` was captured on 0.2.6-r26; the interval option and two gesture switches it shows were removed in 0.3.0.

以下校验值对应仓库内当前文件：

- `ui-home.png` SHA256: `1d1d99e79fea5551cd19ce95ffce46be3812f0434e66adcfb13ea520736ea1c2`
- `ui-settings.png` SHA256: `65102f14599c444aeaaecb1fcaa8174c5dcd660162bed710fe991460d496d3c2`
- `ui-shortcuts.png` SHA256: `2d9d08ca3b0ffd40e34aad98a84061fde504590c47e50084eb9110809da73ff9`
- `ui-panel.png` SHA256: `f29dda44c436f9504edd20613d7f6858e25932d61c2cfdf8eace8fa898a6ba4c`
- `ui-panel-buttons.png` SHA256: `f2a8521aa69b4ffd95958cb955cf97daf5fbe1a4b2315776b4e6d7ffdf6d9f64`
- `ui-pageanim.png` SHA256: `54955a839b01c2579ea578f3360570ad65ffd1e75d1168f49746721e42daead7`
- `ui-pageanim-readers.png` SHA256: `18b1e0865ae2a69c73cc8f9e5a00d80a28641db7607c64fb3d84e53c42dc7c67`
