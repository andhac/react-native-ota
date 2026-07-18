import Foundation

/// Configuration for ``BundleStore``.
public struct BundleStoreConfig: Sendable {
  public var otaDirectory: URL
  public var installedBinaryVersion: String?
  public var supportedSchemaVersion: Int
  public var fileIo: FileIo

  public init(
    otaDirectory: URL,
    installedBinaryVersion: String? = nil,
    supportedSchemaVersion: Int = OtaPaths.supportedSchemaVersion,
    fileIo: FileIo = RealFileIo()
  ) {
    self.otaDirectory = otaDirectory
    self.installedBinaryVersion = installedBinaryVersion
    self.supportedSchemaVersion = supportedSchemaVersion
    self.fileIo = fileIo
  }
}
