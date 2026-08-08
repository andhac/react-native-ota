package com.rnota.verifier

import com.rnota.store.OtaPaths
import java.io.File

/**
 * Minimal manifest v1 parser for verification (full schema lands in packages/protocol MS4+).
 */
internal data class ParsedManifest(
  val runtimeVersion: String?,
  val bundleSha256Hex: String,
  val assets: List<AssetEntry>,
  val canonicalBytes: ByteArray,
)

internal data class AssetEntry(
  val relativePath: String,
  val sha256Hex: String,
)

internal object ManifestCodec {
  fun parse(manifestFile: File): ParsedManifest? {
    if (!manifestFile.isFile) {
      return null
    }
    return try {
      val raw = manifestFile.readBytes()
      if (raw.isEmpty()) {
        return null
      }
      val text = String(raw, Charsets.UTF_8).trim()
      val root = MinimalJson.parseObject(text) ?: return null
      if (!root.containsKey("schemaVersion")) {
        return null
      }

      val bundle = root["bundle"] as? Map<*, *> ?: return null
      val bundleHashRaw = bundle["sha256"] as? String ?: return null
      val normalizedBundleHash = HexUtils.normalizeSha256Hex(bundleHashRaw) ?: return null
      val bundleFileName = bundle["file"] as? String ?: OtaPaths.BUNDLE_FILE_NAME
      if (PathGuard.containsTraversalSegment(bundleFileName)) {
        return null
      }

      val assets = mutableListOf<AssetEntry>()
      @Suppress("UNCHECKED_CAST")
      val assetsArray = root["assets"] as? List<Map<String, String>>
      if (assetsArray != null) {
        for (item in assetsArray) {
          val path = item["path"] ?: return null
          if (PathGuard.containsTraversalSegment(path)) {
            return null
          }
          val hash = item["sha256"] ?: return null
          val normalized = HexUtils.normalizeSha256Hex(hash) ?: return null
          assets.add(AssetEntry(path, normalized))
        }
      }

      val runtimeVersion = root["runtimeVersion"] as? String

      ParsedManifest(
        runtimeVersion = runtimeVersion,
        bundleSha256Hex = normalizedBundleHash,
        assets = assets,
        canonicalBytes = raw,
      )
    } catch (_: Exception) {
      null
    }
  }
}

/** Minimal JSON parser for manifest v1 (no third-party dependency). */
private object MinimalJson {
  fun parseObject(input: String): Map<String, Any?>? {
    val trimmed = input.trim()
    if (!trimmed.startsWith("{") || !trimmed.endsWith("}")) {
      return null
    }
    return parseObjectInner(trimmed, 0).first
  }

  private fun parseObjectInner(
    input: String,
    start: Int,
  ): Pair<Map<String, Any?>, Int> {
    var i = start + 1
    val result = linkedMapOf<String, Any?>()
    while (i < input.length) {
      i = skipWs(input, i)
      if (i < input.length && input[i] == '}') {
        return result to i + 1
      }
      val (key, afterKey) = parseString(input, i) ?: return result to i
      i = skipWs(input, afterKey)
      if (i >= input.length || input[i] != ':') {
        return result to i
      }
      i = skipWs(input, i + 1)
      val (value, afterValue) = parseValue(input, i) ?: return result to i
      result[key] = value
      i = skipWs(input, afterValue)
      if (i < input.length && input[i] == ',') {
        i++
      }
    }
    return result to i
  }

  private fun parseArray(
    input: String,
    start: Int,
  ): Pair<List<Map<String, String>>, Int>? {
    var i = start + 1
    val items = mutableListOf<Map<String, String>>()
    while (i < input.length) {
      i = skipWs(input, i)
      if (i < input.length && input[i] == ']') {
        return items to i + 1
      }
      if (input[i] != '{') {
        return null
      }
      val (obj, afterObj) = parseObjectInner(input, i)
      val stringMap =
        obj.mapNotNull { (k, v) ->
          if (v is String) k to v else null
        }.toMap()
      items.add(stringMap)
      i = skipWs(input, afterObj)
      if (i < input.length && input[i] == ',') {
        i++
      }
    }
    return null
  }

  private fun parseValue(
    input: String,
    start: Int,
  ): Pair<Any?, Int>? {
    var i = skipWs(input, start)
    if (i >= input.length) {
      return null
    }
    return when (input[i]) {
      '"' -> parseString(input, i)?.let { (s, next) -> s to next }
      '{' -> {
        val (obj, next) = parseObjectInner(input, i)
        obj to next
      }
      '[' -> parseArray(input, i)?.let { (arr, next) -> arr to next }
      'n' -> {
        if (input.startsWith("null", i)) {
          null to i + 4
        } else {
          null
        }
      }
      else -> {
        if (input[i].isDigit() || input[i] == '-') {
          var j = i
          while (j < input.length && (input[j].isDigit() || input[j] == '-' || input[j] == '.')) {
            j++
          }
          input.substring(i, j) to j
        } else {
          null
        }
      }
    }
  }

  private fun parseString(
    input: String,
    start: Int,
  ): Pair<String, Int>? {
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
              'n' -> '\n'
              'r' -> '\r'
              't' -> '\t'
              else -> input[i + 1]
            },
          )
          i += 2
        }
        '"' -> return sb.toString() to i + 1
        else -> {
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
