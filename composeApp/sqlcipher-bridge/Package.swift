// swift-tools-version:5.9
import PackageDescription

// Resolves and embeds SQLCipher.swift, then exposes a dlopen-based C bridge to Kotlin.
// The bridge intentionally does not link against SQLCipher symbols: Firebase's Package.swift
// transitively links Apple's system sqlite3, which wins over SQLCipher.framework at link time.
let package = Package(
    name: "SQLCipherBridge",
    platforms: [.iOS(.v16)],
    products: [
        .library(name: "SQLCipherBridge", targets: ["SQLCipherBridge"])
    ],
    dependencies: [
        .package(url: "https://github.com/sqlcipher/SQLCipher.swift.git", exact: "4.15.0")
    ],
    targets: [
        .target(
            name: "SQLCipherBridge",
            dependencies: [.product(name: "SQLCipher", package: "SQLCipher.swift")],
            publicHeadersPath: "include"
        )
    ]
)
