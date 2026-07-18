import Foundation

/// Snapshot of one slot directory under `ota/slots/<id>/`.
public struct SlotInfo: Equatable, Sendable {
  public var id: String
  public var directory: URL
  public var bundleFile: URL
  public var manifestFile: URL
  public var assetsDirectory: URL
  public var isCommitted: Bool
}
