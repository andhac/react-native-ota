# Boot Watchdog (M6) — Design Notes

> Milestone: **MS2** — Boot Watchdog only.  
> Does **not** perform rollback, RN host wiring, or TurboModules.

Sources:

- Android: `packages/client/android/src/main/java/com/rnota/watchdog/`
- iOS: `packages/client/ios/RNOta/Watchdog/`

---

## Responsibility

Decide whether the **current / previous** launch completed healthily, using only `boot.json`.

| Owns | Does not own |
|---|---|
| `boot.json` lifecycle | `state.json` / slots (Bundle Store) |
| Incomplete-boot detection | Choosing which bundle to load (Resolver) |
| `requiresRollback()` signal | Actually flipping slots / deleting packages (Manager later) |

---

## Lifecycle

```
cold start
  → Resolver picks bundle (unchanged)
  → Watchdog.beginBoot()          // status = pending, bootAttempt++
  → app runs…
  → Watchdog.markBootSuccessful() // status = confirmed

If markBootSuccessful never runs:
  next beginBoot()
    → consecutiveFailures++
    → if failures >= maxFailedAttempts (default 2)
         status = rollback_required
    → Manager (future) reads requiresRollback() and flips Store pointers
```

---

## boot.json (schema v1)

```json
{
  "schemaVersion": 1,
  "bootAttempt": 5,
  "lastSuccessfulBoot": 4,
  "status": "pending",
  "consecutiveFailures": 1,
  "lastFailureReason": "incomplete_boot"
}
```

| Field | Meaning |
|---|---|
| `bootAttempt` | Monotonic counter of `beginBoot` calls |
| `lastSuccessfulBoot` | `bootAttempt` value at last success |
| `status` | `idle` \| `pending` \| `confirmed` \| `rollback_required` |
| `consecutiveFailures` | Incomplete boots since last success |
| `lastFailureReason` | Diagnostics |

Writes are atomic: `boot.json.tmp` → fsync → rename (same `FileIo` as the Store).

Empty `{}` placeholders created by Bundle Store initialize are treated as empty watchdog state.

---

## Public API (sync)

| Method | Behavior |
|---|---|
| `beginBoot()` | Start attempt; detect incomplete prior pending |
| `markBootSuccessful()` | Confirm current attempt |
| `currentStatus()` | Current `BootStatus` |
| `requiresRollback()` | `true` iff `rollback_required` |
| `reset()` | Clear to idle / zeros (does not touch Store) |

---

## Invariants

1. Watchdog **never** edits `state.json`, `activeSlot`, `pendingSlot`, or slot directories.
2. Corrupt / missing / future-schema `boot.json` → behave as empty (read path); next write persists a valid file.
3. Default `maxFailedAttempts = 2` (ARCHITECTURE.md M6).
4. Deterministic: same file bytes → same `requiresRollback()` / status.

---

## Examples

**Healthy**

```
beginBoot → pending (1)
markBootSuccessful → confirmed (lastSuccessfulBoot=1)
```

**Crash loop**

```
beginBoot → pending (1)          // crash
beginBoot → pending (2), failures=1
beginBoot → rollback_required (3), failures=2
requiresRollback() == true
// Manager later: flip Store + watchdog.reset() or markBootSuccessful after recovery boot
```

---

## Running tests

```powershell
cd packages\client\store-jvm
.\gradlew.bat test
```

```bash
cd packages/client && swift test
```
