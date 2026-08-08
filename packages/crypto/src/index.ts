/**
 * Shared cryptographic constants and utilities for react-native-ota.
 *
 * Device-side verification is implemented in native M5 (Android/iOS).
 * Ed25519 signatures are over canonical JSON manifest bytes (ROADMAP MS4).
 * M14 CLI sign/verify will use the same canonicalization rules.
 */

/** SHA-256 digest length in bytes. */
export const SHA256_DIGEST_BYTES = 32;

/** SHA-256 digest length in lowercase hex characters. */
export const SHA256_HEX_LENGTH = 64;

/** Ed25519 public key length in bytes. */
export const ED25519_PUBLIC_KEY_BYTES = 32;

/** Ed25519 detached signature length in bytes. */
export const ED25519_SIGNATURE_BYTES = 64;

const SHA256_HEX_REGEX = /^[0-9a-fA-F]{64}$/;

/** Normalize a SHA-256 hex string to lowercase; returns null if malformed. */
export function normalizeSha256Hex(raw: string): string | null {
  const trimmed = raw.trim();
  if (!SHA256_HEX_REGEX.test(trimmed)) {
    return null;
  }
  return trimmed.toLowerCase();
}

/** Placeholder for keypair generation (M14). */
export function generateKeyPair(): never {
  throw new Error('@rnota/crypto: generateKeyPair is not implemented (M14).');
}

/** Placeholder for Ed25519 detached signing over canonical JSON (M14). */
export function sign(_payload: Uint8Array, _privateKey: Uint8Array): never {
  throw new Error('@rnota/crypto: sign is not implemented (M14).');
}

/** Placeholder for signature verification (CLI `ota doctor` parity with M5). */
export function verify(
  _payload: Uint8Array,
  _signature: Uint8Array,
  _publicKey: Uint8Array,
): never {
  throw new Error('@rnota/crypto: verify is not implemented (M14).');
}
