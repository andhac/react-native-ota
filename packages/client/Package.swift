// swift-tools-version: 5.9
import PackageDescription

/// Standalone test package for Store (M2) + Resolver (M1) + Watchdog (M6).
/// Run on macOS: `swift test --package-path packages/client`
let package = Package(
  name: "RNOtaStore",
  platforms: [.iOS(.v15), .macOS(.v13)],
  products: [
    .library(name: "RNOtaStore", targets: ["RNOtaStore"]),
  ],
  targets: [
    .target(
      name: "RNOtaStore",
      path: "ios/RNOta",
      exclude: [
        "Downloader",
        "Verifier",
        "Rollback",
        "RNOta.swift",
      ]
    ),
    .testTarget(
      name: "RNOtaStoreTests",
      dependencies: ["RNOtaStore"],
      path: "ios/RNOtaStoreTests"
    ),
  ]
)
