package com.rnota.resolver

import com.rnota.store.BundleStore
import com.rnota.store.BundleStoreConfig
import com.rnota.store.OtaPaths
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class BundleResolverTest {
  @get:Rule
  val tmp = TemporaryFolder()

  private lateinit var otaDir: File
  private lateinit var store: BundleStore
  private lateinit var resolver: BundleResolver

  @Before
  fun setUp() {
    otaDir = tmp.newFolder("ota")
    store = BundleStore(BundleStoreConfig(otaDirectory = otaDir, installedBinaryVersion = "1.0.0"))
    store.initialize()
    resolver = BundleResolver(store)
  }

  @Test
  fun noActiveSlot_returnsEmbedded() {
    val r = resolver.resolve()
    assertEquals(BundleSource.EMBEDDED, r.source)
    assertNull(r.bundlePath)
    assertNull(r.slotId)
    assertEquals(ResolutionReason.NO_ACTIVE_SLOT, r.reason)
  }

  @Test
  fun invalidSlotIdInStateFile_treatedAsNoActive() {
    File(otaDir, OtaPaths.STATE_FILE_NAME).writeText(
      """{"schemaVersion":1,"installedBinaryVersion":null,"activeSlot":"../evil","pendingSlot":null,"previousSlot":null}""",
    )
    val r = resolver.resolve()
    assertEquals(BundleSource.EMBEDDED, r.source)
    assertEquals(ResolutionReason.NO_ACTIVE_SLOT, r.reason)
  }

  @Test
  fun missingSlotDirectory_returnsEmbedded() {
    writeStateActive("missing-slot")
    val r = resolver.resolve()
    assertEquals(BundleSource.EMBEDDED, r.source)
    assertEquals(ResolutionReason.SLOT_NOT_FOUND, r.reason)
  }

  @Test
  fun missingBundleHbc_returnsEmbedded() {
    store.createSlot("rel-empty")
    // Bypass setActiveSlot (requires committed) by writing state directly.
    writeStateActive("rel-empty")
    val r = resolver.resolve()
    assertEquals(BundleSource.EMBEDDED, r.source)
    assertEquals(ResolutionReason.BUNDLE_MISSING, r.reason)
  }

  @Test
  fun validOtaBundle_returnsAbsolutePath() {
    commitAndActivate("rel-ok")
    val r = resolver.resolve()
    assertEquals(BundleSource.OTA, r.source)
    assertEquals("rel-ok", r.slotId)
    assertEquals(ResolutionReason.ACTIVE_BUNDLE, r.reason)
    assertEquals(store.bundlePath("rel-ok").absolutePath, r.bundlePath)
    assertTrue(File(r.bundlePath!!).isFile)
  }

  @Test
  fun embeddedFallback_corruptState() {
    File(otaDir, OtaPaths.STATE_FILE_NAME).writeText("{broken")
    val r = resolver.resolve()
    assertEquals(BundleSource.EMBEDDED, r.source)
    assertEquals(ResolutionReason.NO_ACTIVE_SLOT, r.reason)
  }

  @Test
  fun corruptedReference_bundleDeleted_fallsBack() {
    commitAndActivate("rel-gone")
    store.bundlePath("rel-gone").delete()
    val r = resolver.resolve()
    assertEquals(BundleSource.EMBEDDED, r.source)
    assertEquals(ResolutionReason.BUNDLE_MISSING, r.reason)
  }

  @Test
  fun peekState_doesNotRewriteCorruptFile() {
    val corrupt = "{broken"
    File(otaDir, OtaPaths.STATE_FILE_NAME).writeText(corrupt)
    resolver.resolve()
    assertEquals(corrupt, File(otaDir, OtaPaths.STATE_FILE_NAME).readText())
  }

  @Test
  fun pendingSlot_ignored() {
    commitAndActivate("active-1")
    val pending = store.createSlot("pend-1")
    pending.bundleFile.writeBytes(byteArrayOf(1, 2, 3))
    writeState(active = "active-1", pending = "pend-1")
    val r = resolver.resolve()
    assertEquals("active-1", r.slotId)
    assertEquals(ResolutionReason.ACTIVE_BUNDLE, r.reason)
  }

  @Test
  fun resolve_isDeterministic() {
    commitAndActivate("rel-ok")
    val a = resolver.resolve()
    val b = resolver.resolve()
    assertEquals(a, b)
  }

  @Test
  fun futureSchema_embedded() {
    File(otaDir, OtaPaths.STATE_FILE_NAME).writeText(
      """{"schemaVersion":99,"installedBinaryVersion":null,"activeSlot":"rel-x","pendingSlot":null,"previousSlot":null}""",
    )
    val r = resolver.resolve()
    assertEquals(BundleSource.EMBEDDED, r.source)
    assertEquals(ResolutionReason.NO_ACTIVE_SLOT, r.reason)
  }

  private fun commitAndActivate(id: String) {
    val info = store.createSlot(id)
    info.bundleFile.writeBytes(byteArrayOf(0xC6.toByte(), 0x1F, 0x00))
    info.manifestFile.writeText("{}")
    store.setActiveSlot(id)
  }

  private fun writeStateActive(slotId: String) {
    writeState(active = slotId, pending = null)
  }

  private fun writeState(
    active: String?,
    pending: String?,
  ) {
    val json =
      buildString {
        append("{\"schemaVersion\":1,")
        append("\"installedBinaryVersion\":\"1.0.0\",")
        append("\"activeSlot\":").append(active?.let { "\"$it\"" } ?: "null").append(",")
        append("\"pendingSlot\":").append(pending?.let { "\"$it\"" } ?: "null").append(",")
        append("\"previousSlot\":null}")
      }
    File(otaDir, OtaPaths.STATE_FILE_NAME).writeText(json)
  }
}
