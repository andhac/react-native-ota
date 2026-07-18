import Foundation

/// Typed failures for the Bundle Store (same cases as Android `BundleStoreException`).
public enum BundleStoreException: Error, Equatable, LocalizedError {
  case invalidSlotId(String)
  case slotNotFound(String)
  case slotAlreadyExists(String)
  case slotNotCommitted(String)
  case slotInUse(String)
  case diskFull
  case ioFailure(String)

  public var errorDescription: String? {
    switch self {
    case let .invalidSlotId(id): "Invalid slot id: \(id)"
    case let .slotNotFound(id): "Slot not found: \(id)"
    case let .slotAlreadyExists(id): "Slot already exists: \(id)"
    case let .slotNotCommitted(id): "Slot is not committed (missing \(OtaPaths.bundleFileName)): \(id)"
    case let .slotInUse(id): "Slot is referenced by state and cannot be deleted: \(id)"
    case .diskFull: "Disk full while writing OTA store"
    case let .ioFailure(message): message
    }
  }
}
