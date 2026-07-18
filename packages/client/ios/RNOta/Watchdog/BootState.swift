import Foundation

public struct BootState: Equatable, Sendable {
  public var schemaVersion: Int
  public var bootAttempt: Int
  public var lastSuccessfulBoot: Int
  public var status: BootStatus
  public var consecutiveFailures: Int
  public var lastFailureReason: BootFailureReason

  public static let empty = BootState(
    schemaVersion: BootPaths.supportedSchemaVersion,
    bootAttempt: 0,
    lastSuccessfulBoot: 0,
    status: .idle,
    consecutiveFailures: 0,
    lastFailureReason: .none
  )

  public init(
    schemaVersion: Int = BootPaths.supportedSchemaVersion,
    bootAttempt: Int = 0,
    lastSuccessfulBoot: Int = 0,
    status: BootStatus = .idle,
    consecutiveFailures: Int = 0,
    lastFailureReason: BootFailureReason = .none
  ) {
    self.schemaVersion = schemaVersion
    self.bootAttempt = bootAttempt
    self.lastSuccessfulBoot = lastSuccessfulBoot
    self.status = status
    self.consecutiveFailures = consecutiveFailures
    self.lastFailureReason = lastFailureReason
  }
}
