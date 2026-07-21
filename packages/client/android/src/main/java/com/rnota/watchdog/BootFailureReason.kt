package com.rnota.watchdog

/**
 * Why the watchdog considers the boot unhealthy (diagnostics / future telemetry).
 */
enum class BootFailureReason {
  NONE,
  /** Previous launch called [BootWatchdog.beginBoot] but never [BootWatchdog.markBootSuccessful]. */
  INCOMPLETE_BOOT,
  /** [INCOMPLETE_BOOT] count reached [BootWatchdogConfig.maxFailedAttempts]. */
  REPEATED_FAILURES,
  /** `boot.json` was missing/corrupt/future-schema and was treated as empty (read path). */
  CORRUPT_BOOT_STATE,
}
