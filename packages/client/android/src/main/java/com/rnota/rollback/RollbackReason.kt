package com.rnota.rollback

/**
 * Why a rollback decision was made or skipped.
 */
enum class RollbackReason {
  NONE,
  /** [com.rnota.watchdog.BootWatchdog.requiresRollback] was false. */
  NOT_REQUIRED,
  /** Watchdog requested recovery. */
  WATCHDOG_REQUIRED,
  /** Caller invoked [RollbackEngine.rollbackToPrevious]. */
  EXPLICIT_PREVIOUS,
  /** Caller invoked [RollbackEngine.rollbackToEmbedded]. */
  EXPLICIT_EMBEDDED,
  /** `previousSlot` was null. */
  PREVIOUS_MISSING,
  /** `previousSlot` directory or `bundle.hbc` missing → embedded used instead. */
  PREVIOUS_BUNDLE_MISSING,
  /** Pointers already match the target (idempotent). */
  ALREADY_AT_TARGET,
}
