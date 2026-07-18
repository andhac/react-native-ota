import Foundation

public struct BootResult: Equatable, Sendable {
  public var bootAttempt: Int
  public var status: BootStatus
  public var requiresRollback: Bool
  public var failureReason: BootFailureReason
  public var consecutiveFailures: Int
}
