package com.rnota.verifier

import java.security.KeyFactory
import java.security.PublicKey
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/**
 * Ed25519 detached signature verification (ARCHITECTURE.md M5 / M14).
 * Uses platform `java.security` — no private keys on device.
 */
internal object Ed25519Verifier {
  /** Raw 32-byte Ed25519 public key. */
  fun verify(
    message: ByteArray,
    signature: ByteArray,
    rawPublicKey: ByteArray,
  ): Boolean {
    if (rawPublicKey.size != 32 || signature.size != 64) {
      return false
    }
    return try {
      val publicKey = rawPublicKeyToPublicKey(rawPublicKey)
      val sig = Signature.getInstance("Ed25519")
      sig.initVerify(publicKey)
      sig.update(message)
      sig.verify(signature)
    } catch (_: Exception) {
      false
    }
  }

  fun decodeBase64Signature(encoded: String): ByteArray? =
    try {
      val bytes = Base64.getDecoder().decode(encoded.trim())
      if (bytes.size != 64) null else bytes
    } catch (_: Exception) {
      null
    }

  private fun rawPublicKeyToPublicKey(raw: ByteArray): PublicKey {
    // PKCS#8 / X.509 wrapper for 32-byte Ed25519 public key (RFC 8410).
    val prefix =
      byteArrayOf(
        0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x70, 0x03, 0x21, 0x00,
      )
    val x509 = prefix + raw
    val spec = X509EncodedKeySpec(x509)
    return KeyFactory.getInstance("Ed25519").generatePublic(spec)
  }
}
