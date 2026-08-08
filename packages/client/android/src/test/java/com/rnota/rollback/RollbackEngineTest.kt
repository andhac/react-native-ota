package com.rnota.rollback

import com.rnota.store.BundleState
import com.rnota.store.BundleStore
import com.rnota.store.BundleStoreConfig
import com.rnota.store.OtaPaths
import com.rnota.watchdog.BootStatus
import com.rnota.watchdog.BootWatchdog
import com.rnota.watchdog.BootWatchdogConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class RollbackEngineTest {
  @get:Rule
  val tmp = TemporaryFolder()

  private lateinit var otaDir: File
  private lateinit var store: BundleStore
  private lateinit var watchdog: BootWatchdog
  private lateinit var engine: RollbackEngine

  @Before
  fun setUp() {
    otaDir = tmp.newFolder("ota")
    store = BundleStore(BundleStoreConfig(otaDirectory = otaDir, installedBinaryVersion = "1.0.0"))
    store.initialize()
    watchdog = BootWatchdog(BootWatchdogConfig(otaDirectory = otaDir, maxFailedAttempts = 2))
    engine = RollbackEngine(store, watchdog)
  }

  @Test
  fun successfulRollback_toPrevious() {
    commit("prev-ok")
    commit("bad-active")
    store.saveState(
      BundleState(
        installedBinaryVersion = "1.0.0",
        activeSlot = "bad-active",
        pendingSlot = "pend-ignored",
        previousSlot = "prev-ok",
      ),
    )
    // pending slot dir may not exist — that's fine; we only clear the pointer
    forceWatchdogRollbackRequired()

    val result = engine.rollbackIfNeeded()
    assertTrue(result.performed)
    assertEquals(RollbackDecision.ROLLBACK_TO_PREVIOUS, result.decision)
    assertEquals(RollbackReason.WATCHDOG_REQUIRED, result.reason)
    assertEquals("bad-active", result.fromSlotId)
    assertEquals("prev-ok", result.toSlotId)
    assertEquals("prev-ok", store.peekState().activeSlot)
    assertNull(store.peekState().pendingSlot)
    assertNull(store.peekState().previousSlot)
    assertTrue(store.slotExists("bad-active")) // never deleted
    assertFalse(watchdog.requiresRollback())
    assertEquals(BootStatus.IDLE, watchdog.currentStatus())
  }

  @Test
  fun previousSlotMissing_fallsBackToEmbedded() {
    commit("bad-active")
    store.saveState(
      BundleState(installedBinaryVersion = "1.0.0", activeSlot = "bad-active", previousSlot = "ghost"),
    )
    forceWatchdogRollbackRequired()

    val result = engine.rollbackIfNeeded()
    assertTrue(result.performed)
    assertEquals(RollbackDecision.ROLLBACK_TO_EMBEDDED, result.decision)
    assertEquals(RollbackReason.PREVIOUS_BUNDLE_MISSING, result.reason)
    assertNull(store.peekState().activeSlot)
    assertTrue(store.slotExists("bad-active"))
  }

  @Test
  fun previousBundleMissing_fallsBackToEmbedded() {
    commit("bad-active")
    store.createSlot("prev-empty")
    store.saveState(
      BundleState(
        installedBinaryVersion = "1.0.0",
        activeSlot = "bad-active",
        previousSlot = "prev-empty",
      ),
    )
    forceWatchdogRollbackRequired()

    val result = engine.rollbackIfNeeded()
    assertTrue(result.performed)
    assertEquals(RollbackDecision.ROLLBACK_TO_EMBEDDED, result.decision)
    assertEquals(RollbackReason.PREVIOUS_BUNDLE_MISSING, result.reason)
    assertNull(result.toSlotId)
    assertNull(store.peekState().activeSlot)
  }

  @Test
  fun noPreviousSlot_embeddedFallback() {
    commit("bad-active")
    store.saveState(
      BundleState(installedBinaryVersion = "1.0.0", activeSlot = "bad-active", previousSlot = null),
    )
    forceWatchdogRollbackRequired()

    val result = engine.rollbackIfNeeded()
    assertTrue(result.performed)
    assertEquals(RollbackDecision.ROLLBACK_TO_EMBEDDED, result.decision)
    assertEquals(RollbackReason.PREVIOUS_MISSING, result.reason)
    assertNull(store.peekState().activeSlot)
  }

  @Test
  fun embeddedFallback_explicit() {
    commit("active-1")
    store.saveState(
      BundleState(installedBinaryVersion = "1.0.0", activeSlot = "active-1", previousSlot = "active-1"),
    )
    val result = engine.rollbackToEmbedded()
    assertTrue(result.performed)
    assertEquals(RollbackDecision.ROLLBACK_TO_EMBEDDED, result.decision)
    assertEquals(RollbackReason.EXPLICIT_EMBEDDED, result.reason)
    assertNull(store.peekState().activeSlot)
    assertTrue(store.slotExists("active-1"))
  }

  @Test
  fun rollbackIdempotent_whenAlreadyAtTarget() {
    commit("prev-ok")
    store.saveState(
      BundleState(installedBinaryVersion = "1.0.0", activeSlot = "prev-ok", previousSlot = null, pendingSlot = null),
    )
    forceWatchdogRollbackRequired()
    // First call: decision wants previous but previous is null → embedded. active is prev-ok, not null.
    // Set up already at previous target:
    store.saveState(
      BundleState(installedBinaryVersion = "1.0.0", activeSlot = "prev-ok", previousSlot = null, pendingSlot = null),
    )
    // Watchdog still requires; decideTarget with previous=null → embedded, not already at target.
    // Make already at embedded:
    store.saveState(BundleState(installedBinaryVersion = "1.0.0"))
    forceWatchdogRollbackRequired()

    val first = engine.rollbackIfNeeded()
    assertFalse(first.performed)
    assertEquals(RollbackReason.ALREADY_AT_TARGET, first.reason)
    assertFalse(watchdog.requiresRollback())

    val second = engine.rollbackIfNeeded()
    assertFalse(second.performed)
    assertEquals(RollbackReason.NOT_REQUIRED, second.reason)
  }

  @Test
  fun repeatedRollback_toEmbedded_isIdempotent() {
    commit("active-1")
    store.saveState(BundleState(installedBinaryVersion = "1.0.0", activeSlot = "active-1"))
    val a = engine.rollbackToEmbedded()
    assertTrue(a.performed)
    val b = engine.rollbackToEmbedded()
    assertFalse(b.performed)
    assertEquals(RollbackReason.ALREADY_AT_TARGET, b.reason)
    assertNull(store.peekState().activeSlot)
  }

  @Test
  fun watchdogSuccess_doNothing() {
    commit("active-1")
    store.saveState(
      BundleState(installedBinaryVersion = "1.0.0", activeSlot = "active-1", previousSlot = "prev-ok"),
    )
    watchdog.beginBoot()
    watchdog.markBootSuccessful()
    assertFalse(watchdog.requiresRollback())

    val result = engine.rollbackIfNeeded()
    assertFalse(result.performed)
    assertEquals(RollbackReason.NOT_REQUIRED, result.reason)
    assertEquals("active-1", store.peekState().activeSlot)
  }

  @Test
  fun corruptedState_withWatchdogRequired_safeEmbedded() {
    File(otaDir, OtaPaths.STATE_FILE_NAME).writeText("{broken")
    forceWatchdogRollbackRequired()
    val result = engine.rollbackIfNeeded()
    // peek is EMPTY → already embedded
    assertFalse(result.performed)
    assertEquals(RollbackReason.ALREADY_AT_TARGET, result.reason)
    assertFalse(watchdog.requiresRollback())
  }

  @Test
  fun canRollback_and_currentDecision() {
    assertFalse(engine.canRollback())
    assertEquals(RollbackDecision.NO_ACTION, engine.currentRollbackDecision())

    commit("prev-ok")
    commit("bad-active")
    store.saveState(
      BundleState(
        installedBinaryVersion = "1.0.0",
        activeSlot = "bad-active",
        previousSlot = "prev-ok",
      ),
    )
    forceWatchdogRollbackRequired()
    assertTrue(engine.canRollback())
    assertEquals(RollbackDecision.ROLLBACK_TO_PREVIOUS, engine.currentRollbackDecision())
  }

  @Test
  fun rollbackToPrevious_explicit() {
    commit("prev-ok")
    commit("bad-active")
    store.saveState(
      BundleState(
        installedBinaryVersion = "1.0.0",
        activeSlot = "bad-active",
        pendingSlot = null,
        previousSlot = "prev-ok",
      ),
    )
    val result = engine.rollbackToPrevious()
    assertTrue(result.performed)
    assertEquals(RollbackDecision.ROLLBACK_TO_PREVIOUS, result.decision)
    assertEquals(RollbackReason.EXPLICIT_PREVIOUS, result.reason)
    assertEquals("prev-ok", store.peekState().activeSlot)
  }

  @Test
  fun neverDeletesBundles() {
    commit("prev-ok")
    commit("bad-active")
    store.saveState(
      BundleState(
        installedBinaryVersion = "1.0.0",
        activeSlot = "bad-active",
        previousSlot = "prev-ok",
      ),
    )
    forceWatchdogRollbackRequired()
    engine.rollbackIfNeeded()
    assertEquals(2, store.listSlots().size)
  }

  @Test
  fun rollbackIfNeeded_twice_secondCallIsNoOp() {
    commit("prev-ok")
    commit("bad-active")
    store.saveState(
      BundleState(
        installedBinaryVersion = "1.0.0",
        activeSlot = "bad-active",
        pendingSlot = "pending-x",
        previousSlot = "prev-ok",
      ),
    )
    forceWatchdogRollbackRequired()

    val first = engine.rollbackIfNeeded()
    assertTrue(first.performed)
    assertEquals("prev-ok", store.peekState().activeSlot)
    assertNull(store.peekState().pendingSlot)

    val second = engine.rollbackIfNeeded()
    assertFalse(second.performed)
    assertEquals(RollbackReason.NOT_REQUIRED, second.reason)
    assertEquals("prev-ok", store.peekState().activeSlot)
  }

  private fun commit(id: String) {
    val info = store.createSlot(id)
    info.bundleFile.writeBytes(byteArrayOf(0xC6.toByte(), 0x1F, 0x00))
    info.manifestFile.writeText("{}")
  }

  private fun forceWatchdogRollbackRequired() {
    watchdog.reset()
    watchdog.beginBoot()
    watchdog.beginBoot()
    watchdog.beginBoot()
    assertTrue(watchdog.requiresRollback())
  }
}
