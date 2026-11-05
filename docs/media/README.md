# 界面截图 / UI screenshots

来源：**真机截屏**（`HLTE203T`，Android 10，720×1440，1-bit 墨水屏）。`ui-home` / `ui-settings` / `ui-shortcuts` 是 App 版本 **0.2.2-r19**，`ui-panel` / `ui-panel-buttons` 是 **0.2.3-r20**。前者用 2×2 均值缩到 360×720，除缩放外未做任何编辑（没有裁切、增强或重绘）；`ui-panel.png` 额外裁掉了面板以上的桌面区域，只保留面板本身。

Source: **physical-device screenshots** (`HLTE203T`, Android 10, 720×1440, 1-bit panel). `ui-home` / `ui-settings` / `ui-shortcuts` are app version **0.2.2-r19**; `ui-panel` / `ui-panel-buttons` are **0.2.3-r20**. The former were downscaled to 360×720 with a 2×2 box filter, nothing else edited; `ui-panel.png` additionally crops away the launcher area above the panel, keeping the panel itself.

- `ui-home.png`：功能首页——就绪状态、`25 已开启 / 74`、各分类的「已开启／总数」。
- `ui-settings.png`：「模块设置」首页——安装、诊断、大功能排序、桌面快捷方式、实验室、名词解释（列表顶部，末项在标签栏后继续）。
- `ui-shortcuts.png`：「模块设置 → 桌面快捷方式」——长按桌面图标时列出的四项及其顺序。
- `ui-panel.png`：**E-Ink 快捷面板**（SystemUI 的 `InkPopWindow`，720×1440 截屏的下半部分，裁成 360×304）——亮度、当前显示模式，底部四格依次是 WiFi、蓝牙、自动刷新、遮挡息屏；底色半透明，能透出后面的桌面图标。
- `ui-panel-buttons.png`：「系统增强 → 快捷面板修改」直达的**快捷面板按钮**页——总开关、四行「序号 · 动作」各带「更换」与「←」「→」、「恢复默认顺序」，以及页内的「点完收起面板」「截屏便签对齐尺寸」开关（列表在标签栏后继续）。

这些是屏幕位图，**不是面板翻拍**：能说明界面版式，不能说明灰阶观感、残影、刷新波形或可读性验收。英文翻译已停止维护，因此不再保留纯英文界面截图（部分文字仍会显示中文）。

These are framebuffer captures, **not panel photographs**: they show layout, not grayscale quality, ghosting, refresh waveforms, or readability acceptance. English UI translation is no longer maintained, so no all-English screenshot is kept.

- `ui-home.png` SHA256: `bfdf8d46f6f22daab09e9b66681e0fa7be3dad6fb997d8e9e4c5354e6c35826c`
- `ui-settings.png` SHA256: `8c95bea9f5ed254975bcd5baf04595b2ffa8228bfee9b197f90f14a049f67c12`
- `ui-shortcuts.png` SHA256: `751d1f6f65e67455bc190cc089387a4f5bf384d997e744d044b437a53c8baa93`
- `ui-panel.png` SHA256: `2fa31a0a78a4107fb9f8e80ff262ac460da19d61e41aacbe70c1a22795e150e7`
- `ui-panel-buttons.png` SHA256: `e24ff93b8c948dbe25a6bb319e0edfad3f1610da9fb10b715c67d4c437d23a6d`
