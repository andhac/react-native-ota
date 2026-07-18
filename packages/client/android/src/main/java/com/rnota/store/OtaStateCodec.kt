package com.rnota.store

/**
 * Encode / decode `state.json` with stable key order (no third-party JSON dependency).
 * Invalid slot-ids are normalized to null (path-traversal defense).
 */
object OtaStateCodec {
  fun encode(state: BundleState): ByteArray {
    val body =
      buildString {
        append('{')
        append("\"schemaVersion\":").append(state.schemaVersion).append(',')
        append("\"installedBinaryVersion\":").append(jsonStringOrNull(state.installedBinaryVersion)).append(',')
        append("\"activeSlot\":").append(jsonStringOrNull(state.activeSlot)).append(',')
        append("\"pendingSlot\":").append(jsonStringOrNull(state.pendingSlot)).append(',')
        append("\"previousSlot\":").append(jsonStringOrNull(state.previousSlot))
        append("}\n")
      }
    return body.toByteArray(Charsets.UTF_8)
  }

  /**
   * @return null if the payload is unusable (corrupt / missing schema / future schema).
   */
  fun decode(
    bytes: ByteArray,
    supportedSchemaVersion: Int,
  ): BundleState? {
    if (bytes.isEmpty()) {
      return null
    }
    return try {
      val root = parseObject(String(bytes, Charsets.UTF_8).trim())
      val schemaRaw = root["schemaVersion"] ?: return null
      val schemaVersion = schemaRaw.toIntOrNull() ?: return null
      if (schemaVersion > supportedSchemaVersion) {
        return null
      }
      BundleState(
        schemaVersion = schemaVersion.coerceAtLeast(1),
        installedBinaryVersion = root.nullableString("installedBinaryVersion"),
        activeSlot = sanitizeSlotId(root.nullableString("activeSlot")),
        pendingSlot = sanitizeSlotId(root.nullableString("pendingSlot")),
        previousSlot = sanitizeSlotId(root.nullableString("previousSlot")),
      )
    } catch (_: Exception) {
      null
    }
  }

  fun isValidSlotId(slotId: String): Boolean = OtaPaths.SLOT_ID_REGEX.matches(slotId)

  fun sanitizeSlotId(raw: String?): String? {
    if (raw.isNullOrEmpty()) {
      return null
    }
    return if (isValidSlotId(raw)) raw else null
  }

  private fun jsonStringOrNull(value: String?): String =
    if (value == null) {
      "null"
    } else {
      "\"${escape(value)}\""
    }

  private fun escape(value: String): String =
    buildString(value.length + 8) {
      for (ch in value) {
        when (ch) {
          '\\' -> append("\\\\")
          '"' -> append("\\\"")
          '\n' -> append("\\n")
          '\r' -> append("\\r")
          '\t' -> append("\\t")
          else -> append(ch)
        }
      }
    }

  private fun Map<String, String?>.nullableString(key: String): String? {
    if (!containsKey(key)) {
      return null
    }
    return this[key]
  }

  /**
   * Minimal object parser for our flat state shape.
   * Values are either JSON null, number, or JSON string.
   */
  private fun parseObject(input: String): Map<String, String?> {
    require(input.startsWith("{") && input.endsWith("}")) { "not an object" }
    val inner = input.substring(1, input.length - 1).trim()
    if (inner.isEmpty()) {
      return emptyMap()
    }
    val result = linkedMapOf<String, String?>()
    var i = 0
    while (i < inner.length) {
      while (i < inner.length && inner[i].isWhitespace()) i++
      require(inner[i] == '"') { "expected key" }
      val keyEnd = findStringEnd(inner, i + 1)
      val key = unescape(inner.substring(i + 1, keyEnd))
      i = keyEnd + 1
      while (i < inner.length && inner[i].isWhitespace()) i++
      require(inner[i] == ':') { "expected colon" }
      i++
      while (i < inner.length && inner[i].isWhitespace()) i++
      val (value, next) = parseValue(inner, i)
      result[key] = value
      i = next
      while (i < inner.length && inner[i].isWhitespace()) i++
      if (i < inner.length && inner[i] == ',') {
        i++
      }
    }
    return result
  }

  private fun parseValue(
    input: String,
    start: Int,
  ): Pair<String?, Int> {
    var i = start
    when {
      input.startsWith("null", i) -> return null to i + 4
      input[i] == '"' -> {
        val end = findStringEnd(input, i + 1)
        return unescape(input.substring(i + 1, end)) to end + 1
      }
      input[i].isDigit() || input[i] == '-' -> {
        var j = i
        while (j < input.length && (input[j].isDigit() || input[j] == '-')) j++
        return input.substring(i, j) to j
      }
      else -> error("unsupported value")
    }
  }

  private fun findStringEnd(
    input: String,
    from: Int,
  ): Int {
    var i = from
    while (i < input.length) {
      val ch = input[i]
      if (ch == '\\') {
        i += 2
        continue
      }
      if (ch == '"') {
        return i
      }
      i++
    }
    error("unterminated string")
  }

  private fun unescape(value: String): String =
    buildString(value.length) {
      var i = 0
      while (i < value.length) {
        val ch = value[i]
        if (ch == '\\' && i + 1 < value.length) {
          when (val next = value[i + 1]) {
            '\\', '"' -> append(next)
            'n' -> append('\n')
            'r' -> append('\r')
            't' -> append('\t')
            else -> append(next)
          }
          i += 2
        } else {
          append(ch)
          i++
        }
      }
    }
}
