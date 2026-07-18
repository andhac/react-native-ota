package com.rnota.watchdog

/**
 * Outcome of [BootWatchdog.beginBoot] (and readable snapshot fields).
 */
data class BootResult(
  val bootAttempt: Int,
  val status: BootStatus,
  val requiresRollback: Boolean,
  val failureReason: BootFailureReason,
  val consecutiveFailures: Int,
)
