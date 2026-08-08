// swift-tools-version: 5.9
import PackageDescription

/// Standalone test package for Store + Resolver + Watchdog + Rollback.
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
