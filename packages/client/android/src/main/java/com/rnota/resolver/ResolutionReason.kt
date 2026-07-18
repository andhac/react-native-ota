package com.rnota.resolver

/**
 * Diagnostic reason for a [BundleResolution].
 * For telemetry / logging only — never used to drive control flow outside the resolver.
 */
enum class ResolutionReason {
  /** `activeSlot` is null after a read-only peek. */
  NO_ACTIVE_SLOT,

  /** Active slot id is present but the slot directory is missing. */
  SLOT_NOT_FOUND,

  /** Slot directory exists but `bundle.hbc` is missing. */
  BUNDLE_MISSING,

  /** Active slot has a usable `bundle.hbc`. */
  ACTIVE_BUNDLE,

  /** Catch-all embedded fallback (unexpected error while resolving). */
  EMBEDDED,
}
