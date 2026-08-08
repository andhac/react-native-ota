import Foundation

public enum RollbackDecision: String, Sendable, Equatable {
  case noAction
  case rollbackToPrevious
  case rollbackToEmbedded
}
