import Foundation

/// M6 — Boot Watchdog (iOS).
///
/// Owns `boot.json` only. Reports when rollback is advised; never mutates Bundle Store.
public final class BootWatchdog: @unchecked Sendable {
  private let config: BootWatchdogConfig
  private let io: FileIo
  private let lock = NSRecursiveLock()
  private let bootFile: URL
  private let bootTempFile: URL
  private var cached: BootState = .empty

  public init(config: BootWatchdogConfig) {
    self.config = config
    self.io = config.fileIo
    self.bootFile = config.otaDirectory.appendingPathComponent(BootPaths.bootFileName)
    self.bootTempFile = config.otaDirectory.appendingPathComponent(BootPaths.bootTempFileName)
  }

  @discardableResult
  public func beginBoot() throws -> BootResult {
    try withLock {
      let previous = readStateLocked()
      let next: BootState
      switch previous.status {
      case .pending:
        let failures = previous.consecutiveFailures + 1
        let rollback = failures >= config.maxFailedAttempts
        next = BootState(
          schemaVersion: config.supportedSchemaVersion,
          bootAttempt: previous.bootAttempt + 1,
          lastSuccessfulBoot: previous.lastSuccessfulBoot,
          status: rollback ? .rollbackRequired : .pending,
          consecutiveFailures: failures,
          lastFailureReason: rollback ? .repeatedFailures : .incompleteBoot
        )
      case .rollbackRequired:
        next = BootState(
          schemaVersion: config.supportedSchemaVersion,
          bootAttempt: previous.bootAttempt + 1,
          lastSuccessfulBoot: previous.lastSuccessfulBoot,
          status: .rollbackRequired,
          consecutiveFailures: previous.consecutiveFailures,
          lastFailureReason: .repeatedFailures
        )
      case .idle, .confirmed:
        next = BootState(
          schemaVersion: config.supportedSchemaVersion,
          bootAttempt: previous.bootAttempt + 1,
          lastSuccessfulBoot: previous.lastSuccessfulBoot,
          status: .pending,
          consecutiveFailures: previous.consecutiveFailures,
          lastFailureReason: .none
        )
      }
      try writeStateLocked(next)
      cached = next
      return toResult(next)
    }
  }

  @discardableResult
  public func markBootSuccessful() throws -> BootResult {
    try withLock {
      let previous = readStateLocked()
      let next = BootState(
        schemaVersion: config.supportedSchemaVersion,
        bootAttempt: previous.bootAttempt,
        lastSuccessfulBoot: previous.bootAttempt,
        status: .confirmed,
        consecutiveFailures: 0,
        lastFailureReason: .none
      )
      try writeStateLocked(next)
      cached = next
      return toResult(next)
    }
  }

  public func currentStatus() -> BootStatus {
    withLock { readStateLocked().status }
  }

  public func requiresRollback() -> Bool {
    withLock { readStateLocked().status == .rollbackRequired }
  }

  @discardableResult
  public func reset() throws -> BootResult {
    try withLock {
      let next = BootState.empty
      let normalized = BootState(
        schemaVersion: config.supportedSchemaVersion,
        bootAttempt: 0,
        lastSuccessfulBoot: 0,
        status: .idle,
        consecutiveFailures: 0,
        lastFailureReason: .none
      )
      try writeStateLocked(normalized)
      cached = normalized
      return toResult(normalized)
    }
  }

  public func currentState() -> BootState {
    withLock { readStateLocked() }
  }

  private func readStateLocked() -> BootState {
    if io.fileExists(bootTempFile) {
      try? io.removeItem(bootTempFile)
    }
    guard io.isFile(bootFile) else { return .empty }
    do {
      let bytes = try io.readData(from: bootFile)
      if let decoded = BootStateCodec.decode(bytes: bytes, supportedSchemaVersion: config.supportedSchemaVersion) {
        return decoded
      }
      return BootState(
        schemaVersion: config.supportedSchemaVersion,
        lastFailureReason: .corruptBootState
      )
    } catch {
      return BootState(
        schemaVersion: config.supportedSchemaVersion,
        lastFailureReason: .corruptBootState
      )
    }
  }

  private func writeStateLocked(_ state: BootState) throws {
    try io.createDirectory(config.otaDirectory)
    try io.writeAtomic(target: bootFile, temporary: bootTempFile, bytes: BootStateCodec.encode(state))
  }

  private func toResult(_ state: BootState) -> BootResult {
    BootResult(
      bootAttempt: state.bootAttempt,
      status: state.status,
      requiresRollback: state.status == .rollbackRequired,
      failureReason: state.lastFailureReason,
      consecutiveFailures: state.consecutiveFailures
    )
  }

  private func withLock<T>(_ body: () throws -> T) rethrows -> T {
    lock.lock()
    defer { lock.unlock() }
    return try body()
  }
}
