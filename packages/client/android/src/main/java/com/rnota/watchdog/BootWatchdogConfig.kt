package com.rnota.watchdog

import com.rnota.store.FileIo
import java.io.File

/**
 * Configuration for [BootWatchdog].
 *
 * @property otaDirectory Absolute path to `<app-data>/ota` (same root as Bundle Store).
 * @property maxFailedAttempts Incomplete boots allowed before [BootStatus.ROLLBACK_REQUIRED]
 *   (ARCHITECTURE.md M6 default: 2).
 */
data class BootWatchdogConfig(
  val otaDirectory: File,
  val maxFailedAttempts: Int = 2,
  val supportedSchemaVersion: Int = BootPaths.SUPPORTED_SCHEMA_VERSION,
  val fileIo: FileIo = FileIo.DEFAULT,
) {
  init {
    require(maxFailedAttempts >= 1) { "maxFailedAttempts must be >= 1" }
  }
}

internal object BootPaths {
  const val BOOT_FILE_NAME: String = "boot.json"
  const val BOOT_TEMP_FILE_NAME: String = "boot.json.tmp"
  const val SUPPORTED_SCHEMA_VERSION: Int = 1
}
