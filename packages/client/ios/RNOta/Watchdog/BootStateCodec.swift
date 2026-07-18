import Foundation

enum BootStateCodec {
  static func encode(_ state: BootState) -> Data {
    let body =
      "{"
      + "\"schemaVersion\":\(state.schemaVersion),"
      + "\"bootAttempt\":\(state.bootAttempt),"
      + "\"lastSuccessfulBoot\":\(state.lastSuccessfulBoot),"
      + "\"status\":\"\(state.status.rawValue)\","
      + "\"consecutiveFailures\":\(state.consecutiveFailures),"
      + "\"lastFailureReason\":\"\(state.lastFailureReason.rawValue)\""
      + "}\n"
    return Data(body.utf8)
  }

  static func decode(bytes: Data, supportedSchemaVersion: Int) -> BootState? {
    guard !bytes.isEmpty, let text = String(data: bytes, encoding: .utf8)?.trimmingCharacters(in: .whitespacesAndNewlines)
    else { return nil }
    if text == "{}" { return .empty }
    guard let root = try? parseObject(text) else { return nil }
    guard let schemaRaw = root["schemaVersion"], let schemaVersion = Int(schemaRaw ?? "") else { return nil }
    if schemaVersion > supportedSchemaVersion { return nil }
    return BootState(
      schemaVersion: max(schemaVersion, 1),
      bootAttempt: max(Int(stringValue(root, "bootAttempt", default: "0")) ?? 0, 0),
      lastSuccessfulBoot: max(Int(stringValue(root, "lastSuccessfulBoot", default: "0")) ?? 0, 0),
      status: BootStatus(rawValue: stringValue(root, "status", default: "idle")) ?? .idle,
      consecutiveFailures: max(Int(stringValue(root, "consecutiveFailures", default: "0")) ?? 0, 0),
      lastFailureReason: BootFailureReason(
        rawValue: stringValue(root, "lastFailureReason", default: "none")
      ) ?? .none
    )
  }

  /// `parseObject` yields `[String: String?]`, so subscript is `String??`.
  private static func stringValue(
    _ root: [String: String?],
    _ key: String,
    default defaultValue: String
  ) -> String {
    (root[key] ?? nil) ?? defaultValue
  }

  private static func parseObject(_ input: String) throws -> [String: String?] {
    guard input.hasPrefix("{"), input.hasSuffix("}") else {
      throw BundleStoreException.ioFailure("not an object")
    }
    let inner = String(input.dropFirst().dropLast()).trimmingCharacters(in: .whitespacesAndNewlines)
    if inner.isEmpty { return [:] }
    var result: [String: String?] = [:]
    var i = inner.startIndex
    while i < inner.endIndex {
      while i < inner.endIndex, inner[i].isWhitespace { i = inner.index(after: i) }
      guard inner[i] == "\"" else { throw BundleStoreException.ioFailure("expected key") }
      let keyStart = inner.index(after: i)
      let keyEnd = try findStringEnd(inner, from: keyStart)
      let key = unescape(String(inner[keyStart..<keyEnd]))
      i = inner.index(after: keyEnd)
      while i < inner.endIndex, inner[i].isWhitespace { i = inner.index(after: i) }
      guard inner[i] == ":" else { throw BundleStoreException.ioFailure("expected colon") }
      i = inner.index(after: i)
      while i < inner.endIndex, inner[i].isWhitespace { i = inner.index(after: i) }
      let (value, next) = try parseValue(inner, from: i)
      result[key] = value
      i = next
      while i < inner.endIndex, inner[i].isWhitespace { i = inner.index(after: i) }
      if i < inner.endIndex, inner[i] == "," {
        i = inner.index(after: i)
      }
    }
    return result
  }

  private static func parseValue(_ input: String, from start: String.Index) throws -> (String?, String.Index) {
    guard start < input.endIndex else {
      throw BundleStoreException.ioFailure("unexpected end of input")
    }
    if input[start...].hasPrefix("null") {
      return (nil, input.index(start, offsetBy: 4))
    }
    if input[start] == "\"" {
      let end = try findStringEnd(input, from: input.index(after: start))
      let value = unescape(String(input[input.index(after: start)..<end]))
      return (value, input.index(after: end))
    }
    if input[start].isNumber || input[start] == "-" {
      var j = start
      while j < input.endIndex, input[j].isNumber || input[j] == "-" {
        j = input.index(after: j)
      }
      return (String(input[start..<j]), j)
    }
    throw BundleStoreException.ioFailure("unsupported value")
  }

  private static func findStringEnd(_ input: String, from: String.Index) throws -> String.Index {
    var i = from
    while i < input.endIndex {
      let ch = input[i]
      if ch == "\\" {
        i = input.index(i, offsetBy: 2, limitedBy: input.endIndex) ?? input.endIndex
        continue
      }
      if ch == "\"" { return i }
      i = input.index(after: i)
    }
    throw BundleStoreException.ioFailure("unterminated string")
  }

  private static func unescape(_ value: String) -> String {
    var out = ""
    var i = value.startIndex
    while i < value.endIndex {
      let ch = value[i]
      if ch == "\\", value.index(after: i) < value.endIndex {
        let next = value[value.index(after: i)]
        switch next {
        case "\\", "\"": out.append(next)
        case "n": out.append("\n")
        case "r": out.append("\r")
        case "t": out.append("\t")
        default: out.append(next)
        }
        i = value.index(i, offsetBy: 2)
      } else {
        out.append(ch)
        i = value.index(after: i)
      }
    }
    return out
  }
}
