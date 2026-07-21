import Foundation
import XCTest

@testable import RNOtaStore

final class BootWatchdogTests: XCTestCase {
  private var otaDir: URL!
  private var watchdog: BootWatchdog!

  override func setUpWithError() throws {
    otaDir = FileManager.default.temporaryDirectory
      .appendingPathComponent("ota-watchdog-\(UUID().uuidString)", isDirectory: true)
    try FileManager.default.createDirectory(at: otaDir, withIntermediateDirectories: true)
    watchdog = BootWatchdog(config: BootWatchdogConfig(otaDirectory: otaDir, maxFailedAttempts: 2))
  }

  override func tearDownWithError() throws {
    try? FileManager.default.removeItem(at: otaDir)
  }

  func testFirstBootSetsPending() throws {
    let result = try watchdog.beginBoot()
    XCTAssertEqual(result.bootAttempt, 1)
    XCTAssertEqual(result.status, .pending)
    XCTAssertFalse(result.requiresRollback)
    XCTAssertEqual(watchdog.currentStatus(), .pending)
  }

  func testSuccessfulBootConfirms() throws {
    try watchdog.beginBoot()
    let ok = try watchdog.markBootSuccessful()
    XCTAssertEqual(ok.status, .confirmed)
    XCTAssertEqual(ok.bootAttempt, 1)
    XCTAssertEqual(watchdog.currentState().lastSuccessfulBoot, 1)
    XCTAssertEqual(ok.consecutiveFailures, 0)
    XCTAssertFalse(watchdog.requiresRollback())
  }

  func testInterruptedBootUnderThreshold() throws {
    try watchdog.beginBoot()
    let second = try watchdog.beginBoot()
    XCTAssertEqual(second.bootAttempt, 2)
    XCTAssertEqual(second.consecutiveFailures, 1)
    XCTAssertEqual(second.status, .pending)
    XCTAssertEqual(second.failureReason, .incompleteBoot)
    XCTAssertFalse(second.requiresRollback)
  }

  func testRepeatedCrashesRequireRollback() throws {
    try watchdog.beginBoot()
    try watchdog.beginBoot()
    let third = try watchdog.beginBoot()
    XCTAssertEqual(third.bootAttempt, 3)
    XCTAssertEqual(third.consecutiveFailures, 2)
    XCTAssertEqual(third.status, .rollbackRequired)
    XCTAssertTrue(third.requiresRollback)
    XCTAssertTrue(watchdog.requiresRollback())
    XCTAssertEqual(third.failureReason, .repeatedFailures)
  }

  func testMissingBootJson() throws {
    XCTAssertEqual(watchdog.currentStatus(), .idle)
    let r = try watchdog.beginBoot()
    XCTAssertEqual(r.bootAttempt, 1)
  }

  func testCorruptBootJson() throws {
    try Data("{not-json".utf8).write(to: otaDir.appendingPathComponent(BootPaths.bootFileName))
    let state = watchdog.currentState()
    XCTAssertEqual(state.bootAttempt, 0)
    XCTAssertEqual(state.lastFailureReason, .corruptBootState)
    let r = try watchdog.beginBoot()
    XCTAssertEqual(r.bootAttempt, 1)
    XCTAssertEqual(r.status, .pending)
  }

  func testEmptyObjectPlaceholder() throws {
    try Data("{}\n".utf8).write(to: otaDir.appendingPathComponent(BootPaths.bootFileName))
    let r = try watchdog.beginBoot()
    XCTAssertEqual(r.bootAttempt, 1)
  }

  func testFutureSchema() throws {
    let json =
      #"{"schemaVersion":99,"bootAttempt":9,"lastSuccessfulBoot":9,"status":"pending","consecutiveFailures":0,"lastFailureReason":"none"}"#
    try Data(json.utf8).write(to: otaDir.appendingPathComponent(BootPaths.bootFileName))
    let r = try watchdog.beginBoot()
    XCTAssertEqual(r.bootAttempt, 1)
    XCTAssertEqual(r.status, .pending)
  }

  func testAtomicCrashBeforeRename() throws {
    try watchdog.beginBoot()
    try watchdog.markBootSuccessful()
    let before = try Data(contentsOf: otaDir.appendingPathComponent(BootPaths.bootFileName))

    let controllable = ControllableFileIo(crashPoint: .beforeRename)
    let crashing = BootWatchdog(
      config: BootWatchdogConfig(otaDirectory: otaDir, maxFailedAttempts: 2, fileIo: controllable)
    )
    XCTAssertThrowsError(try crashing.beginBoot())
    let after = try Data(contentsOf: otaDir.appendingPathComponent(BootPaths.bootFileName))
    XCTAssertEqual(before, after)
    XCTAssertTrue(
      FileManager.default.fileExists(atPath: otaDir.appendingPathComponent(BootPaths.bootTempFileName).path)
    )

    controllable.crashPoint = .none
    let recovered = BootWatchdog(
      config: BootWatchdogConfig(otaDirectory: otaDir, maxFailedAttempts: 2, fileIo: controllable)
    )
    XCTAssertEqual(recovered.currentStatus(), .confirmed)
  }

  func testDeterministic() throws {
    try watchdog.beginBoot()
    try watchdog.beginBoot()
    try watchdog.beginBoot()
    XCTAssertEqual(watchdog.requiresRollback(), watchdog.requiresRollback())
    XCTAssertEqual(watchdog.currentStatus(), watchdog.currentStatus())
  }

  func testResetClearsRollback() throws {
    try watchdog.beginBoot()
    try watchdog.beginBoot()
    try watchdog.beginBoot()
    XCTAssertTrue(watchdog.requiresRollback())
    let cleared = try watchdog.reset()
    XCTAssertEqual(cleared.status, .idle)
    XCTAssertFalse(watchdog.requiresRollback())
    XCTAssertEqual(watchdog.currentState().bootAttempt, 0)
  }

  func testSuccessClearsFailureStreak() throws {
    try watchdog.beginBoot()
    try watchdog.beginBoot()
    XCTAssertEqual(watchdog.currentState().consecutiveFailures, 1)
    try watchdog.markBootSuccessful()
    XCTAssertEqual(watchdog.currentState().consecutiveFailures, 0)
    let next = try watchdog.beginBoot()
    XCTAssertEqual(next.status, .pending)
    XCTAssertEqual(next.consecutiveFailures, 0)
    XCTAssertFalse(next.requiresRollback)
  }

  func testDoesNotTouchStateJson() throws {
    let stateURL = otaDir.appendingPathComponent(OtaPaths.stateFileName)
    let original = Data(#"{"schemaVersion":1,"activeSlot":null}"#.utf8)
    try original.write(to: stateURL)
    try watchdog.beginBoot()
    try watchdog.markBootSuccessful()
    XCTAssertEqual(try Data(contentsOf: stateURL), original)
  }
}
