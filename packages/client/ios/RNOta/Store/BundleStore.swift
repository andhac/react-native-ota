import Foundation

/// M2 — Bundle Store (iOS).
///
/// Owns on-disk OTA layout and atomic `state.json` transitions.
/// Deliberately unaware of networking, crypto, and React Native bundle loading.
///
/// Thread-safe via an internal lock. Feature-equivalent to Android `BundleStore`.
public final class BundleStore: @unchecked Sendable {
  private let config: BundleStoreConfig
  private let io: FileIo
  private let lock = NSRecursiveLock()

  private let otaDir: URL
  private let stateFile: URL
  private let stateTempFile: URL
  private let bootFile: URL
  private let slotsDir: URL

  private var cachedState: BundleState = .empty

  public init(config: BundleStoreConfig) {
    self.config = config
    self.io = config.fileIo
    self.otaDir = config.otaDirectory
    self.stateFile = otaDir.appendingPathComponent(OtaPaths.stateFileName)
    self.stateTempFile = otaDir.appendingPathComponent(OtaPaths.stateTempFileName)
    self.bootFile = otaDir.appendingPathComponent(OtaPaths.bootFileName)
    self.slotsDir = otaDir.appendingPathComponent(OtaPaths.slotsDirName)
  }

  public func initialize() throws {
    try withLock {
      try ensureLayout()
      try discardIncompleteTemp()
      cachedState = try readAndRecoverLocked()
    }
  }

  /// Reads only `state.json` (plus recovery rewrite if needed). Does not scan `slots/`.
  @discardableResult
  public func loadState() throws -> BundleState {
    try withLock {
      try ensureLayout()
      try discardIncompleteTemp()
      cachedState = try readAndRecoverLocked()
      return cachedState
    }
  }

  public func saveState(_ state: BundleState) throws {
    try withLock {
      try ensureLayout()
      let normalized = BundleState(
        schemaVersion: config.supportedSchemaVersion,
        installedBinaryVersion: state.installedBinaryVersion ?? config.installedBinaryVersion,
        activeSlot: OtaStateCodec.sanitizeSlotId(state.activeSlot),
        pendingSlot: OtaStateCodec.sanitizeSlotId(state.pendingSlot),
        previousSlot: OtaStateCodec.sanitizeSlotId(state.previousSlot)
      )
      try writeStateLocked(normalized)
      cachedState = normalized
    }
  }

  /// Creates slot dirs + `assets/`. Never writes `bundle.hbc` / never touches `state.json`.
  @discardableResult
  public func createSlot(_ slotId: String) throws -> SlotInfo {
    try withLock {
      try requireValidSlotId(slotId)
      let dir = slotDirectory(slotId)
      if io.fileExists(dir) {
        throw BundleStoreException.slotAlreadyExists(slotId)
      }
      let assets = dir.appendingPathComponent(OtaPaths.assetsDirName)
      do {
        try io.createDirectory(dir)
        try io.createDirectory(assets)
      } catch {
        throw BundleStoreException.ioFailure("Failed to create slot directories for \(slotId)")
      }
      return slotInfoLocked(slotId)
    }
  }

  public func deleteSlot(_ slotId: String) throws {
    try withLock {
      try requireValidSlotId(slotId)
      if cachedState.referencedSlotIds().contains(slotId) {
        throw BundleStoreException.slotInUse(slotId)
      }
      let dir = slotDirectory(slotId)
      guard io.fileExists(dir) else {
        throw BundleStoreException.slotNotFound(slotId)
      }
      do {
        try io.removeItem(dir)
      } catch {
        throw BundleStoreException.ioFailure("Failed to delete slot \(slotId)")
      }
    }
  }

  public func slotExists(_ slotId: String) -> Bool {
    withLock {
      guard OtaStateCodec.isValidSlotId(slotId) else { return false }
      return io.isDirectory(slotDirectory(slotId))
    }
  }

  /// Explicit directory scan — call only when listing is required.
  public func listSlots() throws -> [SlotInfo] {
    try withLock {
      try ensureLayout()
      let names = try io.listDirectoryNames(slotsDir)
      return names
        .filter { OtaStateCodec.isValidSlotId($0) }
        .sorted()
        .map { slotInfoLocked($0) }
    }
  }

  public func setActiveSlot(_ slotId: String?) throws {
    try withLock {
      let next: BundleState
      if let slotId {
        try requireValidSlotId(slotId)
        try requireCommittedSlot(slotId)
        next = withBinaryVersion(cachedState.withActive(slotId))
      } else {
        next = withBinaryVersion(cachedState.withActive(nil))
      }
      try writeStateLocked(next)
      cachedState = next
    }
  }

  public func setPendingSlot(_ slotId: String?) throws {
    try withLock {
      let next: BundleState
      if let slotId {
        try requireValidSlotId(slotId)
        try requireCommittedSlot(slotId)
        next = withBinaryVersion(cachedState.withPending(slotId))
      } else {
        next = withBinaryVersion(cachedState.withPending(nil))
      }
      try writeStateLocked(next)
      cachedState = next
    }
  }

  public func clearPendingSlot() throws {
    try setPendingSlot(nil)
  }

  public func setPreviousSlot(_ slotId: String?) throws {
    try withLock {
      let next: BundleState
      if let slotId {
        try requireValidSlotId(slotId)
        try requireCommittedSlot(slotId)
        next = withBinaryVersion(cachedState.withPrevious(slotId))
      } else {
        next = withBinaryVersion(cachedState.withPrevious(nil))
      }
      try writeStateLocked(next)
      cachedState = next
    }
  }

  @discardableResult
  public func garbageCollect() throws -> [String] {
    try withLock {
      try ensureLayout()
      let keep = cachedState.referencedSlotIds()
      var deleted: [String] = []
      for name in try io.listDirectoryNames(slotsDir) {
        if !OtaStateCodec.isValidSlotId(name) {
          try? io.removeItem(slotsDir.appendingPathComponent(name))
          deleted.append(name)
          continue
        }
        if !keep.contains(name) {
          try? io.removeItem(slotsDir.appendingPathComponent(name))
          deleted.append(name)
        }
      }
      return deleted.sorted()
    }
  }

  public func slotDirectory(_ slotId: String) -> URL {
    slotsDir.appendingPathComponent(slotId)
  }

  public func bundlePath(_ slotId: String) -> URL {
    slotDirectory(slotId).appendingPathComponent(OtaPaths.bundleFileName)
  }

  public func manifestPath(_ slotId: String) -> URL {
    slotDirectory(slotId).appendingPathComponent(OtaPaths.manifestFileName)
  }

  public func assetsDirectory(_ slotId: String) -> URL {
    slotDirectory(slotId).appendingPathComponent(OtaPaths.assetsDirName)
  }

  public func currentState() -> BundleState {
    withLock { cachedState }
  }

  /// Read-only snapshot of `state.json` for M1 Bundle Resolver.
  /// Does **not** create layout, recover, or write.
  public func peekState() -> BundleState {
    withLock {
      guard io.isFile(stateFile) else { return .empty }
      do {
        let bytes = try io.readData(from: stateFile)
        return OtaStateCodec.decode(bytes: bytes, supportedSchemaVersion: config.supportedSchemaVersion)
          ?? .empty
      } catch {
        return .empty
      }
    }
  }

  /// True when `slots/<id>/bundle.hbc` exists as a regular file. Read-only.
  public func hasCommittedBundle(_ slotId: String) -> Bool {
    withLock {
      OtaStateCodec.isValidSlotId(slotId) && isCommittedLocked(slotId)
    }
  }

  // MARK: - Internals

  private func ensureLayout() throws {
    do {
      try io.createDirectory(otaDir)
      try io.createDirectory(slotsDir)
    } catch {
      throw BundleStoreException.ioFailure("Cannot create OTA directories")
    }
    if !io.fileExists(bootFile) {
      let bootTemp = otaDir.appendingPathComponent("\(OtaPaths.bootFileName).tmp")
      try io.writeAtomic(target: bootFile, temporary: bootTemp, bytes: Data("{}\n".utf8))
    }
  }

  private func discardIncompleteTemp() throws {
    if io.fileExists(stateTempFile) {
      try? io.removeItem(stateTempFile)
    }
  }

  private func readAndRecoverLocked() throws -> BundleState {
    let loaded: BundleState?
    if io.isFile(stateFile) {
      loaded = try? OtaStateCodec.decode(
        bytes: io.readData(from: stateFile),
        supportedSchemaVersion: config.supportedSchemaVersion
      )
    } else {
      loaded = nil
    }

    let base = loaded ?? BundleState(
      schemaVersion: config.supportedSchemaVersion,
      installedBinaryVersion: config.installedBinaryVersion
    )
    let recovered = sanitizeReferences(base)
    let needsRewrite =
      loaded == nil ||
      recovered != base ||
      recovered.schemaVersion != config.supportedSchemaVersion

    let toPersist = BundleState(
      schemaVersion: config.supportedSchemaVersion,
      installedBinaryVersion: recovered.installedBinaryVersion ?? config.installedBinaryVersion,
      activeSlot: recovered.activeSlot,
      pendingSlot: recovered.pendingSlot,
      previousSlot: recovered.previousSlot
    )

    if needsRewrite {
      try writeStateLocked(toPersist)
    }
    return toPersist
  }

  private func sanitizeReferences(_ state: BundleState) -> BundleState {
    func resolve(_ id: String?) -> String? {
      guard let id else { return nil }
      guard let sanitized = OtaStateCodec.sanitizeSlotId(id) else { return nil }
      return isCommittedLocked(sanitized) ? sanitized : nil
    }
    return BundleState(
      schemaVersion: state.schemaVersion,
      installedBinaryVersion: state.installedBinaryVersion,
      activeSlot: resolve(state.activeSlot),
      pendingSlot: resolve(state.pendingSlot),
      previousSlot: resolve(state.previousSlot)
    )
  }

  private func writeStateLocked(_ state: BundleState) throws {
    let bytes = OtaStateCodec.encode(state)
    try io.writeAtomic(target: stateFile, temporary: stateTempFile, bytes: bytes)
  }

  private func withBinaryVersion(_ state: BundleState) -> BundleState {
    BundleState(
      schemaVersion: config.supportedSchemaVersion,
      installedBinaryVersion: state.installedBinaryVersion ?? config.installedBinaryVersion,
      activeSlot: state.activeSlot,
      pendingSlot: state.pendingSlot,
      previousSlot: state.previousSlot
    )
  }

  private func requireValidSlotId(_ slotId: String) throws {
    guard OtaStateCodec.isValidSlotId(slotId) else {
      throw BundleStoreException.invalidSlotId(slotId)
    }
  }

  private func requireCommittedSlot(_ slotId: String) throws {
    guard io.isDirectory(slotDirectory(slotId)) else {
      throw BundleStoreException.slotNotFound(slotId)
    }
    guard isCommittedLocked(slotId) else {
      throw BundleStoreException.slotNotCommitted(slotId)
    }
  }

  private func isCommittedLocked(_ slotId: String) -> Bool {
    io.isFile(bundlePath(slotId))
  }

  private func slotInfoLocked(_ slotId: String) -> SlotInfo {
    SlotInfo(
      id: slotId,
      directory: slotDirectory(slotId),
      bundleFile: bundlePath(slotId),
      manifestFile: manifestPath(slotId),
      assetsDirectory: assetsDirectory(slotId),
      isCommitted: isCommittedLocked(slotId)
    )
  }

  private func withLock<T>(_ body: () throws -> T) rethrows -> T {
    lock.lock()
    defer { lock.unlock() }
    return try body()
  }
}

private extension BundleState {
  func withActive(_ id: String?) -> BundleState {
    BundleState(
      schemaVersion: schemaVersion,
      installedBinaryVersion: installedBinaryVersion,
      activeSlot: id,
      pendingSlot: pendingSlot,
      previousSlot: previousSlot
    )
  }

  func withPending(_ id: String?) -> BundleState {
    BundleState(
      schemaVersion: schemaVersion,
      installedBinaryVersion: installedBinaryVersion,
      activeSlot: activeSlot,
      pendingSlot: id,
      previousSlot: previousSlot
    )
  }

  func withPrevious(_ id: String?) -> BundleState {
    BundleState(
      schemaVersion: schemaVersion,
      installedBinaryVersion: installedBinaryVersion,
      activeSlot: activeSlot,
      pendingSlot: pendingSlot,
      previousSlot: id
    )
  }
}
