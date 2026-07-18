package com.rnota.watchdog

import com.rnota.store.BundleStoreException
import com.rnota.store.FileIo
import java.io.File
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * M6 — Boot Watchdog (Android).
 *
 * Owns `boot.json` only. Detects incomplete boots and reports when a rollback
 * is advised. Does **not** mutate Bundle Store pointers or delete slots —
 * the Update Manager performs rollback in a later milestone.
 *
 * All public methods are synchronous and filesystem-only.
 */
class BootWatchdog(
  private val config: BootWatchdogConfig,
) {
  private val lock = ReentrantLock()
  private val io: FileIo = config.fileIo
  private val bootFile: File = File(config.otaDirectory, BootPaths.BOOT_FILE_NAME)
  private val bootTempFile: File = File(config.otaDirectory, BootPaths.BOOT_TEMP_FILE_NAME)

  @Volatile
  private var cached: BootState = BootState.EMPTY

  /**
   * Record the start of a launch that will load a bundle.
   *
   * If the previous status was [BootStatus.PENDING], the prior launch never
   * confirmed success → counts as an incomplete boot toward the failure threshold.
   */
  fun beginBoot(): BootResult =
    lock.withLock {
      val previous = readStateLocked()
      val next =
        when (previous.status) {
          BootStatus.PENDING -> {
            val failures = previous.consecutiveFailures + 1
            val rollback = failures >= config.maxFailedAttempts
            previous.copy(
              schemaVersion = config.supportedSchemaVersion,
              bootAttempt = previous.bootAttempt + 1,
              status = if (rollback) BootStatus.ROLLBACK_REQUIRED else BootStatus.PENDING,
              consecutiveFailures = failures,
              lastFailureReason =
                if (rollback) {
                  BootFailureReason.REPEATED_FAILURES
                } else {
                  BootFailureReason.INCOMPLETE_BOOT
                },
            )
          }
          BootStatus.ROLLBACK_REQUIRED -> {
            // Stay in rollback-required until Manager acts + [reset] / success after recovery.
            previous.copy(
              schemaVersion = config.supportedSchemaVersion,
              bootAttempt = previous.bootAttempt + 1,
              lastFailureReason = BootFailureReason.REPEATED_FAILURES,
            )
          }
          BootStatus.IDLE, BootStatus.CONFIRMED -> {
            previous.copy(
              schemaVersion = config.supportedSchemaVersion,
              bootAttempt = previous.bootAttempt + 1,
              status = BootStatus.PENDING,
              // Do not clear consecutiveFailures here — only markBootSuccessful / reset clear them.
              lastFailureReason = BootFailureReason.NONE,
            )
          }
        }
      writeStateLocked(next)
      cached = next
      toResult(next)
    }

  /** Application finished a healthy boot (future: JS `notifyAppReady`). */
  fun markBootSuccessful(): BootResult =
    lock.withLock {
      val previous = readStateLocked()
      val next =
        previous.copy(
          schemaVersion = config.supportedSchemaVersion,
          lastSuccessfulBoot = previous.bootAttempt,
          status = BootStatus.CONFIRMED,
          consecutiveFailures = 0,
          lastFailureReason = BootFailureReason.NONE,
        )
      writeStateLocked(next)
      cached = next
      toResult(next)
    }

  fun currentStatus(): BootStatus =
    lock.withLock {
      readStateLocked().status
    }

  fun requiresRollback(): Boolean =
    lock.withLock {
      readStateLocked().status == BootStatus.ROLLBACK_REQUIRED
    }

  /** Clears watchdog state to [BootState.EMPTY]. Does not touch `state.json` or slots. */
  fun reset(): BootResult =
    lock.withLock {
      val next = BootState.EMPTY.copy(schemaVersion = config.supportedSchemaVersion)
      writeStateLocked(next)
      cached = next
      toResult(next)
    }

  /** Read-only snapshot (may recover empty in memory for corrupt files without rewriting). */
  fun currentState(): BootState =
    lock.withLock {
      readStateLocked()
    }

  // region internals

  private fun readStateLocked(): BootState {
    discardIncompleteTemp()
    if (!io.exists(bootFile) || !io.isFile(bootFile)) {
      return BootState.EMPTY
    }
    return try {
      val decoded = BootStateCodec.decode(io.readBytes(bootFile), config.supportedSchemaVersion)
      decoded ?: BootState.EMPTY.copy(lastFailureReason = BootFailureReason.CORRUPT_BOOT_STATE)
    } catch (_: Exception) {
      BootState.EMPTY.copy(lastFailureReason = BootFailureReason.CORRUPT_BOOT_STATE)
    }
  }

  private fun writeStateLocked(state: BootState) {
    if (!io.mkdir(config.otaDirectory)) {
      throw BundleStoreException.IoFailure("Cannot create OTA directory for boot.json")
    }
    val bytes = BootStateCodec.encode(state)
    io.writeAtomic(bootFile, bootTempFile, bytes)
  }

  private fun discardIncompleteTemp() {
    if (io.exists(bootTempFile)) {
      io.deleteRecursively(bootTempFile)
    }
  }

  private fun toResult(state: BootState): BootResult =
    BootResult(
      bootAttempt = state.bootAttempt,
      status = state.status,
      requiresRollback = state.status == BootStatus.ROLLBACK_REQUIRED,
      failureReason = state.lastFailureReason,
      consecutiveFailures = state.consecutiveFailures,
    )

  // endregion
}
