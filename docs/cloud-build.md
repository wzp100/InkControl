# GitHub 云端构建

[English](cloud-build.en.md)

仓库公开维护。所有 Java、原生库、资源、Dex、Magisk ZIP 与 APK 编译均在 `.github/workflows/build.yml` 的 GitHub 托管 Ubuntu 24.04 运行器执行。本地工具保留为可复现底层入口，不再在本地运行编译。

推送 main 自动构建签名候选。Actions → Cloud build → Run workflow 默认 signed，也可选择 unsigned 排查构建。PR 执行无私有输入的 Java/后端回归、翻译检查、原生策略/队列及软件 GLES 核对照；不读取固件和签名 secrets，不使用 pull_request_target。

固定工具链：Python 3.12、Temurin JDK 21（Java 8 目标）、Platform 33、Build Tools 35.0.0、NDK 29.0.14206865、设备 API 29 arm64。依赖安装在 runner 中，未引入本机路径。Actions 引用固定提交 SHA，令牌只读 contents，checkout 不持久化凭据。

## 私有依赖

`ci/private-inputs.zip.enc` 仅包含两个准确匹配的 HWC 输入，经 OpenSSL AES-256-CBC、PBKDF2、200000 次迭代加密。文件哈希及解密后的输入哈希均在 `ci/inputs.json`；构建器还会按自身固定值再次核对。解密密码只在 `INKCONTROL_INPUT_PASSWORD` Actions secret。解密文件位于 runner 临时目录，成员名严格白名单，退出清理，不上传明文依赖或整个临时目录。

这些依赖仍是厂商私有二进制，不属于 MIT 许可。产出的 APK/ZIP 包含运行所需的厂商派生库，公开 Release 可下载；它们不因仓库公开而获得 MIT 授权。源码导出排除加密依赖，第三方权利范围见 NOTICE.md。

## 签名

`ci/trusted-certificate.der` 是 rc13 起的新发行公钥证书，不能用于签名。未签名产物带 `-unsigned`，App 未签名，不能安装。

signed 构建需要以下 Actions secrets：`INKCONTROL_KEYSTORE_BASE64`、`INKCONTROL_STORE_PASSWORD`、`INKCONTROL_KEY_PASSWORD`、`INKCONTROL_KEY_ALIAS`。由维护者在 GitHub 设置页面或通过标准输入配置，不写入仓库、命令参数或日志。2026-10-17 按维护者明确要求新建长期发行密钥并配置这四项 secrets。密钥采用 JKS、RSA-4096、SHA256withRSA、10000 天有效期，别名 `inkcontrol-release`；本地 `signing/release-20261017/` 保存密钥、随机密码说明、公钥与轮换元数据，整个目录不入 Git。密码说明与密钥应一同离线备份。

新公钥证书 SHA256：`4d2ddf8b324da50e54c28c11c9c61ae55eae69f1a9f2073be8315d62342aba29`。此前证书 SHA256：`44b04d7a953320f0c0c1bb2843a715a23f8c2207c1d602db5c0c4cfbb4313155`。旧 `candidate.jks` 和此前公钥备份保持原样。此轮是签名身份轮换，**无法直接覆盖旧签名 App**：迁移前备份配置，卸载旧 App，安装新 App 及其配套后端，重新核对框架作用域并完整重启。此处只记录后续人工迁移要求，CI 不执行这些设备操作。后续版本必须保留新发行密钥，避免再次失去升级兼容性。

构建器先计算后端 ZIP 哈希并嵌入 App，最后签 App。云端核对输出公钥必须与仓库固定的可信证书一致，防止后续版本意外更换签名。不能只给外层 APK 补签。

## 证据与产物

每次运行生成唯一目录和七天保留的 artifacts：测试输出、APK、后端 ZIP、SHA256SUMS、构建来源清单及包装验证。记录 commit SHA、run ID 和 attempt。main 的签名构建全部检查通过后自动创建版本 Release；rc 版本标为预发布，已发布同版本跳过不覆盖。发布 job 单独持有 contents:write，先验证同轮证据与哈希，再建草稿、上传并校验附件、发布。不会安装设备或改变设备配置。详见 [Wiki 发布流程](wiki/build-and-release.md)。

离线回归与软件 GLES 成功不等于真实 Mali、HWC 集成、面板观感或长稳验收通过。现有设备仍运行 rc10 修复版；新候选包含分模式算法、双语/云构建和新发行签名，需独立执行签名迁移和上机验收。

依据：[GitHub Secrets](https://docs.github.com/en/actions/how-tos/write-workflows/choose-what-workflows-do/use-secrets)、[安全使用 Actions](https://docs.github.com/en/actions/reference/security/secure-use)。
