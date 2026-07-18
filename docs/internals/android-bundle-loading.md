# RN 0.82 Android — Bundle Loading Internals (verified)

> Source of truth: RN `0.82-stable` files mirrored in `.rn-src/` (android). Line numbers refer to those files.
> This is the evidence base for the Android resolver design in `docs/ARCHITECTURE.md`.

## The 0.82 New-Architecture chain (ReactInstanceManager does NOT participate)

```
MainApplication.onCreate → SoLoader.init + load()
  → ReactApplication.reactHost (lazy)
  → DefaultReactNativeHost.toReactHost()          ← reads getJSBundleFile() ONCE (snapshot)
  → DefaultReactHost.getDefaultReactHost()        ← builds JSBundleLoader (fork below)
  → DefaultReactHostDelegate(jsBundleLoader=...)  ← frozen constructor val
  → ReactHostImpl                                 ← orchestrator (replaces ReactInstanceManager)
  → ReactActivity → ReactActivityDelegate → ReactDelegate.loadApp()
  → ReactSurfaceImpl.start() → ReactHostImpl.startSurface()
  → getOrCreateReactInstanceTask()                ← ReactHostImpl.kt:939
  → ReactInstance (init: threads v_js/v_native, initHybrid JNI, TurboModules, Fabric)
  → ReactInstance.loadJSBundle()                  ← ReactInstance.kt:303
  → JNI loadJSBundleFromFile/FromAssets           ← ReactInstance.kt:441-443
  → C++ JSBigFileString mmap → ReactInstance::loadScript → HermesRuntime (HBC magic sniff)
  → AppRegistry.runApplication → Fabric mounts App.tsx
```

## The bundle-source fork (DefaultReactHost.kt)

```kotlin
val bundleLoader =
    if (jsBundleFilePath != null) {
      if (jsBundleFilePath.startsWith("assets://")) {
        JSBundleLoader.createAssetLoader(context, jsBundleFilePath, true)
      } else {
        JSBundleLoader.createFileLoader(jsBundleFilePath)     // ★ OTA path
      }
    } else {
      JSBundleLoader.createAssetLoader(context, "assets://$jsBundleAssetPath", true)
    }
```

Legacy equivalent (`ReactNativeHost.java`): `getJSBundleFile() != null ? setJSBundleFile(...) : setBundleAssetName(...)`.

## Key verified facts

1. **Snapshot semantics:** `toReactHost()` passes `jsBundleFile` *by value* into `DefaultReactHostDelegate`'s constructor. After host creation, `getJSBundleFile()` is never consulted again for the process lifetime — even across `reload()`.
2. **Re-read seam:** `ReactHostImpl`'s private `jsBundleLoader` getter reads `reactHostDelegate.jsBundleLoader` on **every instance creation** (ReactHostImpl.kt:1068, 1086), and `reload()` re-runs instance creation (1243→1325). A custom `ReactHostDelegate` with a *computed* `jsBundleLoader` property → instant apply without process restart.
3. **Dev override:** with dev support on and Metro reachable, the delegate's loader is ignored (`loadJSBundleFromMetro()`, ReactHostImpl.kt:1059-1072) — OTA is naturally disabled in dev.
4. **Delegate not injectable:** `ReactInstance.loadJSBundle` hands a hardcoded anonymous `JSBundleLoaderDelegate` to `loadScript` (ReactInstance.kt:303-335). The *loader* stays fully injectable; the delegate does not.
5. **`createFileLoader(path)`** delegates to the 3-arg overload with `loadSynchronously = false` (JSBundleLoader.kt:43-44); the release asset loader uses `true`. Functionally equivalent (downstream awaits), relevant for startup profiling.
6. Bytes never enter Java/Kotlin: JNI → C++ `JSBigFileString` (mmap) → `HermesRuntime::evaluateJavaScript`, which sniffs HBC magic bytes (filename irrelevant).
7. `assets/index.android.bundle` is produced at build time by the RN Gradle plugin: Metro serialize → `hermesc` (version-locked to `libhermes.so`) → renamed into APK assets.

## Interception points (ranked)

| # | Hook | Arch | Verdict |
|---|---|---|---|
| P1 | `getJSBundleFile()` override | both (reused by `toReactHost`) | ✅ **Primary** — stable protected API; next-launch apply |
| P2 | Call `getDefaultReactHost(..., jsBundleFilePath = otaPath)` directly | bridgeless | ✅ Same tier; controls *when* resolution runs |
| P3 | Custom `ReactHostDelegate` with computed `jsBundleLoader` | bridgeless | ⚠️ Instant apply (+ `reload()`); `@UnstableReactNativeAPI` |
| P4 | Custom `JSBundleLoader` subclass (`loadScript` override) | both | ✅ Reserved for verify-on-load / decryption |
| P5 | `ReactHostImpl.loadBundle()` into live runtime | — | ❌ internal; module-registry corruption risk |
| P6 | `ReactHost.reload(reason)` | both | ✅ The sanctioned apply *trigger* (pairs with P3) |
| P7 | `JSBundleLoaderDelegate` / JNI / C++ | — | ❌ Never — requires forking RN |

## Hard rules

- Never touch `JSBundleLoaderDelegate` / C++ `loadScriptFromFile` — that's the stable JNI/Hermes contract; we only choose the path upstream.
- `getJSBundleFile()` must be crash-proof and instant (launch critical path): one cheap state read, no I/O beyond `stat`, never throw.
