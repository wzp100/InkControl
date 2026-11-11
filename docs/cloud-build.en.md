# GitHub cloud builds

[简体中文](cloud-build.md)

The repository is public. All Java, native, resource, Dex, Magisk ZIP, and APK compilation runs on GitHub-hosted Ubuntu 24.04 through `.github/workflows/build.yml`. Low-level build tools remain for reproducibility but are no longer run locally.

Pushes to main produce signed candidates. Actions → Cloud build → Run workflow defaults to signed; unsigned remains available for build investigation. Pull requests run translation, Java/backend regression, native policy/queue, and software GLES tests without private firmware or signing secrets. The workflow does not use pull_request_target.

Toolchain: Python 3.12, Temurin JDK 21 targeting Java 8, Platform 33, Build Tools 35.0.0, NDK 29.0.14206865, Android API 29 arm64. Actions are pinned to commit SHAs, contents permission is read-only, and checkout does not persist credentials.

## Private dependencies

`ci/private-inputs.zip.enc` contains exactly two matching HWC dependencies, encrypted with OpenSSL AES-256-CBC and PBKDF2 at 200000 iterations. `ci/inputs.json` records encrypted and plaintext hashes; the build tool checks its own pinned input hashes as well. The passphrase exists only as the `INKCONTROL_INPUT_PASSWORD` Actions secret. Decryption uses a temporary runner directory, validates member names, and removes temporary inputs on exit. Neither plaintext input archives nor complete temporary directories are uploaded.

Vendor binaries are outside the MIT license. Public APK/ZIP downloads contain the runtime binary derivatives; public visibility does not relicense these under MIT. Source archives exclude encrypted dependencies. See NOTICE.en.md for third-party rights.

## Signing

`ci/trusted-certificate.der` is the new release public certificate used from rc13 onward, not a private key. Unsigned candidates have `-unsigned` names; the app is unsigned and cannot be installed.

Signed builds require `INKCONTROL_KEYSTORE_BASE64`, `INKCONTROL_STORE_PASSWORD`, `INKCONTROL_KEY_PASSWORD`, and `INKCONTROL_KEY_ALIAS` Actions secrets. Configure these using GitHub settings or standard input, never repository files, command arguments, or logs. At the maintainer's explicit request, a new long-term release key was generated and all four secrets configured on 2026-10-17. It uses JKS, RSA-4096, SHA256withRSA, a 10000-day validity period, and alias `inkcontrol-release`. Local `signing/release-20261017/` holds the keystore, random password instructions, public certificate, and rotation metadata. This directory is excluded from Git. Back up both the keystore and password offline.

New certificate SHA256: `4d2ddf8b324da50e54c28c11c9c61ae55eae69f1a9f2073be8315d62342aba29`. Previous certificate SHA256: `44b04d7a953320f0c0c1bb2843a715a23f8c2207c1d602db5c0c4cfbb4313155`. The original `candidate.jks` and previous public certificate backup remain intact. **The new signing identity cannot update the old app in place.** Back up configuration, uninstall the old app, install the new app and matching backend, check framework scopes again, and restart fully. These are instructions for a separate manual migration; CI performs no device operations. Retain the new release key for all future updates.

The backend ZIP is hashed and embedded into the app, which is signed last. CI rejects a certificate different from the repository's fixed trusted certificate to prevent accidental identity changes in later updates. Signing only the outer APK is insufficient.

## Evidence and artifacts

Each run has a unique directory. Artifacts retained for seven days contain test evidence, APK, backend ZIP, SHA256SUMS, a source manifest, and package verification. Commit SHA, run ID, and attempt are recorded. A successful signed main build automatically publishes a versioned Release. RC versions become prereleases; existing published versions are retained without replacement. Only the release job has contents:write. It validates same-run evidence/hashes, creates a draft, uploads/verifies assets, then publishes. It never installs on devices or changes device settings. See the [release Wiki](wiki/build-and-release.en.md).

Software GLES and regression success do not prove Mali execution, HWC integration, panel quality, or long-term stability. The device still runs rc10 with the GPU fix. New candidates include per-mode algorithms, bilingual UI, cloud builds, and the new release identity; signing migration and device acceptance remain separate tasks.

References: [GitHub Secrets](https://docs.github.com/en/actions/how-tos/write-workflows/choose-what-workflows-do/use-secrets), [Actions security](https://docs.github.com/en/actions/reference/security/secure-use).
