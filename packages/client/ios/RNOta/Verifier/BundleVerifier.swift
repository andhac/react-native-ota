import Foundation
import CryptoKit

enum VerificationStatus: Equatable {
  case verified
  case rejected
}

enum VerificationFailureReason: Equatable {
  case none
  case bundleMissing
  case bundleUnreadable
  case bundleEmpty
  case sizeMismatch
  case hashMismatch
  case malformedHash
  case signatureInvalid
  case signatureMissing
  case malformedSignature
  case manifestMissing
  case manifestInvalid
  case runtimeMismatch
  case pathUnsafe
  case internalError
}

struct VerificationRequest: Equatable {
  let bundleFile: URL
  let expectedSha256Hex: String
  let expectedSizeBytes: Int64?
  let allowedRoot: URL?
  let manifestFile: URL?
  let detachedSignatureBase64: String?
  let trustedPublicKeys: [Data]
  let runningRuntimeVersion: String?

  init(
    bundleFile: URL,
    expectedSha256Hex: String,
    expectedSizeBytes: Int64? = nil,
    allowedRoot: URL? = nil,
    manifestFile: URL? = nil,
    detachedSignatureBase64: String? = nil,
    trustedPublicKeys: [Data] = [],
    runningRuntimeVersion: String? = nil
  ) {
    self.bundleFile = bundleFile
    self.expectedSha256Hex = expectedSha256Hex
    self.expectedSizeBytes = expectedSizeBytes
    self.allowedRoot = allowedRoot
    self.manifestFile = manifestFile
    self.detachedSignatureBase64 = detachedSignatureBase64
    self.trustedPublicKeys = trustedPublicKeys
    self.runningRuntimeVersion = runningRuntimeVersion
  }
}

struct VerificationResult: Equatable {
  let status: VerificationStatus
  let reason: VerificationFailureReason
  let expectedSha256Hex: String?
  let actualSha256Hex: String?
  let bundleSizeBytes: Int64?
  let message: String?

  var isVerified: Bool { status == .verified }

  static func verified(
    expectedSha256Hex: String,
    actualSha256Hex: String,
    bundleSizeBytes: Int64
  ) -> VerificationResult {
    VerificationResult(
      status: .verified,
      reason: .none,
      expectedSha256Hex: expectedSha256Hex,
      actualSha256Hex: actualSha256Hex,
      bundleSizeBytes: bundleSizeBytes,
      message: nil
    )
  }

  static func rejected(
    reason: VerificationFailureReason,
    expectedSha256Hex: String? = nil,
    actualSha256Hex: String? = nil,
    bundleSizeBytes: Int64? = nil,
    message: String? = nil
  ) -> VerificationResult {
    VerificationResult(
      status: .rejected,
      reason: reason,
      expectedSha256Hex: expectedSha256Hex,
      actualSha256Hex: actualSha256Hex,
      bundleSizeBytes: bundleSizeBytes,
      message: message
    )
  }
}

enum HexUtils {
  private static let hexRegex = try! NSRegularExpression(pattern: "^[0-9a-fA-F]{64}$")

  static func normalizeSha256Hex(_ raw: String) -> String? {
    let trimmed = raw.trimmingCharacters(in: .whitespacesAndNewlines)
    let range = NSRange(trimmed.startIndex..<trimmed.endIndex, in: trimmed)
    guard hexRegex.firstMatch(in: trimmed, range: range) != nil else {
      return nil
    }
    return trimmed.lowercased()
  }

  static func bytesToHex(_ bytes: Data) -> String {
    bytes.map { String(format: "%02x", $0) }.joined()
  }
}

enum PathGuard {
  static func isUnderAllowedRoot(target: URL, allowedRoot: URL?) -> Bool {
    guard let allowedRoot else { return true }
    let root = allowedRoot.standardizedFileURL.resolvingSymlinksInPath()
    let resolved = target.standardizedFileURL.resolvingSymlinksInPath()
    return resolved.path.hasPrefix(root.path + "/") || resolved.path == root.path
  }

  static func containsTraversalSegment(_ path: String) -> Bool {
    path.split(separator: "/").contains { $0 == ".." } ||
      path.split(separator: "\\").contains { $0 == ".." }
  }
}

enum Ed25519Verifier {
  static func verify(message: Data, signature: Data, rawPublicKey: Data) -> Bool {
    guard rawPublicKey.count == 32, signature.count == 64 else { return false }
    do {
      let key = try Curve25519.Signing.PublicKey(rawRepresentation: rawPublicKey)
      return key.isValidSignature(signature, for: message)
    } catch {
      return false
    }
  }

  static func decodeBase64Signature(_ encoded: String) -> Data? {
    guard let data = Data(base64Encoded: encoded.trimmingCharacters(in: .whitespacesAndNewlines)),
          data.count == 64
    else {
      return nil
    }
    return data
  }
}

struct AssetEntry: Equatable {
  let relativePath: String
  let sha256Hex: String
}

struct ParsedManifest: Equatable {
  let runtimeVersion: String?
  let bundleSha256Hex: String
  let assets: [AssetEntry]
  let canonicalBytes: Data
}

enum ManifestCodec {
  static func parse(manifestFile: URL) -> ParsedManifest? {
    guard FileManager.default.fileExists(atPath: manifestFile.path),
          let raw = try? Data(contentsOf: manifestFile),
          !raw.isEmpty,
          let json = try? JSONSerialization.jsonObject(with: raw) as? [String: Any],
          json["schemaVersion"] != nil,
          let bundle = json["bundle"] as? [String: Any],
          let bundleHashRaw = bundle["sha256"] as? String,
          let normalizedBundleHash = HexUtils.normalizeSha256Hex(bundleHashRaw)
    else {
      return nil
    }

    let bundleFileName = bundle["file"] as? String ?? OtaPaths.bundleFileName
    if PathGuard.containsTraversalSegment(bundleFileName) {
      return nil
    }

    var assets: [AssetEntry] = []
    if let assetsArray = json["assets"] as? [[String: Any]] {
      for item in assetsArray {
        guard let path = item["path"] as? String,
              let hashRaw = item["sha256"] as? String,
              let normalized = HexUtils.normalizeSha256Hex(hashRaw)
        else {
          return nil
        }
        if PathGuard.containsTraversalSegment(path) {
          return nil
        }
        assets.append(AssetEntry(relativePath: path, sha256Hex: normalized))
      }
    }

    let runtimeVersion = json["runtimeVersion"] as? String
    return ParsedManifest(
      runtimeVersion: runtimeVersion,
      bundleSha256Hex: normalizedBundleHash,
      assets: assets,
      canonicalBytes: raw
    )
  }
}

/// Security gate for OTA bundles — validation only, never activation.
final class BundleVerifier {
  static let defaultChunkSizeBytes = 64 * 1024

  private let chunkSizeBytes: Int

  init(chunkSizeBytes: Int = BundleVerifier.defaultChunkSizeBytes) {
    self.chunkSizeBytes = chunkSizeBytes
  }

  func verify(_ request: VerificationRequest) -> VerificationResult {
    guard let expectedHash = HexUtils.normalizeSha256Hex(request.expectedSha256Hex) else {
      return .rejected(
        reason: .malformedHash,
        expectedSha256Hex: request.expectedSha256Hex,
        message: "Expected SHA-256 must be 64 hex characters"
      )
    }

    if !PathGuard.isUnderAllowedRoot(target: request.bundleFile, allowedRoot: request.allowedRoot) {
      return .rejected(
        reason: .pathUnsafe,
        expectedSha256Hex: expectedHash,
        message: "Bundle path is outside allowed root"
      )
    }

    let fm = FileManager.default
    var isDirectory: ObjCBool = false
    if !fm.fileExists(atPath: request.bundleFile.path, isDirectory: &isDirectory) {
      return .rejected(
        reason: .bundleMissing,
        expectedSha256Hex: expectedHash,
        message: "Bundle file does not exist"
      )
    }

    if isDirectory.boolValue {
      return .rejected(
        reason: .bundleUnreadable,
        expectedSha256Hex: expectedHash,
        message: "Bundle path is not a regular file"
      )
    }

    if !fm.isReadableFile(atPath: request.bundleFile.path) {
      return .rejected(
        reason: .bundleUnreadable,
        expectedSha256Hex: expectedHash,
        message: "Bundle file is not readable"
      )
    }

    let sizeBytes: Int64
    do {
      let attrs = try fm.attributesOfItem(atPath: request.bundleFile.path)
      sizeBytes = (attrs[.size] as? NSNumber)?.int64Value ?? 0
    } catch {
      return .rejected(
        reason: .bundleUnreadable,
        expectedSha256Hex: expectedHash,
        message: "Failed to stat bundle file"
      )
    }

    if sizeBytes == 0 {
      return .rejected(
        reason: .bundleEmpty,
        expectedSha256Hex: expectedHash,
        bundleSizeBytes: 0,
        message: "Bundle file is empty"
      )
    }

    if let expectedSize = request.expectedSizeBytes, sizeBytes != expectedSize {
      return .rejected(
        reason: .sizeMismatch,
        expectedSha256Hex: expectedHash,
        bundleSizeBytes: sizeBytes,
        message: "Expected size \(expectedSize) bytes, actual \(sizeBytes) bytes"
      )
    }

    if let manifestResult = verifyManifestChain(
      request: request,
      expectedHash: expectedHash,
      sizeBytes: sizeBytes
    ) {
      return manifestResult
    }

    guard let actualHashBytes = hashFile(at: request.bundleFile) else {
      return .rejected(
        reason: .bundleUnreadable,
        expectedSha256Hex: expectedHash,
        bundleSizeBytes: sizeBytes,
        message: "Failed to read bundle for hashing"
      )
    }

    let actualHash = HexUtils.bytesToHex(actualHashBytes)
    if !constantTimeHashEquals(expectedHash, actualHash) {
      return .rejected(
        reason: .hashMismatch,
        expectedSha256Hex: expectedHash,
        actualSha256Hex: actualHash,
        bundleSizeBytes: sizeBytes,
        message: "SHA-256 mismatch"
      )
    }

    return .verified(
      expectedSha256Hex: expectedHash,
      actualSha256Hex: actualHash,
      bundleSizeBytes: sizeBytes
    )
  }

  func verifySlot(
    slotDirectory: URL,
    trustedPublicKeys: [Data],
    allowedRoot: URL? = nil,
    runningRuntimeVersion: String? = nil,
    signatureBase64: String? = nil
  ) -> VerificationResult {
    if !PathGuard.isUnderAllowedRoot(target: slotDirectory, allowedRoot: allowedRoot) {
      return .rejected(
        reason: .pathUnsafe,
        message: "Slot directory is outside allowed root"
      )
    }

    var isDirectory: ObjCBool = false
    let fm = FileManager.default
    if !fm.fileExists(atPath: slotDirectory.path, isDirectory: &isDirectory) || !isDirectory.boolValue {
      return .rejected(reason: .bundleMissing, message: "Slot directory does not exist")
    }

    let manifestFile = slotDirectory.appendingPathComponent(OtaPaths.manifestFileName)
    guard fm.fileExists(atPath: manifestFile.path) else {
      return .rejected(reason: .manifestMissing, message: "manifest.json is missing")
    }

    let signatureFile = slotDirectory.appendingPathComponent("\(OtaPaths.manifestFileName).sig")
    guard let parsed = ManifestCodec.parse(manifestFile: manifestFile) else {
      return .rejected(reason: .manifestInvalid, message: "manifest.json is invalid")
    }

    if trustedPublicKeys.isEmpty {
      return .rejected(
        reason: .signatureMissing,
        expectedSha256Hex: parsed.bundleSha256Hex,
        message: "No trusted public keys configured"
      )
    }

    let sigEncoded = signatureBase64 ?? readSignatureSidecar(signatureFile)
    guard let sigEncoded, !sigEncoded.isEmpty else {
      return .rejected(
        reason: .signatureMissing,
        expectedSha256Hex: parsed.bundleSha256Hex,
        message: "Detached manifest signature is missing"
      )
    }

    guard let signatureBytes = Ed25519Verifier.decodeBase64Signature(sigEncoded) else {
      return .rejected(
        reason: .malformedSignature,
        expectedSha256Hex: parsed.bundleSha256Hex,
        message: "Detached signature is malformed"
      )
    }

    if !verifySignatureWithAnyKey(
      message: parsed.canonicalBytes,
      signature: signatureBytes,
      publicKeys: trustedPublicKeys
    ) {
      return .rejected(
        reason: .signatureInvalid,
        expectedSha256Hex: parsed.bundleSha256Hex,
        message: "Ed25519 signature verification failed"
      )
    }

    if let running = runningRuntimeVersion,
       let manifestRuntime = parsed.runtimeVersion,
       manifestRuntime != running
    {
      return .rejected(
        reason: .runtimeMismatch,
        expectedSha256Hex: parsed.bundleSha256Hex,
        message: "runtimeVersion mismatch: manifest=\(manifestRuntime) running=\(running)"
      )
    }

    let bundleFile = slotDirectory.appendingPathComponent(OtaPaths.bundleFileName)
    let bundleResult = verify(
      VerificationRequest(
        bundleFile: bundleFile,
        expectedSha256Hex: parsed.bundleSha256Hex,
        allowedRoot: allowedRoot ?? slotDirectory.deletingLastPathComponent()
      )
    )
    if !bundleResult.isVerified {
      return bundleResult
    }

    for asset in parsed.assets {
      let assetFile = slotDirectory.appendingPathComponent(asset.relativePath)
      if !PathGuard.isUnderAllowedRoot(target: assetFile, allowedRoot: slotDirectory) {
        return .rejected(
          reason: .pathUnsafe,
          expectedSha256Hex: asset.sha256Hex,
          message: "Asset path escapes slot: \(asset.relativePath)"
        )
      }
      let assetResult = verify(
        VerificationRequest(
          bundleFile: assetFile,
          expectedSha256Hex: asset.sha256Hex,
          allowedRoot: slotDirectory
        )
      )
      if !assetResult.isVerified {
        return VerificationResult(
          status: assetResult.status,
          reason: assetResult.reason,
          expectedSha256Hex: assetResult.expectedSha256Hex,
          actualSha256Hex: assetResult.actualSha256Hex,
          bundleSizeBytes: assetResult.bundleSizeBytes,
          message: "Asset \(asset.relativePath): \(assetResult.message ?? String(describing: assetResult.reason))"
        )
      }
    }

    return bundleResult
  }

  private func verifyManifestChain(
    request: VerificationRequest,
    expectedHash: String,
    sizeBytes: Int64
  ) -> VerificationResult? {
    guard let manifestFile = request.manifestFile else { return nil }
    let hasSigInput = request.detachedSignatureBase64 != nil || !request.trustedPublicKeys.isEmpty
    if !hasSigInput { return nil }

    let fm = FileManager.default
    if !fm.fileExists(atPath: manifestFile.path) {
      return .rejected(
        reason: .manifestMissing,
        expectedSha256Hex: expectedHash,
        bundleSizeBytes: sizeBytes
      )
    }

    let signatureFile = manifestFile.deletingLastPathComponent()
      .appendingPathComponent("\(manifestFile.lastPathComponent).sig")
    guard let parsed = ManifestCodec.parse(manifestFile: manifestFile) else {
      return .rejected(
        reason: .manifestInvalid,
        expectedSha256Hex: expectedHash,
        bundleSizeBytes: sizeBytes
      )
    }

    if !constantTimeHashEquals(expectedHash, parsed.bundleSha256Hex) {
      return .rejected(
        reason: .hashMismatch,
        expectedSha256Hex: expectedHash,
        actualSha256Hex: parsed.bundleSha256Hex,
        bundleSizeBytes: sizeBytes,
        message: "Expected hash does not match signed manifest bundle hash"
      )
    }

    if request.trustedPublicKeys.isEmpty {
      return .rejected(
        reason: .signatureMissing,
        expectedSha256Hex: expectedHash,
        bundleSizeBytes: sizeBytes
      )
    }

    let sigEncoded = request.detachedSignatureBase64 ?? readSignatureSidecar(signatureFile)
    guard let sigEncoded, !sigEncoded.isEmpty else {
      return .rejected(
        reason: .signatureMissing,
        expectedSha256Hex: expectedHash,
        bundleSizeBytes: sizeBytes
      )
    }

    guard let signatureBytes = Ed25519Verifier.decodeBase64Signature(sigEncoded) else {
      return .rejected(
        reason: .malformedSignature,
        expectedSha256Hex: expectedHash,
        bundleSizeBytes: sizeBytes
      )
    }

    if !verifySignatureWithAnyKey(
      message: parsed.canonicalBytes,
      signature: signatureBytes,
      publicKeys: request.trustedPublicKeys
    ) {
      return .rejected(
        reason: .signatureInvalid,
        expectedSha256Hex: expectedHash,
        bundleSizeBytes: sizeBytes
      )
    }

    if let running = request.runningRuntimeVersion,
       let manifestRuntime = parsed.runtimeVersion,
       manifestRuntime != running
    {
      return .rejected(
        reason: .runtimeMismatch,
        expectedSha256Hex: expectedHash,
        bundleSizeBytes: sizeBytes,
        message: "runtimeVersion mismatch"
      )
    }

    return nil
  }

  private func hashFile(at url: URL) -> Data? {
    guard let handle = try? FileHandle(forReadingFrom: url) else { return nil }
    defer { try? handle.close() }

    var hasher = SHA256()
    while true {
      let chunk = try? handle.read(upToCount: chunkSizeBytes)
      guard let chunk, !chunk.isEmpty else { break }
      hasher.update(data: chunk)
    }
    return Data(hasher.finalize())
  }

  private func readSignatureSidecar(_ signatureFile: URL) -> String? {
    guard let data = try? Data(contentsOf: signatureFile),
          let text = String(data: data, encoding: .utf8)?
            .trimmingCharacters(in: .whitespacesAndNewlines),
          !text.isEmpty
    else {
      return nil
    }
    return text
  }

  private func verifySignatureWithAnyKey(
    message: Data,
    signature: Data,
    publicKeys: [Data]
  ) -> Bool {
    publicKeys.contains { key in
      Ed25519Verifier.verify(message: message, signature: signature, rawPublicKey: key)
    }
  }

  private func constantTimeHashEquals(_ expected: String, _ actual: String) -> Bool {
    let a = Data(expected.lowercased().utf8)
    let b = Data(actual.lowercased().utf8)
    guard a.count == b.count else { return false }
    return a.withUnsafeBytes { aPtr in
      b.withUnsafeBytes { bPtr in
        timingsafe_bcmp(aPtr.baseAddress, bPtr.baseAddress, a.count) == 0
      }
    }
  }
}

private func timingsafe_bcmp(_ a: UnsafeRawPointer?, _ b: UnsafeRawPointer?, _ len: Int) -> Int32 {
  guard let a, let b else { return 1 }
  var result: UInt8 = 0
  for i in 0..<len {
    result |= a.load(fromByteOffset: i, as: UInt8.self) ^
      b.load(fromByteOffset: i, as: UInt8.self)
  }
  return Int32(result)
}
