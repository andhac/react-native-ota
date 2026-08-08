package com.rnota.rollback

import com.rnota.store.BundleState
import com.rnota.store.BundleStore
import com.rnota.watchdog.BootWatchdog
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * M6 companion — Rollback Engine.
 *
 * Performs pointer flips on [BundleStore] when [BootWatchdog] reports failure
 * (or when an explicit rollback API is called). Never deletes slots or runs GC.
 *
 * Uses only Bundle Store public APIs for `state.json` mutations.
 */
class RollbackEngine(
  private val store: BundleStore,
  private val watchdog: BootWatchdog,
) {
  private val lock = ReentrantLock()

  /**
   * Advises the next recovery action based on watchdog + store pointers.
   * Does not mutate anything.
   */
  fun currentRollbackDecision(): RollbackDecision =
    lock.withLock {
      if (!watchdog.requiresRollback()) {
        return RollbackDecision.NO_ACTION
      }
      decideTarget(store.peekState()).decision
    }

  /**
   * True when there is a recoverable OTA posture:
   * usable previous slot, or a non-null active slot that can be cleared to embedded.
   */
  fun canRollback(): Boolean =
    lock.withLock {
      val state = store.peekState()
      usablePrevious(state) != null || state.activeSlot != null || state.pendingSlot != null
    }

  /**
   * If the watchdog requires rollback, apply [currentRollbackDecision] and reset the watchdog.
   * If the watchdog reports success / no requirement → no-op.
   */
  fun rollbackIfNeeded(): RollbackResult =
    lock.withLock {
      if (!watchdog.requiresRollback()) {
        return@withLock noOp(
          decision = RollbackDecision.NO_ACTION,
          reason = RollbackReason.NOT_REQUIRED,
          state = store.peekState(),
        )
      }
      applyDecision(
        plan = decideTarget(store.peekState()),
        trigger = RollbackReason.WATCHDOG_REQUIRED,
      )
    }

  /** Explicitly activate previous (or embedded if previous unusable). */
  fun rollbackToPrevious(): RollbackResult =
    lock.withLock {
      val state = store.peekState()
      val plan = decideTarget(state)
      val reason =
        when {
          plan.previousMissing -> RollbackReason.PREVIOUS_MISSING
          plan.previousBundleMissing -> RollbackReason.PREVIOUS_BUNDLE_MISSING
          plan.decision == RollbackDecision.ROLLBACK_TO_PREVIOUS -> RollbackReason.EXPLICIT_PREVIOUS
          else -> RollbackReason.EXPLICIT_PREVIOUS
        }
      applyDecision(plan = plan, trigger = reason)
    }

  /** Explicitly clear active/pending → embedded. Keeps slot files on disk. */
  fun rollbackToEmbedded(): RollbackResult =
    lock.withLock {
      applyDecision(
        plan =
          TargetPlan(
            decision = RollbackDecision.ROLLBACK_TO_EMBEDDED,
            toSlotId = null,
            previousMissing = false,
            previousBundleMissing = false,
          ),
        trigger = RollbackReason.EXPLICIT_EMBEDDED,
      )
    }

  // region internals

  private data class TargetPlan(
    val decision: RollbackDecision,
    val toSlotId: String?,
    val previousMissing: Boolean,
    val previousBundleMissing: Boolean,
  )

  private fun decideTarget(state: BundleState): TargetPlan {
    val previous = state.previousSlot
    if (previous == null) {
      return TargetPlan(
        decision = RollbackDecision.ROLLBACK_TO_EMBEDDED,
        toSlotId = null,
        previousMissing = true,
        previousBundleMissing = false,
      )
    }
    if (!store.slotExists(previous) || !store.hasCommittedBundle(previous)) {
      return TargetPlan(
        decision = RollbackDecision.ROLLBACK_TO_EMBEDDED,
        toSlotId = null,
        previousMissing = false,
        previousBundleMissing = true,
      )
    }
    return TargetPlan(
      decision = RollbackDecision.ROLLBACK_TO_PREVIOUS,
      toSlotId = previous,
      previousMissing = false,
      previousBundleMissing = false,
    )
  }

  private fun usablePrevious(state: BundleState): String? {
    val previous = state.previousSlot ?: return null
    return if (store.slotExists(previous) && store.hasCommittedBundle(previous)) previous else null
  }

  private fun applyDecision(
    plan: TargetPlan,
    trigger: RollbackReason,
  ): RollbackResult {
    val before = store.peekState()
    val from = before.activeSlot
    val already =
      before.activeSlot == plan.toSlotId &&
        before.pendingSlot == null &&
        before.previousSlot == null

    if (already) {
      // Still clear watchdog if it was asking for rollback.
      if (watchdog.requiresRollback()) {
        watchdog.reset()
      }
      return RollbackResult(
        performed = false,
        decision = plan.decision,
        reason = RollbackReason.ALREADY_AT_TARGET,
        fromSlotId = from,
        toSlotId = plan.toSlotId,
        activeSlotAfter = before.activeSlot,
        pendingCleared = true,
      )
    }

    // Single atomic state.json replace via Store API — never edit files directly.
    // Failed slot remains on disk; previous pointer cleared (quarantine by unreference).
    store.saveState(
      before.copy(
        activeSlot = plan.toSlotId,
        pendingSlot = null,
        previousSlot = null,
      ),
    )

    watchdog.reset()

    val after = store.peekState()
    val reason =
      when {
        trigger == RollbackReason.WATCHDOG_REQUIRED && plan.previousMissing ->
          RollbackReason.PREVIOUS_MISSING
        trigger == RollbackReason.WATCHDOG_REQUIRED && plan.previousBundleMissing ->
          RollbackReason.PREVIOUS_BUNDLE_MISSING
        trigger == RollbackReason.EXPLICIT_PREVIOUS && plan.previousMissing ->
          RollbackReason.PREVIOUS_MISSING
        trigger == RollbackReason.EXPLICIT_PREVIOUS && plan.previousBundleMissing ->
          RollbackReason.PREVIOUS_BUNDLE_MISSING
        else -> trigger
      }

    return RollbackResult(
      performed = true,
      decision = plan.decision,
      reason = reason,
      fromSlotId = from,
      toSlotId = plan.toSlotId,
      activeSlotAfter = after.activeSlot,
      pendingCleared = true,
    )
  }

  private fun noOp(
    decision: RollbackDecision,
    reason: RollbackReason,
    state: BundleState,
  ): RollbackResult =
    RollbackResult(
      performed = false,
      decision = decision,
      reason = reason,
      fromSlotId = state.activeSlot,
      toSlotId = state.activeSlot,
      activeSlotAfter = state.activeSlot,
      pendingCleared = false,
    )

  // endregion
}
