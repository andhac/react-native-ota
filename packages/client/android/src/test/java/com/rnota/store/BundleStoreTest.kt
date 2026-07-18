package com.rnota.store

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class BundleStoreTest {
  @get:Rule
  val tmp = TemporaryFolder()

  private lateinit var otaDir: File
  private lateinit var store: BundleStore

  @Before
  fun setUp() {
    otaDir = tmp.newFolder("ota")
    store = BundleStore(BundleStoreConfig(otaDirectory = otaDir, installedBinaryVersion = "1.0.0"))
    store.initialize()
  }

  @Test
  fun initialize_createsLayout() {
    assertTrue(File(otaDir, OtaPaths.SLOTS_DIR_NAME).isDirectory)
    assertTrue(File(otaDir, OtaPaths.STATE_FILE_NAME).isFile)
    assertTrue(File(otaDir, OtaPaths.BOOT_FILE_NAME).isFile)
    assertEquals(BundleState.EMPTY.copy(installedBinaryVersion = "1.0.0", schemaVersion = 1), store.loadState())
  }

  @Test
  fun loadState_missingStateJson_recoversToEmpty() {
    File(otaDir, OtaPaths.STATE_FILE_NAME).delete()
    val state = store.loadState()
    assertNull(state.activeSlot)
    assertTrue(File(otaDir, OtaPaths.STATE_FILE_NAME).isFile)
  }

  @Test
  fun loadState_corruptedStateJson_recoversToEmpty() {
    File(otaDir, OtaPaths.STATE_FILE_NAME).writeText("{not-json")
    val state = store.loadState()
    assertNull(state.activeSlot)
    assertNull(state.pendingSlot)
  }

  @Test
  fun loadState_emptyStateJson_recoversToEmpty() {
    File(otaDir, OtaPaths.STATE_FILE_NAME).writeText("")
    val state = store.loadState()
    assertNull(state.activeSlot)
  }

  @Test
  fun loadState_futureSchema_recoversToEmpty() {
    File(otaDir, OtaPaths.STATE_FILE_NAME).writeText(
      """{"schemaVersion":99,"installedBinaryVersion":null,"activeSlot":null,"pendingSlot":null,"previousSlot":null}""",
    )
    val state = store.loadState()
    assertEquals(1, state.schemaVersion)
    assertNull(state.activeSlot)
  }

  @Test
  fun createSlot_doesNotActivate() {
    store.createSlot("rel-abc")
    assertNull(store.loadState().activeSlot)
    assertFalse(store.listSlots().single().isCommitted)
  }

  @Test
  fun setActiveSlot_requiresCommittedBundle() {
    store.createSlot("rel-abc")
    try {
      store.setActiveSlot("rel-abc")
      fail("expected SlotNotCommitted")
    } catch (_: BundleStoreException.SlotNotCommitted) {
      // expected
    }
    assertNull(store.currentState().activeSlot)
  }

  @Test
  fun setActiveSlot_explicitActivationOnly() {
    val slot = commitSlot("rel-abc")
    store.setActiveSlot(slot.id)
    assertEquals("rel-abc", store.loadState().activeSlot)
  }

  @Test
  fun pendingAndPrevious_independentOfActive() {
    commitSlot("active-1")
    commitSlot("pend-1")
    commitSlot("prev-1")
    store.setActiveSlot("active-1")
    store.setPendingSlot("pend-1")
    store.setPreviousSlot("prev-1")
    val state = store.loadState()
    assertEquals("active-1", state.activeSlot)
    assertEquals("pend-1", state.pendingSlot)
    assertEquals("prev-1", state.previousSlot)
    store.clearPendingSlot()
    assertNull(store.loadState().pendingSlot)
  }

  @Test
  fun invalidSlotId_rejected() {
    try {
      store.createSlot("../evil")
      fail()
    } catch (_: BundleStoreException.InvalidSlotId) {
    }
    try {
      store.createSlot(".hidden")
      fail()
    } catch (_: BundleStoreException.InvalidSlotId) {
    }
  }

  @Test
  fun danglingReference_droppedOnRecover() {
    commitSlot("gone")
    store.setActiveSlot("gone")
    store.bundlePath("gone").delete()
    val state = store.loadState()
    assertNull(state.activeSlot)
  }

  @Test
  fun orphanSlot_removedByGarbageCollect() {
    commitSlot("keep")
    commitSlot("orphan")
    store.setActiveSlot("keep")
    val deleted = store.garbageCollect()
    assertEquals(listOf("orphan"), deleted)
    assertTrue(store.slotExists("keep"))
    assertFalse(store.slotExists("orphan"))
  }

  @Test
  fun deleteSlot_refusesIfReferenced() {
    commitSlot("keep")
    store.setActiveSlot("keep")
    try {
      store.deleteSlot("keep")
      fail()
    } catch (_: BundleStoreException.SlotInUse) {
    }
  }

  @Test
  fun multipleSlots_listDoesNotAffectState() {
    commitSlot("a")
    commitSlot("b")
    assertEquals(listOf("a", "b"), store.listSlots().map { it.id })
    assertNull(store.loadState().activeSlot)
  }

  @Test
  fun crashDuringWrite_recoversWithoutTornState() {
    commitSlot("rel-ok")
    store.setActiveSlot("rel-ok")
    val before = File(otaDir, OtaPaths.STATE_FILE_NAME).readText()

    val controllable = ControllableFileIo(crashPoint = AtomicWriteCrashPoint.BEFORE_RENAME)
    val crashing =
      BundleStore(
        BundleStoreConfig(otaDirectory = otaDir, installedBinaryVersion = "1.0.0", fileIo = controllable),
      )
    crashing.initialize()
    try {
      crashing.setPendingSlot("rel-ok")
      fail("expected crash")
    } catch (_: SimulatedCrashException) {
    }

    assertTrue(
      "temp file should remain after crash before rename",
      File(otaDir, OtaPaths.STATE_TEMP_FILE_NAME).isFile,
    )
    // Old state.json must remain intact (rename never happened).
    assertEquals(before, File(otaDir, OtaPaths.STATE_FILE_NAME).readText())

    controllable.crashPoint = AtomicWriteCrashPoint.NONE
    val recovered =
      BundleStore(
        BundleStoreConfig(otaDirectory = otaDir, installedBinaryVersion = "1.0.0", fileIo = controllable),
      )
    recovered.initialize()
    assertEquals("rel-ok", recovered.loadState().activeSlot)
    assertNull(recovered.loadState().pendingSlot)
    assertFalse(File(otaDir, OtaPaths.STATE_TEMP_FILE_NAME).exists())
  }

  @Test
  fun atomicRenameRecovery_afterSuccessfulRename() {
    commitSlot("rel-ok")
    store.setActiveSlot("rel-ok")

    val controllable = ControllableFileIo(crashPoint = AtomicWriteCrashPoint.AFTER_RENAME)
    val crashing =
      BundleStore(
        BundleStoreConfig(otaDirectory = otaDir, installedBinaryVersion = "1.0.0", fileIo = controllable),
      )
    crashing.initialize()
    try {
      crashing.setPreviousSlot("rel-ok")
      fail("expected crash")
    } catch (_: SimulatedCrashException) {
    }

    // Rename completed — new state must be visible and valid JSON.
    controllable.crashPoint = AtomicWriteCrashPoint.NONE
    val recovered =
      BundleStore(
        BundleStoreConfig(otaDirectory = otaDir, installedBinaryVersion = "1.0.0", fileIo = controllable),
      )
    recovered.initialize()
    assertEquals("rel-ok", recovered.loadState().previousSlot)
  }

  @Test
  fun diskFull_simulation_preservesPriorState() {
    commitSlot("rel-ok")
    store.setActiveSlot("rel-ok")
    val before = store.loadState()

    val controllable = ControllableFileIo(failWriteWithDiskFull = true)
    val failing =
      BundleStore(
        BundleStoreConfig(otaDirectory = otaDir, installedBinaryVersion = "1.0.0", fileIo = controllable),
      )
    failing.initialize()
    try {
      failing.setPendingSlot("rel-ok")
      fail("expected DiskFull")
    } catch (_: BundleStoreException.DiskFull) {
    }

    controllable.failWriteWithDiskFull = false
    val reread =
      BundleStore(
        BundleStoreConfig(otaDirectory = otaDir, installedBinaryVersion = "1.0.0", fileIo = controllable),
      )
    reread.initialize()
    assertEquals(before.activeSlot, reread.loadState().activeSlot)
    assertNull(reread.loadState().pendingSlot)
  }

  @Test
  fun saveState_roundTrip() {
    val state =
      BundleState(
        schemaVersion = 1,
        installedBinaryVersion = "2.4.0",
        activeSlot = null,
        pendingSlot = null,
        previousSlot = null,
      )
    store.saveState(state)
    assertEquals(state, store.loadState())
  }

  @Test
  fun codec_rejectsPathTraversalSlotIds() {
    val encoded =
      """{"schemaVersion":1,"installedBinaryVersion":null,"activeSlot":"../x","pendingSlot":null,"previousSlot":null}"""
    val decoded = OtaStateCodec.decode(encoded.toByteArray(), 1)!!
    assertNull(decoded.activeSlot)
  }

  private fun commitSlot(id: String): SlotInfo {
    val info = store.createSlot(id)
    info.bundleFile.writeBytes(byteArrayOf(0xC6.toByte(), 0x1F, 0x00)) // fake HBC magic-ish
    info.manifestFile.writeText("{}")
    return store.listSlots().first { it.id == id }
  }
}
