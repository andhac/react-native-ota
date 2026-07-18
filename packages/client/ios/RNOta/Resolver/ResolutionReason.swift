import Foundation

/// Diagnostic reason for a ``BundleResolution``.
public enum ResolutionReason: String, Sendable, Equatable {
  case noActiveSlot
  case slotNotFound
  case bundleMissing
  case activeBundle
  case embedded
}
