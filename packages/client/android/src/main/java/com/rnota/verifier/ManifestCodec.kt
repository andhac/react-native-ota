package com.rnota.verifier

import com.rnota.store.OtaPaths
import java.io.File

internal data class ParsedManifest(
  val runtimeVersion: String?,
  val bundleSha256Hex: String,
  val assets: List<AssetEntry>,
  /** Ed25519 signing payload — canonical JSON bytes (not raw file bytes). */
  val signingPayloadBytes: ByteArray,
)

internal data class AssetEntry(
  val relativePath: String,
  val sha256Hex: String,
)

internal sealed class ManifestParseResult {
  data class Ok(val manifest: ParsedManifest) : ManifestParseResult()

  data class Err(
    val reason: VerificationFailureReason,
    val message: String? = null,
  ) : ManifestParseResult()
}

internal object ManifestCodec {
  fun parse(manifestFile: File): ManifestParseResult {
    if (!manifestFile.isFile) {
      return ManifestParseResult.Err(
        reason = VerificationFailureReason.MANIFEST_MISSING,
        message = "manifest.json is missing",
      )
    }
    return try {
      val raw = manifestFile.readBytes()
      if (raw.isEmpty()) {
        return ManifestParseResult.Err(VerificationFailureReason.MANIFEST_INVALID, "manifest is empty")
      }
      val text = String(raw, Charsets.UTF_8).trim()
      val root = ManifestJsonParser.parseObject(text)
        ?: return ManifestParseResult.Err(VerificationFailureReason.MANIFEST_INVALID, "invalid JSON object")

      if (!root.entries.containsKey("schemaVersion")) {
        return ManifestParseResult.Err(VerificationFailureReason.MANIFEST_INVALID, "schemaVersion is required")
      }

      val bundleObj = root.entries["bundle"] as? JsonValue.Obj
        ?: return ManifestParseResult.Err(VerificationFailureReason.MANIFEST_INVALID, "bundle object is required")

      val bundleFileValue = bundleObj.entries["file"]
      if (bundleFileValue !is JsonValue.Str) {
        return ManifestParseResult.Err(
          reason = VerificationFailureReason.MANIFEST_INVALID,
          message = "bundle.file is required",
        )
      }
      val bundleFileName = bundleFileValue.value
      if (PathGuard.containsTraversalSegment(bundleFileName)) {
        return ManifestParseResult.Err(
          reason = VerificationFailureReason.BUNDLE_FILENAME_MISMATCH,
          message = "bundle.file contains path traversal",
        )
      }
      if (bundleFileName != OtaPaths.BUNDLE_FILE_NAME) {
        return ManifestParseResult.Err(
          reason = VerificationFailureReason.BUNDLE_FILENAME_MISMATCH,
          message = "bundle.file must be ${OtaPaths.BUNDLE_FILE_NAME}",
        )
      }

      val bundleHashValue = bundleObj.entries["sha256"]
      if (bundleHashValue !is JsonValue.Str) {
        return ManifestParseResult.Err(VerificationFailureReason.MANIFEST_INVALID, "bundle.sha256 is required")
      }
      val normalizedBundleHash = HexUtils.normalizeSha256Hex(bundleHashValue.value)
        ?: return ManifestParseResult.Err(VerificationFailureReason.MALFORMED_HASH, "bundle.sha256 is malformed")

      val assets = mutableListOf<AssetEntry>()
      when (val assetsNode = root.entries["assets"]) {
        null, JsonValue.Null -> Unit
        is JsonValue.Arr -> {
          for (item in assetsNode.items) {
            val assetObj = item as? JsonValue.Obj
              ?: return ManifestParseResult.Err(VerificationFailureReason.MANIFEST_INVALID, "asset must be object")
            val pathValue = assetObj.entries["path"] as? JsonValue.Str
              ?: return ManifestParseResult.Err(VerificationFailureReason.MANIFEST_INVALID, "asset.path is required")
            if (PathGuard.containsTraversalSegment(pathValue.value)) {
              return ManifestParseResult.Err(
                reason = VerificationFailureReason.PATH_UNSAFE,
                message = "asset path contains traversal",
              )
            }
            val hashValue = assetObj.entries["sha256"] as? JsonValue.Str
              ?: return ManifestParseResult.Err(VerificationFailureReason.MANIFEST_INVALID, "asset.sha256 is required")
            val normalized = HexUtils.normalizeSha256Hex(hashValue.value)
              ?: return ManifestParseResult.Err(VerificationFailureReason.MALFORMED_HASH, "asset.sha256 is malformed")
            assets.add(AssetEntry(pathValue.value, normalized))
          }
        }
        else ->
          return ManifestParseResult.Err(VerificationFailureReason.MANIFEST_INVALID, "assets must be an array")
      }

      val runtimeVersion = (root.entries["runtimeVersion"] as? JsonValue.Str)?.value

      ManifestParseResult.Ok(
        ParsedManifest(
          runtimeVersion = runtimeVersion,
          bundleSha256Hex = normalizedBundleHash,
          assets = assets,
          signingPayloadBytes = CanonicalJson.encode(root),
        ),
      )
    } catch (_: Exception) {
      ManifestParseResult.Err(VerificationFailureReason.MANIFEST_INVALID, "manifest parse failed")
    }
  }
}

/** Minimal JSON parser producing [JsonValue] (no third-party dependency). */
private object ManifestJsonParser {
  fun parseObject(input: String): JsonValue.Obj? {
    val trimmed = input.trim()
    if (!trimmed.startsWith("{") || !trimmed.endsWith("}")) {
      return null
    }
    val (obj, next) = parseObjectInner(trimmed, 0) ?: return null
    if (skipWs(trimmed, next) != trimmed.length) {
      return null
    }
    return obj
  }

  private fun parseObjectInner(input: String, start: Int): Pair<JsonValue.Obj, Int>? {
    var i = start + 1
    val entries = linkedMapOf<String, JsonValue>()
    while (true) {
      i = skipWs(input, i)
      if (i < input.length && input[i] == '}') {
        return JsonValue.Obj(entries) to i + 1
      }
      val (key, afterKey) = parseString(input, i) ?: return null
      i = skipWs(input, afterKey)
      if (i >= input.length || input[i] != ':') {
        return null
      }
      i = skipWs(input, i + 1)
      val (value, afterValue) = parseValue(input, i) ?: return null
      entries[key] = value
      i = skipWs(input, afterValue)
      if (i < input.length && input[i] == ',') {
        i++
        continue
      }
    }
  }

  private fun parseArray(input: String, start: Int): Pair<JsonValue.Arr, Int>? {
    var i = start + 1
    val items = mutableListOf<JsonValue>()
    while (true) {
      i = skipWs(input, i)
      if (i < input.length && input[i] == ']') {
        return JsonValue.Arr(items) to i + 1
      }
      val (value, afterValue) = parseValue(input, i) ?: return null
      items.add(value)
      i = skipWs(input, afterValue)
      if (i < input.length && input[i] == ',') {
        i++
        continue
      }
    }
  }

  private fun parseValue(input: String, start: Int): Pair<JsonValue, Int>? {
    var i = skipWs(input, start)
    if (i >= input.length) {
      return null
    }
    return when (input[i]) {
      '"' -> parseString(input, i)?.let { (s, next) -> JsonValue.Str(s) to next }
      '{' -> parseObjectInner(input, i)
      '[' -> parseArray(input, i)
      't' -> if (input.startsWith("true", i)) JsonValue.Bool(true) to i + 4 else null
      'f' -> if (input.startsWith("false", i)) JsonValue.Bool(false) to i + 5 else null
      'n' -> if (input.startsWith("null", i)) JsonValue.Null to i + 4 else null
      else -> parseNumber(input, i)
    }
  }

  private fun parseNumber(input: String, start: Int): Pair<JsonValue.Num, Int>? {
    var i = start
    if (input[i] == '-') {
      i++
    }
    if (i >= input.length || !input[i].isDigit()) {
      return null
    }
    val intStart = i
    while (i < input.length && input[i].isDigit()) {
      i++
    }
    var canonical = input.substring(start, i)
    if (i < input.length && input[i] == '.') {
      val fracStart = i
      i++
      if (i >= input.length || !input[i].isDigit()) {
        return null
      }
      while (i < input.length && input[i].isDigit()) {
        i++
      }
      canonical = input.substring(start, i)
    }
    if (i < input.length && (input[i] == 'e' || input[i] == 'E')) {
      return null
    }
    if (canonical.startsWith("-0") && canonical.length > 1 && canonical[1].isDigit()) {
      return null
    }
    if (!canonical.startsWith("-") && canonical.startsWith("0") && canonical.length > 1 && canonical[1].isDigit()) {
      return null
    }
    return JsonValue.Num(canonical) to i
  }

  private fun parseString(input: String, start: Int): Pair<String, Int>? {
    if (start >= input.length || input[start] != '"') {
      return null
    }
    var i = start + 1
    val sb = StringBuilder()
    while (i < input.length) {
      when (val ch = input[i]) {
        '\\' -> {
          if (i + 1 >= input.length) {
            return null
          }
          sb.append(
            when (input[i + 1]) {
              '\\', '"' -> input[i + 1]
              'b' -> '\b'
              'f' -> '\u000C'
              'n' -> '\n'
              'r' -> '\r'
              't' -> '\t'
              'u' -> {
                if (i + 5 >= input.length) {
                  return null
                }
                val hex = input.substring(i + 2, i + 6)
                i += 4
                hex.toInt(16).toChar()
              }
              else -> return null
            },
          )
          i += 2
        }
        '"' -> return sb.toString() to i + 1
        else -> {
          if (ch.code < 0x20) {
            return null
          }
          sb.append(ch)
          i++
        }
      }
    }
    return null
  }

  private fun skipWs(input: String, start: Int): Int {
    var i = start
    while (i < input.length && input[i].isWhitespace()) {
      i++
    }
    return i
  }
}
