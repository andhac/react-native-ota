import Foundation

/// Rollback Engine — flips Bundle Store pointers when the Watchdog requires recovery.
/// Never deletes slots or runs GC. Uses only Bundle Store public APIs for `state.json`.
public final class RollbackEngine: @unchecked Sendable {
  private let store: BundleStore
  private let watchdog: BootWatchdog
  private let lock = NSRecursiveLock()

  public init(store: BundleStore, watchdog: BootWatchdog) {
    self.store = store
    self.watchdog = watchdog
  }

  public func currentRollbackDecision() -> RollbackDecision {
    withLock {
      guard watchdog.requiresRollback() else { return .noAction }
      return decideTarget(store.peekState()).decision
    }
  }

  public func canRollback() -> Bool {
    withLock {
      let state = store.peekState()
      return usablePrevious(state) != nil || state.activeSlot != nil || state.pendingSlot != nil
    }
  }

  @discardableResult
  public func rollbackIfNeeded() throws -> RollbackResult {
    try withLock {
      let state = store.peekState()
      guard watchdog.requiresRollback() else {
        return noOp(decision: .noAction, reason: .notRequired, state: state)
      }
      return try applyDecision(plan: decideTarget(state), trigger: .watchdogRequired)
    }
  }

  @discardableResult
  public func rollbackToPrevious() throws -> RollbackResult {
    try withLock {
      let plan = decideTarget(store.peekState())
      let reason: RollbackReason
      if plan.previousMissing {
        reason = .previousMissing
      } else if plan.previousBundleMissing {
        reason = .previousBundleMissing
      } else {
        reason = .explicitPrevious
      }
      return try applyDecision(plan: plan, trigger: reason)
    }
  }

  @discardableResult
  public func rollbackToEmbedded() throws -> RollbackResult {
    try withLock {
      try applyDecision(
        plan: TargetPlan(
          decision: .rollbackToEmbedded,
          toSlotId: nil,
          previousMissing: false,
          previousBundleMissing: false
        ),
        trigger: .explicitEmbedded
      )
    }
  }

  private struct TargetPlan {
    var decision: RollbackDecision
    var toSlotId: String?
    var previousMissing: Bool
    var previousBundleMissing: Bool
  }

  private func decideTarget(_ state: BundleState) -> TargetPlan {
    guard let previous = state.previousSlot else {
      return TargetPlan(
        decision: .rollbackToEmbedded,
        toSlotId: nil,
        previousMissing: true,
        previousBundleMissing: false
      )
    }
    if !store.slotExists(previous) || !store.hasCommittedBundle(previous) {
      return TargetPlan(
        decision: .rollbackToEmbedded,
        toSlotId: nil,
        previousMissing: false,
        previousBundleMissing: true
      )
    }
    return TargetPlan(
      decision: .rollbackToPrevious,
      toSlotId: previous,
      previousMissing: false,
      previousBundleMissing: false
    )
  }

  private func usablePrevious(_ state: BundleState) -> String? {
    guard let previous = state.previousSlot else { return nil }
    return store.slotExists(previous) && store.hasCommittedBundle(previous) ? previous : nil
  }

  private func applyDecision(plan: TargetPlan, trigger: RollbackReason) throws -> RollbackResult {
    let before = store.peekState()
    let from = before.activeSlot
    let already =
      before.activeSlot == plan.toSlotId &&
      before.pendingSlot == nil &&
      before.previousSlot == nil

    if already {
      if watchdog.requiresRollback() {
        _ = try watchdog.reset()
      }
      return RollbackResult(
        performed: false,
        decision: plan.decision,
        reason: .alreadyAtTarget,
        fromSlotId: from,
        toSlotId: plan.toSlotId,
        activeSlotAfter: before.activeSlot,
        pendingCleared: true
      )
    }

    try store.saveState(
      BundleState(
        schemaVersion: before.schemaVersion,
        installedBinaryVersion: before.installedBinaryVersion,
        activeSlot: plan.toSlotId,
        pendingSlot: nil,
        previousSlot: nil
      )
    )
    _ = try watchdog.reset()

    let after = store.peekState()
    let reason: RollbackReason
    if (trigger == .watchdogRequired || trigger == .explicitPrevious) && plan.previousMissing {
      reason = .previousMissing
    } else if (trigger == .watchdogRequired || trigger == .explicitPrevious) && plan.previousBundleMissing {
      reason = .previousBundleMissing
    } else {
      reason = trigger
    }

    return RollbackResult(
      performed: true,
      decision: plan.decision,
      reason: reason,
      fromSlotId: from,
      toSlotId: plan.toSlotId,
      activeSlotAfter: after.activeSlot,
      pendingCleared: true
    )
  }

  private func noOp(decision: RollbackDecision, reason: RollbackReason, state: BundleState) -> RollbackResult {
    RollbackResult(
      performed: false,
      decision: decision,
      reason: reason,
      fromSlotId: state.activeSlot,
      toSlotId: state.activeSlot,
      activeSlotAfter: state.activeSlot,
      pendingCleared: false
    )
  }

  private func withLock<T>(_ body: () throws -> T) rethrows -> T {
    lock.lock()
    defer { lock.unlock() }
    return try body()
  }
}
