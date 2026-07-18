# Bundle Store (M2) — Design Notes

> Milestone: **MS1 Part 1** — Bundle Store only. No Resolver, Downloader, Verifier, or Rollback.

This document explains the native Bundle Store shipped under:

- Android: `packages/client/android/src/main/java/com/rnota/store/`
- iOS: `packages/client/ios/RNOta/Store/`

Both platforms expose the **same logical API** and obey [state-json.md](state-json.md) and [ARCHITECTURE.md](../ARCHITECTURE.md) § M2.

---

## Why each type exists

| Type | Role |
|---|---|
| **`BundleStoreConfig`** | Injects the absolute `ota/` directory, binary version, schema support, and optional `FileIo` test double. Keeps the store free of Android `Context` / iOS `UIApplication` coupling in unit tests. |
| **`BundleState`** | Immutable snapshot of `state.json` (active / pending / previous pointers). The only mutable authority is `BundleStore`. |
| **`SlotInfo`** | Describes one `slots/<id>/` directory and whether it is **committed** (`bundle.hbc` present). |
| **`BundleStoreException`** | Typed failures (`InvalidSlotId`, `SlotNotCommitted`, `DiskFull`, …) so later modules can map errors without parsing strings. |
| **`OtaPaths`** | Single place for path/file names and the slot-id regex — no magic strings. |
| **`OtaStateCodec`** | Encode/decode with **stable key order**; sanitizes illegal slot-ids to `null`. |
| **`FileIo` / `ControllableFileIo`** | Production FS vs crash-injection / disk-full simulation for tests. |
| **`BundleStore`** | The M2 façade: layout, atomic pointer writes, slot lifecycle, GC. |

---

## On-disk layout

```
<app-data>/ota/
├── state.json          ← pointer file (schema v1)
├── state.json.tmp      ← write staging only
├── boot.json           ← created empty (`{}`); owned by M6 later
└── slots/
    └── <slot-id>/
        ├── manifest.json   ← written later by Verifier / publish pipeline
        ├── bundle.hbc      ← required for “committed”
        └── assets/
```

`createSlot(id)` creates the directory + `assets/` only. It does **not** create `bundle.hbc` and does **not** modify `state.json`.

---

## How atomic writes work

Every `state.json` replacement follows:

1. Encode the full `BundleState` to bytes (whole-file replace — never patch in place).
2. Write bytes to `state.json.tmp`.
3. `fsync` the temp file (Android `FileDescriptor.sync()` / iOS `FileHandle.synchronize()`).
4. Atomically rename/move temp → `state.json` (`ATOMIC_MOVE` when available).
5. Best-effort parent directory sync.

Readers therefore observe either the **previous** complete file or the **new** complete file — never a torn JSON document.

---

## How crash recovery works

On `initialize()` / `loadState()`:

1. Delete leftover `state.json.tmp` (incomplete write).
2. If `state.json` is missing, empty, corrupt, or has `schemaVersion` greater than supported → treat as **EMPTY** and atomically rewrite.
3. Sanitize each pointer: invalid slot-id → `null`; missing or non-committed slot → `null`.
4. If anything changed, rewrite `state.json` atomically.

Invariant after recovery: every non-null pointer references a committed slot, or the pointer is cleared (embedded bundle remains the ultimate fallback once M1 exists).

Crash injection tests cover: after temp write, after fsync, before rename, after rename.

---

## Why the store is independent of networking

ARCHITECTURE.md M2: *“Deliberately zero knowledge of network or crypto.”*

- **Downloader (M4)** will write opaque bytes into a slot directory the store created.
- **Verifier (M5)** will validate those bytes, then ask the store to set `pendingSlot`.
- **Resolver (M1)** will only *read* state (and `stat` the active bundle) — it never calls mutating store APIs on the boot path in a way that can fail the process.
- **Watchdog (M6)** will flip pointers (`previous` ← `active` ← `pending`) using the same primitives.

Keeping IO policy out of the store means crash recovery stays local and testable offline.

---

## How later modules interact

```
M4 Downloader  --writes-->  slots/<id>/{bundle.hbc, assets/...}
M5 Verifier    --ok------>  BundleStore.setPendingSlot(id)
M3 Manager     --apply--->  (M6) BundleStore.setPrevious/setActive/clearPending
M6 Watchdog    --rollback-> BundleStore pointer flips + garbageCollect()
M1 Resolver    --reads--->  state.json + stat(active bundle)   [not in this milestone]
```

**Activation rule:** no slot becomes `activeSlot` until another module calls `setActiveSlot` on a **committed** slot. `createSlot` alone never activates.

**GC retention:** keep `active` + `previous` + at most one `pending`; delete everything else.

---

## Running tests

### Android (JVM, no device)

```powershell
cd packages\client\store-jvm
.\gradlew.bat test
```

On macOS/Linux:

```bash
cd packages/client/store-jvm
./gradlew test
```

Sources are the same files under `android/src/main/java/com/rnota/store`.

### Android (AGP unit tests, requires SDK + wrapper in android/)

Not set up yet for MS1 Part 1. Use the JVM harness above.
### iOS / macOS (SwiftPM)

```bash
cd packages/client
swift test
```
