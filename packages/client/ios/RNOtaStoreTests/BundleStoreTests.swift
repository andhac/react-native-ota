import Foundation
import XCTest

@testable import RNOtaStore

final class BundleStoreTests: XCTestCase {
  private var otaDir: URL!
  private var store: BundleStore!

  override func setUpWithError() throws {
    otaDir = FileManager.default.temporaryDirectory
      .appendingPathComponent("ota-test-\(UUID().uuidString)", isDirectory: true)
    try FileManager.default.createDirectory(at: otaDir, withIntermediateDirectories: true)
    store = BundleStore(config: BundleStoreConfig(otaDirectory: otaDir, installedBinaryVersion: "1.0.0"))
    try store.initialize()
  }

  override func tearDownWithError() throws {
    try? FileManager.default.removeItem(at: otaDir)
  }

  func testInitializeCreatesLayout() throws {
    XCTAssertTrue(FileManager.default.fileExists(atPath: otaDir.appendingPathComponent(OtaPaths.slotsDirName).path))
    XCTAssertTrue(FileManager.default.fileExists(atPath: otaDir.appendingPathComponent(OtaPaths.stateFileName).path))
    XCTAssertTrue(FileManager.default.fileExists(atPath: otaDir.appendingPathComponent(OtaPaths.bootFileName).path))
    let state = try store.loadState()
    XCTAssertNil(state.activeSlot)
  }

  func testMissingStateRecovers() throws {
    try FileManager.default.removeItem(at: otaDir.appendingPathComponent(OtaPaths.stateFileName))
    let state = try store.loadState()
    XCTAssertNil(state.activeSlot)
    XCTAssertTrue(FileManager.default.fileExists(atPath: otaDir.appendingPathComponent(OtaPaths.stateFileName).path))
  }

  func testCorruptedStateRecovers() throws {
    try Data("{not-json".utf8).write(to: otaDir.appendingPathComponent(OtaPaths.stateFileName))
    let state = try store.loadState()
    XCTAssertNil(state.activeSlot)
  }

  func testEmptyStateRecovers() throws {
    try Data().write(to: otaDir.appendingPathComponent(OtaPaths.stateFileName))
    let state = try store.loadState()
    XCTAssertNil(state.activeSlot)
  }

  func testFutureSchemaRecovers() throws {
    let json = #"{"schemaVersion":99,"installedBinaryVersion":null,"activeSlot":null,"pendingSlot":null,"previousSlot":null}"#
    try Data(json.utf8).write(to: otaDir.appendingPathComponent(OtaPaths.stateFileName))
    let state = try store.loadState()
    XCTAssertEqual(state.schemaVersion, 1)
    XCTAssertNil(state.activeSlot)
  }

  func testCreateSlotDoesNotActivate() throws {
    _ = try store.createSlot("rel-abc")
    XCTAssertNil(try store.loadState().activeSlot)
    XCTAssertFalse(try store.listSlots().first!.isCommitted)
  }

  func testSetActiveRequiresCommitted() throws {
    _ = try store.createSlot("rel-abc")
    XCTAssertThrowsError(try store.setActiveSlot("rel-abc")) { error in
      XCTAssertEqual(error as? BundleStoreException, .slotNotCommitted("rel-abc"))
    }
  }

  func testExplicitActivation() throws {
    _ = try commitSlot("rel-abc")
    try store.setActiveSlot("rel-abc")
    XCTAssertEqual(try store.loadState().activeSlot, "rel-abc")
  }

  func testPendingPreviousAndClear() throws {
    _ = try commitSlot("active-1")
    _ = try commitSlot("pend-1")
    _ = try commitSlot("prev-1")
    try store.setActiveSlot("active-1")
    try store.setPendingSlot("pend-1")
    try store.setPreviousSlot("prev-1")
    var state = try store.loadState()
    XCTAssertEqual(state.activeSlot, "active-1")
    XCTAssertEqual(state.pendingSlot, "pend-1")
    XCTAssertEqual(state.previousSlot, "prev-1")
    try store.clearPendingSlot()
    state = try store.loadState()
    XCTAssertNil(state.pendingSlot)
  }

  func testInvalidSlotId() {
    XCTAssertThrowsError(try store.createSlot("../evil"))
    XCTAssertThrowsError(try store.createSlot(".hidden"))
  }

  func testDanglingReferenceDropped() throws {
    _ = try commitSlot("gone")
    try store.setActiveSlot("gone")
    try FileManager.default.removeItem(at: store.bundlePath("gone"))
    let state = try store.loadState()
    XCTAssertNil(state.activeSlot)
  }

  func testGarbageCollectOrphans() throws {
    _ = try commitSlot("keep")
    _ = try commitSlot("orphan")
    try store.setActiveSlot("keep")
    let deleted = try store.garbageCollect()
    XCTAssertEqual(deleted, ["orphan"])
    XCTAssertTrue(store.slotExists("keep"))
    XCTAssertFalse(store.slotExists("orphan"))
  }

  func testDeleteRefusesIfReferenced() throws {
    _ = try commitSlot("keep")
    try store.setActiveSlot("keep")
    XCTAssertThrowsError(try store.deleteSlot("keep")) { error in
      XCTAssertEqual(error as? BundleStoreException, .slotInUse("keep"))
    }
  }

  func testCrashBeforeRenamePreservesState() throws {
    _ = try commitSlot("rel-ok")
    try store.setActiveSlot("rel-ok")
    let before = try Data(contentsOf: otaDir.appendingPathComponent(OtaPaths.stateFileName))

    let controllable = ControllableFileIo(crashPoint: .beforeRename)
    let crashing = BundleStore(
      config: BundleStoreConfig(otaDirectory: otaDir, installedBinaryVersion: "1.0.0", fileIo: controllable)
    )
    try crashing.initialize()
    XCTAssertThrowsError(try crashing.setPendingSlot("rel-ok"))

    let after = try Data(contentsOf: otaDir.appendingPathComponent(OtaPaths.stateFileName))
    XCTAssertEqual(before, after)
    XCTAssertTrue(FileManager.default.fileExists(atPath: otaDir.appendingPathComponent(OtaPaths.stateTempFileName).path))

    controllable.crashPoint = .none
    let recovered = BundleStore(
      config: BundleStoreConfig(otaDirectory: otaDir, installedBinaryVersion: "1.0.0", fileIo: controllable)
    )
    try recovered.initialize()
    XCTAssertEqual(try recovered.loadState().activeSlot, "rel-ok")
    XCTAssertNil(try recovered.loadState().pendingSlot)
  }

  func testCrashAfterRenameKeepsNewState() throws {
    _ = try commitSlot("rel-ok")
    try store.setActiveSlot("rel-ok")

    let controllable = ControllableFileIo(crashPoint: .afterRename)
    let crashing = BundleStore(
      config: BundleStoreConfig(otaDirectory: otaDir, installedBinaryVersion: "1.0.0", fileIo: controllable)
    )
    try crashing.initialize()
    XCTAssertThrowsError(try crashing.setPreviousSlot("rel-ok"))

    controllable.crashPoint = .none
    let recovered = BundleStore(
      config: BundleStoreConfig(otaDirectory: otaDir, installedBinaryVersion: "1.0.0", fileIo: controllable)
    )
    try recovered.initialize()
    XCTAssertEqual(try recovered.loadState().previousSlot, "rel-ok")
  }

  func testDiskFullPreservesPriorState() throws {
    _ = try commitSlot("rel-ok")
    try store.setActiveSlot("rel-ok")

    let controllable = ControllableFileIo(failWriteWithDiskFull: true)
    let failing = BundleStore(
      config: BundleStoreConfig(otaDirectory: otaDir, installedBinaryVersion: "1.0.0", fileIo: controllable)
    )
    try failing.initialize()
    XCTAssertThrowsError(try failing.setPendingSlot("rel-ok")) { error in
      XCTAssertEqual(error as? BundleStoreException, .diskFull)
    }

    controllable.failWriteWithDiskFull = false
    let reread = BundleStore(
      config: BundleStoreConfig(otaDirectory: otaDir, installedBinaryVersion: "1.0.0", fileIo: controllable)
    )
    try reread.initialize()
    XCTAssertEqual(try reread.loadState().activeSlot, "rel-ok")
    XCTAssertNil(try reread.loadState().pendingSlot)
  }

  func testCodecRejectsTraversalIds() {
    let encoded = Data(
      #"{"schemaVersion":1,"installedBinaryVersion":null,"activeSlot":"../x","pendingSlot":null,"previousSlot":null}"#.utf8
    )
    let decoded = OtaStateCodec.decode(bytes: encoded, supportedSchemaVersion: 1)
    XCTAssertNil(decoded?.activeSlot)
  }

  @discardableResult
  private func commitSlot(_ id: String) throws -> SlotInfo {
    let info = try store.createSlot(id)
    try Data([0xC6, 0x1F, 0x00]).write(to: info.bundleFile)
    try Data("{}".utf8).write(to: info.manifestFile)
    return try store.listSlots().first { $0.id == id }!
  }
}
