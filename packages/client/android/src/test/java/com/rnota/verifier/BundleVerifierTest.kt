package com.rnota.verifier

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import java.security.KeyPairGenerator
import java.security.Signature
import java.util.Base64

class BundleVerifierTest {
  @get:Rule
  val temp = TemporaryFolder()

  private lateinit var root: File
  private lateinit var verifier: BundleVerifier
  private lateinit var publicKeyRaw: ByteArray
  private lateinit var privateKey: java.security.PrivateKey

  @Before
  fun setUp() {
    root = temp.newFolder("ota-root")
    verifier = BundleVerifier()
    val keyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
    privateKey = keyPair.private
    publicKeyRaw = extractRawPublicKey(keyPair.public.encoded)
  }

  @Test
  fun verify_validBundle_succeeds() {
    val bundle = writeBundle("hello-bundle", expectedContent = "valid bundle bytes")
    val hash = sha256HexOf(bundle)

    val result =
      verifier.verify(
        VerificationRequest(
          bundleFile = bundle,
          expectedSha256Hex = hash,
          allowedRoot = root,
        ),
      )

    assertTrue(result.isVerified)
    assertEquals(VerificationFailureReason.NONE, result.reason)
    assertEquals(hash, result.actualSha256Hex)
  }

  @Test
  fun verify_missingBundle_rejects() {
    val missing = File(root, "missing.hbc")
    val result =
      verifier.verify(
        VerificationRequest(
          bundleFile = missing,
          expectedSha256Hex = "a".repeat(64),
          allowedRoot = root,
        ),
      )

    assertFalse(result.isVerified)
    assertEquals(VerificationFailureReason.BUNDLE_MISSING, result.reason)
  }

  @Test
  fun verify_emptyBundle_rejects() {
    val empty = File(root, "empty.hbc").apply { writeBytes(ByteArray(0)) }
    val result =
      verifier.verify(
        VerificationRequest(
          bundleFile = empty,
          expectedSha256Hex = "a".repeat(64),
          allowedRoot = root,
        ),
      )

    assertEquals(VerificationFailureReason.BUNDLE_EMPTY, result.reason)
  }

  @Test
  fun verify_unreadableBundle_rejects() {
    if (!isPosix()) {
      return
    }
    val content = "secret"
    val bundle = writeBundle("unreadable", content)
    val hash = sha256HexOf(content.toByteArray())
    Files.setPosixFilePermissions(
      bundle.toPath(),
      setOf(PosixFilePermission.OWNER_WRITE),
    )

    val result =
      verifier.verify(
        VerificationRequest(
          bundleFile = bundle,
          expectedSha256Hex = hash,
          allowedRoot = root,
        ),
      )

    assertEquals(VerificationFailureReason.BUNDLE_UNREADABLE, result.reason)
  }

  @Test
  fun verify_correctHash_succeeds() {
    val content = "correct-hash-content"
    val bundle = writeBundle("correct", content)
    val hash = sha256HexOf(content.toByteArray())

    val result =
      verifier.verify(
        VerificationRequest(
          bundleFile = bundle,
          expectedSha256Hex = hash,
          allowedRoot = root,
        ),
      )

    assertTrue(result.isVerified)
  }

  @Test
  fun verify_incorrectHash_rejects() {
    val bundle = writeBundle("wrong-hash", "payload")
    val wrongHash = "0".repeat(64)

    val result =
      verifier.verify(
        VerificationRequest(
          bundleFile = bundle,
          expectedSha256Hex = wrongHash,
          allowedRoot = root,
        ),
      )

    assertEquals(VerificationFailureReason.HASH_MISMATCH, result.reason)
    assertEquals(wrongHash, result.expectedSha256Hex)
    assertTrue(result.actualSha256Hex!!.isNotEmpty())
  }

  @Test
  fun verify_malformedExpectedHash_rejects() {
    val bundle = writeBundle("malformed", "x")
    val result =
      verifier.verify(
        VerificationRequest(
          bundleFile = bundle,
          expectedSha256Hex = "not-a-hash",
          allowedRoot = root,
        ),
      )

    assertEquals(VerificationFailureReason.MALFORMED_HASH, result.reason)
  }

  @Test
  fun verify_hashCaseNormalization_succeeds() {
    val content = "case-test"
    val bundle = writeBundle("case", content)
    val lower = sha256HexOf(content.toByteArray())
    val upper = lower.uppercase()

    val result =
      verifier.verify(
        VerificationRequest(
          bundleFile = bundle,
          expectedSha256Hex = upper,
          allowedRoot = root,
        ),
      )

    assertTrue(result.isVerified)
    assertEquals(lower, result.actualSha256Hex)
  }

  @Test
  fun verify_largeBundle_streamsWithoutLoadingAllIntoMemory() {
    val large = ByteArray(5 * 1024 * 1024) { (it % 251).toByte() }
    val bundle = File(root, "large.hbc").apply { writeBytes(large) }
    val hash = sha256HexOf(large)
    val streamingVerifier = BundleVerifier(chunkSizeBytes = 4096)

    val result =
      streamingVerifier.verify(
        VerificationRequest(
          bundleFile = bundle,
          expectedSha256Hex = hash,
          allowedRoot = root,
        ),
      )

    assertTrue(result.isVerified)
    assertEquals(large.size.toLong(), result.bundleSizeBytes)
  }

  @Test
  fun verify_corruptedBundle_rejectsWhenHashWrong() {
    val bundle = writeBundle("corrupt", "original")
    val tamperedHash = sha256HexOf("tampered".toByteArray())

    val result =
      verifier.verify(
        VerificationRequest(
          bundleFile = bundle,
          expectedSha256Hex = tamperedHash,
          allowedRoot = root,
        ),
      )

    assertEquals(VerificationFailureReason.HASH_MISMATCH, result.reason)
  }

  @Test
  fun verify_pathOutsideAllowedRoot_rejects() {
    val outside = temp.newFolder("outside")
    val bundle = writeBundle("outside", "x", dir = outside)
    val hash = sha256HexOf("x".toByteArray())

    val result =
      verifier.verify(
        VerificationRequest(
          bundleFile = bundle,
          expectedSha256Hex = hash,
          allowedRoot = root,
        ),
      )

    assertEquals(VerificationFailureReason.PATH_UNSAFE, result.reason)
  }

  @Test
  fun verify_pathInsideAllowedRoot_succeeds() {
    val bundle = writeBundle("inside", "inside-bytes")
    val hash = sha256HexOf("inside-bytes".toByteArray())

    val result =
      verifier.verify(
        VerificationRequest(
          bundleFile = bundle,
          expectedSha256Hex = hash,
          allowedRoot = root,
        ),
      )

    assertTrue(result.isVerified)
  }

  @Test
  fun verifySlot_canonicalJsonFormatting_equivalentPayloadsVerify() {
    val slot = File(root, "slots/canonical-slot").apply { mkdirs() }
    val bundleContent = "canonical-payload"
    File(slot, "bundle.hbc").writeText(bundleContent)
    val bundleHash = sha256HexOf(bundleContent.toByteArray())
    val compact =
      """{"schemaVersion":1,"runtimeVersion":"1.0.0","bundle":{"file":"bundle.hbc","sha256":"$bundleHash"},"assets":[]}"""
    val pretty =
      """
      {
        "schemaVersion": 1,
        "runtimeVersion": "1.0.0",
        "bundle": { "file": "bundle.hbc", "sha256": "$bundleHash" },
        "assets": []
      }
      """.trimIndent()
    writeSignedManifest(slot, pretty)

    val result =
      verifier.verifySlot(
        slotDirectory = slot,
        trustedPublicKeys = listOf(publicKeyRaw),
        allowedRoot = root,
        runningRuntimeVersion = "1.0.0",
      )

    assertTrue(result.isVerified)
    assertEquals(signingPayload(compact).toList(), signingPayload(pretty).toList())
  }

  @Test
  fun verifySlot_wrongBundleFilename_rejects() {
    val slot = File(root, "slots/wrong-name").apply { mkdirs() }
    File(slot, "bundle.hbc").writeText("payload")
    val bundleHash = sha256HexOf("payload".toByteArray())
    val manifest =
      """
      {
        "schemaVersion": 1,
        "bundle": { "file": "other.hbc", "sha256": "$bundleHash" },
        "assets": []
      }
      """.trimIndent()
    File(slot, "manifest.json").writeText(manifest)

    val result =
      verifier.verifySlot(
        slotDirectory = slot,
        trustedPublicKeys = listOf(publicKeyRaw),
        allowedRoot = root,
      )

    assertEquals(VerificationFailureReason.BUNDLE_FILENAME_MISMATCH, result.reason)
  }

  @Test
  fun verifySlot_missingBundleFileField_rejects() {
    val slot = File(root, "slots/no-file-field").apply { mkdirs() }
    File(slot, "bundle.hbc").writeText("payload")
    val bundleHash = sha256HexOf("payload".toByteArray())
    val manifest =
      """
      {
        "schemaVersion": 1,
        "bundle": { "sha256": "$bundleHash" },
        "assets": []
      }
      """.trimIndent()
    File(slot, "manifest.json").writeText(manifest)

    val result =
      verifier.verifySlot(
        slotDirectory = slot,
        trustedPublicKeys = listOf(publicKeyRaw),
        allowedRoot = root,
      )

    assertEquals(VerificationFailureReason.MANIFEST_INVALID, result.reason)
  }

  @Test
  fun verifySlot_traversalBundleFilename_rejects() {
    val slot = File(root, "slots/traversal-file").apply { mkdirs() }
    File(slot, "bundle.hbc").writeText("payload")
    val bundleHash = sha256HexOf("payload".toByteArray())
    val manifest =
      """
      {
        "schemaVersion": 1,
        "bundle": { "file": "../bundle.hbc", "sha256": "$bundleHash" },
        "assets": []
      }
      """.trimIndent()
    File(slot, "manifest.json").writeText(manifest)

    val result =
      verifier.verifySlot(
        slotDirectory = slot,
        trustedPublicKeys = listOf(publicKeyRaw),
        allowedRoot = root,
      )

    assertEquals(VerificationFailureReason.BUNDLE_FILENAME_MISMATCH, result.reason)
  }

  @Test
  fun verify_pathTraversal_rejects() {
    val outside = temp.newFolder("outside")
    val bundle = writeBundle("outside", "x", dir = outside)
    val hash = sha256HexOf(bundle)

    val result =
      verifier.verify(
        VerificationRequest(
          bundleFile = bundle,
          expectedSha256Hex = hash,
          allowedRoot = root,
        ),
      )

    assertEquals(VerificationFailureReason.PATH_UNSAFE, result.reason)
  }

  @Test
  fun verify_deterministicResult() {
    val bundle = writeBundle("deterministic", "same-bytes")
    val hash = sha256HexOf(bundle)
    val request =
      VerificationRequest(
        bundleFile = bundle,
        expectedSha256Hex = hash,
        allowedRoot = root,
      )

    val first = verifier.verify(request)
    val second = verifier.verify(request)

    assertEquals(first, second)
  }

  @Test
  fun verify_sizeMismatch_rejects() {
    val bundle = writeBundle("size", "12345")
    val hash = sha256HexOf(bundle)

    val result =
      verifier.verify(
        VerificationRequest(
          bundleFile = bundle,
          expectedSha256Hex = hash,
          expectedSizeBytes = 999L,
          allowedRoot = root,
        ),
      )

    assertEquals(VerificationFailureReason.SIZE_MISMATCH, result.reason)
  }

  @Test
  fun verifySlot_validSignatureAndHash_succeeds() {
    val slot = File(root, "slots/slot-a").apply { mkdirs() }
    val bundleContent = "signed-bundle-payload"
    val bundle = File(slot, "bundle.hbc").apply { writeText(bundleContent) }
    val bundleHash = sha256HexOf(bundleContent.toByteArray())
    val manifest =
      """
      {
        "schemaVersion": 1,
        "runtimeVersion": "1.0.0",
        "bundle": { "file": "bundle.hbc", "sha256": "$bundleHash" },
        "assets": []
      }
      """.trimIndent()
    val manifestFile = File(slot, "manifest.json").apply { writeText(manifest) }
    writeSignature(slot, manifest)

    val result =
      verifier.verifySlot(
        slotDirectory = slot,
        trustedPublicKeys = listOf(publicKeyRaw),
        allowedRoot = root,
        runningRuntimeVersion = "1.0.0",
      )

    assertTrue(result.isVerified)
    assertEquals(bundleHash, result.actualSha256Hex)
  }

  @Test
  fun verifySlot_invalidSignature_rejects() {
    val slot = setupSignedSlot("bad-sig")
    File(slot, "manifest.json.sig").writeText(Base64.getEncoder().encodeToString(ByteArray(64)))

    val result =
      verifier.verifySlot(
        slotDirectory = slot,
        trustedPublicKeys = listOf(publicKeyRaw),
        allowedRoot = root,
      )

    assertEquals(VerificationFailureReason.SIGNATURE_INVALID, result.reason)
  }

  @Test
  fun verifySlot_wrongPublicKey_rejects() {
    val slot = setupSignedSlot("wrong-key")
    val otherKey = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
    val otherRaw = extractRawPublicKey(otherKey.public.encoded)

    val result =
      verifier.verifySlot(
        slotDirectory = slot,
        trustedPublicKeys = listOf(otherRaw),
        allowedRoot = root,
      )

    assertEquals(VerificationFailureReason.SIGNATURE_INVALID, result.reason)
  }

  @Test
  fun verifySlot_modifiedBundle_rejects() {
    val slot = setupSignedSlot("modified")
    File(slot, "bundle.hbc").writeText("tampered-after-sign")

    val result =
      verifier.verifySlot(
        slotDirectory = slot,
        trustedPublicKeys = listOf(publicKeyRaw),
        allowedRoot = root,
      )

    assertEquals(VerificationFailureReason.HASH_MISMATCH, result.reason)
  }

  @Test
  fun verifySlot_malformedSignature_rejects() {
    val slot = setupSignedSlot("malformed-sig")
    File(slot, "manifest.json.sig").writeText("!!!not-base64!!!")

    val result =
      verifier.verifySlot(
        slotDirectory = slot,
        trustedPublicKeys = listOf(publicKeyRaw),
        allowedRoot = root,
      )

    assertEquals(VerificationFailureReason.MALFORMED_SIGNATURE, result.reason)
  }

  @Test
  fun verifySlot_missingSignature_rejects() {
    val slot = setupSignedSlot("missing-sig")
    File(slot, "manifest.json.sig").delete()

    val result =
      verifier.verifySlot(
        slotDirectory = slot,
        trustedPublicKeys = listOf(publicKeyRaw),
        allowedRoot = root,
      )

    assertEquals(VerificationFailureReason.SIGNATURE_MISSING, result.reason)
  }

  @Test
  fun verifySlot_runtimeMismatch_rejects() {
    val slot = setupSignedSlot("runtime-mismatch", runtimeVersion = "2.0.0")

    val result =
      verifier.verifySlot(
        slotDirectory = slot,
        trustedPublicKeys = listOf(publicKeyRaw),
        allowedRoot = root,
        runningRuntimeVersion = "1.0.0",
      )

    assertEquals(VerificationFailureReason.RUNTIME_MISMATCH, result.reason)
  }

  @Test
  fun verifySlot_assetHashMismatch_rejects() {
    val slot = File(root, "slots/asset-slot").apply { mkdirs() }
    val bundleContent = "bundle"
    File(slot, "bundle.hbc").writeText(bundleContent)
    val assetsDir = File(slot, "assets").apply { mkdirs() }
    val assetFile = File(assetsDir, "icon.png").apply { writeText("icon-bytes") }
    val bundleHash = sha256HexOf(bundleContent.toByteArray())
    val wrongAssetHash = "f".repeat(64)
    val manifest =
      """
      {
        "schemaVersion": 1,
        "runtimeVersion": "1.0.0",
        "bundle": { "file": "bundle.hbc", "sha256": "$bundleHash" },
        "assets": [ { "path": "assets/icon.png", "sha256": "$wrongAssetHash" } ]
      }
      """.trimIndent()
    File(slot, "manifest.json").writeText(manifest)
    writeSignature(slot, manifest)

    val result =
      verifier.verifySlot(
        slotDirectory = slot,
        trustedPublicKeys = listOf(publicKeyRaw),
        allowedRoot = root,
        runningRuntimeVersion = "1.0.0",
      )

    assertEquals(VerificationFailureReason.HASH_MISMATCH, result.reason)
  }

  @Test
  fun verifySlot_assetPathTraversal_rejects() {
    val slot = File(root, "slots/traversal-slot").apply { mkdirs() }
    File(slot, "bundle.hbc").writeText("b")
    val bundleHash = sha256HexOf("b".toByteArray())
    val manifest =
      """
      {
        "schemaVersion": 1,
        "bundle": { "file": "bundle.hbc", "sha256": "$bundleHash" },
        "assets": [ { "path": "../escape.png", "sha256": "${"a".repeat(64)}" } ]
      }
      """.trimIndent()
    File(slot, "manifest.json").writeText(manifest)

    val result =
      verifier.verifySlot(
        slotDirectory = slot,
        trustedPublicKeys = listOf(publicKeyRaw),
        allowedRoot = root,
      )

    assertEquals(VerificationFailureReason.PATH_UNSAFE, result.reason)
  }

  private fun setupSignedSlot(
    name: String,
    runtimeVersion: String = "1.0.0",
  ): File {
    val slot = File(root, "slots/$name").apply { mkdirs() }
    val bundleContent = "payload-$name"
    File(slot, "bundle.hbc").writeText(bundleContent)
    val bundleHash = sha256HexOf(bundleContent.toByteArray())
    val manifest =
      """
      {
        "schemaVersion": 1,
        "runtimeVersion": "$runtimeVersion",
        "bundle": { "file": "bundle.hbc", "sha256": "$bundleHash" },
        "assets": []
      }
      """.trimIndent()
    File(slot, "manifest.json").writeText(manifest)
    writeSignature(slot, manifest)
    return slot
  }

  private fun signingPayload(manifestText: String): ByteArray {
    val file = File.createTempFile("manifest", ".json", root)
    file.writeText(manifestText)
    val parsed = ManifestCodec.parse(file) as ManifestParseResult.Ok
    return parsed.manifest.signingPayloadBytes
  }

  private fun writeSignature(slot: File, manifestText: String) {
    val payload = signingPayload(manifestText)
    File(slot, "manifest.json.sig").writeText(
      Base64.getEncoder().encodeToString(sign(payload)),
    )
  }

  private fun writeSignedManifest(slot: File, manifestText: String) {
    File(slot, "manifest.json").writeText(manifestText)
    writeSignature(slot, manifestText)
  }

  private fun writeBundle(
    name: String,
    expectedContent: String,
    dir: File = root,
  ): File = File(dir, "$name.hbc").apply { writeText(expectedContent) }

  private fun sign(message: ByteArray): ByteArray {
    val sig = Signature.getInstance("Ed25519")
    sig.initSign(privateKey)
    sig.update(message)
    return sig.sign()
  }

  private fun sha256HexOf(file: File): String = sha256HexOf(file.readBytes())

  private fun sha256HexOf(bytes: ByteArray): String {
    val digest = java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
    return digest.joinToString("") { "%02x".format(it) }
  }

  private fun extractRawPublicKey(x509Encoded: ByteArray): ByteArray =
    x509Encoded.copyOfRange(x509Encoded.size - 32, x509Encoded.size)

  private fun isPosix(): Boolean =
    try {
      Files.getPosixFilePermissions(temp.root.toPath())
      true
    } catch (_: UnsupportedOperationException) {
      false
    }
}
