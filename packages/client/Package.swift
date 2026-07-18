// swift-tools-version: 5.9
import PackageDescription

/// Standalone test package for the Bundle Store (M2).
/// Run on macOS: `swift test --package-path packages/client`
/// The same sources are compiled into the CocoaPods library via react-native-ota.podspec.
let package = Package(
  name: "RNOtaStore",
  platforms: [.iOS(.v15), .macOS(.v13)],
  products: [
    .library(name: "RNOtaStore", targets: ["RNOtaStore"]),
  ],
  targets: [
    .target(
      name: "RNOtaStore",
      path: "ios/RNOta/Store",
      exclude: []
    ),
    .testTarget(
      name: "RNOtaStoreTests",
      dependencies: ["RNOtaStore"],
      path: "ios/RNOtaStoreTests"
    ),
  ]
)
