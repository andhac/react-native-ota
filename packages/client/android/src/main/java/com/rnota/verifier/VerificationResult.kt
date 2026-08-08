package com.rnota.verifier

/**
 * Outcome of [BundleVerifier.verify]. Never throws for expected rejection cases.
 */
data class VerificationResult(
  val status: VerificationStatus,
  val reason: VerificationFailureReason,
  val expectedSha256Hex: String?,
  val actualSha256Hex: String?,
  val bundleSizeBytes: Long?,
  val message: String? = null,
) {
  val isVerified: Boolean get() = status == VerificationStatus.VERIFIED

  companion object {
    fun verified(
      expectedSha256Hex: String,
      actualSha256Hex: String,
      bundleSizeBytes: Long,
    ): VerificationResult =
      VerificationResult(
        status = VerificationStatus.VERIFIED,
        reason = VerificationFailureReason.NONE,
        expectedSha256Hex = expectedSha256Hex,
        actualSha256Hex = actualSha256Hex,
        bundleSizeBytes = bundleSizeBytes,
      )

    fun rejected(
      reason: VerificationFailureReason,
      expectedSha256Hex: String? = null,
      actualSha256Hex: String? = null,
      bundleSizeBytes: Long? = null,
      message: String? = null,
    ): VerificationResult =
      VerificationResult(
        status = VerificationStatus.REJECTED,
        reason = reason,
        expectedSha256Hex = expectedSha256Hex,
        actualSha256Hex = actualSha256Hex,
        bundleSizeBytes = bundleSizeBytes,
        message = message,
      )
  }
}
