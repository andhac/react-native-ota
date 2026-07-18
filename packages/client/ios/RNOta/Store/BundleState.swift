import Foundation

/// In-memory representation of `state.json` (schema v1).
public struct BundleState: Equatable, Sendable {
  public var schemaVersion: Int
  public var installedBinaryVersion: String?
  public var activeSlot: String?
  public var pendingSlot: String?
  public var previousSlot: String?

  public static let empty = BundleState(
    schemaVersion: OtaPaths.supportedSchemaVersion,
    installedBinaryVersion: nil,
    activeSlot: nil,
    pendingSlot: nil,
    previousSlot: nil
  )

  public init(
    schemaVersion: Int = OtaPaths.supportedSchemaVersion,
    installedBinaryVersion: String? = nil,
    activeSlot: String? = nil,
    pendingSlot: String? = nil,
    previousSlot: String? = nil
  ) {
    self.schemaVersion = schemaVersion
    self.installedBinaryVersion = installedBinaryVersion
    self.activeSlot = activeSlot
    self.pendingSlot = pendingSlot
    self.previousSlot = previousSlot
  }

  public func referencedSlotIds() -> Set<String> {
    var ids = Set<String>()
    if let activeSlot { ids.insert(activeSlot) }
    if let pendingSlot { ids.insert(pendingSlot) }
    if let previousSlot { ids.insert(previousSlot) }
    return ids
  }
}
