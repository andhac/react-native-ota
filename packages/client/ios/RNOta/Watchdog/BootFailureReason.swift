import Foundation

public enum BootFailureReason: String, Sendable, Equatable {
  case none
  case incompleteBoot = "incomplete_boot"
  case repeatedFailures = "repeated_failures"
  case corruptBootState = "corrupt_boot_state"
}
