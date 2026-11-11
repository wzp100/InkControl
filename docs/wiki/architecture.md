# 源码地图与架构

[English](architecture.en.md) · [Wiki](README.md) · [详细业务规则](../architecture.md)

## 去哪里改代码

| 仓库相对路径 | 职责 |
| --- | --- |
| `src/com/inkcontrol/core` | 功能目录、配置、依赖、恢复规则；无 Android 依赖 |
| `src/com/inkcontrol/ui` | 页面、Guide、预览；只调用应用服务 |
| `src/com/inkcontrol/platform` | Android 组合、AtomicFile、Provider、RootBridge、Hook 路由与回执 |
| `src/com/inkcontrol/features` | motion、lock、desktop、fix、refresh（自动刷新）、wave（体感）、qsext（控制中心与 E-Ink 面板）、navbar 处理器 |
| `src/com/inkcontrol/shared` | 天气图标／解析、媒体快照等实际共用逻辑 |
| `src/com/inkcontrol/i18n` | 纯 Java 显示短语翻译 |
| `native/service_gpu.cpp` | GPU 服务与生产着色器 |
| `native/service_algorithm_policy.hpp` | 纯算法选择策略 |
| `native/service_queue_retry.hpp` | 输入队列稳定选择与有界重试 |
| `native/service_fusion.hpp` | 翻页动画的分带波形相位（GPU 融合） |
| `backend` | Magisk 生命周期、白名单控制器、启动保护与条件恢复 |
| `test`、`tools` | 回归／探针与云端构建、包装、发布工具 |
| `ci`、`.github/workflows` | 固定工具链、加密输入、公钥与 CI |

## 配置从哪里来

唯一用户真源是 App 设备保护存储 `files/configuration.txt`，AtomicFile 提交。后端 `/data/adb/inkcontrol/configuration.txt` 是生成的启动输入；`applied.txt`、`boot.txt` 是回执。不要把后端副本当成另一份用户配置编辑。

`FeatureCatalog` 同时驱动 UI、校验、测试与构建生成的 `features.tsv/defaults.txt`。ID、有限选项、依赖、角色、HOT／REBOOT 与实验标记集中定义。缺失的新算法字段按兼容规则保留默认，不自动重写旧修订号。

用户修改经过依赖／参数检查 → 保存期望快照 → 执行 → 等待回执。失败保留期望值并明确提示重试；不会仅因保存成功就显示已生效。父级关闭保留子项选择。

## 回执、权限与恢复

Hook 回执要匹配 PID、`/proc/PID/stat` starttime、版本、修订号；启动项还要匹配 boot_id 与启动快照。GPU 另需当前 helper、有效遥测、零错误。进程更新或回执丢失不能复用旧成功。

Provider `com.inkcontrol.state` 运行在独立的 `:state` 进程，按 UID／角色限制访问，无外部任意配置写入、文件访问或 Root 执行接口。唯一的写入口是 `toggle`：只接受系统界面或墨控自身，且只能切换白名单里的五个开关（自动刷新、锁屏主题、遮挡息屏、翻页动画、全刷扫过）。导出的透明页 `PanelToggleActivity` 是它在 Provider 拉不起来时的兜底，因此其他已安装应用也能触发这五项的开关，但不能写入其他配置或取值。显示服务权限增强绑定构建时公钥摘要。RootBridge 仅调用固定 `inkctl`，用户值走 stdin 与白名单解析。

接管原厂状态保存 before/written 账本；只有当前值仍等于自身写入值时恢复。每应用 XML 使用字段级合并，保留后来用户改动。独立 Magisk 停用／卸载入口负责恢复。显示后端的监测复用 Magisk 启动服务。自动刷新与体感各有一个无障碍服务（`:refresh`、`:motion` 进程），只在对应开关开启时由后端加入系统的已启用列表，关闭后移除。

App 声明了联网权限，仅用于「关于」页手动检测更新（访问 GitHub Release 接口，不自动检查、下载或安装）；存储读取权限用于选择字体文件。

来源：`R01`、`R04`；原始迁入来源见 [source-provenance.json](../source-provenance.json)。迁入哈希不是当前修改后源码哈希，当前来源以构建清单为准。
