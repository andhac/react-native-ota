package com.rnota.store

import java.io.File

/**
 * Configuration for [BundleStore].
 *
 * @property otaDirectory Absolute path to `<app-data>/ota`.
 * @property installedBinaryVersion Written into state when saving (reserved for M6).
 * @property supportedSchemaVersion Must match [OtaPaths.SUPPORTED_SCHEMA_VERSION] unless testing.
 * @property fileIo Injectable IO for crash / disk-full tests. Defaults to real FS.
 */
data class BundleStoreConfig(
  val otaDirectory: File,
  val installedBinaryVersion: String? = null,
  val supportedSchemaVersion: Int = OtaPaths.SUPPORTED_SCHEMA_VERSION,
  val fileIo: FileIo = FileIo.DEFAULT,
)
