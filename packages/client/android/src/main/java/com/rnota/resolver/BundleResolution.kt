package com.rnota.resolver

/**
 * Result of one synchronous resolution pass (M1).
 *
 * When [source] is [BundleSource.EMBEDDED], [bundlePath] is null — the host app
 * loads its packaged asset. When [source] is [BundleSource.OTA], [bundlePath] is
 * an absolute filesystem path to `bundle.hbc`.
 */
data class BundleResolution(
  val source: BundleSource,
  val bundlePath: String?,
  val slotId: String?,
  val reason: ResolutionReason,
) {
  companion object {
    fun embedded(reason: ResolutionReason): BundleResolution =
      BundleResolution(
        source = BundleSource.EMBEDDED,
        bundlePath = null,
        slotId = null,
        reason = reason,
      )

    fun ota(
      absolutePath: String,
      slotId: String,
    ): BundleResolution =
      BundleResolution(
        source = BundleSource.OTA,
        bundlePath = absolutePath,
        slotId = slotId,
        reason = ResolutionReason.ACTIVE_BUNDLE,
      )
  }
}
