# Cloud builds, signing, and automatic Releases

[简体中文](build-and-release.md) · [Wiki](README.en.md) · [Toolchain details](../cloud-build.en.md)

The pipeline is: **main push/manual signed run → repository/docs/translations → Java/backend/native/GPU regressions → compile/sign → packaging/four bilingual UI configurations → same-run provenance/hash checks → draft/upload/verify/publish**.

All compilation and build tests run on GitHub-hosted Ubuntu 24.04. Local work is editing/static inspection. PRs receive no private firmware or signing secrets and cannot publish. Main defaults to signed; manual unsigned output cannot be installed or released. Pinned tools: Python3.12, JDK21 targeting Java8, Platform33, Build Tools35.0.0, NDK29.0.14206865.

## Version policy

Current candidate: `0.2.3-r20`. A successful new version creates `v<version>` and a Release at the exact verified commit. `-rcN` becomes a prerelease without Latest; stable versions become Latest. Cloud success is not device-stability acceptance.

Synchronize `AndroidManifest.xml`, `tools/build.py`, `backend/lib/common.sh`, app/module versionCode, notes, and the knowledge index when changing versions. Documentation-only pushes can retain a version. An existing published version is skipped without changing its tag, notes, or assets. Behavioral/resource changes require a new version.

Publishing creates a draft, uploads all assets, verifies remote hashes, then publishes. Interrupted drafts resume only for the same commit and matching existing assets. Uploads never use overwrite. Main pipelines serialize publication; only the release job receives `contents:write`.

## Gates and assets

The publisher verifies this run/attempt's repository, commit, trusted certificate, APK/backend hashes, off-by-default packaging, all regression commands, four bilingual pages, and native policy/queue/software-GPU outputs.

| Asset | Purpose |
| --- | --- |
| `InkControl-v<version>.apk` | Signed app with matching embedded backend |
| `inkcontrol_backend-v<version>.zip` | The same backend for Magisk |
| `SHA256SUMS` | Hashes of all other attachments |
| `build-manifest.json` | Commit/run, source and input hashes |
| `package-verification.json` | Embedded ZIP identity, module checksums, defaults |
| `release-verification.json` | Release-time regression/UI/GPU/certificate/provenance evidence |
| `RELEASE_NOTES.md` | Bilingual changes and validation boundaries |

Raw Actions test/UI artifacts last seven days. Versioned Release assets are publicly retained. Failed checks and unsigned builds cannot publish. Publication is restricted to the expected repository and Actions/main; foreign repositories are rejected.

The fixed signing identity starts at rc13. The public certificate is tracked; private keys/passwords stay local and in Actions secrets. Old signed-app migration requires backup and uninstall; future updates retain the new key. Overlay and app are signed in sequence; outer-APK-only signing is insufficient.

The two HWC inputs are encrypted in `ci/private-inputs.zip.enc`, decrypted and validated only in temporary runner storage, then cleaned. No private key, password, backup, or plaintext input is attached. Device deployment and acceptance archival remain separate tasks.

Implementation: [workflow](../../.github/workflows/build.yml), [publisher](../../tools/publish_release.py). References: [GitHub CLI](https://cli.github.com/manual/gh_release_create), [Actions secrets](https://docs.github.com/en/actions/how-tos/write-workflows/choose-what-workflows-do/use-secrets).

Every Release description must retain a prominent test-device recommendation, an off-device backup reminder, and the risks of boot failure, data loss and unrecoverable damage. Cloud success and rollback mechanisms must not be described as guarantees of safety or recovery.
