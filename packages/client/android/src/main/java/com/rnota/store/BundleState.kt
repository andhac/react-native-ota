package com.rnota.store

/**
 * In-memory representation of `state.json` (schema v1).
 * The store owns all mutations; other modules only receive snapshots.
 */
data class BundleState(
  val schemaVersion: Int = OtaPaths.SUPPORTED_SCHEMA_VERSION,
  val installedBinaryVersion: String? = null,
  val activeSlot: String? = null,
  val pendingSlot: String? = null,
  val previousSlot: String? = null,
) {
  fun referencedSlotIds(): Set<String> =
    buildSet {
      activeSlot?.let { add(it) }
      pendingSlot?.let { add(it) }
      previousSlot?.let { add(it) }
    }

  companion object {
    val EMPTY: BundleState = BundleState()
  }
}
