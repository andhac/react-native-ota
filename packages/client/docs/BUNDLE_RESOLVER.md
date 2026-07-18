# Bundle Resolver (M1) — Design Notes

> Milestone: **MS1 Part 2** — Bundle Resolver only.  
> No React Native host hooks, Downloader, Verifier, Rollback, or JS API.

Sources:

- Android: `packages/client/android/src/main/java/com/rnota/resolver/`
- iOS: `packages/client/ios/RNOta/Resolver/`

Contracts: [ARCHITECTURE.md](../../../docs/ARCHITECTURE.md) § M1, [state-json.md](../../../docs/specs/state-json.md), internals docs for future host wiring.

---

## Responsibility

Answer one question, synchronously and infallibly:

> Which JavaScript bundle should this launch use?

Outputs a [BundleResolution](#bundleResolution) that is either:

| Source | Meaning |
|---|---|
| **Embedded** | Host uses packaged bundle (`assets://…` / `main.jsbundle`). `bundlePath` is `null`. |
| **OTA** | Absolute path to `slots/<id>/bundle.hbc`. |

The Resolver **never**:

- mutates `state.json`
- repairs / recovers store state
- downloads, hashes, or verifies packages
- boots `pendingSlot` (that pointer is for M6)

---

## Algorithm

```
peekState()                         // read-only
if activeSlot is null/empty      → EMBEDDED (NO_ACTIVE_SLOT)
if slot directory missing        → EMBEDDED (SLOT_NOT_FOUND)
if bundle.hbc missing            → EMBEDDED (BUNDLE_MISSING)
else                             → OTA path (ACTIVE_BUNDLE)
```

Any unexpected exception → EMBEDDED (`EMBEDDED` / `.embedded` reason).

Corrupt / future-schema / path-traversal slot ids are treated as **no active slot** by `peekState` + codec sanitization (same observable result as empty state for the resolver).

---

## Types

| Type | Role |
|---|---|
| **`BundleResolver`** | Pure resolution entrypoint; depends on `BundleStore` read APIs only. |
| **`BundleResolution`** | Result: `source`, `bundlePath`, `slotId`, `reason`. |
| **`BundleSource`** | `EMBEDDED` \| `OTA`. |
| **`ResolutionReason`** | Diagnostics for logs / future M8 telemetry. |

`BundleResolutionException` is **not** used — the resolver must not fail the boot path; it always returns a resolution.

### Store integration (additive)

| API | Why |
|---|---|
| **`BundleStore.peekState()`** | Read `state.json` **without** recovery rewrite (Resolver must not repair). |
| **`BundleStore.hasCommittedBundle(id)`** | Single `stat`-equivalent check for `bundle.hbc`. |

Existing mutating APIs are unchanged.

---

## Invariants

1. **Read-only** — resolving twice with unchanged disk yields the same result.
2. **Embedded is always safe** — every failure mode returns embedded.
3. **Pending is invisible** — only `activeSlot` is consulted.
4. **No hashing / crypto** on the resolution path (ARCHITECTURE: boot path budget).
5. **No RN API calls** in this milestone — host wiring is deferred.

---

## Failure cases → embedded

| Situation | Reason |
|---|---|
| No / null active slot | `NO_ACTIVE_SLOT` |
| Invalid slot id in file (sanitized away) | `NO_ACTIVE_SLOT` |
| Slot directory missing | `SLOT_NOT_FOUND` |
| Directory exists, `bundle.hbc` missing | `BUNDLE_MISSING` |
| Unexpected error | `EMBEDDED` |

---

## Examples

**Fresh install**

```
state.activeSlot = null  →  embedded / NO_ACTIVE_SLOT
```

**Healthy OTA**

```
activeSlot = "rel-abc", slots/rel-abc/bundle.hbc exists
→ ota, path=…/slots/rel-abc/bundle.hbc, ACTIVE_BUNDLE
```

**Tampered / deleted bundle**

```
activeSlot = "rel-abc", file deleted
→ embedded / BUNDLE_MISSING
```

---

## How later modules use this

| Milestone | Integration |
|---|---|
| MS1 Part 2 (now) | Filesystem resolution + unit tests |
| Later (Android) | `OtaReactNativeHost.getJSBundleFile()` → `resolve().bundlePath` |
| Later (iOS) | `AppDelegate.bundleURL` → file URL from `resolve().bundlePath` |
| MS2 (M6) | Watchdog may flip pointers **before** resolve; resolver still only reads `activeSlot` |
| MS6 (M8) | Emit resolution `reason` / slot as telemetry |

---

## Running tests

```powershell
cd packages\client\store-jvm
.\gradlew.bat test
```

```bash
cd packages/client
swift test
```
