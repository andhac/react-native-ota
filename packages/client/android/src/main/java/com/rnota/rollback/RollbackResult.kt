package com.rnota.rollback

/**
 * Outcome of a rollback API call.
 *
 * @property toSlotId null means embedded bundle.
 */
data class RollbackResult(
  val performed: Boolean,
  val decision: RollbackDecision,
  val reason: RollbackReason,
  val fromSlotId: String?,
  val toSlotId: String?,
  val activeSlotAfter: String?,
  val pendingCleared: Boolean,
)
