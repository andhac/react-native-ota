package com.rnota.resolver

import com.rnota.store.BundleStore

/**
 * M1 — Bundle Resolver (Android).
 *
 * Pure, synchronous, read-only: given [BundleStore] pointer state, decide which
 * JS bundle path React Native should load. Never mutates `state.json`.
 *
 * No React Native host integration in this milestone — filesystem resolution only.
 * Ignores `pendingSlot` (never boot pending; M6 owns promotion).
 */
class BundleResolver(
  private val store: BundleStore,
) {
  /**
   * Resolve the bundle for this launch.
   * Deterministic: same store disk state → same [BundleResolution].
   * Any unexpected exception → embedded ([ResolutionReason.EMBEDDED]).
   */
  fun resolve(): BundleResolution =
    try {
      resolveInternal()
    } catch (_: Exception) {
      BundleResolution.embedded(ResolutionReason.EMBEDDED)
    }

  private fun resolveInternal(): BundleResolution {
    val state = store.peekState()
    val activeSlot = state.activeSlot
    if (activeSlot.isNullOrEmpty()) {
      return BundleResolution.embedded(ResolutionReason.NO_ACTIVE_SLOT)
    }

    if (!store.slotExists(activeSlot)) {
      return BundleResolution.embedded(ResolutionReason.SLOT_NOT_FOUND)
    }

    if (!store.hasCommittedBundle(activeSlot)) {
      return BundleResolution.embedded(ResolutionReason.BUNDLE_MISSING)
    }

    val path = store.bundlePath(activeSlot)
    return BundleResolution.ota(
      absolutePath = path.absolutePath,
      slotId = activeSlot,
    )
  }
}
