import Foundation

/// Where the resolved JavaScript bundle comes from.
public enum BundleSource: String, Sendable, Equatable {
  /// Ship-with-binary `main.jsbundle` — host uses its default URL.
  case embedded
  /// On-disk OTA slot (`bundle.hbc`).
  case ota
}
