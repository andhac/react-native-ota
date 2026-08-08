import CryptoKit
import Foundation
import XCTest
@testable import RNOtaStore

final class BundleVerifierTests: XCTestCase {
  private var root: URL!
  private var verifier: BundleVerifier!
  private var privateKey: Curve25519.Signing.PrivateKey!
  private var publicKeyRaw: Data!

  override func setUpWithError() throws {
    root = FileManager.default.temporaryDirectory
      .appendingPathComponent("ota-verifier-\(UUID().uuidString)", isDirectory: true)
    try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
    verifier = BundleVerifier()
    privateKey = Curve25519.Signing.PrivateKey()
    publicKeyRaw = privateKey.publicKey.rawRepresentation
  }

  override func tearDownWithError() throws {
    try? FileManager.default.removeItem(at: root)
  }

  func testVerifyValidBundleSucceeds() throws {
    let content = Data("valid bundle bytes".utf8)
    let bundle = try writeBundle(name: "valid", content: content)
    let hash = sha256Hex(of: content)

    let result = verifier.verify(
      VerificationRequest(
        bundleFile: bundle,
        expectedSha256Hex: hash,
        allowedRoot: root
      )
    )

    XCTAssertTrue(result.isVerified)
    XCTAssertEqual(result.reason, VerificationFailureReason.none)
    XCTAssertEqual(result.actualSha256Hex, hash)
  }

  func testVerifyMissingBundleRejects() {
    let missing = root.appendingPathComponent("missing.hbc")
    let result = verifier.verify(
      VerificationRequest(
        bundleFile: missing,
        expectedSha256Hex: String(repeating: "a", count: 64),
        allowedRoot: root
      )
    )

    XCTAssertFalse(result.isVerified)
    XCTAssertEqual(result.reason, .bundleMissing)
  }

  func testVerifyEmptyBundleRejects() throws {
    let empty = root.appendingPathComponent("empty.hbc")
    try Data().write(to: empty)

    let result = verifier.verify(
      VerificationRequest(
        bundleFile: empty,
        expectedSha256Hex: String(repeating: "a", count: 64),
        allowedRoot: root
      )
    )

    XCTAssertEqual(result.reason, .bundleEmpty)
  }

  func testVerifyCorrectHashSucceeds() throws {
    let content = Data("correct-hash-content".utf8)
    let bundle = try writeBundle(name: "correct", content: content)
    let hash = sha256Hex(of: content)

    let result = verifier.verify(
      VerificationRequest(
        bundleFile: bundle,
        expectedSha256Hex: hash,
        allowedRoot: root
      )
    )

    XCTAssertTrue(result.isVerified)
  }

  func testVerifyIncorrectHashRejects() throws {
    let bundle = try writeBundle(name: "wrong", content: Data("payload".utf8))
    let wrongHash = String(repeating: "0", count: 64)

    let result = verifier.verify(
      VerificationRequest(
        bundleFile: bundle,
        expectedSha256Hex: wrongHash,
        allowedRoot: root
      )
    )

    XCTAssertEqual(result.reason, .hashMismatch)
    XCTAssertNotNil(result.actualSha256Hex)
  }

  func testVerifyMalformedExpectedHashRejects() throws {
    let bundle = try writeBundle(name: "malformed", content: Data("x".utf8))
    let result = verifier.verify(
      VerificationRequest(
        bundleFile: bundle,
        expectedSha256Hex: "not-a-hash",
        allowedRoot: root
      )
    )

    XCTAssertEqual(result.reason, .malformedHash)
  }

  func testVerifyHashCaseNormalizationSucceeds() throws {
    let content = Data("case-test".utf8)
    let bundle = try writeBundle(name: "case", content: content)
    let lower = sha256Hex(of: content)
    let upper = lower.uppercased()

    let result = verifier.verify(
      VerificationRequest(
        bundleFile: bundle,
        expectedSha256Hex: upper,
        allowedRoot: root
      )
    )

    XCTAssertTrue(result.isVerified)
    XCTAssertEqual(result.actualSha256Hex, lower)
  }

  func testVerifyLargeBundleStreams() throws {
    var large = Data(count: 5 * 1024 * 1024)
    for i in 0..<large.count {
      large[i] = UInt8(i % 251)
    }
    let bundle = root.appendingPathComponent("large.hbc")
    try large.write(to: bundle)
    let hash = sha256Hex(of: large)
    let streamingVerifier = BundleVerifier(chunkSizeBytes: 4096)

    let result = streamingVerifier.verify(
      VerificationRequest(
        bundleFile: bundle,
        expectedSha256Hex: hash,
        allowedRoot: root
      )
    )

    XCTAssertTrue(result.isVerified)
    XCTAssertEqual(result.bundleSizeBytes, Int64(large.count))
  }

  func testVerifyCorruptedBundleRejectsWhenHashWrong() throws {
    let bundle = try writeBundle(name: "corrupt", content: Data("original".utf8))
    let tamperedHash = sha256Hex(of: Data("tampered".utf8))

    let result = verifier.verify(
      VerificationRequest(
        bundleFile: bundle,
        expectedSha256Hex: tamperedHash,
        allowedRoot: root
      )
    )

    XCTAssertEqual(result.reason, .hashMismatch)
  }

  func testVerifyPathTraversalRejects() throws {
    let outside = root.deletingLastPathComponent()
      .appendingPathComponent("outside-\(UUID().uuidString)", isDirectory: true)
    try FileManager.default.createDirectory(at: outside, withIntermediateDirectories: true)
    defer { try? FileManager.default.removeItem(at: outside) }

    let bundle = try writeBundle(name: "outside", content: Data("x".utf8), dir: outside)
    let hash = sha256Hex(of: Data("x".utf8))

    let result = verifier.verify(
      VerificationRequest(
        bundleFile: bundle,
        expectedSha256Hex: hash,
        allowedRoot: root
      )
    )

    XCTAssertEqual(result.reason, .pathUnsafe)
  }

  func testVerifyDeterministicResult() throws {
    let bundle = try writeBundle(name: "deterministic", content: Data("same-bytes".utf8))
    let hash = sha256Hex(of: Data("same-bytes".utf8))
    let request = VerificationRequest(
      bundleFile: bundle,
      expectedSha256Hex: hash,
      allowedRoot: root
    )

    XCTAssertEqual(verifier.verify(request), verifier.verify(request))
  }

  func testVerifySizeMismatchRejects() throws {
    let content = Data("12345".utf8)
    let bundle = try writeBundle(name: "size", content: content)
    let hash = sha256Hex(of: content)

    let result = verifier.verify(
      VerificationRequest(
        bundleFile: bundle,
        expectedSha256Hex: hash,
        expectedSizeBytes: 999,
        allowedRoot: root
      )
    )

    XCTAssertEqual(result.reason, .sizeMismatch)
  }

  func testVerifySlotValidSignatureAndHashSucceeds() throws {
    let slot = try makeSlot(name: "slot-a")
    let bundleContent = Data("signed-bundle-payload".utf8)
    try bundleContent.write(to: slot.appendingPathComponent("bundle.hbc"))
    let bundleHash = sha256Hex(of: bundleContent)
    let manifest = """
    {
      "schemaVersion": 1,
      "runtimeVersion": "1.0.0",
      "bundle": { "file": "bundle.hbc", "sha256": "\(bundleHash)" },
      "assets": []
    }
    """
    try Data(manifest.utf8).write(to: slot.appendingPathComponent("manifest.json"))
    try writeSignature(slot: slot, manifest: manifest)

    let result = verifier.verifySlot(
      slotDirectory: slot,
      trustedPublicKeys: [publicKeyRaw],
      allowedRoot: root,
      runningRuntimeVersion: "1.0.0"
    )

    XCTAssertTrue(result.isVerified)
    XCTAssertEqual(result.actualSha256Hex, bundleHash)
  }

  func testVerifySlotInvalidSignatureRejects() throws {
    let slot = try setupSignedSlot(name: "bad-sig")
    try Data(repeating: 0, count: 64).base64EncodedData()
      .write(to: slot.appendingPathComponent("manifest.json.sig"))

    let result = verifier.verifySlot(
      slotDirectory: slot,
      trustedPublicKeys: [publicKeyRaw],
      allowedRoot: root
    )

    XCTAssertEqual(result.reason, .signatureInvalid)
  }

  func testVerifySlotWrongPublicKeyRejects() throws {
    let slot = try setupSignedSlot(name: "wrong-key")
    let otherKey = Curve25519.Signing.PrivateKey()

    let result = verifier.verifySlot(
      slotDirectory: slot,
      trustedPublicKeys: [otherKey.publicKey.rawRepresentation],
      allowedRoot: root
    )

    XCTAssertEqual(result.reason, .signatureInvalid)
  }

  func testVerifySlotModifiedBundleRejects() throws {
    let slot = try setupSignedSlot(name: "modified")
    try Data("tampered-after-sign".utf8).write(to: slot.appendingPathComponent("bundle.hbc"))

    let result = verifier.verifySlot(
      slotDirectory: slot,
      trustedPublicKeys: [publicKeyRaw],
      allowedRoot: root
    )

    XCTAssertEqual(result.reason, .hashMismatch)
  }

  func testVerifySlotMalformedSignatureRejects() throws {
    let slot = try setupSignedSlot(name: "malformed-sig")
    try Data("!!!not-base64!!!".utf8).write(to: slot.appendingPathComponent("manifest.json.sig"))

    let result = verifier.verifySlot(
      slotDirectory: slot,
      trustedPublicKeys: [publicKeyRaw],
      allowedRoot: root
    )

    XCTAssertEqual(result.reason, .malformedSignature)
  }

  func testVerifySlotMissingSignatureRejects() throws {
    let slot = try setupSignedSlot(name: "missing-sig")
    try FileManager.default.removeItem(at: slot.appendingPathComponent("manifest.json.sig"))

    let result = verifier.verifySlot(
      slotDirectory: slot,
      trustedPublicKeys: [publicKeyRaw],
      allowedRoot: root
    )

    XCTAssertEqual(result.reason, .signatureMissing)
  }

  func testVerifySlotRuntimeMismatchRejects() throws {
    let slot = try setupSignedSlot(name: "runtime-mismatch", runtimeVersion: "2.0.0")

    let result = verifier.verifySlot(
      slotDirectory: slot,
      trustedPublicKeys: [publicKeyRaw],
      allowedRoot: root,
      runningRuntimeVersion: "1.0.0"
    )

    XCTAssertEqual(result.reason, .runtimeMismatch)
  }

  func testVerifySlotAssetHashMismatchRejects() throws {
    let slot = try makeSlot(name: "asset-slot")
    try Data("bundle".utf8).write(to: slot.appendingPathComponent("bundle.hbc"))
    let assetsDir = slot.appendingPathComponent("assets", isDirectory: true)
    try FileManager.default.createDirectory(at: assetsDir, withIntermediateDirectories: true)
    try Data("icon-bytes".utf8).write(to: assetsDir.appendingPathComponent("icon.png"))
    let bundleHash = sha256Hex(of: Data("bundle".utf8))
    let wrongAssetHash = String(repeating: "f", count: 64)
    let manifest = """
    {
      "schemaVersion": 1,
      "runtimeVersion": "1.0.0",
      "bundle": { "file": "bundle.hbc", "sha256": "\(bundleHash)" },
      "assets": [ { "path": "assets/icon.png", "sha256": "\(wrongAssetHash)" } ]
    }
    """
    try Data(manifest.utf8).write(to: slot.appendingPathComponent("manifest.json"))
    try writeSignature(slot: slot, manifest: manifest)

    let result = verifier.verifySlot(
      slotDirectory: slot,
      trustedPublicKeys: [publicKeyRaw],
      allowedRoot: root,
      runningRuntimeVersion: "1.0.0"
    )

    XCTAssertEqual(result.reason, .hashMismatch)
  }

  func testVerifySlotAssetPathTraversalRejects() throws {
    let slot = try makeSlot(name: "traversal-slot")
    try Data("b".utf8).write(to: slot.appendingPathComponent("bundle.hbc"))
    let bundleHash = sha256Hex(of: Data("b".utf8))
    let manifest = """
    {
      "schemaVersion": 1,
      "bundle": { "file": "bundle.hbc", "sha256": "\(bundleHash)" },
      "assets": [ { "path": "../escape.png", "sha256": "\(String(repeating: "a", count: 64))" } ]
    }
    """
    try Data(manifest.utf8).write(to: slot.appendingPathComponent("manifest.json"))

    let result = verifier.verifySlot(
      slotDirectory: slot,
      trustedPublicKeys: [publicKeyRaw],
      allowedRoot: root
    )

    XCTAssertEqual(result.reason, .pathUnsafe)
  }

  func testVerifyPathOutsideAllowedRootRejects() throws {
    let outside = root.deletingLastPathComponent()
      .appendingPathComponent("outside-\(UUID().uuidString)", isDirectory: true)
    try FileManager.default.createDirectory(at: outside, withIntermediateDirectories: true)
    defer { try? FileManager.default.removeItem(at: outside) }
    let bundle = try writeBundle(name: "outside", content: Data("x".utf8), dir: outside)
    let hash = sha256Hex(of: Data("x".utf8))

    let result = verifier.verify(
      VerificationRequest(
        bundleFile: bundle,
        expectedSha256Hex: hash,
        allowedRoot: root
      )
    )

    XCTAssertEqual(result.reason, .pathUnsafe)
  }

  func testVerifySlotWrongBundleFilenameRejects() throws {
    let slot = try makeSlot(name: "wrong-name")
    try Data("payload".utf8).write(to: slot.appendingPathComponent("bundle.hbc"))
    let bundleHash = sha256Hex(of: Data("payload".utf8))
    let manifest = """
    {
      "schemaVersion": 1,
      "bundle": { "file": "other.hbc", "sha256": "\(bundleHash)" },
      "assets": []
    }
    """
    try Data(manifest.utf8).write(to: slot.appendingPathComponent("manifest.json"))

    let result = verifier.verifySlot(
      slotDirectory: slot,
      trustedPublicKeys: [publicKeyRaw],
      allowedRoot: root
    )

    XCTAssertEqual(result.reason, .bundleFilenameMismatch)
  }

  func testVerifySlotCanonicalJsonEquivalentFormatting() throws {
    let slot = try makeSlot(name: "canonical")
    let bundleContent = Data("canonical-payload".utf8)
    try bundleContent.write(to: slot.appendingPathComponent("bundle.hbc"))
    let bundleHash = sha256Hex(of: bundleContent)
    let pretty = """
    {
      "schemaVersion": 1,
      "runtimeVersion": "1.0.0",
      "bundle": { "file": "bundle.hbc", "sha256": "\(bundleHash)" },
      "assets": []
    }
    """
    let compact =
      "{\"schemaVersion\":1,\"runtimeVersion\":\"1.0.0\",\"bundle\":{\"file\":\"bundle.hbc\",\"sha256\":\"\(bundleHash)\"},\"assets\":[]}"
    try Data(pretty.utf8).write(to: slot.appendingPathComponent("manifest.json"))
    try writeSignature(slot: slot, manifest: pretty)

    let result = verifier.verifySlot(
      slotDirectory: slot,
      trustedPublicKeys: [publicKeyRaw],
      allowedRoot: root,
      runningRuntimeVersion: "1.0.0"
    )

    XCTAssertTrue(result.isVerified)
    XCTAssertEqual(try signingPayload(pretty), try signingPayload(compact))
  }

  private func makeSlot(name: String) throws -> URL {
    let slot = root.appendingPathComponent("slots/\(name)", isDirectory: true)
    try FileManager.default.createDirectory(at: slot, withIntermediateDirectories: true)
    return slot
  }

  private func setupSignedSlot(
    name: String,
    runtimeVersion: String = "1.0.0"
  ) throws -> URL {
    let slot = try makeSlot(name: name)
    let bundleContent = Data("payload-\(name)".utf8)
    try bundleContent.write(to: slot.appendingPathComponent("bundle.hbc"))
    let bundleHash = sha256Hex(of: bundleContent)
    let manifest = """
    {
      "schemaVersion": 1,
      "runtimeVersion": "\(runtimeVersion)",
      "bundle": { "file": "bundle.hbc", "sha256": "\(bundleHash)" },
      "assets": []
    }
    """
    try Data(manifest.utf8).write(to: slot.appendingPathComponent("manifest.json"))
    try writeSignature(slot: slot, manifest: manifest)
    return slot
  }

  private func signingPayload(_ manifest: String) throws -> Data {
    let file = root.appendingPathComponent("manifest-tmp-\(UUID().uuidString).json")
    try Data(manifest.utf8).write(to: file)
    defer { try? FileManager.default.removeItem(at: file) }
    guard case let .ok(parsed) = ManifestCodec.parse(manifestFile: file) else {
      throw NSError(domain: "BundleVerifierTests", code: 2)
    }
    return parsed.signingPayloadBytes
  }

  private func writeSignature(slot: URL, manifest: String) throws {
    let payload = try signingPayload(manifest)
    let signature = try privateKey.signature(for: payload)
    try Data(signature.base64EncodedString().utf8)
      .write(to: slot.appendingPathComponent("manifest.json.sig"))
  }

  private func writeBundle(
    name: String,
    content: Data,
    dir: URL? = nil
  ) throws -> URL {
    guard let base = dir ?? root else {
      throw NSError(domain: "BundleVerifierTests", code: 1)
    }
    let url = base.appendingPathComponent("\(name).hbc")
    try content.write(to: url)
    return url
  }

  private func sha256Hex(of data: Data) -> String {
    HexUtils.bytesToHex(Data(SHA256.hash(data: data)))
  }
}
