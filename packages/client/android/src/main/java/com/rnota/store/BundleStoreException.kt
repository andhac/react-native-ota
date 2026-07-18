package com.rnota.store

/**
 * Typed failures for the Bundle Store.
 * Callers (M3/M5/M6) map these to telemetry; the boot-path resolver never uses this API.
 */
sealed class BundleStoreException(
  message: String,
  cause: Throwable? = null,
) : Exception(message, cause) {
  class InvalidSlotId(
    val slotId: String,
  ) : BundleStoreException("Invalid slot id: $slotId")

  class SlotNotFound(
    val slotId: String,
  ) : BundleStoreException("Slot not found: $slotId")

  class SlotAlreadyExists(
    val slotId: String,
  ) : BundleStoreException("Slot already exists: $slotId")

  class SlotNotCommitted(
    val slotId: String,
  ) : BundleStoreException("Slot is not committed (missing ${OtaPaths.BUNDLE_FILE_NAME}): $slotId")

  class SlotInUse(
    val slotId: String,
  ) : BundleStoreException("Slot is referenced by state and cannot be deleted: $slotId")

  class DiskFull(
    cause: Throwable? = null,
  ) : BundleStoreException("Disk full while writing OTA store", cause)

  class IoFailure(
    message: String,
    cause: Throwable? = null,
  ) : BundleStoreException(message, cause)
}
