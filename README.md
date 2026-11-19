![Kotlin](https://img.shields.io/badge/Kotlin-2.4.21-blue?logo=kotlin) ![Room](https://img.shields.io/badge/Room-2.8.5-blueviolet?logo=android) ![SQLCipher](https://img.shields.io/badge/SQLCipher-4.9.0%20(Android)%20%7C%204.15.0%20(iOS%2C%20SPM)-orange) ![KSP](https://img.shields.io/badge/KSP-2.3.14-yellow) ![AndroidX%20SQLite](https://img.shields.io/badge/AndroidX%20SQLite-2.6.2-lightgrey)

## Encrypted Room Database Example (Kotlin Multiplatform)

This repository showcases a Kotlin Multiplatform (KMP) example that implements an encrypted Room database shared across Android and iOS using SQLCipher. The project demonstrates full integration of Room in shared code with Compose for Android and Compose Multiplatform for iOS, including schema setup, platform-specific drivers, and end-to-end encryption support.

It follows [SQLCipher + Firebase in KMP: When SPM import Symbol Conflicts Break Your Encryption](https://funkymuse.dev/posts/encrypt-kmp-database-with-firebase-in-project) and adapts the original [Encrypted Room Database in KMP](https://desquared.notion.site/Encrypted-Room-Database-in-Kotlin-Multiplatform-KMP-for-Android-iOS-25b9aac7b816801b9067c627602335c6) guide from CocoaPods to Kotlin's SwiftPM import.

### Overview

- **Platforms:** Android & iOS (KMP)
- **Encryption:** SQLCipher
- **Database:** Room
- **iOS dependencies:** SwiftPM import (no CocoaPods)
- **iOS targets:** `iosArm64` and `iosSimulatorArm64` (Compose Multiplatform no longer publishes `iosX64`)
- **UI:** Compose Multiplatform

### Why there is a `sqlcipher-bridge` package

Firebase's `Package.swift` transitively links Apple's system `libsqlite3` (`FirebaseAnalyticsWrapper` and friends declare `.linkedLibrary("sqlite3")`). Both libraries export the same `sqlite3_*` symbols, and the linker always picks the system one. The result is a database that looks encrypted in code but is written as plaintext on disk (`cipher_version: null`).

`composeApp/sqlcipher-bridge` is a local Swift package that:

1. depends on [SQLCipher.swift](https://github.com/sqlcipher/SQLCipher.swift) so the framework is resolved and embedded, and
2. exposes `sc_*` wrappers that `dlopen` `SQLCipher.framework` and resolve every `sqlite3_*` function from **that** library at runtime, which is immune to link order.

The Kotlin side (`SQLCipherDriver`, `SQLCipherConnection`, `SQLCipherStatement`) is a port of AndroidX's `NativeSQLiteDriver` that calls the `sc_*` bridge instead of `sqlite3_*`, and applies `sqlite3_key` immediately after opening the connection.

Firebase Analytics is linked but not used by the app; it is here to keep the sample reproducing the symbol conflict the bridge exists for.

### Key management

The passphrase is 32 random bytes, generated on first launch and never hard-coded:

- **Android:** wrapped with an AES-256-GCM key from the Android Keystore and stored in `SharedPreferences`.
- **iOS:** generated with `SecRandomCopyBytes` and stored in the Keychain (`kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly`).

### Verification and migration

- `SQLCipherTester` (debug binaries only) checks `PRAGMA cipher_version`, reads `sqlite_master` with the key, and checks that the file header is not `SQLite format 3\0`.
- `deleteIfPlaintext` runs before Room opens the database: if an older, unencrypted database exists it copies schema and rows into a new encrypted file, then swaps the files.

### iOS setup

CocoaPods is not used anymore. The SwiftPM linkage package has to be attached to the Xcode project once, on a machine with Xcode:

```bash
XCODEPROJ_PATH=iosApp/iosApp.xcodeproj ./gradlew :composeApp:integrateEmbedAndSign
XCODEPROJ_PATH=iosApp/iosApp.xcodeproj ./gradlew :composeApp:integrateLinkagePackage
```

Commit the changes these tasks make to `iosApp/iosApp.xcodeproj`. Then set `TEAM_ID` in `iosApp/Configuration/Config.xcconfig` and run the `iosApp` scheme.

### How to Use

```bash
./gradlew :composeApp:assembleDebug      # Android
```

Note for existing installs: the passphrase used to be the hard-coded `replace-it-with-your-own-key` string. Databases created by that version cannot be opened with the new random key and have to be cleared. Only unencrypted databases are migrated automatically.
