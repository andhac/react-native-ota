package com.rnota.watchdog

import com.rnota.store.AtomicWriteCrashPoint
import com.rnota.store.ControllableFileIo
import com.rnota.store.SimulatedCrashException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class BootWatchdogTest {
  @get:Rule
  val tmp = TemporaryFolder()

  private lateinit var otaDir: File
  private lateinit var watchdog: BootWatchdog

  @Before
  fun setUp() {
    otaDir = tmp.newFolder("ota")
    watchdog =
      BootWatchdog(
        BootWatchdogConfig(otaDirectory = otaDir, maxFailedAttempts = 2),
      )
  }

  @Test
  fun firstBoot_setsPending() {
    val result = watchdog.beginBoot()
    assertEquals(1, result.bootAttempt)
    assertEquals(BootStatus.PENDING, result.status)
    assertFalse(result.requiresRollback)
    assertEquals(BootStatus.PENDING, watchdog.currentStatus())
    assertTrue(File(otaDir, BootPaths.BOOT_FILE_NAME).isFile)
  }

  @Test
  fun successfulBoot_confirms() {
    watchdog.beginBoot()
    val ok = watchdog.markBootSuccessful()
    assertEquals(BootStatus.CONFIRMED, ok.status)
    assertEquals(1, ok.bootAttempt)
    assertEquals(1, watchdog.currentState().lastSuccessfulBoot)
    assertEquals(0, ok.consecutiveFailures)
    assertFalse(watchdog.requiresRollback())
  }

  @Test
  fun interruptedBoot_countsFailure_staysPendingUnderThreshold() {
    watchdog.beginBoot() // attempt 1 pending
    val second = watchdog.beginBoot() // incomplete → failures=1, attempt=2, still pending
    assertEquals(2, second.bootAttempt)
    assertEquals(1, second.consecutiveFailures)
    assertEquals(BootStatus.PENDING, second.status)
    assertEquals(BootFailureReason.INCOMPLETE_BOOT, second.failureReason)
    assertFalse(second.requiresRollback)
  }

  @Test
  fun repeatedCrashes_requireRollback() {
    watchdog.beginBoot() // 1
    watchdog.beginBoot() // incomplete → failures 1
    val third = watchdog.beginBoot() // incomplete → failures 2 → rollback_required
    assertEquals(3, third.bootAttempt)
    assertEquals(2, third.consecutiveFailures)
    assertEquals(BootStatus.ROLLBACK_REQUIRED, third.status)
    assertTrue(third.requiresRollback)
    assertTrue(watchdog.requiresRollback())
    assertEquals(BootFailureReason.REPEATED_FAILURES, third.failureReason)
  }

  @Test
  fun bootJsonMissing_treatedAsEmpty() {
    assertEquals(BootStatus.IDLE, watchdog.currentStatus())
    val r = watchdog.beginBoot()
    assertEquals(1, r.bootAttempt)
  }

  @Test
  fun corruptBootJson_treatedAsEmpty() {
    File(otaDir, BootPaths.BOOT_FILE_NAME).writeText("{not-json")
    val state = watchdog.currentState()
    assertEquals(0, state.bootAttempt)
    assertEquals(BootFailureReason.CORRUPT_BOOT_STATE, state.lastFailureReason)
    val r = watchdog.beginBoot()
    assertEquals(1, r.bootAttempt)
    assertEquals(BootStatus.PENDING, r.status)
  }

  @Test
  fun emptyObjectPlaceholder_treatedAsEmpty() {
    File(otaDir, BootPaths.BOOT_FILE_NAME).writeText("{}\n")
    val r = watchdog.beginBoot()
    assertEquals(1, r.bootAttempt)
  }

  @Test
  fun futureSchema_treatedAsEmpty() {
    File(otaDir, BootPaths.BOOT_FILE_NAME).writeText(
      """{"schemaVersion":99,"bootAttempt":9,"lastSuccessfulBoot":9,"status":"pending","consecutiveFailures":0,"lastFailureReason":"none"}""",
    )
    val r = watchdog.beginBoot()
    assertEquals(1, r.bootAttempt)
    assertEquals(BootStatus.PENDING, r.status)
  }

  @Test
  fun atomicWriteRecovery_crashBeforeRename() {
    watchdog.beginBoot()
    watchdog.markBootSuccessful()
    val before = File(otaDir, BootPaths.BOOT_FILE_NAME).readText()

    val controllable = ControllableFileIo(crashPoint = AtomicWriteCrashPoint.BEFORE_RENAME)
    val crashing =
      BootWatchdog(
        BootWatchdogConfig(otaDirectory = otaDir, maxFailedAttempts = 2, fileIo = controllable),
      )
    try {
      crashing.beginBoot()
      fail("expected crash")
    } catch (_: SimulatedCrashException) {
    }
    assertEquals(before, File(otaDir, BootPaths.BOOT_FILE_NAME).readText())
    assertTrue(File(otaDir, BootPaths.BOOT_TEMP_FILE_NAME).isFile)

    controllable.crashPoint = AtomicWriteCrashPoint.NONE
    val recovered =
      BootWatchdog(
        BootWatchdogConfig(otaDirectory = otaDir, maxFailedAttempts = 2, fileIo = controllable),
      )
    assertEquals(BootStatus.CONFIRMED, recovered.currentStatus())
  }

  @Test
  fun deterministic_requiresRollback() {
    watchdog.beginBoot()
    watchdog.beginBoot()
    watchdog.beginBoot()
    assertEquals(watchdog.requiresRollback(), watchdog.requiresRollback())
    assertEquals(watchdog.currentStatus(), watchdog.currentStatus())
  }

  @Test
  fun reset_clearsRollback() {
    watchdog.beginBoot()
    watchdog.beginBoot()
    watchdog.beginBoot()
    assertTrue(watchdog.requiresRollback())
    val cleared = watchdog.reset()
    assertEquals(BootStatus.IDLE, cleared.status)
    assertFalse(watchdog.requiresRollback())
    assertEquals(0, watchdog.currentState().bootAttempt)
  }

  @Test
  fun success_clearsFailureStreak() {
    watchdog.beginBoot()
    watchdog.beginBoot() // 1 failure
    assertEquals(1, watchdog.currentState().consecutiveFailures)
    watchdog.markBootSuccessful()
    assertEquals(0, watchdog.currentState().consecutiveFailures)
    val next = watchdog.beginBoot()
    assertEquals(BootStatus.PENDING, next.status)
    assertEquals(0, next.consecutiveFailures)
    assertFalse(next.requiresRollback)
  }

  @Test
  fun doesNotTouchStateJson() {
    val stateJson = File(otaDir, "state.json")
    stateJson.writeText("""{"schemaVersion":1,"activeSlot":null}""")
    watchdog.beginBoot()
    watchdog.markBootSuccessful()
    assertEquals("""{"schemaVersion":1,"activeSlot":null}""", stateJson.readText())
  }
}
