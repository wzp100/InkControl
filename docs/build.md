# 构建与归档

[English](cloud-build.en.md) · [云端构建与签名](cloud-build.md)

自 2026-10-17 起，所有编译在 GitHub 托管 Actions 执行。本机只编辑和做静态检查。

```powershell
# 在项目根目录执行：只做静态检查，不编译。
$env:PYTHONUTF8 = '1'
$env:PYTHONIOENCODING = 'utf-8'
python tools/check_repository.py
python tools/check_i18n.py
python tools/check_docs.py
# 推送 main 自动触发发行签名构建；手动启动也在云端执行。
gh workflow run build.yml --repo wzp100/InkControl -f signing=signed
```

底层顺序仍为：Java 核心回归、目录白名单生成、原生库与观察器、Magisk ZIP、Java/Dex/资源、App 签名及内置包一致性校验。入口是 `tools/ci_build.py`，调用 `tools/build.py` 与 `tools/verify_package.py`。具体工具版本、私有输入哈希、加密方式与发行证书签名 secrets 见云端说明。

输出按 run ID 和 attempt 创建新目录，不覆盖旧候选。云构建默认使用固定发行密钥签名；手动 unsigned 产物不能安装。rc13 按维护者要求更换发行身份，首次迁移需备份配置并卸载旧签名 App，后续更新必须沿用新密钥；不能只补签外层 APK。测试和构建产物由 Actions 保存七天，不直接创建公开 Release。

正式交付归档使用 `tools/archive.py`，仍要求与 APK/ZIP 哈希匹配的实际验收摘要；未签名候选不能正式归档。历史发布目录保持不变，不能以云构建成功代替实机验收。

main 签名构建全检查通过后另由 CI 自动生成 Release，附云端验证边界，不冒充实机交付归档；rc 标记预发布，同版本不覆盖。详见 [Wiki](wiki/build-and-release.md)。

设备脚本 `tools/test_device_*.py` 独立于构建，可能改开关、切换模块或重启，只能在明确设备授权后执行。目录整理与本次仓库改造不执行这些脚本。
