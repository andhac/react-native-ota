package com.rnota.watchdog

/**
 * Encode / decode `boot.json` with stable key order.
 */
internal object BootStateCodec {
  fun encode(state: BootState): ByteArray {
    val body =
      buildString {
        append('{')
        append("\"schemaVersion\":").append(state.schemaVersion).append(',')
        append("\"bootAttempt\":").append(state.bootAttempt).append(',')
        append("\"lastSuccessfulBoot\":").append(state.lastSuccessfulBoot).append(',')
        append("\"status\":\"").append(statusToWire(state.status)).append("\",")
        append("\"consecutiveFailures\":").append(state.consecutiveFailures).append(',')
        append("\"lastFailureReason\":\"").append(reasonToWire(state.lastFailureReason)).append('"')
        append("}\n")
      }
    return body.toByteArray(Charsets.UTF_8)
  }

  fun decode(
    bytes: ByteArray,
    supportedSchemaVersion: Int,
  ): BootState? {
    if (bytes.isEmpty()) {
      return null
    }
    return try {
      val text = String(bytes, Charsets.UTF_8).trim()
      // Empty placeholder `{}` from BundleStore.initialize → treat as empty watchdog state.
      if (text == "{}") {
        return BootState.EMPTY
      }
      val root = parseObject(text)
      val schemaRaw = root["schemaVersion"] ?: return null
      val schemaVersion = schemaRaw.toIntOrNull() ?: return null
      if (schemaVersion > supportedSchemaVersion) {
        return null
      }
      BootState(
        schemaVersion = schemaVersion.coerceAtLeast(1),
        bootAttempt = root["bootAttempt"]?.toIntOrNull()?.coerceAtLeast(0) ?: 0,
        lastSuccessfulBoot = root["lastSuccessfulBoot"]?.toIntOrNull()?.coerceAtLeast(0) ?: 0,
        status = statusFromWire(root["status"]),
        consecutiveFailures = root["consecutiveFailures"]?.toIntOrNull()?.coerceAtLeast(0) ?: 0,
        lastFailureReason = reasonFromWire(root["lastFailureReason"]),
      )
    } catch (_: Exception) {
      null
    }
  }

  private fun statusToWire(status: BootStatus): String =
    when (status) {
      BootStatus.IDLE -> "idle"
      BootStatus.PENDING -> "pending"
      BootStatus.CONFIRMED -> "confirmed"
      BootStatus.ROLLBACK_REQUIRED -> "rollback_required"
    }

  private fun statusFromWire(raw: String?): BootStatus =
    when (raw) {
      "pending" -> BootStatus.PENDING
      "confirmed" -> BootStatus.CONFIRMED
      "rollback_required" -> BootStatus.ROLLBACK_REQUIRED
      "idle", null -> BootStatus.IDLE
      else -> BootStatus.IDLE
    }

  private fun reasonToWire(reason: BootFailureReason): String =
    when (reason) {
      BootFailureReason.NONE -> "none"
      BootFailureReason.INCOMPLETE_BOOT -> "incomplete_boot"
      BootFailureReason.REPEATED_FAILURES -> "repeated_failures"
      BootFailureReason.CORRUPT_BOOT_STATE -> "corrupt_boot_state"
    }

  private fun reasonFromWire(raw: String?): BootFailureReason =
    when (raw) {
      "incomplete_boot" -> BootFailureReason.INCOMPLETE_BOOT
      "repeated_failures" -> BootFailureReason.REPEATED_FAILURES
      "corrupt_boot_state" -> BootFailureReason.CORRUPT_BOOT_STATE
      else -> BootFailureReason.NONE
    }

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
