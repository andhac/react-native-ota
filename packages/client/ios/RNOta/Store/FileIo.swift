import Foundation

public enum AtomicWriteCrashPoint: Sendable {
  case none
  case afterTempWrite
  case afterTempFsync
  case beforeRename
  case afterRename
}

public struct SimulatedCrashError: Error, Equatable {
  public let message: String
  public init(_ message: String) { self.message = message }
}

/// Filesystem boundary for the store (injectable for crash / disk-full tests).
public protocol FileIo: Sendable {
  func writeAtomic(target: URL, temporary: URL, bytes: Data) throws
  func readData(from file: URL) throws -> Data
  func fileExists(_ url: URL) -> Bool
  func isFile(_ url: URL) -> Bool
  func isDirectory(_ url: URL) -> Bool
  func createDirectory(_ url: URL) throws
  func removeItem(_ url: URL) throws
  func listDirectoryNames(_ url: URL) throws -> [String]
  func moveItem(from: URL, to: URL) throws
}

public struct RealFileIo: FileIo {
  public init() {}

  public func writeAtomic(target: URL, temporary: URL, bytes: Data) throws {
    do {
      try FileManager.default.createDirectory(
        at: temporary.deletingLastPathComponent(),
        withIntermediateDirectories: true
      )
      try bytes.write(to: temporary, options: .atomic)
      let handle = try FileHandle(forWritingTo: temporary)
      defer { try? handle.close() }
      try handle.synchronize()
      try moveItem(from: temporary, to: target)
    } catch let error as BundleStoreException {
      throw error
    } catch {
      if Self.isNoSpace(error) {
        try? FileManager.default.removeItem(at: temporary)
        throw BundleStoreException.diskFull
      }
      throw BundleStoreException.ioFailure("Failed to atomically write \(target.lastPathComponent)")
    }
  }

  public func readData(from file: URL) throws -> Data {
    try Data(contentsOf: file)
  }

  public func fileExists(_ url: URL) -> Bool {
    FileManager.default.fileExists(atPath: url.path)
  }

  public func isFile(_ url: URL) -> Bool {
    var isDir: ObjCBool = false
    guard FileManager.default.fileExists(atPath: url.path, isDirectory: &isDir) else { return false }
    return !isDir.boolValue
  }

  public func isDirectory(_ url: URL) -> Bool {
    var isDir: ObjCBool = false
    guard FileManager.default.fileExists(atPath: url.path, isDirectory: &isDir) else { return false }
    return isDir.boolValue
  }

  public func createDirectory(_ url: URL) throws {
    try FileManager.default.createDirectory(at: url, withIntermediateDirectories: true)
  }

  public func removeItem(_ url: URL) throws {
    if fileExists(url) {
      try FileManager.default.removeItem(at: url)
    }
  }

  public func listDirectoryNames(_ url: URL) throws -> [String] {
    guard isDirectory(url) else { return [] }
    return try FileManager.default.contentsOfDirectory(atPath: url.path)
  }

  public func moveItem(from: URL, to: URL) throws {
    let fm = FileManager.default
    if fm.fileExists(atPath: to.path) {
      try fm.removeItem(at: to)
    }
    try fm.moveItem(at: from, to: to)
  }

  private static func isNoSpace(_ error: Error) -> Bool {
    let ns = error as NSError
    return ns.domain == NSPOSIXErrorDomain && ns.code == Int(ENOSPC)
  }
}

/// Test double that can simulate mid-write crashes and disk-full errors.
public final class ControllableFileIo: FileIo, @unchecked Sendable {
  private let delegate: FileIo
  public var crashPoint: AtomicWriteCrashPoint
  public var failWriteWithDiskFull: Bool

  public init(
    delegate: FileIo = RealFileIo(),
    crashPoint: AtomicWriteCrashPoint = .none,
    failWriteWithDiskFull: Bool = false
  ) {
    self.delegate = delegate
    self.crashPoint = crashPoint
    self.failWriteWithDiskFull = failWriteWithDiskFull
  }

  public func writeAtomic(target: URL, temporary: URL, bytes: Data) throws {
    if failWriteWithDiskFull {
      throw BundleStoreException.diskFull
    }
    try FileManager.default.createDirectory(
      at: temporary.deletingLastPathComponent(),
      withIntermediateDirectories: true
    )
    switch crashPoint {
    case .none:
      try delegate.writeAtomic(target: target, temporary: temporary, bytes: bytes)
    case .afterTempWrite:
      try bytes.write(to: temporary, options: .atomic)
      throw SimulatedCrashError("crash after temp write")
    case .afterTempFsync:
      try bytes.write(to: temporary, options: .atomic)
      let handle = try FileHandle(forWritingTo: temporary)
      try handle.synchronize()
      try handle.close()
      throw SimulatedCrashError("crash after temp fsync")
    case .beforeRename:
      try bytes.write(to: temporary, options: .atomic)
      let handle = try FileHandle(forWritingTo: temporary)
      try handle.synchronize()
      try handle.close()
      throw SimulatedCrashError("crash before rename")
    case .afterRename:
      try bytes.write(to: temporary, options: .atomic)
      let handle = try FileHandle(forWritingTo: temporary)
      try handle.synchronize()
      try handle.close()
      try delegate.moveItem(from: temporary, to: target)
      throw SimulatedCrashError("crash after rename")
    }
  }

  public func readData(from file: URL) throws -> Data { try delegate.readData(from: file) }
  public func fileExists(_ url: URL) -> Bool { delegate.fileExists(url) }
  public func isFile(_ url: URL) -> Bool { delegate.isFile(url) }
  public func isDirectory(_ url: URL) -> Bool { delegate.isDirectory(url) }
  public func createDirectory(_ url: URL) throws { try delegate.createDirectory(url) }
  public func removeItem(_ url: URL) throws { try delegate.removeItem(url) }
  public func listDirectoryNames(_ url: URL) throws -> [String] { try delegate.listDirectoryNames(url) }
  public func moveItem(from: URL, to: URL) throws { try delegate.moveItem(from: from, to: to) }
}
