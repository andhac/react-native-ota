package com.rnota.watchdog

/**
 * Lifecycle status persisted in `boot.json`.
 */
enum class BootStatus {
  /** No boot in progress; last outcome was idle/reset or never started. */
  IDLE,

  /** [BootWatchdog.beginBoot] recorded; awaiting [BootWatchdog.markBootSuccessful]. */
  PENDING,

  /** Last boot was confirmed successful. */
  CONFIRMED,

  /** Incomplete boots exceeded threshold — Manager should roll back (Watchdog does not). */
  ROLLBACK_REQUIRED,
}
