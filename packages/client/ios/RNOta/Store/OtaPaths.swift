import Foundation

/// Canonical relative paths under `<app-data>/ota/`.
public enum OtaPaths {
  public static let otaDirName = "ota"
  public static let stateFileName = "state.json"
  public static let stateTempFileName = "state.json.tmp"
  public static let bootFileName = "boot.json"
  public static let slotsDirName = "slots"
  public static let bundleFileName = "bundle.hbc"
  public static let manifestFileName = "manifest.json"
  public static let assetsDirName = "assets"
  public static let supportedSchemaVersion = 1

  /// slot-id: `[A-Za-z0-9][A-Za-z0-9._-]{0,63}`
  public static let slotIdPattern = #"^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$"#
}
