package com.rnota.verifier

import java.io.File

/**
 * Inputs for [BundleVerifier.verify].
 *
 * [allowedRoot] is required — verification fails closed when the bundle path is outside it.
 */
data class VerificationRequest(
  val bundleFile: File,
  val expectedSha256Hex: String,
  val allowedRoot: File,
  val expectedSizeBytes: Long? = null,
  val manifestFile: File? = null,
  val detachedSignatureBase64: String? = null,
  val trustedPublicKeys: List<ByteArray> = emptyList(),
  val runningRuntimeVersion: String? = null,
)
