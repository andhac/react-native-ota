package com.rnota.verifier

import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest

/**
 * Streaming SHA-256 — O(n) time, O(chunk) memory.
 */
internal object StreamingSha256 {
  private const val CHUNK_SIZE = 64 * 1024

  fun hashFile(file: File): ByteArray? =
    try {
      FileInputStream(file).use { input ->
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(CHUNK_SIZE)
        while (true) {
          val read = input.read(buffer)
          if (read <= 0) {
            break
          }
          digest.update(buffer, 0, read)
        }
        digest.digest()
      }
    } catch (_: Exception) {
      null
    }
}
