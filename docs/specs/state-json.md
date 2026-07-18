# state.json — Schema v1

> Normative spec for the OTA pointer file. Implemented by `packages/client/android/.../store/OtaStateCodec.kt` (Android) and, later, the iOS store. Changes require a schemaVersion bump and an entry here.

## Location

`<app-data>/ota/state.json` — written only by the Bundle Store (M2) via atomic replace; read by the store and by the boot-path resolver (M1).

## Schema

```json
{
  "schemaVersion": 1,
  "installedBinaryVersion": "2.4.0",
  "activeSlot": "rel-a1b2c3",
  "pendingSlot": null,
  "previousSlot": null
}
```

| Field | Type | Meaning |
|---|---|---|
| `schemaVersion` | int, required | Version of this schema. A reader encountering a **greater** value must treat the file as unusable (app-downgrade case). |
| `installedBinaryVersion` | string \| null | `versionName` of the binary that wrote the state. Reserved for M6 (detect store-binary update → OTA state invalidation). |
| `activeSlot` | slot-id \| null | Slot the resolver boots. `null` → embedded bundle. |
| `pendingSlot` | slot-id \| null | Slot staged by M3, awaiting M6 watchdog promotion. **Never booted directly.** |
| `previousSlot` | slot-id \| null | Rollback target for M6. |

**slot-id format:** `[A-Za-z0-9][A-Za-z0-9._-]{0,63}` — no leading dot, no path separators. Anything else is treated as absent (defends against tampered/corrupt state pointing outside `slots/`).

## Invariants

1. Every non-null slot reference points to a **committed** slot directory (`slots/<id>/` containing `bundle.hbc`). Enforced at write time by the store; verified at read time by recovery.
2. The file is only ever replaced whole: write `state.json.tmp` → `fsync` → `rename(2)` over `state.json` → `fsync` parent dir. Readers can only observe old or new content.
3. `schemaVersion` increases monotonically across library versions; fields are only ever added, never repurposed.

## Failure semantics

| Condition | Resolver (M1, boot path) | Store (M2, recovery) |
|---|---|---|
| File missing | embedded bundle | write `EMPTY` state |
| Invalid JSON / no schemaVersion | embedded bundle | atomic reset to `EMPTY` |
| `schemaVersion` > supported | embedded bundle | atomic reset to `EMPTY` (newer state is unusable by an older binary by definition) |
| Referenced slot missing on disk | embedded bundle | drop the dangling reference, rewrite |
| Invalid slot-id string | treated as `null` reference | dropped on rewrite |

Both readers converge on the same behavior: **a broken pointer is indistinguishable from no pointer**, and the embedded bundle always boots.
