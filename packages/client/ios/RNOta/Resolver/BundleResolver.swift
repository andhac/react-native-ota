import Foundation

/// M1 — Bundle Resolver (iOS).
///
/// Pure, synchronous, read-only filesystem resolution.
/// No AppDelegate / `bundleURL` wiring in this milestone.
/// Ignores `pendingSlot` (never boot pending; M6 owns promotion).
public final class BundleResolver: @unchecked Sendable {
  private let store: BundleStore

  public init(store: BundleStore) {
    self.store = store
  }

  /// Resolve the bundle for this launch. Never throws — failures become embedded.
  public func resolve() -> BundleResolution {
    let state = store.peekState()
    guard let activeSlot = state.activeSlot, !activeSlot.isEmpty else {
      return .embedded(reason: .noActiveSlot)
    }

    guard store.slotExists(activeSlot) else {
      return .embedded(reason: .slotNotFound)
    }

    guard store.hasCommittedBundle(activeSlot) else {
      return .embedded(reason: .bundleMissing)
    }

    let path = store.bundlePath(activeSlot).path
    return .ota(absolutePath: path, slotId: activeSlot)
  }
}
