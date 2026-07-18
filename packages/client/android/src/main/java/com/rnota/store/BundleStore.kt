package com.rnota.store

import java.io.File
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * M2 — Bundle Store.
 *
 * Owns on-disk OTA layout and atomic `state.json` transitions.
 * Deliberately unaware of networking, crypto, and React Native bundle loading.
 *
 * Thread-safe: all public mutations take an internal lock.
 */
class BundleStore(
  private val config: BundleStoreConfig,
) {
  private val lock = ReentrantLock()
  private val io: FileIo = config.fileIo

  private val otaDir: File = config.otaDirectory
  private val stateFile: File = File(otaDir, OtaPaths.STATE_FILE_NAME)
  private val stateTempFile: File = File(otaDir, OtaPaths.STATE_TEMP_FILE_NAME)
  private val bootFile: File = File(otaDir, OtaPaths.BOOT_FILE_NAME)
  private val slotsDir: File = File(otaDir, OtaPaths.SLOTS_DIR_NAME)

  @Volatile
  private var cachedState: BundleState = BundleState.EMPTY

  /** Creates `<ota>/`, `slots/`, `boot.json`, and recovers `state.json` into a valid snapshot. */
  fun initialize() =
    lock.withLock {
      ensureLayout()
      discardIncompleteTemp()
      cachedState = readAndRecoverLocked()
    }

  /** Reads only `state.json` (plus recovery rewrite if needed). Does not scan `slots/`. */
  fun loadState(): BundleState =
    lock.withLock {
      ensureLayout()
      discardIncompleteTemp()
      cachedState = readAndRecoverLocked()
      cachedState
    }

  /** Atomically persists [state] (write temp → fsync → rename). */
  fun saveState(state: BundleState) =
    lock.withLock {
      ensureLayout()
      val normalized =
        state.copy(
          schemaVersion = config.supportedSchemaVersion,
          installedBinaryVersion = state.installedBinaryVersion ?: config.installedBinaryVersion,
          activeSlot = OtaStateCodec.sanitizeSlotId(state.activeSlot),
          pendingSlot = OtaStateCodec.sanitizeSlotId(state.pendingSlot),
          previousSlot = OtaStateCodec.sanitizeSlotId(state.previousSlot),
        )
      writeStateLocked(normalized)
      cachedState = normalized
    }

  /**
   * Creates `slots/<id>/` with `assets/`. Does **not** create `bundle.hbc` / `manifest.json`
   * (those are written by Downloader / Verifier). Never touches `state.json`.
   */
  fun createSlot(slotId: String): SlotInfo =
    lock.withLock {
      requireValidSlotId(slotId)
      val dir = slotDirectory(slotId)
      if (io.exists(dir)) {
        throw BundleStoreException.SlotAlreadyExists(slotId)
      }
      val assets = File(dir, OtaPaths.ASSETS_DIR_NAME)
      if (!io.mkdir(dir) || !io.mkdir(assets)) {
        throw BundleStoreException.IoFailure("Failed to create slot directories for $slotId")
      }
      slotInfoLocked(slotId)
    }

  fun deleteSlot(slotId: String) =
    lock.withLock {
      requireValidSlotId(slotId)
      val state = cachedState
      if (slotId in state.referencedSlotIds()) {
        throw BundleStoreException.SlotInUse(slotId)
      }
      val dir = slotDirectory(slotId)
      if (!io.exists(dir)) {
        throw BundleStoreException.SlotNotFound(slotId)
      }
      if (!io.deleteRecursively(dir)) {
        throw BundleStoreException.IoFailure("Failed to delete slot $slotId")
      }
    }

  fun slotExists(slotId: String): Boolean =
    lock.withLock {
      if (!OtaStateCodec.isValidSlotId(slotId)) {
        return false
      }
      io.isDirectory(slotDirectory(slotId))
    }

  /** Explicit directory scan — call only when listing is required. */
  fun listSlots(): List<SlotInfo> =
    lock.withLock {
      ensureLayout()
      io.listNames(slotsDir)
        .filter { OtaStateCodec.isValidSlotId(it) }
        .sorted()
        .map { slotInfoLocked(it) }
    }

  /**
   * Explicitly marks [slotId] as active. Requires a **committed** slot (`bundle.hbc` present).
   * Pass null to clear (embedded fallback). Does not auto-promote pending.
   */
  fun setActiveSlot(slotId: String?) =
    lock.withLock {
      val next =
        when (slotId) {
          null -> cachedState.copy(activeSlot = null)
          else -> {
            requireValidSlotId(slotId)
            requireCommittedSlot(slotId)
            cachedState.copy(activeSlot = slotId)
          }
        }
      writeStateLocked(withBinaryVersion(next))
      cachedState = withBinaryVersion(next)
    }

  fun setPendingSlot(slotId: String?) =
    lock.withLock {
      val next =
        when (slotId) {
          null -> cachedState.copy(pendingSlot = null)
          else -> {
            requireValidSlotId(slotId)
            requireCommittedSlot(slotId)
            cachedState.copy(pendingSlot = slotId)
          }
        }
      writeStateLocked(withBinaryVersion(next))
      cachedState = withBinaryVersion(next)
    }

  fun clearPendingSlot() {
    setPendingSlot(null)
  }

  fun setPreviousSlot(slotId: String?) =
    lock.withLock {
      val next =
        when (slotId) {
          null -> cachedState.copy(previousSlot = null)
          else -> {
            requireValidSlotId(slotId)
            requireCommittedSlot(slotId)
            cachedState.copy(previousSlot = slotId)
          }
        }
      writeStateLocked(withBinaryVersion(next))
      cachedState = withBinaryVersion(next)
    }

  /**
   * Deletes slot directories not referenced by active / pending / previous.
   * Retention policy: active + previous + at most one pending (ARCHITECTURE.md M2).
   */
  fun garbageCollect(): List<String> =
    lock.withLock {
      ensureLayout()
      val keep = cachedState.referencedSlotIds()
      val deleted = mutableListOf<String>()
      for (name in io.listNames(slotsDir)) {
        if (!OtaStateCodec.isValidSlotId(name)) {
          // Non-conforming directory names are removed defensively.
          io.deleteRecursively(File(slotsDir, name))
          deleted.add(name)
          continue
        }
        if (name !in keep) {
          io.deleteRecursively(File(slotsDir, name))
          deleted.add(name)
        }
      }
      deleted.sorted()
    }

  /** Path helpers for other modules (Downloader writes into these). */
  fun slotDirectory(slotId: String): File = File(slotsDir, slotId)

  fun bundlePath(slotId: String): File = File(slotDirectory(slotId), OtaPaths.BUNDLE_FILE_NAME)

  fun manifestPath(slotId: String): File = File(slotDirectory(slotId), OtaPaths.MANIFEST_FILE_NAME)

  fun assetsDirectory(slotId: String): File = File(slotDirectory(slotId), OtaPaths.ASSETS_DIR_NAME)

  fun currentState(): BundleState = lock.withLock { cachedState }

  // region internals

  private fun ensureLayout() {
    if (!io.mkdir(otaDir)) {
      throw BundleStoreException.IoFailure("Cannot create OTA directory: $otaDir")
    }
    if (!io.mkdir(slotsDir)) {
      throw BundleStoreException.IoFailure("Cannot create slots directory: $slotsDir")
    }
    if (!io.exists(bootFile)) {
      // M6 owns boot.json semantics; we only ensure the path exists.
      try {
        io.writeAtomic(bootFile, File(otaDir, "${OtaPaths.BOOT_FILE_NAME}.tmp"), "{}\n".toByteArray())
      } catch (e: BundleStoreException) {
        throw e
      } catch (e: Exception) {
        throw BundleStoreException.IoFailure("Cannot create boot.json", e)
      }
    }
  }

  private fun discardIncompleteTemp() {
    if (io.exists(stateTempFile)) {
      io.deleteRecursively(stateTempFile)
    }
  }

  private fun readAndRecoverLocked(): BundleState {
    val loaded =
      if (!io.exists(stateFile) || !io.isFile(stateFile)) {
        null
      } else {
        try {
          OtaStateCodec.decode(io.readBytes(stateFile), config.supportedSchemaVersion)
        } catch (e: Exception) {
          null
        }
      }

    val base = loaded ?: BundleState.EMPTY.copy(installedBinaryVersion = config.installedBinaryVersion)
    val recovered = sanitizeReferences(base)
    val needsRewrite =
      loaded == null ||
        recovered != base ||
        recovered.schemaVersion != config.supportedSchemaVersion

    val toPersist =
      recovered.copy(
        schemaVersion = config.supportedSchemaVersion,
        installedBinaryVersion = recovered.installedBinaryVersion ?: config.installedBinaryVersion,
      )

    if (needsRewrite) {
      writeStateLocked(toPersist)
    }
    return toPersist
  }

  private fun sanitizeReferences(state: BundleState): BundleState {
    fun resolve(id: String?): String? {
      if (id == null) return null
      val sanitized = OtaStateCodec.sanitizeSlotId(id) ?: return null
      return if (isCommittedLocked(sanitized)) sanitized else null
    }
    return state.copy(
      activeSlot = resolve(state.activeSlot),
      pendingSlot = resolve(state.pendingSlot),
      previousSlot = resolve(state.previousSlot),
    )
  }

  private fun writeStateLocked(state: BundleState) {
    val bytes = OtaStateCodec.encode(state)
    io.writeAtomic(stateFile, stateTempFile, bytes)
  }

  private fun withBinaryVersion(state: BundleState): BundleState =
    state.copy(
      schemaVersion = config.supportedSchemaVersion,
      installedBinaryVersion = state.installedBinaryVersion ?: config.installedBinaryVersion,
    )

  private fun requireValidSlotId(slotId: String) {
    if (!OtaStateCodec.isValidSlotId(slotId)) {
      throw BundleStoreException.InvalidSlotId(slotId)
    }
  }

  private fun requireCommittedSlot(slotId: String) {
    if (!io.isDirectory(slotDirectory(slotId))) {
      throw BundleStoreException.SlotNotFound(slotId)
    }
    if (!isCommittedLocked(slotId)) {
      throw BundleStoreException.SlotNotCommitted(slotId)
    }
  }

  private fun isCommittedLocked(slotId: String): Boolean = io.isFile(bundlePath(slotId))

  private fun slotInfoLocked(slotId: String): SlotInfo {
    val dir = slotDirectory(slotId)
    return SlotInfo(
      id = slotId,
      directory = dir,
      bundleFile = bundlePath(slotId),
      manifestFile = manifestPath(slotId),
      assetsDirectory = assetsDirectory(slotId),
      isCommitted = isCommittedLocked(slotId),
    )
  }

  // endregion
}
