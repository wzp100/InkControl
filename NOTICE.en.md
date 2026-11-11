# Sources and license boundaries

[简体中文](NOTICE.md)

Original Java, C++, Python and shell implementations and self-created theme assets are organized under the MIT license. This does not grant additional rights over:

- Hisense/Unisoc stock HWC libraries and their binary derivatives. These are user-supplied private build dependencies, outside the MIT license.
- Android SDK/NDK, JDK, Magisk, Xposed/LSPosed/Vector and other external tools or runtimes, which retain their own licenses.

`src/cn/lyric/getter/api/data/LyricData.java` is a Parcel protocol compatibility implementation retaining the required package name. `stubs` contains minimal compile-time declarations, not framework implementations. The Magisk installer delegates to the device's existing `util_functions.sh`.

Private keys, personal backups, firmware images, raw logs, and device data are excluded from source. Encrypted vendor dependencies are retained for trusted cloud builds and excluded from source export. The repository and versioned downloads are public; APK/ZIP assets contain runtime vendor derivatives. Signing is fixed from rc13 onward. A sensitive-data audit does not grant additional third-party rights.
