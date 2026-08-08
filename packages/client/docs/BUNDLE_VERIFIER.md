# Bundle Verifier (M5)

The **Bundle Verifier** is the security gate for OTA packages. It answers a single question:

> Is this bundle valid according to the configured verification policy?

It **never** activates a bundle, mutates `state.json`, downloads content, or talks to React Native. Another module (Update Manager, M3) decides what to do with the result.

---

## 1. Responsibility

| Owns | Does not own |
|------|----------------|
| Integrity checks (SHA-256) | Slot lifecycle / `state.json` |
| Authenticity checks (Ed25519 manifest signature) | Download / quarantine extraction |
| Path-safety for verification targets | Rollback / boot watchdog |
| Structured `VerificationResult` | Update policy / telemetry |
| Optional `runtimeVersion` constraint check | Bundle activation (`setActiveSlot`, etc.) |

**Platform locations**

- Android: `packages/client/android/src/main/java/com/rnota/verifier/`
- iOS: `packages/client/ios/RNOta/Verifier/`

---

## 2. Verification pipeline

Deterministic sequence — fail closed on the first failed check:

```
Bundle / slot
  ↓
Path under allowed root?          → PATH_UNSAFE
  ↓
Exists & readable?                → BUNDLE_MISSING / BUNDLE_UNREADABLE
  ↓
Non-empty?                        → BUNDLE_EMPTY
  ↓
Expected size (if provided)?      → SIZE_MISMATCH
  ↓
Manifest signature (if keys set)? → SIGNATURE_* / MANIFEST_*
  ↓
runtimeVersion (if both set)?     → RUNTIME_MISMATCH
  ↓
SHA-256 hash                      → HASH_MISMATCH / MALFORMED_HASH
  ↓
Asset hashes (slot mode)          → HASH_MISMATCH / PATH_UNSAFE
  ↓
VERIFIED
```

### APIs

**Single file** — `verify(VerificationRequest)`:

- Validates one file (typically `bundle.hbc`) against an expected SHA-256 hex digest.
- Optional manifest + detached Ed25519 signature when trust-chain inputs are supplied.

**Slot directory** — `verifySlot(...)`:

- Reads `manifest.json` and `manifest.json.sig` (Base64 detached Ed25519 signature).
- Verifies signature against a **list** of trusted 32-byte raw public keys (key rotation).
- Verifies `bundle.hbc` and every listed asset hash against the signed manifest.

---

## 3. Supported algorithms

| Check | Algorithm | Notes |
|-------|-----------|-------|
| Bundle / asset integrity | **SHA-256** | Streaming reads; hex compared case-insensitively |
| Manifest authenticity | **Ed25519** detached signature | Platform crypto: `java.security` (Android/JVM), **CryptoKit** (iOS) |
| Hash encoding | Lowercase hex, 64 characters | Malformed expected hashes rejected |

Shared constants and hex normalization live in `packages/crypto/src/index.ts` for CLI parity (M14).

---

## 4. Failure behavior

All expected failures return `VerificationResult` with `status = REJECTED` and a typed `VerificationFailureReason`. The verifier **does not throw** for:

- Missing / unreadable files
- Hash or signature mismatch
- Malformed metadata
- Path traversal attempts

Unexpected I/O during hashing may surface as `BUNDLE_UNREADABLE` or `INTERNAL_ERROR`.

Reason codes align with ARCHITECTURE.md M5:

| Reason | Architecture code |
|--------|-------------------|
| `SIGNATURE_INVALID` | `E_SIG` |
| `HASH_MISMATCH` | `E_HASH` |
| `RUNTIME_MISMATCH` | `E_RUNTIME_MISMATCH` |
| `PATH_UNSAFE` | `E_ZIP_UNSAFE` (path analogue) |

---

## 5. Security model

- **Fail closed** — missing hash, missing signature (when keys configured), or any mismatch → rejected.
- **Never trust filenames** — only signed manifest hashes and explicit request metadata.
- **Never trust server responses alone** — verification uses on-disk bytes only.
- **Path traversal defense** — verification targets must resolve under `allowedRoot`; manifest asset paths with `..` are rejected.
- **No secrets in logs** — results expose hex digests for diagnostics, not private keys or full signature material in messages.
- **Constant-time hash comparison** — expected vs actual SHA-256 compared without early exit on first differing byte.

---

## 6. Trust boundaries

```
Publisher (offline, M14)          Device (M5)
────────────────────────          ────────────
Private Ed25519 key    ──sign──►  Public key(s) baked into app binary
manifest.json          ──OTA──►  manifest.json + manifest.json.sig
bundle.hbc + assets    ──OTA──►  SHA-256 verified against signed manifest
```

- Private keys **never** ship in the client.
- Multiple public keys may be configured; verification succeeds if **any** key validates the signature (rotation).
- The verifier does **not** decide whether an old-but-valid package should be applied (replay policy — deferred).

---

## 7. Performance considerations

| Resource | Complexity |
|----------|------------|
| Time | **O(n)** in bundle bytes (single pass streaming hash) |
| Memory | **O(chunk size)** — default 64 KiB read buffer |

Large-bundle tests use multi-megabyte fixtures with a 4 KiB chunk size to exercise streaming without loading the full file into RAM.

---

## 8. What the verifier does NOT do

- Activate, stage, or rollback bundles
- Mutate `state.json`, `boot.json`, or slot directories
- Download manifests or packages
- Extract zip archives (ZipGuard — deferred to Downloader / full MS4 ZipGuard)
- Enforce binary-version ranges beyond optional `runtimeVersion` string equality
- Replay / freshness checks (`validUntil`, monotonic release ids)
- Telemetry or update policy

---

## 9. Example verification flow

### Hash-only (pre-trust-chain unit check)

```kotlin
val result = BundleVerifier().verify(
  VerificationRequest(
    bundleFile = File(slotDir, "bundle.hbc"),
    expectedSha256Hex = "abc123…", // 64 hex chars
    allowedRoot = otaRoot,
  ),
)
if (result.isVerified) { /* M3 may promote slot */ }
```

### Full slot trust chain

```kotlin
val result = BundleVerifier().verifySlot(
  slotDirectory = File(otaRoot, "slots/release-42"),
  trustedPublicKeys = listOf(rawEd25519PublicKey32Bytes),
  allowedRoot = otaRoot,
  runningRuntimeVersion = BuildConfig.RUNTIME_VERSION,
)
when (result.reason) {
  VerificationFailureReason.NONE -> { /* all checks passed */ }
  VerificationFailureReason.SIGNATURE_INVALID -> { /* E_SIG — alert pipeline */ }
  else -> { /* typed rejection */ }
}
```

---

## 10. Deferred security features

These are specified in ARCHITECTURE.md but **out of scope for this M5 deliverable**:

| Feature | Target |
|---------|--------|
| Safe zip extraction (ZipGuard: traversal, bombs, symlinks) | Downloader + full MS4 |
| Canonical JSON signing spec + CLI `sign`/`verify` (M14) | MS8 CLI / crypto package |
| Binary-version range checks | Policy + manifest schema |
| Manifest `validUntil` / expiry grace | Policy engine (M7) |
| Replay protection for old signed bundles | Update Manager + Policy |
| Build-time public key embedding hooks (Gradle / Info.plist) | Documented in ROADMAP MS4; wiring in host integration |
| Cross-platform known-answer fixture corpus in `packages/protocol/fixtures/` | MS4 close-out / MS8 |

---

## Manifest v1 (minimal, verification subset)

Until `packages/protocol` publishes the full schema:

```json
{
  "schemaVersion": 1,
  "runtimeVersion": "0.82.1-hermes",
  "bundle": { "file": "bundle.hbc", "sha256": "<64-hex>" },
  "assets": [
    { "path": "assets/icon.png", "sha256": "<64-hex>" }
  ]
}
```

Detached signature: `manifest.json.sig` — Base64-encoded 64-byte Ed25519 signature over the raw `manifest.json` bytes.

---

## Related documents

- [ARCHITECTURE.md](../../docs/ARCHITECTURE.md) — M5 module definition
- [BUNDLE_STORE.md](./BUNDLE_STORE.md) — slot layout (`bundle.hbc`, `manifest.json`)
- [ROLLBACK_ENGINE.md](./ROLLBACK_ENGINE.md) — separate concern; verifier never triggers rollback
