package com.rnota.verifier

import java.io.File

/**
 * Inputs for [BundleVerifier.verify].
 *
 * Hash verification requires [expectedSha256Hex]. Signature verification requires
 * [manifestFile], [detachedSignatureBase64], and at least one [trustedPublicKeys].
 *
 * @property allowedRoot When set, [bundleFile] must resolve under this directory.
 */
data class VerificationRequest(
  val bundleFile: File,
  val expectedSha256Hex: String,
  val expectedSizeBytes: Long? = null,
  val allowedRoot: File? = null,
  val manifestFile: File? = null,
  val detachedSignatureBase64: String? = null,
  val trustedPublicKeys: List<ByteArray> = emptyList(),
  val runningRuntimeVersion: String? = null,
)
