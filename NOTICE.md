# 来源与许可范围

[English](NOTICE.en.md)

本项目的原创 Java、C++、Python、Shell 实现及自制主题素材按 MIT 许可整理。既有本地项目的来源证据见 `docs/source-provenance.json`。迁移脚本以文本保存在 `docs/evidence/`，只作历史证据，不参与构建。

以下内容不由 MIT 许可授予额外权利：

- 海信／展锐原厂 HWC 和其派生二进制：仅作为用户合法取得的私有构建输入；不进入公开源码 ZIP。
- Android SDK、NDK、JDK、Magisk、Xposed／LSPosed／Vector：工具或外部运行环境各自适用其许可，不作为本项目原创代码授权；本源码不打包它们的完整实现。

`src/cn/lyric/getter/api/data/LyricData.java` 是既有项目的 Parcel 协议兼容实现，保留协议要求的包名。`stubs/` 为本地最小编译声明，不把 Xposed 实现复制进应用。Magisk 的最小 ZIP 安装入口调用用户已有的 `util_functions.sh`，未打包 Magisk 实现。

仓库公开维护，全部编译在云端 Actions 执行。私钥、密码、个人备份、固件镜像、设备日志和实机原始数据不进入公开源码。正式签名自 rc13 起固定。公开 Release 的 APK/ZIP 包含运行所需的厂商派生二进制；上述第三方权利仍由原权利人保留，敏感信息审计不构成额外许可。
