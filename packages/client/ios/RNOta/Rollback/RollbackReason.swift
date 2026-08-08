import Foundation

public enum RollbackReason: String, Sendable, Equatable {
  case none
  case notRequired
  case watchdogRequired
  case explicitPrevious
  case explicitEmbedded
  case previousMissing
  case previousBundleMissing
  case alreadyAtTarget
}
