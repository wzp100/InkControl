# 界面截图 / UI screenshots

来源：**真机截屏**（`HLTE203T`，Android 10，1-bit 墨水屏）。`ui-home` / `ui-settings` / `ui-shortcuts` 是 App 版本 **0.2.2-r19**，其余 `ui-*` 是 **0.2.5-r23**。旧三张用 2×2 均值缩到 360×720，除缩放外未做任何编辑（没有裁切、增强或重绘）；`ui-panel.png` 额外裁掉面板以上的桌面区域，两张 `ui-pageanim-*.png` 为 472×944。

Source: **physical-device screenshots** (`HLTE203T`, Android 10, 720×1440, 1-bit panel). `ui-home` / `ui-settings` / `ui-shortcuts` are app version **0.2.2-r19**; `ui-panel` / `ui-panel-buttons` / `ui-pageanim` / `ui-pageanim-readers` are **0.2.5-r23**. The older three were downscaled to 360×720 with a 2×2 box filter, nothing else edited; `ui-panel.png` additionally crops away the launcher area above the panel, keeping the panel itself.

- `ui-home.png`：功能首页——就绪状态、`25 已开启 / 74`、各分类的「已开启／总数」。
- `ui-settings.png`：「模块设置」首页——安装、诊断、大功能排序、桌面快捷方式、实验室、名词解释（列表顶部，末项在标签栏后继续）。
- `ui-shortcuts.png`：「模块设置 → 桌面快捷方式」——长按桌面图标时列出的四项及其顺序。
- `ui-panel.png`：**E-Ink 快捷面板**（SystemUI 的 `InkPopWindow`，720×1440 截屏的下半部分，裁成 360×304）——亮度、当前显示模式，底部四格依次是 WiFi、蓝牙、自动刷新、遮挡息屏；底色半透明，能透出后面的桌面图标。
- `ui-panel-buttons.png`：「系统增强 → 快捷面板修改」直达的**快捷面板按钮**页——总开关、四行「序号 · 动作」各带「更换」与「←」「→」、「恢复默认顺序」，以及页内的「点完收起面板」「截屏便签对齐尺寸」开关（列表在标签栏后继续）。
- `ui-pageanim.png`：**翻页动画**专用紧凑页——总开关与「已生效」回执、`01 选择翻页风格` 四个下拉（横向展开 / 12 阶段（均衡）/ 12 块 / 250 毫秒）、`02 翻页操作` 与「选完下次翻页生效，无需重启」提示。
- `ui-pageanim-readers.png`：`03 目标阅读器` 的「选择应用 · 目标阅读器」弹窗——搜索框输入「微信」后过滤出「微信读书」并勾选，底部为「取消」「保存」。

这些是屏幕位图，**不是面板翻拍**：能说明界面版式，不能说明灰阶观感、残影、刷新波形或可读性验收。英文翻译已停止维护，因此不再保留纯英文界面截图（部分文字仍会显示中文）。

These are framebuffer captures, **not panel photographs**: they show layout, not grayscale quality, ghosting, refresh waveforms, or readability acceptance. English UI translation is no longer maintained, so no all-English screenshot is kept.

以下校验值对应仓库内当前文件：

- `ui-home.png` SHA256: `9593bd41b399063be1387b740c94fcbb28115ba84ff1e9fb0769bb6af77a9b84`
- `ui-settings.png` SHA256: `65102f14599c444aeaaecb1fcaa8174c5dcd660162bed710fe991460d496d3c2`
- `ui-shortcuts.png` SHA256: `2d9d08ca3b0ffd40e34aad98a84061fde504590c47e50084eb9110809da73ff9`
- `ui-panel.png` SHA256: `7954495f3f38320f6c06c67977c74d1fda08a338960eeeb13fab87abb7df607e`
- `ui-panel-buttons.png` SHA256: `8f33eceebdfdae01d7e077c4cc293ed9d1f3ed1be5352fde8c5adbfab1e6ea81`
- `ui-pageanim.png` SHA256: `6fa4883af756c158453b9d63b4fdb4a4962a188be7ffb94c687c77613a39c186`
- `ui-pageanim-readers.png` SHA256: `18b1e0865ae2a69c73cc8f9e5a00d80a28641db7607c64fb3d84e53c42dc7c67`
