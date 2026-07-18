package com.rnota.store

import java.io.File

/**
 * Snapshot of one slot directory under `ota/slots/<id>/`.
 *
 * A slot is **committed** only when [bundleFile] exists as a regular file.
 * [createSlot] never marks a slot active — activation is explicit via [BundleStore.setActiveSlot].
 */
data class SlotInfo(
  val id: String,
  val directory: File,
  val bundleFile: File,
  val manifestFile: File,
  val assetsDirectory: File,
  val isCommitted: Boolean,
)
