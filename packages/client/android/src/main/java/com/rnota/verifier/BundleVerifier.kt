package com.rnota.verifier

import com.rnota.store.OtaPaths
import java.io.File
import java.security.MessageDigest

/**
 * Security gate for OTA bundles — validation only, never activation.
 *
 * Pipeline: path safety → exists → readable → size → SHA-256 → optional Ed25519 manifest
 * signature → optional runtimeVersion → VERIFIED / REJECTED.
 */
class BundleVerifier(
  private val chunkSizeBytes: Int = DEFAULT_CHUNK_SIZE_BYTES,
) {
  /**
   * Verifies a single bundle file against expected metadata.
   */
  fun verify(request: VerificationRequest): VerificationResult {
    val expectedHash = HexUtils.normalizeSha256Hex(request.expectedSha256Hex)
    if (expectedHash == null) {
      return VerificationResult.rejected(
        reason = VerificationFailureReason.MALFORMED_HASH,
        expectedSha256Hex = request.expectedSha256Hex,
        message = "Expected SHA-256 must be 64 hex characters",
      )
    }

    if (!PathGuard.isUnderAllowedRoot(request.bundleFile, request.allowedRoot)) {
      return VerificationResult.rejected(
        reason = VerificationFailureReason.PATH_UNSAFE,
        expectedSha256Hex = expectedHash,
        message = "Bundle path is outside allowed root",
      )
    }

    val bundleFile = request.bundleFile
    if (!bundleFile.exists()) {
      return VerificationResult.rejected(
        reason = VerificationFailureReason.BUNDLE_MISSING,
        expectedSha256Hex = expectedHash,
        message = "Bundle file does not exist",
      )
    }

    if (!bundleFile.isFile) {
      return VerificationResult.rejected(
        reason = VerificationFailureReason.BUNDLE_UNREADABLE,
        expectedSha256Hex = expectedHash,
        message = "Bundle path is not a regular file",
      )
    }

    if (!bundleFile.canRead()) {
      return VerificationResult.rejected(
        reason = VerificationFailureReason.BUNDLE_UNREADABLE,
        expectedSha256Hex = expectedHash,
        message = "Bundle file is not readable",
      )
    }

    val sizeBytes = bundleFile.length()
    if (sizeBytes == 0L) {
      return VerificationResult.rejected(
        reason = VerificationFailureReason.BUNDLE_EMPTY,
        expectedSha256Hex = expectedHash,
        bundleSizeBytes = 0L,
        message = "Bundle file is empty",
      )
    }

    request.expectedSizeBytes?.let { expectedSize ->
      if (sizeBytes != expectedSize) {
        return VerificationResult.rejected(
          reason = VerificationFailureReason.SIZE_MISMATCH,
          expectedSha256Hex = expectedHash,
          bundleSizeBytes = sizeBytes,
          message = "Expected size $expectedSize bytes, actual $sizeBytes bytes",
        )
      }
    }

    val manifestResult = verifyManifestChain(request, expectedHash, sizeBytes)
    if (manifestResult != null) {
      return manifestResult
    }

    val actualHashBytes = hashFile(bundleFile)
    if (actualHashBytes == null) {
      return VerificationResult.rejected(
        reason = VerificationFailureReason.BUNDLE_UNREADABLE,
        expectedSha256Hex = expectedHash,
        bundleSizeBytes = sizeBytes,
        message = "Failed to read bundle for hashing",
      )
    }

    val actualHash = HexUtils.bytesToHex(actualHashBytes)
    if (!constantTimeHashEquals(expectedHash, actualHash)) {
      return VerificationResult.rejected(
        reason = VerificationFailureReason.HASH_MISMATCH,
        expectedSha256Hex = expectedHash,
        actualSha256Hex = actualHash,
        bundleSizeBytes = sizeBytes,
        message = "SHA-256 mismatch",
      )
    }

    return VerificationResult.verified(
      expectedSha256Hex = expectedHash,
      actualSha256Hex = actualHash,
      bundleSizeBytes = sizeBytes,
    )
  }

  /**
   * Verifies an extracted slot directory: manifest signature, bundle hash, and asset hashes.
   *
   * Does not mutate [state.json] or mark the slot verified — orchestration is M3's job.
   */
  fun verifySlot(
    slotDirectory: File,
    trustedPublicKeys: List<ByteArray>,
    allowedRoot: File? = null,
    runningRuntimeVersion: String? = null,
    signatureBase64: String? = null,
  ): VerificationResult {
    if (!PathGuard.isUnderAllowedRoot(slotDirectory, allowedRoot)) {
      return VerificationResult.rejected(
        reason = VerificationFailureReason.PATH_UNSAFE,
        message = "Slot directory is outside allowed root",
      )
    }

    if (!slotDirectory.isDirectory) {
      return VerificationResult.rejected(
        reason = VerificationFailureReason.BUNDLE_MISSING,
        message = "Slot directory does not exist",
      )
    }

    val manifestFile = File(slotDirectory, OtaPaths.MANIFEST_FILE_NAME)
    if (!manifestFile.isFile) {
      return VerificationResult.rejected(
        reason = VerificationFailureReason.MANIFEST_MISSING,
        message = "manifest.json is missing",
      )
    }

    val signatureFile = File(slotDirectory, "${OtaPaths.MANIFEST_FILE_NAME}.sig")
    val parsed =
      ManifestCodec.parse(manifestFile, signatureFile)
        ?: return VerificationResult.rejected(
          reason = VerificationFailureReason.MANIFEST_INVALID,
          message = "manifest.json is invalid",
        )

    if (trustedPublicKeys.isEmpty()) {
      return VerificationResult.rejected(
        reason = VerificationFailureReason.SIGNATURE_MISSING,
        expectedSha256Hex = parsed.bundleSha256Hex,
        message = "No trusted public keys configured",
      )
    }

    val sigEncoded = signatureBase64 ?: readSignatureSidecar(signatureFile)
    if (sigEncoded.isNullOrBlank()) {
      return VerificationResult.rejected(
        reason = VerificationFailureReason.SIGNATURE_MISSING,
        expectedSha256Hex = parsed.bundleSha256Hex,
        message = "Detached manifest signature is missing",
      )
    }

    val signatureBytes = Ed25519Verifier.decodeBase64Signature(sigEncoded)
    if (signatureBytes == null) {
      return VerificationResult.rejected(
        reason = VerificationFailureReason.MALFORMED_SIGNATURE,
        expectedSha256Hex = parsed.bundleSha256Hex,
        message = "Detached signature is malformed",
      )
    }

    if (!verifySignatureWithAnyKey(parsed.canonicalBytes, signatureBytes, trustedPublicKeys)) {
      return VerificationResult.rejected(
        reason = VerificationFailureReason.SIGNATURE_INVALID,
        expectedSha256Hex = parsed.bundleSha256Hex,
        message = "Ed25519 signature verification failed",
      )
    }

    runningRuntimeVersion?.let { running ->
      val manifestRuntime = parsed.runtimeVersion
      if (manifestRuntime != null && manifestRuntime != running) {
        return VerificationResult.rejected(
          reason = VerificationFailureReason.RUNTIME_MISMATCH,
          expectedSha256Hex = parsed.bundleSha256Hex,
          message = "runtimeVersion mismatch: manifest=$manifestRuntime running=$running",
        )
      }
    }

    val bundleFile = File(slotDirectory, OtaPaths.BUNDLE_FILE_NAME)
    val bundleResult =
      verify(
        VerificationRequest(
          bundleFile = bundleFile,
          expectedSha256Hex = parsed.bundleSha256Hex,
          allowedRoot = allowedRoot ?: slotDirectory.parentFile,
        ),
      )
    if (!bundleResult.isVerified) {
      return bundleResult
    }

    for (asset in parsed.assets) {
      val assetFile = File(slotDirectory, asset.relativePath)
      if (!PathGuard.isUnderAllowedRoot(assetFile, slotDirectory)) {
        return VerificationResult.rejected(
          reason = VerificationFailureReason.PATH_UNSAFE,
          expectedSha256Hex = asset.sha256Hex,
          message = "Asset path escapes slot: ${asset.relativePath}",
        )
      }
      val assetResult =
        verify(
          VerificationRequest(
            bundleFile = assetFile,
            expectedSha256Hex = asset.sha256Hex,
            allowedRoot = slotDirectory,
          ),
        )
      if (!assetResult.isVerified) {
        return assetResult.copy(
          message = "Asset ${asset.relativePath}: ${assetResult.message ?: assetResult.reason.name}",
        )
      }
    }

    return bundleResult
  }

  private fun verifyManifestChain(
    request: VerificationRequest,
    expectedHash: String,
    sizeBytes: Long,
  ): VerificationResult? {
    val manifestFile = request.manifestFile ?: return null
    val hasSigInput =
      request.detachedSignatureBase64 != null || request.trustedPublicKeys.isNotEmpty()
    if (!hasSigInput) {
      return null
    }

    if (!manifestFile.isFile) {
      return VerificationResult.rejected(
        reason = VerificationFailureReason.MANIFEST_MISSING,
        expectedSha256Hex = expectedHash,
        bundleSizeBytes = sizeBytes,
      )
    }

    val signatureFile = File(manifestFile.parentFile, "${manifestFile.name}.sig")
    val parsed =
      ManifestCodec.parse(manifestFile, signatureFile)
        ?: return VerificationResult.rejected(
          reason = VerificationFailureReason.MANIFEST_INVALID,
          expectedSha256Hex = expectedHash,
          bundleSizeBytes = sizeBytes,
        )

    if (!constantTimeHashEquals(expectedHash, parsed.bundleSha256Hex)) {
      return VerificationResult.rejected(
        reason = VerificationFailureReason.HASH_MISMATCH,
        expectedSha256Hex = expectedHash,
        actualSha256Hex = parsed.bundleSha256Hex,
        bundleSizeBytes = sizeBytes,
        message = "Expected hash does not match signed manifest bundle hash",
      )
    }

    if (request.trustedPublicKeys.isEmpty()) {
      return VerificationResult.rejected(
        reason = VerificationFailureReason.SIGNATURE_MISSING,
        expectedSha256Hex = expectedHash,
        bundleSizeBytes = sizeBytes,
      )
    }

    val sigEncoded = request.detachedSignatureBase64 ?: readSignatureSidecar(signatureFile)
    if (sigEncoded.isNullOrBlank()) {
      return VerificationResult.rejected(
        reason = VerificationFailureReason.SIGNATURE_MISSING,
        expectedSha256Hex = expectedHash,
        bundleSizeBytes = sizeBytes,
      )
    }

    val signatureBytes = Ed25519Verifier.decodeBase64Signature(sigEncoded)
    if (signatureBytes == null) {
      return VerificationResult.rejected(
        reason = VerificationFailureReason.MALFORMED_SIGNATURE,
        expectedSha256Hex = expectedHash,
        bundleSizeBytes = sizeBytes,
      )
    }

    if (!verifySignatureWithAnyKey(parsed.canonicalBytes, signatureBytes, request.trustedPublicKeys)) {
      return VerificationResult.rejected(
        reason = VerificationFailureReason.SIGNATURE_INVALID,
        expectedSha256Hex = expectedHash,
        bundleSizeBytes = sizeBytes,
      )
    }

    request.runningRuntimeVersion?.let { running ->
      val manifestRuntime = parsed.runtimeVersion
      if (manifestRuntime != null && manifestRuntime != running) {
        return VerificationResult.rejected(
          reason = VerificationFailureReason.RUNTIME_MISMATCH,
          expectedSha256Hex = expectedHash,
          bundleSizeBytes = sizeBytes,
          message = "runtimeVersion mismatch",
        )
      }
    }

    return null
  }

  private fun hashFile(file: File): ByteArray? =
    try {
      file.inputStream().use { input ->
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(chunkSizeBytes)
        while (true) {
          val read = input.read(buffer)
          if (read <= 0) {
            break
          }
          digest.update(buffer, 0, read)
        }
        digest.digest()
      }
    } catch (_: Exception) {
      null
    }

  private fun readSignatureSidecar(signatureFile: File): String? {
    return try {
      if (!signatureFile.isFile) {
        null
      } else {
        signatureFile.readText().trim().takeIf { it.isNotEmpty() }
      }
    } catch (_: Exception) {
      null
    }
  }

  private fun verifySignatureWithAnyKey(
    message: ByteArray,
    signature: ByteArray,
    publicKeys: List<ByteArray>,
  ): Boolean =
    publicKeys.any { key ->
      Ed25519Verifier.verify(message, signature, key)
    }

  private fun constantTimeHashEquals(expected: String, actual: String): Boolean {
    if (expected.length != actual.length) {
      return false
    }
    val a = expected.lowercase().toByteArray(Charsets.UTF_8)
    val b = actual.lowercase().toByteArray(Charsets.UTF_8)
    return MessageDigest.isEqual(a, b)
  }

  companion object {
    const val DEFAULT_CHUNK_SIZE_BYTES: Int = 64 * 1024
  }
}
