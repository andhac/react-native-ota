import Foundation

public struct RollbackResult: Equatable, Sendable {
  public var performed: Bool
  public var decision: RollbackDecision
  public var reason: RollbackReason
  public var fromSlotId: String?
  public var toSlotId: String?
  public var activeSlotAfter: String?
  public var pendingCleared: Bool
}
