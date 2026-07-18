import Foundation
import XCTest

@testable import RNOtaStore

final class BundleResolverTests: XCTestCase {
  private var otaDir: URL!
  private var store: BundleStore!
  private var resolver: BundleResolver!

  override func setUpWithError() throws {
    otaDir = FileManager.default.temporaryDirectory
      .appendingPathComponent("ota-resolver-\(UUID().uuidString)", isDirectory: true)
    try FileManager.default.createDirectory(at: otaDir, withIntermediateDirectories: true)
    store = BundleStore(config: BundleStoreConfig(otaDirectory: otaDir, installedBinaryVersion: "1.0.0"))
    try store.initialize()
    resolver = BundleResolver(store: store)
  }

  override func tearDownWithError() throws {
    try? FileManager.default.removeItem(at: otaDir)
  }

  func testNoActiveSlot() {
    let r = resolver.resolve()
    XCTAssertEqual(r.source, .embedded)
    XCTAssertNil(r.bundlePath)
    XCTAssertNil(r.slotId)
    XCTAssertEqual(r.reason, .noActiveSlot)
  }

  func testInvalidSlotIdInState() throws {
    try writeState(active: "../evil", pending: nil)
    let r = resolver.resolve()
    XCTAssertEqual(r.source, .embedded)
    XCTAssertEqual(r.reason, .noActiveSlot)
  }

  func testMissingSlotDirectory() throws {
    try writeState(active: "missing-slot", pending: nil)
    let r = resolver.resolve()
    XCTAssertEqual(r.source, .embedded)
    XCTAssertEqual(r.reason, .slotNotFound)
  }

  func testMissingBundleHbc() throws {
    _ = try store.createSlot("rel-empty")
    try writeState(active: "rel-empty", pending: nil)
    let r = resolver.resolve()
    XCTAssertEqual(r.source, .embedded)
    XCTAssertEqual(r.reason, .bundleMissing)
  }

  func testValidOtaBundle() throws {
    try commitAndActivate("rel-ok")
    let r = resolver.resolve()
    XCTAssertEqual(r.source, .ota)
    XCTAssertEqual(r.slotId, "rel-ok")
    XCTAssertEqual(r.reason, .activeBundle)
    XCTAssertEqual(r.bundlePath, store.bundlePath("rel-ok").path)
    XCTAssertTrue(FileManager.default.fileExists(atPath: r.bundlePath!))
  }

  func testCorruptStateEmbedded() throws {
    try Data("{broken".utf8).write(to: otaDir.appendingPathComponent(OtaPaths.stateFileName))
    let r = resolver.resolve()
    XCTAssertEqual(r.source, .embedded)
    XCTAssertEqual(r.reason, .noActiveSlot)
  }

  func testPeekDoesNotRewriteCorruptFile() throws {
    let corrupt = Data("{broken".utf8)
    let stateURL = otaDir.appendingPathComponent(OtaPaths.stateFileName)
    try corrupt.write(to: stateURL)
    _ = resolver.resolve()
    XCTAssertEqual(try Data(contentsOf: stateURL), corrupt)
  }

  func testCorruptedReferenceBundleDeleted() throws {
    try commitAndActivate("rel-gone")
    try FileManager.default.removeItem(at: store.bundlePath("rel-gone"))
    let r = resolver.resolve()
    XCTAssertEqual(r.source, .embedded)
    XCTAssertEqual(r.reason, .bundleMissing)
  }

  func testPendingSlotIgnored() throws {
    try commitAndActivate("active-1")
    let pending = try store.createSlot("pend-1")
    try Data([1, 2, 3]).write(to: pending.bundleFile)
    try writeState(active: "active-1", pending: "pend-1")
    let r = resolver.resolve()
    XCTAssertEqual(r.slotId, "active-1")
    XCTAssertEqual(r.reason, .activeBundle)
  }

  func testDeterministic() throws {
    try commitAndActivate("rel-ok")
    XCTAssertEqual(resolver.resolve(), resolver.resolve())
  }

  func testFutureSchemaEmbedded() throws {
    let json =
      #"{"schemaVersion":99,"installedBinaryVersion":null,"activeSlot":"rel-x","pendingSlot":null,"previousSlot":null}"#
    try Data(json.utf8).write(to: otaDir.appendingPathComponent(OtaPaths.stateFileName))
    let r = resolver.resolve()
    XCTAssertEqual(r.source, .embedded)
    XCTAssertEqual(r.reason, .noActiveSlot)
  }

  private func commitAndActivate(_ id: String) throws {
    let info = try store.createSlot(id)
    try Data([0xC6, 0x1F, 0x00]).write(to: info.bundleFile)
    try Data("{}".utf8).write(to: info.manifestFile)
    try store.setActiveSlot(id)
  }

  private func writeState(active: String?, pending: String?) throws {
    let activeJson = active.map { "\"\($0)\"" } ?? "null"
    let pendingJson = pending.map { "\"\($0)\"" } ?? "null"
    let json =
      "{\"schemaVersion\":1,\"installedBinaryVersion\":\"1.0.0\",\"activeSlot\":\(activeJson),\"pendingSlot\":\(pendingJson),\"previousSlot\":null}"
    try Data(json.utf8).write(to: otaDir.appendingPathComponent(OtaPaths.stateFileName))
  }
}
