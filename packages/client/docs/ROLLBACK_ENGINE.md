# Rollback Engine — Design Notes

> Milestone: **MS3** — Rollback Engine only.  
> Watchdog detects failure; this module **performs** pointer recovery.

Sources:

- Android: `packages/client/android/src/main/java/com/rnota/rollback/`
- iOS: `packages/client/ios/RNOta/Rollback/`

---

## Responsibility

| Module | Role |
|---|---|
| Watchdog | Sets `requiresRollback()` via `boot.json` |
| **Rollback Engine** | Flips `state.json` pointers through Bundle Store APIs |
| Bundle Store | Owns atomic `state.json` |
| Resolver | Unchanged — reads whatever is active next launch |

Never deletes slots. Never runs GC. Never edits files outside Store APIs.

---

## Algorithm

```
rollbackIfNeeded():
  if !watchdog.requiresRollback() → NO_ACTION / NOT_REQUIRED
  else apply decideTarget(peekState()) + watchdog.reset()

decideTarget(state):
  if previousSlot usable (dir + bundle.hbc) → ROLLBACK_TO_PREVIOUS
  else → ROLLBACK_TO_EMBEDDED

apply:
  saveState({
    activeSlot: previous | null,
    pendingSlot: null,
    previousSlot: null
  })
  // failed slot files remain on disk (unreferenced)
  watchdog.reset()
```

---

## Public API

| Method | Behavior |
|---|---|
| `currentRollbackDecision()` | Read-only advice from watchdog + store |
| `canRollback()` | Previous usable **or** active/pending can be cleared |
| `rollbackIfNeeded()` | Act only when watchdog requires |
| `rollbackToPrevious()` | Explicit previous (or embedded if unusable) |
| `rollbackToEmbedded()` | Explicit clear to embedded |

---

## State transition example

Before (failed OTA):

```json
{ "activeSlot": "bad", "pendingSlot": null, "previousSlot": "good" }
```

After `rollbackIfNeeded()`:

```json
{ "activeSlot": "good", "pendingSlot": null, "previousSlot": null }
```

`slots/bad/` still exists on disk.

---

## Invariants

1. All `state.json` writes go through `BundleStore.saveState` (atomic).
2. No `deleteSlot` / `garbageCollect` in this module.
3. Idempotent: already at target → `performed=false`, `ALREADY_AT_TARGET`.
4. After a performed/handled watchdog rollback, watchdog is `reset()` so the signal clears.

---

## Running tests

```powershell
cd packages\client\store-jvm
.\gradlew.bat test
```

```bash
cd packages/client && swift test
```
