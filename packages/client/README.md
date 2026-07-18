# react-native-ota (client)

The device-plane npm library: TypeScript SDK + Android (Kotlin) + iOS (Swift) native modules.

## Why this package exists

Host apps depend on **one** package (`react-native-ota`) that answers “which JS bundle do I run?” and orchestrates check → download → verify → stage → apply after boot. Native code ships in the binary; JS may ship inside OTA bundles (version skew is a permanent design constraint).

## Future responsibilities

| Area | Modules | Milestone |
|---|---|---|
| Bundle Store / Resolver | M2, M1 | MS1 (Android), MS3 (iOS) |
| Boot Watchdog | M6 | MS2 / MS3 |
| Verifier | M5 | MS4 |
| Downloader + Manager + Public API | M4, M3, M9 | MS5 |
| Policy + Telemetry | M7, M8 | MS6 |

## Layout

```
android/   Kotlin stubs (com.rnota.*)
ios/       Swift stubs (RNOta)
src/       TS entry + reserved folders (api, manager, policy, telemetry, native)
```

## Status

MS0: **empty placeholders only** — no OTA logic.
