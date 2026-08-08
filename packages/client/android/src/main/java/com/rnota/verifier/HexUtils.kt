package com.rnota.verifier

internal object HexUtils {
  private val HEX_REGEX = Regex("^[0-9a-fA-F]{64}$")

  fun normalizeSha256Hex(raw: String): String? {
    val trimmed = raw.trim()
    if (!HEX_REGEX.matches(trimmed)) {
      return null
    }
    return trimmed.lowercase()
  }

  fun bytesToHex(bytes: ByteArray): String =
    buildString(bytes.size * 2) {
      for (b in bytes) {
        append(String.format("%02x", b))
      }
    }
}
