package com.rnota.verifier

/**
 * Deterministic canonical JSON encoding for manifest signing (ROADMAP MS4 / ARCHITECTURE M14).
 *
 * Rules (both platforms must match byte-for-byte):
 * - UTF-8, no BOM, no insignificant whitespace
 * - Object keys sorted lexicographically (Unicode code-point order)
 * - Array element order preserved
 * - Strings escaped per RFC 8259
 * - Numbers: JSON number tokens as parsed (no leading-zero padding)
 * - Booleans: lowercase `true` / `false`; null: `null`
 */
internal object CanonicalJson {
  fun encode(value: JsonValue): ByteArray =
    encodeValue(value).toByteArray(Charsets.UTF_8)

  fun encodeValue(value: JsonValue): String =
    when (value) {
      is JsonValue.Str -> encodeString(value.value)
      is JsonValue.Num -> value.canonical
      is JsonValue.Bool -> if (value.value) "true" else "false"
      JsonValue.Null -> "null"
      is JsonValue.Obj -> encodeObject(value.entries)
      is JsonValue.Arr -> encodeArray(value.items)
    }

  private fun encodeObject(entries: Map<String, JsonValue>): String {
    if (entries.isEmpty()) {
      return "{}"
    }
    val body =
      entries.keys.sorted().joinToString(",") { key ->
        "${encodeString(key)}:${encodeValue(entries.getValue(key))}"
      }
    return "{$body}"
  }

  private fun encodeArray(items: List<JsonValue>): String {
    if (items.isEmpty()) {
      return "[]"
    }
    return items.joinToString(",", prefix = "[", postfix = "]") { encodeValue(it) }
  }

  private fun encodeString(raw: String): String =
    buildString(raw.length + 2) {
      append('"')
      for (ch in raw) {
        when (ch) {
          '\\' -> append("\\\\")
          '"' -> append("\\\"")
          '\b' -> append("\\b")
          '\u000C' -> append("\\f")
          '\n' -> append("\\n")
          '\r' -> append("\\r")
          '\t' -> append("\\t")
          else ->
            if (ch.code < 0x20) {
              append(String.format("\\u%04x", ch.code))
            } else {
              append(ch)
            }
        }
      }
      append('"')
    }
}

/** Typed JSON tree for parse + canonical encode. */
internal sealed class JsonValue {
  data class Str(val value: String) : JsonValue()
  data class Num(val canonical: String) : JsonValue()
  data class Bool(val value: Boolean) : JsonValue()
  data object Null : JsonValue()
  data class Obj(val entries: Map<String, JsonValue>) : JsonValue()
  data class Arr(val items: List<JsonValue>) : JsonValue()
}
