import Foundation
import XCTest

@testable import RNOtaStore

final class RollbackEngineTests: XCTestCase {
  private var otaDir: URL!
  private var store: BundleStore!
  private var watchdog: BootWatchdog!
  private var engine: RollbackEngine!

  override func setUpWithError() throws {
    otaDir = FileManager.default.temporaryDirectory
      .appendingPathComponent("ota-rollback-\(UUID().uuidString)", isDirectory: true)
    try FileManager.default.createDirectory(at: otaDir, withIntermediateDirectories: true)
    store = BundleStore(config: BundleStoreConfig(otaDirectory: otaDir, installedBinaryVersion: "1.0.0"))
    try store.initialize()
    watchdog = BootWatchdog(config: BootWatchdogConfig(otaDirectory: otaDir, maxFailedAttempts: 2))
    engine = RollbackEngine(store: store, watchdog: watchdog)
  }

  override func tearDownWithError() throws {
    try? FileManager.default.removeItem(at: otaDir)
  }

  func testSuccessfulRollbackToPrevious() throws {
    try commit("prev-ok")
    try commit("bad-active")
    try store.saveState(
      BundleState(
        installedBinaryVersion: "1.0.0",
        activeSlot: "bad-active",
        pendingSlot: nil,
        previousSlot: "prev-ok"
      )
    )
    try forceWatchdogRollbackRequired()

    let result = try engine.rollbackIfNeeded()
    XCTAssertTrue(result.performed)
    XCTAssertEqual(result.decision, .rollbackToPrevious)
    XCTAssertEqual(result.reason, .watchdogRequired)
    XCTAssertEqual(result.fromSlotId, "bad-active")
    XCTAssertEqual(result.toSlotId, "prev-ok")
    XCTAssertEqual(store.peekState().activeSlot, "prev-ok")
    XCTAssertNil(store.peekState().pendingSlot)
    XCTAssertNil(store.peekState().previousSlot)
    XCTAssertTrue(store.slotExists("bad-active"))
    XCTAssertFalse(watchdog.requiresRollback())
  }

  func testPreviousSlotMissing() throws {
    try commit("bad-active")
    try store.saveState(
      BundleState(installedBinaryVersion: "1.0.0", activeSlot: "bad-active", previousSlot: "ghost")
    )
    try forceWatchdogRollbackRequired()
    let result = try engine.rollbackIfNeeded()
    XCTAssertTrue(result.performed)
    XCTAssertEqual(result.decision, .rollbackToEmbedded)
    XCTAssertEqual(result.reason, .previousBundleMissing)
    XCTAssertNil(store.peekState().activeSlot)
  }

  func testPreviousBundleMissing() throws {
    try commit("bad-active")
    _ = try store.createSlot("prev-empty")
    try store.saveState(
      BundleState(installedBinaryVersion: "1.0.0", activeSlot: "bad-active", previousSlot: "prev-empty")
    )
    try forceWatchdogRollbackRequired()
    let result = try engine.rollbackIfNeeded()
    XCTAssertTrue(result.performed)
    XCTAssertEqual(result.decision, .rollbackToEmbedded)
    XCTAssertEqual(result.reason, .previousBundleMissing)
  }

  func testNoPreviousSlot() throws {
    try commit("bad-active")
    try store.saveState(BundleState(installedBinaryVersion: "1.0.0", activeSlot: "bad-active"))
    try forceWatchdogRollbackRequired()
    let result = try engine.rollbackIfNeeded()
    XCTAssertTrue(result.performed)
    XCTAssertEqual(result.decision, .rollbackToEmbedded)
    XCTAssertEqual(result.reason, .previousMissing)
  }

  func testEmbeddedFallbackExplicit() throws {
    try commit("active-1")
    try store.saveState(BundleState(installedBinaryVersion: "1.0.0", activeSlot: "active-1"))
    let result = try engine.rollbackToEmbedded()
    XCTAssertTrue(result.performed)
    XCTAssertEqual(result.reason, .explicitEmbedded)
    XCTAssertNil(store.peekState().activeSlot)
    XCTAssertTrue(store.slotExists("active-1"))
  }

  func testRollbackIdempotent() throws {
    try store.saveState(BundleState(installedBinaryVersion: "1.0.0"))
    try forceWatchdogRollbackRequired()
    let first = try engine.rollbackIfNeeded()
    XCTAssertFalse(first.performed)
    XCTAssertEqual(first.reason, .alreadyAtTarget)
    let second = try engine.rollbackIfNeeded()
    XCTAssertFalse(second.performed)
    XCTAssertEqual(second.reason, .notRequired)
  }

  func testRepeatedRollbackToEmbedded() throws {
    try commit("active-1")
    try store.saveState(BundleState(installedBinaryVersion: "1.0.0", activeSlot: "active-1"))
    XCTAssertTrue(try engine.rollbackToEmbedded().performed)
    let second = try engine.rollbackToEmbedded()
    XCTAssertFalse(second.performed)
    XCTAssertEqual(second.reason, .alreadyAtTarget)
  }

  func testWatchdogSuccessDoNothing() throws {
    try commit("active-1")
    try commit("prev-ok")
    try store.saveState(
      BundleState(installedBinaryVersion: "1.0.0", activeSlot: "active-1", previousSlot: "prev-ok")
    )
    _ = try watchdog.beginBoot()
    _ = try watchdog.markBootSuccessful()
    let result = try engine.rollbackIfNeeded()
    XCTAssertFalse(result.performed)
    XCTAssertEqual(result.reason, .notRequired)
    XCTAssertEqual(store.peekState().activeSlot, "active-1")
  }

  func testCorruptedState() throws {
    try Data("{broken".utf8).write(to: otaDir.appendingPathComponent(OtaPaths.stateFileName))
    try forceWatchdogRollbackRequired()
    let result = try engine.rollbackIfNeeded()
    XCTAssertFalse(result.performed)
    XCTAssertEqual(result.reason, .alreadyAtTarget)
    XCTAssertFalse(watchdog.requiresRollback())
  }

  func testNeverDeletesBundles() throws {
    try commit("prev-ok")
    try commit("bad-active")
    try store.saveState(
      BundleState(installedBinaryVersion: "1.0.0", activeSlot: "bad-active", previousSlot: "prev-ok")
    )
    try forceWatchdogRollbackRequired()
    _ = try engine.rollbackIfNeeded()
    XCTAssertEqual(try store.listSlots().count, 2)
  }

  private func commit(_ id: String) throws {
    let info = try store.createSlot(id)
    try Data([0xC6, 0x1F, 0x00]).write(to: info.bundleFile)
    try Data("{}".utf8).write(to: info.manifestFile)
  }

  private func forceWatchdogRollbackRequired() throws {
    _ = try watchdog.reset()
    _ = try watchdog.beginBoot()
    _ = try watchdog.beginBoot()
    _ = try watchdog.beginBoot()
    XCTAssertTrue(watchdog.requiresRollback())
  }
}
