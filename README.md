# Zyll Photo (open-source edition)

An end-to-end encrypted gallery for Android. Photos are encrypted on your phone and stored as ciphertext in
**your own** Google Drive or OneDrive. There is no server of ours in between, and the cloud provider cannot see
your pictures, album structure or keys.

This repository is the free, open-source edition. It contains no closed-source libraries
(CI checks the dependency tree) and is licensed under [GPL-3.0](LICENSE).

## What is included

- Local gallery: timeline, albums, viewer, video playback, search by name, date and place
- Backup to Google Drive or OneDrive, with optional end-to-end encryption (ZYLC v1, AES-256-GCM, Argon2id)
- One cloud account at a time
- Deduplication by content hash, automatic backup of new photos

Not included (these are only in the Google Play edition): multiple cloud accounts, on-device AI search and
classification, the photo editor, and "Connect cloud media" for the system Photo Picker.

## Building

```bash
cd android
./gradlew :app:assembleGithubDebug
```

The package name is `com.zyll.photo.oss` (set by `zyll.appId` in `android/gradle.properties`), so it can be
installed next to the Play edition.

### Cloud sign-in

Client IDs are not secrets, but they are bound to a package name and signing key, so builds from source need
their own. Put them in `android/gradle.properties`, `~/.gradle/gradle.properties` or environment variables.
Left empty, the backup screen shows "not configured".

| Property | Environment variable | Where to get it |
| --- | --- | --- |
| `zyll.googleClientId` | `ZYLL_GOOGLE_CLIENT_ID` | Google Cloud Console → OAuth client of type Android, package `com.zyll.photo.oss` + your SHA-1, enable custom URI scheme; scope `drive.appdata` |
| `zyll.onedriveClientId` | `ZYLL_ONEDRIVE_CLIENT_ID` | Microsoft Entra → app registration for all Microsoft accounts, platform Android, package `com.zyll.photo.oss` + signature hash; permissions `Files.ReadWrite.AppFolder User.Read offline_access` |
| `zyll.onedriveSignatureHash` | `ZYLL_ONEDRIVE_SIGNATURE_HASH` | The signature hash Entra shows for that platform, URL-encoded (`=` → `%3D`) |

### Release signing

Create `android/keystore.properties` (ignored by git) with `storeFile`, `storePassword`, `keyAlias` and
`keyPassword`, or set `ZYLL_STORE_FILE`, `ZYLL_STORE_PASSWORD`, `ZYLL_KEY_ALIAS` and `ZYLL_KEY_PASSWORD`.
Without them the release APK is unsigned. Pushing a `v*` tag builds a release and attaches the APK and its
SHA-256 when the `ZYLL_KEYSTORE_BASE64` secret and the other three are set.

## Layout

| Path | Contents |
| --- | --- |
| `spec/` | Encrypted container format, key bundle, cloud layout, shared test vectors |
| `android/core/crypto` | Encryption core (pure JVM, tested against `spec/test-vectors`) |
| `android/core/backup` | Backup rules (pure JVM, unit tested) |
| `android/core/cloud` | Google Drive / OneDrive clients and background backup |
| `android/feature/gallery` | Gallery UI |
| `android/app` | App shell and settings |

```bash
# Encryption core without the Android SDK
cd android && ./gradlew -Pzyll.jvmOnly :core:crypto:test
```

---

# Zyll 相册（开源版）

端到端加密的安卓相册。照片在手机上加密后，以密文存进**你自己的** Google Drive 或 OneDrive；中间没有我们的服务器，
网盘服务商看不到照片内容、相册结构和密钥。

本仓库是免费的开源版，不含任何闭源库（CI 会检查依赖树），以 [GPL-3.0](LICENSE) 授权。

- 包含：本地相册、网盘备份与可选的端到端加密、一个网盘账号、按内容去重、新照片自动备份。
- 不包含（只在 Google Play 版里）：多个网盘账号、端侧 AI 搜索和分类、修图、“接入云端媒体”。
- 构建：`cd android && ./gradlew :app:assembleGithubDebug`。包名是 `com.zyll.photo.oss`，可以和 Play 版同时安装。
- 网盘登录需要自己登记的客户端 ID，填法见上面英文部分的表格；留空时备份页显示“未配置”。
