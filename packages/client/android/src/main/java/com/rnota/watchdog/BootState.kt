package com.rnota.watchdog

/**
 * In-memory / on-disk representation of `boot.json` (schema v1).
 *
 * Owned exclusively by [BootWatchdog]. Never written by Bundle Store recovery
 * beyond creating an empty placeholder file.
 */
data class BootState(
  val schemaVersion: Int = BootPaths.SUPPORTED_SCHEMA_VERSION,
  val bootAttempt: Int = 0,
  val lastSuccessfulBoot: Int = 0,
  val status: BootStatus = BootStatus.IDLE,
  val consecutiveFailures: Int = 0,
  val lastFailureReason: BootFailureReason = BootFailureReason.NONE,
) {
  companion object {
    val EMPTY: BootState = BootState()
  }
}
