import Foundation

public enum BootStatus: String, Sendable, Equatable {
  case idle
  case pending
  case confirmed
  case rollbackRequired = "rollback_required"
}
