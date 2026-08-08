package com.rnota.rollback

/**
 * What the engine intends to do (diagnostics + [RollbackEngine.currentRollbackDecision]).
 */
enum class RollbackDecision {
  /** Watchdog does not require rollback, or there is nothing to change. */
  NO_ACTION,

  /** Activate a usable [com.rnota.store.BundleState.previousSlot]. */
  ROLLBACK_TO_PREVIOUS,

  /** Clear active OTA pointer → embedded bundle. */
  ROLLBACK_TO_EMBEDDED,
}
