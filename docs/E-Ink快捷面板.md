# E-Ink 快捷面板：唤起方式与按钮接管（参考）

> 适用：海信 A5 Pro 经典版（HLTE203T / Android 10 / 720×1440 灰度）。本文只保留可直接参照的类名、命令、hook 点与约束；测试记录与过程数据不在此收录。

## 一、面板是什么

截图里含「亮度 / 当前显示模式 / 截屏便签」的界面不是快捷设置磁贴，而是 SystemUI 自己画的悬浮窗口。

| 项 | 值 |
| --- | --- |
| 类 | `com.hmct.systemui.epd.InkPopWindow`（`Vision_SystemUI.apk` 的 `smali_classes24`） |
| 窗口名 | `Statusbar_InkScreen` |
| 打开 / 收起 | `showPopWindow()`；收起 `dismissView(true)` + `closePopWindow()` |
| 面板按钮 | 由配置字符串 `ink_pop_window_buttons` 动态生成，`addButtons()` 分发 |
| 「截屏便签」 | 资源 `string/ink_launch_pad_pinscreen`（`0x7f1203a5`），监听器字段 `mPinScreenOnEink` |

原厂点击逻辑：先把 `mReallyView` 设为 `GONE` 收起面板，再延时 500 ms post `mShowScreenshotPin`，截屏并钉到墨水屏。

因为按钮是「按名字动态生成」的，资源 ID 只出现在 `R$string`，改资源覆盖没用；接管点应放在 `showPopWindow()` 之后处理视图，或构造后替换 `mPinScreenOnEink` 这一个字段。

## 二、唤起面板的四种方式

| 方式 | 触发 | 是否要坐标/手势 | 适用 |
| --- | --- | --- | --- |
| 两侧滑动手势 | 左下/右下贴边长距离上滑 | 要 | 手势导航下手动操作 |
| 导航栏面板按钮 | 点击底部最右小屏图标 | 要 | 三大金刚键下手动操作 |
| **adb 广播** | `am broadcast -a hmct.intent.action.aikey.inkpopwindow` | **不要** | **脚本 / 自动化回归，首选** |
| 墨智键（AI 键）单击 | 物理键 | 不要 | 日常手动 |

### 2.1 adb 广播（首选）

SystemUI 的 `StatusBar` 启动时注册动态接收器 `StatusBar$14`，`IntentFilter` 含 `hmct.intent.action.aikey.inkpopwindow`，用 `UserHandle.ALL` 注册、权限为 null，shell 或任意应用都能直接发：

```sh
adb shell am broadcast -a hmct.intent.action.aikey.inkpopwindow
```

收到后 `StatusBar$14.onReceive()` → `access$1200()` → `showInkPopWindow()`，内部同样走 `dismissView` + `closePopWindow` + `showPopWindow()`。

**它是 toggle**：面板已显示时再发一次会收起。脚本里要先确保收起再唤出，或用 `dumpsys window windows | grep Statusbar_InkScreen` 判状态。

### 2.2 两侧滑动手势

导航方式设为「两侧滑动手势」时，从屏幕左下角或右下角贴边、长距离上滑即可唤出，不限当前界面：

```sh
adb shell input swipe 90 1430 90 700 320
```

代码路径：`NavigationGestureView` / `NavigationGestureSideBackBottomView` / `NavigationGestureIndicatorsView` 的私有方法 `doShowEinkView()` → `InkPopWindow.showPopWindow()`。起点要贴边（x≈90 或 630）、距离 >600 px、时长约 300 ms；短距离或偏内侧上滑会变成「桌面工具」或「水墨屏桌面」。

### 2.3 导航栏「水墨屏控制面板」按钮

设置 → 系统导航 → 导航方式选「导航键」→「导航键组合」里选中带「水墨屏控制面板」的一套，底部导航栏最右侧出现小屏幕按钮，点一下唤起、再点收起。

```sh
adb shell input swipe 647 1395 647 1395 120
```

代码路径：`com.android.systemui.statusbar.phone.NavigationBarView$2`（`mInkSwitcherClickListener`）→ 取 `InkPopWindow` 实例 → `isShowing()` 后 `dismissView` 或 `showPopWindow()`。

### 2.4 墨智键单击

设置 → 墨智键（`com.hmct.settings.ai.AiKeyActivity`）→ 单击动作设为「E-Ink 控制面板」（资源 `hali_key_click_e_ink_control_panel`）。物理键一键唤起，底层就是发 2.1 那条广播。

## 三、唤起与点击的约束

- 唤出后按 HOME / back 会发 `android.intent.action.CLOSE_SYSTEM_DIALOGS`，`StatusBar$14` 收到后 `dismissView`；唤出到点击之间不要插返回操作。
- 息屏 / 锁屏唤不出，且无线 adb 在息屏时会掉线；测试前确认 `dumpsys power` 为 `Awake`、`dumpsys trust` 为 `deviceLocked=0`。
- `input tap` 会丢点（一次连发只进前几个），点按钮统一用 `input swipe x y x y 120`。
- 四格按钮的设备像素中心约 x=107 / 277 / 445 / 618、y≈1275；截图 720×1440。
- **收起必须调 `closePopWindow()`**：它 removeView 并置空；只把 `mReallyView` 设 `GONE`，下次唤起会被当成「关闭」吞掉一次。
- 拉起墨控用 `FLAG_ACTIVITY_NEW_TASK | FLAG_ACTIVITY_SINGLE_TOP`；`MainActivity` 没声明 launchMode，不加 `SINGLE_TOP` 会重复开页。

## 四、当前实现：`qs.panel` 四格按钮

主体在 `src/com/inkcontrol/features/qsext/PanelHook.java`：

1. 只在 `com.android.systemui` 作用域安装一次（`sInstalled`），hook `InkPopWindow.showPopWindow` 的 `afterHookedMethod`，异常一律写 `XposedBridge.log`，不让 SystemUI 崩。
2. 从 `mButtonsParent` / `mContext` 取容器与上下文，按资源名取 `ink_screen_button_item` 布局；把原厂「截屏便签」按钮摘下来复用（它可能被排到别的位次）。
3. 按 `qs.panel.btn1` … `qs.panel.btn4` 的取值摆四个位置，四格都标 `two`，与原厂单行同排；`InkButtonsParent` 按每行个数平分宽度，面板高度不变。App 里换位是**拖把手**：按住左侧三条横线上下拖，松手后把四格动作整体搬一次（只写真的动了的那几格）。
4. 动作表见 `FeatureCatalog.PANEL_ACTIONS`：控制中心那套（WiFi、蓝牙、铃声、飞行模式、手电筒、自动旋转、整屏翻页、免打扰、省电模式、悬浮球）、墨控自己的开关（自动刷新 / 锁屏主题 / 遮挡息屏 / 翻页动画 / 全刷扫过）、就地动作「刷新格言」、「墨控快捷设置」、原厂「截屏便签」、任意应用包名、以及 `PANEL_NONE`（这一格不显示）。取值非法时退回该位置的默认值。
5. 长按是「跳去设置」：WiFi / 蓝牙跳系统设置页；墨控的几项用 `quick=1` 或 `category=<分类名>` extra 落到对应页，全刷扫过再带 `focus=motion.gc_clear` 让「动画」页直接滚到 `02 全刷扫过`，刷新格言落到「锁屏显示」页。跳转前一律收起面板。
6. 「刷新格言」不是开关，也不是功能项：点一下由 `run()` 直接发 `com.inkcontrol.action.LOCK_MOTTO_NEXT` 给本进程（SystemUI）的锁屏 Hook，由它反射原厂 `MottoHelper.getNextMotto()` 换下一条并提示。桌面快捷方式里的同名项走同一条广播。
7. `qs.panel.close` 控制点完是否收起；`qs.panel.pinsize` 把原厂「截屏便签」的 28dp 图标 / 12sp 文字与补上的按钮对齐。总开关 `qs.panel` 关闭时完全走原厂，不摘按钮、不换顺序、不缩尺寸。

配置键一览：`qs.panel`、`qs.panel.btn1` … `btn4`、`qs.panel.close`、`qs.panel.pinsize`。新增/修改这类键需按仓库「增删功能键」规范同步 `FeatureCatalog`、`Guide`、`common.sh` 的 `ic_validate`、`CoreTest` 等处。

## 五、面板开关与 App 状态同步

- `ConfigProvider` 跑在独立进程 `:state`，`toggle` 在那里写盘；App 主进程的 `snapshot` 不会自己变。
- `toggle` 成功后发 `changed("panel-toggle")`；`MainActivity` 用精确 URI 的 `ContentObserver` 收到后重跑 `inspect()` + `snapshot()`，比对有变化才 `render()`。五条开关 `refresh.master` / `lock.style` / `wave.lock` / `pageanim.master` / `motion.gc_clear` 共用这条链路，白名单在 `ConfigProvider.TOGGLES`。
- 不要用 `save()` 自带的 `/config` 通知刷界面，那是给注入进程的。
- **App 被强制停止后面板开关会失败**：厂商进程管控禁止 SystemUI 拉起被 force-stop 包的 provider（`Unknown authority com.inkcontrol.state`）。`PanelHook.toggle` 的 catch 里改为显式 `startActivity` 拉起透明的 `PanelToggleActivity`（`noHistory` / `excludeFromRecents`），由应用进程转交 `ConfigProvider.toggle` 后立刻 finish；带上显式的 `enabled` 保证重试幂等。写配置仍必须由墨控进程完成，但拉起的是无界面空壳，UI 同步不要求 App 常驻。

## 六、相关结论

- 「截屏便签」在 `InkPopWindow`，`qs.extend`（放开第三方 QS 磁贴）在 `TileQueryHelper`，两者不是一回事。
- 面板按钮按字符串配置动态生成，新增/替换按钮不必改资源表，替换视图或监听器字段即可。
- 面板里原厂按钮数量随显示模式与应用过滤变化（`mIsSupportNewDisplayMode`），实现不能假定固定为 7 个。
