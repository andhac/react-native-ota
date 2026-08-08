import Foundation

enum JsonValue: Equatable {
  case str(String)
  case num(String)
  case bool(Bool)
  case null
  case obj([String: JsonValue])
  case arr([JsonValue])
}

enum CanonicalJson {
  static func encode(_ value: JsonValue) -> Data {
    Data(encodeValue(value).utf8)
  }

  static func encodeValue(_ value: JsonValue) -> String {
    switch value {
    case let .str(s):
      return encodeString(s)
    case let .num(n):
      return n
    case let .bool(b):
      return b ? "true" : "false"
    case .null:
      return "null"
    case let .obj(entries):
      return encodeObject(entries)
    case let .arr(items):
      return encodeArray(items)
    }
  }

  private static func encodeObject(_ entries: [String: JsonValue]) -> String {
    if entries.isEmpty { return "{}" }
    let body =
      entries.keys.sorted()
        .map { key in "\(encodeString(key)):\(encodeValue(entries[key]!))" }
        .joined(separator: ",")
    return "{\(body)}"
  }

  private static func encodeArray(_ items: [JsonValue]) -> String {
    if items.isEmpty { return "[]" }
    return "[\(items.map(encodeValue).joined(separator: ","))]"
  }

  private static func encodeString(_ raw: String) -> String {
    var out = "\""
    for ch in raw {
      switch ch {
      case "\\": out += "\\\\"
      case "\"": out += "\\\""
      case "\u{08}": out += "\\b"
      case "\u{0C}": out += "\\f"
      case "\n": out += "\\n"
      case "\r": out += "\\r"
      case "\t": out += "\\t"
      default:
        if ch.unicodeScalars.first!.value < 0x20 {
          out += String(format: "\\u%04x", ch.unicodeScalars.first!.value)
        } else {
          out.append(ch)
        }
      }
    }
    out += "\""
    return out
  }
}

enum ManifestJsonParser {
  static func parseObject(_ input: String) -> JsonValue? {
    let trimmed = input.trimmingCharacters(in: .whitespacesAndNewlines)
    guard trimmed.hasPrefix("{"), trimmed.hasSuffix("}") else { return nil }
    var index = trimmed.startIndex
    guard let (value, next) = parseObjectInner(trimmed, trimmed.startIndex) else {
      return nil
    }
    index = skipWs(trimmed, next)
    guard index == trimmed.endIndex else { return nil }
    guard case .obj = value else { return nil }
    return value
  }

  private static func parseObjectInner(
    _ input: String,
    _ start: String.Index
  ) -> (JsonValue, String.Index)? {
    var index = input.index(after: start)
    var entries: [String: JsonValue] = [:]
    while true {
      index = skipWs(input, index)
      if index < input.endIndex, input[index] == "}" {
        let next = input.index(after: index)
        return (JsonValue.obj(entries), next)
      }
      guard let (key, afterKey) = parseString(input, index) else { return nil }
      index = skipWs(input, afterKey)
      guard index < input.endIndex, input[index] == ":" else { return nil }
      index = skipWs(input, input.index(after: index))
      guard let (value, afterValue) = parseValue(input, index) else { return nil }
      entries[key] = value
      index = skipWs(input, afterValue)
      if index < input.endIndex, input[index] == "," {
        index = input.index(after: index)
        continue
      }
    }
  }

  private static func parseArray(
    _ input: String,
    _ start: String.Index
  ) -> (JsonValue, String.Index)? {
    var index = input.index(after: start)
    var items: [JsonValue] = []
    while true {
      index = skipWs(input, index)
      if index < input.endIndex, input[index] == "]" {
        let next = input.index(after: index)
        return (JsonValue.arr(items), next)
      }
      guard let (value, afterValue) = parseValue(input, index) else { return nil }
      items.append(value)
      index = skipWs(input, afterValue)
      if index < input.endIndex, input[index] == "," {
        index = input.index(after: index)
        continue
      }
    }
  }

  private static func parseValue(_ input: String, _ start: String.Index) -> (JsonValue, String.Index)? {
    let index = skipWs(input, start)
    guard index < input.endIndex else { return nil }
    switch input[index] {
    case "\"":
      return parseString(input, index).map { (JsonValue.str($0.0), $0.1) }
    case "{":
      return parseObjectInner(input, index)
    case "[":
      return parseArray(input, index)
    case "t":
      guard input[index...].hasPrefix("true") else { return nil }
      let next = input.index(index, offsetBy: 4)
      return (JsonValue.bool(true), next)
    case "f":
      guard input[index...].hasPrefix("false") else { return nil }
      let next = input.index(index, offsetBy: 5)
      return (JsonValue.bool(false), next)
    case "n":
      guard input[index...].hasPrefix("null") else { return nil }
      let next = input.index(index, offsetBy: 4)
      return (JsonValue.null, next)
    default:
      return parseNumber(input, index)
    }
  }

  private static func parseNumber(_ input: String, _ start: String.Index) -> (JsonValue, String.Index)? {
    var index = start
    if input[index] == "-" { index = input.index(after: index) }
    guard index < input.endIndex, input[index].isNumber else { return nil }
    while index < input.endIndex, input[index].isNumber { index = input.index(after: index) }
    var end = index
    if index < input.endIndex, input[index] == "." {
      index = input.index(after: index)
      guard index < input.endIndex, input[index].isNumber else { return nil }
      while index < input.endIndex, input[index].isNumber { index = input.index(after: index) }
      end = index
    }
    if index < input.endIndex, input[index] == "e" || input[index] == "E" { return nil }
    let canonical = String(input[start..<end])
    if canonical.hasPrefix("-0"), canonical.count > 1, canonical.dropFirst().first!.isNumber { return nil }
    if !canonical.hasPrefix("-"), canonical.hasPrefix("0"), canonical.count > 1, canonical.dropFirst().first!.isNumber {
      return nil
    }
    return (JsonValue.num(canonical), end)
  }

  private static func parseString(_ input: String, _ start: String.Index) -> (String, String.Index)? {
    guard start < input.endIndex, input[start] == "\"" else { return nil }
    var index = input.index(after: start)
    var out = ""
    while index < input.endIndex {
      let ch = input[index]
      switch ch {
      case "\\":
        index = input.index(after: index)
        guard index < input.endIndex else { return nil }
        switch input[index] {
        case "\\", "\"": out.append(input[index])
        case "b": out.append("\u{08}")
        case "f": out.append("\u{0C}")
        case "n": out.append("\n")
        case "r": out.append("\r")
        case "t": out.append("\t")
        case "u":
          let hexStart = input.index(after: index)
          guard let hexEnd = input.index(hexStart, offsetBy: 4, limitedBy: input.endIndex),
                hexEnd <= input.endIndex
          else { return nil }
          let hex = String(input[hexStart..<hexEnd])
          guard let scalar = UInt32(hex, radix: 16), let uni = UnicodeScalar(scalar) else { return nil }
          out.append(Character(uni))
          index = hexEnd
        default:
          return nil
        }
      case "\"":
        let next = input.index(after: index)
        return (out, next)
      default:
        if ch.unicodeScalars.first!.value < 0x20 { return nil }
        out.append(ch)
      }
      index = input.index(after: index)
    }
    return nil
  }

  private static func skipWs(_ input: String, _ index: String.Index) -> String.Index {
    var i = index
    while i < input.endIndex, input[i].isWhitespace {
      i = input.index(after: i)
    }
    return i
  }
}

struct ParsedManifest: Equatable {
  let runtimeVersion: String?
  let bundleSha256Hex: String
  let assets: [AssetEntry]
  let signingPayloadBytes: Data
}

enum ManifestParseResult: Equatable {
  case ok(ParsedManifest)
  case err(VerificationFailureReason, String?)
}

enum ManifestCodec {
  static func parse(manifestFile: URL) -> ManifestParseResult {
    let fm = FileManager.default
    guard fm.fileExists(atPath: manifestFile.path) else {
      return .err(.manifestMissing, "manifest.json is missing")
    }
    guard let raw = try? Data(contentsOf: manifestFile), !raw.isEmpty else {
      return .err(.manifestInvalid, "manifest is empty")
    }
    let text = String(data: raw, encoding: .utf8)?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
    guard let root = ManifestJsonParser.parseObject(text) else {
      return .err(.manifestInvalid, "invalid JSON object")
    }
    guard case let .obj(entries) = root else {
      return .err(.manifestInvalid, "top-level must be object")
    }
    guard entries["schemaVersion"] != nil else {
      return .err(.manifestInvalid, "schemaVersion is required")
    }
    guard case let .obj(bundleEntries) = entries["bundle"] else {
      return .err(.manifestInvalid, "bundle object is required")
    }
    guard case let .str(bundleFileName) = bundleEntries["file"] else {
      return .err(.manifestInvalid, "bundle.file is required")
    }
    if PathGuard.containsTraversalSegment(bundleFileName) {
      return .err(.bundleFilenameMismatch, "bundle.file contains path traversal")
    }
    if bundleFileName != OtaPaths.bundleFileName {
      return .err(.bundleFilenameMismatch, "bundle.file must be \(OtaPaths.bundleFileName)")
    }
    guard case let .str(bundleHashRaw) = bundleEntries["sha256"],
          let normalizedBundleHash = HexUtils.normalizeSha256Hex(bundleHashRaw)
    else {
      return .err(.malformedHash, "bundle.sha256 is malformed")
    }

    var assets: [AssetEntry] = []
    if let assetsNode = entries["assets"] {
      switch assetsNode {
      case .null:
        break
      case let .arr(items):
        for item in items {
          guard case let .obj(assetEntries) = item else {
            return .err(.manifestInvalid, "asset must be object")
          }
          guard case let .str(path) = assetEntries["path"] else {
            return .err(.manifestInvalid, "asset.path is required")
          }
          if PathGuard.containsTraversalSegment(path) {
            return .err(.pathUnsafe, "asset path contains traversal")
          }
          guard case let .str(hashRaw) = assetEntries["sha256"],
                let normalized = HexUtils.normalizeSha256Hex(hashRaw)
          else {
            return .err(.malformedHash, "asset.sha256 is malformed")
          }
          assets.append(AssetEntry(relativePath: path, sha256Hex: normalized))
        }
      default:
        return .err(.manifestInvalid, "assets must be an array")
      }
    }

    let runtimeVersion: String?
    if case let .str(rv) = entries["runtimeVersion"] {
      runtimeVersion = rv
    } else {
      runtimeVersion = nil
    }

    return .ok(
      ParsedManifest(
        runtimeVersion: runtimeVersion,
        bundleSha256Hex: normalizedBundleHash,
        assets: assets,
        signingPayloadBytes: CanonicalJson.encode(root)
      )
    )
  }
}
