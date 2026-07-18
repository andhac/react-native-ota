import Foundation

/// Result of one synchronous resolution pass (M1).
public struct BundleResolution: Equatable, Sendable {
  public var source: BundleSource
  /// Absolute filesystem path when ``source`` is ``BundleSource/ota``; otherwise `nil`.
  public var bundlePath: String?
  public var slotId: String?
  public var reason: ResolutionReason

  public static func embedded(reason: ResolutionReason) -> BundleResolution {
    BundleResolution(source: .embedded, bundlePath: nil, slotId: nil, reason: reason)
  }

  public static func ota(absolutePath: String, slotId: String) -> BundleResolution {
    BundleResolution(
      source: .ota,
      bundlePath: absolutePath,
      slotId: slotId,
      reason: .activeBundle
    )
  }
}
