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
allowedRoot provided?             → (required at API level)
  ↓
Path under allowedRoot?           → PATH_UNSAFE
  ↓
Exists & readable?                → BUNDLE_MISSING / BUNDLE_UNREADABLE
  ↓
Non-empty?                        → BUNDLE_EMPTY
  ↓
Expected size (if provided)?      → SIZE_MISMATCH
  ↓
Manifest parse + bundle.file      → MANIFEST_* / BUNDLE_FILENAME_MISMATCH
  ↓
Ed25519 over canonical JSON       → SIGNATURE_*
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
- **`allowedRoot` is required** — there is no opt-out path validation mode.
- Optional manifest + detached Ed25519 signature when trust-chain inputs are supplied.

**Slot directory** — `verifySlot(...)`:

- Reads `manifest.json` and `manifest.json.sig` (Base64 detached Ed25519 signature).
- **`allowedRoot` is required** — slot directory must resolve under it.
- Verifies signature against a **list** of trusted 32-byte raw public keys (key rotation).
- Enforces `manifest.bundle.file == "bundle.hbc"` (store invariant).
- Verifies `bundle.hbc` and every listed asset hash against the signed manifest.

---

## 3. Path trust boundary

- `allowedRoot` is **mandatory** on both `verify()` and `verifySlot()`.
- Targets are resolved with canonical paths (`canonicalFile` / `resolvingSymlinksInPath()`).
- Android uses `Path.startsWith(root)`; iOS uses `path.hasPrefix(root.path + "/")` or exact root match.
- Manifest asset paths and `bundle.file` containing `..` are rejected.
- A missing or out-of-root path never falls through to hash/signature checks.

---

## 4. Bundle filename invariant

Committed OTA slots always use `bundle.hbc` ([BUNDLE_STORE.md](./BUNDLE_STORE.md)).

| `manifest.bundle.file` | Result |
|------------------------|--------|
| `"bundle.hbc"` | Accepted (if other checks pass) |
| Any other value | `BUNDLE_FILENAME_MISMATCH` |
| Missing | `MANIFEST_INVALID` |
| Contains `..` | `BUNDLE_FILENAME_MISMATCH` |

The verifier always hashes `slots/<id>/bundle.hbc` — never a manifest-redirected path.

---

## 5. Signature payload — canonical JSON

Per **ROADMAP MS4** and **ARCHITECTURE.md M14**, Ed25519 detached signatures are over **canonical JSON bytes**, not raw `manifest.json` file bytes.

Both Android and iOS:

1. Parse the manifest JSON into a typed tree.
2. Re-encode with identical canonical rules.
3. Verify the Ed25519 signature over those bytes.

### Canonicalization rules (v1)

| Rule | Detail |
|------|--------|
| Encoding | UTF-8, no BOM |
| Objects | Keys sorted lexicographically (Unicode code-point order) |
| Arrays | Element order preserved |
| Whitespace | None (compact) |
| Strings | RFC 8259 escaping |
| Numbers | JSON number tokens as parsed (no leading-zero integers) |
| Booleans / null | `true`, `false`, `null` |

On-disk `manifest.json` may be pretty-printed; verification uses the canonical payload only. Publishers (M14 CLI) must sign the same canonical bytes devices verify.

---

## 6. Supported algorithms

| Check | Algorithm | Notes |
|-------|-----------|-------|
| Bundle / asset integrity | **SHA-256** | Streaming reads; hex compared case-insensitively |
| Manifest authenticity | **Ed25519** detached signature | Platform crypto: `java.security` (Android/JVM), **CryptoKit** (iOS) |
| Hash encoding | Lowercase hex, 64 characters | Malformed expected hashes rejected |

Shared constants and hex normalization live in `packages/crypto/src/index.ts` for CLI parity (M14).

---

## 7. Failure behavior

All expected failures return `VerificationResult` with `status = REJECTED` and a typed `VerificationFailureReason`. The verifier **does not throw** for:

- Missing / unreadable files
- Hash or signature mismatch
- Malformed metadata
- Path traversal attempts
- Wrong `bundle.file`

Reason codes align with ARCHITECTURE.md M5:

| Reason | Architecture code |
|--------|-------------------|
| `SIGNATURE_INVALID` | `E_SIG` |
| `HASH_MISMATCH` | `E_HASH` |
| `RUNTIME_MISMATCH` | `E_RUNTIME_MISMATCH` |
| `PATH_UNSAFE` / `BUNDLE_FILENAME_MISMATCH` | `E_ZIP_UNSAFE` (path analogue) |

---

## 8. Security model

- **Fail closed** — missing root, missing hash, missing signature (when keys configured), or any mismatch → rejected.
- **Never trust filenames** — only `bundle.hbc` plus signed manifest hashes.
- **Never trust server responses alone** — verification uses on-disk bytes only.
- **No secrets in logs** — results expose hex digests for diagnostics, not private keys.
- **Constant-time hash comparison** — expected vs actual SHA-256 compared without early exit.

---

## 9. Performance considerations

| Resource | Complexity |
|----------|------------|
| Time | **O(n)** in bundle bytes (single pass streaming hash) |
| Memory | **O(chunk size)** — default 64 KiB read buffer |

---

## 10. What the verifier does NOT do

- Activate, stage, or rollback bundles
- Mutate `state.json`, `boot.json`, or slot directories
- Download manifests or packages
- Extract zip archives (ZipGuard — Downloader / future)
- Replay / freshness checks (`validUntil`, monotonic release ids)
- Telemetry or update policy

---

## 11. Example verification flow

```kotlin
val result = BundleVerifier().verifySlot(
  slotDirectory = File(otaRoot, "slots/release-42"),
  trustedPublicKeys = listOf(rawEd25519PublicKey32Bytes),
  allowedRoot = otaRoot,
  runningRuntimeVersion = BuildConfig.RUNTIME_VERSION,
)
when (result.reason) {
  VerificationFailureReason.NONE -> { /* all checks passed */ }
  VerificationFailureReason.SIGNATURE_INVALID -> { /* E_SIG */ }
  VerificationFailureReason.BUNDLE_FILENAME_MISMATCH -> { /* manifest tried to redirect bundle path */ }
  else -> { /* typed rejection */ }
}
```

---

## 12. Deferred features

| Feature | Target |
|---------|--------|
| M14 CLI `sign` / `verify` implementation | MS8 |
| Safe zip extraction (ZipGuard) | Downloader |
| Binary-version range checks | Policy + manifest schema |
| Replay protection | Update Manager + Policy |
| Build-time public key embedding hooks | Host integration |
| Cross-platform fixture corpus in `packages/protocol/fixtures/` | MS4 close-out |

---

## Manifest v1 (verification subset)

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

Detached signature: `manifest.json.sig` — Base64-encoded 64-byte Ed25519 signature over **canonical JSON** bytes.

---

## Related documents

- [ARCHITECTURE.md](../../docs/ARCHITECTURE.md) — M5 module definition
- [BUNDLE_STORE.md](./BUNDLE_STORE.md) — slot layout (`bundle.hbc`, `manifest.json`)
- [ROLLBACK_ENGINE.md](./ROLLBACK_ENGINE.md) — separate concern; verifier never triggers rollback
