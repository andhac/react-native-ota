# react-native-ota (client)

The device-plane npm library: TypeScript SDK + Android (Kotlin) + iOS (Swift) native modules.

## Status

**MS1 Part 1 complete:** Bundle Store (M2) on Android + iOS.  
No Resolver, Downloader, Verifier, Rollback, or JS update API yet.

## Bundle Store

See [docs/BUNDLE_STORE.md](docs/BUNDLE_STORE.md) for atomic writes, crash recovery, and module boundaries.

| Platform | Path |
|---|---|
| Android | `android/src/main/java/com/rnota/store/` |
| iOS | `ios/RNOta/Store/` |
| JVM tests | `store-jvm/` (`gradle test`) |
| Swift tests | `swift test` from this package |

## Why this package exists

Host apps depend on **one** package (`react-native-ota`) that answers “which JS bundle do I run?” and orchestrates check → download → verify → stage → apply after boot. Native code ships in the binary; JS may ship inside OTA bundles (version skew is a permanent design constraint).

## Future responsibilities

| Area | Modules | Milestone |
|---|---|---|
| Bundle Resolver | M1 | MS1 Part 2 (Android), MS3 (iOS wiring) |
| Boot Watchdog | M6 | MS2 / MS3 |
| Verifier | M5 | MS4 |
| Downloader + Manager + Public API | M4, M3, M9 | MS5 |
| Policy + Telemetry | M7, M8 | MS6 |
