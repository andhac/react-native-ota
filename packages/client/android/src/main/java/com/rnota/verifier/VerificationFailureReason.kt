package com.rnota.verifier

/**
 * Typed rejection reasons aligned with ARCHITECTURE.md M5 (`E_*` codes).
 */
enum class VerificationFailureReason {
  NONE,
  BUNDLE_MISSING,
  BUNDLE_UNREADABLE,
  BUNDLE_EMPTY,
  SIZE_MISMATCH,
  HASH_MISMATCH,
  MALFORMED_HASH,
  /** E_SIG */
  SIGNATURE_INVALID,
  SIGNATURE_MISSING,
  MALFORMED_SIGNATURE,
  MANIFEST_MISSING,
  MANIFEST_INVALID,
  /** E_RUNTIME_MISMATCH */
  RUNTIME_MISMATCH,
  /** Path outside allowed root / traversal (E_ZIP_UNSAFE analogue for paths). */
  PATH_UNSAFE,
  INTERNAL_ERROR,
}
