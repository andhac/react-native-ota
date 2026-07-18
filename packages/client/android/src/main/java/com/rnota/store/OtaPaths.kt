package com.rnota.store

/**
 * Canonical relative paths under `<app-data>/ota/`.
 * No magic strings elsewhere in the store.
 */
object OtaPaths {
  const val OTA_DIR_NAME: String = "ota"
  const val STATE_FILE_NAME: String = "state.json"
  const val STATE_TEMP_FILE_NAME: String = "state.json.tmp"
  const val BOOT_FILE_NAME: String = "boot.json"
  const val SLOTS_DIR_NAME: String = "slots"
  const val BUNDLE_FILE_NAME: String = "bundle.hbc"
  const val MANIFEST_FILE_NAME: String = "manifest.json"
  const val ASSETS_DIR_NAME: String = "assets"

  /** Highest schemaVersion this binary understands (docs/specs/state-json.md). */
  const val SUPPORTED_SCHEMA_VERSION: Int = 1

  /** slot-id: `[A-Za-z0-9][A-Za-z0-9._-]{0,63}` */
  val SLOT_ID_REGEX: Regex = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$")
}
