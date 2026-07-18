# RN 0.82 iOS — Bundle Loading Internals (verified)

> Source of truth: RN `0.82-stable` files mirrored in `.rn-src/ios/`. Line numbers refer to those files.
> This is the evidence base for the iOS resolver design in `docs/ARCHITECTURE.md`.

## The 0.82 New-Architecture chain (RCTBridge does NOT participate)

```
main() → UIApplicationMain → AppDelegate(: RCTAppDelegate)
  → didFinishLaunchingWithOptions                    RCTAppDelegate.mm:40
  → RCTReactNativeFactory(delegate: self)            RCTAppDelegate.mm:42
      wraps delegate methods into blocks: bundleURLBlock = ^{ delegate.bundleURL }
  → RCTRootViewFactory.viewWithModuleName()          RCTRootViewFactory.mm:154
  → createReactHost → RCTHost(bundleURLProvider:...) RCTRootViewFactory.mm:236-252
  → [reactHost start] → _setBundleURL(provider())    RCTHost.mm:210-214   ← URL RESOLVED
  → RCTInstance created                              RCTHost.mm:243
  → RCTInstance reads bundleManager.bundleURL        RCTInstance.mm:440
  → _loadJSBundle → delegate(=RCTHost).loadBundleAtURL   RCTInstance.mm:502,513
  → RCTHost: hostDelegate hook? else RCTJavaScriptLoader RCTHost.mm:354-363
  → RCTJavaScriptLoader: fopen + header sniff + mmap     RCTJavaScriptLoader.mm:96-200
  → _loadScriptFromSource → reactInstance->loadScript    RCTInstance.mm:567-585
  → HermesRuntime.evaluateJavaScript (RCTHermesInstance factory)
  → AppRegistry.runApplication → Fabric → App.tsx in RCTSurfaceHostingProxyRootView
```

## Key verified facts

1. **Closure semantics (the iOS advantage):** `RCTReactNativeFactory` stores `bundleURLBlock` as a closure over the delegate's `bundleURL` method — not a value snapshot. `RCTHost.start` calls it (RCTHost.mm:212-214), and **`_reloadWithShouldRestartSurfaces` re-calls it on every reload** (RCTHost.mm:442-443). Instant apply = override `bundleURL` + `RCTTriggerReloadCommandListeners(reason)`. No custom delegate needed, unlike Android.
2. `bundleURL` / `sourceURLForBridge:` **raise NSException if not overridden** (RCTDefaultReactNativeFactoryDelegate.mm:22-26, 72-74) — every app must answer explicitly.
3. **Formal byte-load hook:** `RCTHost loadBundleAtURL:` defers to `_hostDelegate` if it implements the selector, else `RCTJavaScriptLoader` (RCTHost.mm:358-362). Protocol-level custom loader — cleaner than Android's subclass approach.
4. **Where main.jsbundle is opened:** `RCTJavaScriptLoader.attemptSynchronousLoadOfBundleAtURL` — `fopen` at RCTJavaScriptLoader.mm:137, 4-byte `BundleHeader` sniff via `parseTypeFromHeader` (:165; RAM-bundle magic 0xFB0BD1E5, HBC magic, plain source), full load with `NSDataReadingMappedIfSafe` (mmap) at :172.
5. **Executed by:** C++ `facebook::react::ReactInstance::loadScript` (reached from RCTInstance.mm:585 via `NSDataBigString`) → `HermesRuntime` created by `RCTHermesInstance::createJSRuntime` (RCTHermesInstance.mm:26-31). Same shared C++ class as Android.
6. `RCTBundleManager` is the URL access layer: RCTHost wires getter/setter/defaultGetter closures (RCTHost.mm:194-196); `RCTInstance` reads the URL from it, not from RCTHost directly. `_setBundleURL` also publishes globally via `RCTReloadCommandSetBundleURL` (RCTHost.mm:435).
7. `RCTBundleURLProvider` is a dev-server locator; in release it's inert (packager access compiled out, RCTBundleURLProvider.mm:133-141) — fallback is `[NSBundle mainBundle] URLForResource:@"main" withExtension:@"jsbundle"]` (:225-229). Not an OTA hook.
8. `main.jsbundle` is produced by the "Bundle React Native code and images" Xcode phase (`react-native-xcode.sh`): Metro → `hermesc` → HBC named `main.jsbundle` + `assets/` copied into the signed `.app` (read-only). OTA bundles must live in the sandbox (`Library/Application Support`, backup-excluded); `fopen` works on any sandbox path — code signing does not gate data files. App Store policy 3.3.2 permits OTA JS (policy constraint for docs).
9. Legacy chain (compat): `RCTBridge.setUp` (RCTBridge.mm:568) → `sourceURLForBridge` → `RCTCxxBridge.loadSource` (RCTCxxBridge.mm:543) → delegate `loadSourceForBridge:*` hooks (:569-572) else `RCTJavaScriptLoader` (:580) → `executeSourceCode` (:1030) → `RCTJavaScriptDidLoadNotification`.

## Interception points (ranked)

| # | Hook | Arch | Verdict |
|---|---|---|---|
| I1 | **`bundleURL` override in AppDelegate** | bridgeless | ✅ **Primary** — closure is re-called on reload → next-launch AND instant apply from one stable API |
| I2 | `sourceURLForBridge:` override | legacy | ✅ Required for old-arch support; same resolver behind both |
| I3 | `RCTHostDelegate.loadBundleAtURL:onProgress:onComplete:` | bridgeless | ✅ Custom byte loading (verify/decrypt before handover) |
| I4 | `RCTBridgeDelegate.loadSourceForBridge:*` / Configuration blocks | legacy | ✅ Old-arch twin of I3 |
| I5 | `RCTTriggerReloadCommandListeners(reason)` | both | ✅ The apply trigger — public C function |
| I6 | `RCTHost setBundleURLProvider:` / direct init | bridgeless | ⚠️ Internal-category plumbing; only if bypassing RCTAppDelegate |
| I7 | `RCTBundleManager` setter | both | ⚠️ Mutates live URL state mid-flight; not a load hook. Avoid |
| I8 | Swizzle RCTJavaScriptLoader / fork C++ | — | ❌ Never |

## Cross-platform summary (drives the shared resolver contract)

| Concern | Android | iOS |
|---|---|---|
| Resolver API | `getJSBundleFile(): String?` | `bundleURL() -> URL?` |
| Resolution timing | once (value snapshot at `toReactHost`) | at start + every reload (closure) |
| Instant apply | custom `ReactHostDelegate` (P3) + `reload()` | free via I1 + I5 |
| Custom byte loading | `JSBundleLoader` subclass (P4) | `RCTHostDelegate.loadBundleAtURL` (I3) |
| Bytes opened by | C++ `JSBigFileString` mmap (JNI) | `fopen` + `NSDataReadingMappedIfSafe` mmap |
| Executed by | C++ `ReactInstance::loadScript` → Hermes | same (shared ReactCommon) |
| Reload trigger | `ReactHost.reload(reason)` | `RCTTriggerReloadCommandListeners(reason)` |
| Embedded bundle | `assets/index.android.bundle` (APK) | `main.jsbundle` (.app, code-signed) |
| OTA storage | `filesDir` | `Library/Application Support` + backup exclusion |

**Resolver contract consequence:** may be called once per process (Android) or once per JS instance (iOS reload) — must be pure, instant, idempotent, and never throw.
