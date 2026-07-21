import Foundation

public struct BootWatchdogConfig: Sendable {
  public var otaDirectory: URL
  public var maxFailedAttempts: Int
  public var supportedSchemaVersion: Int
  public var fileIo: FileIo

  public init(
    otaDirectory: URL,
    maxFailedAttempts: Int = 2,
    supportedSchemaVersion: Int = BootPaths.supportedSchemaVersion,
    fileIo: FileIo = RealFileIo()
  ) {
    precondition(maxFailedAttempts >= 1)
    self.otaDirectory = otaDirectory
    self.maxFailedAttempts = maxFailedAttempts
    self.supportedSchemaVersion = supportedSchemaVersion
    self.fileIo = fileIo
  }
}

public enum BootPaths {
  public static let bootFileName = "boot.json"
  public static let bootTempFileName = "boot.json.tmp"
  public static let supportedSchemaVersion = 1
}
