/**
 * CLI-side signing & key management (M14).
 *
 * Placeholders only — no cryptographic implementation in MS0.
 * Device-side verification lives in native M5 (MS4).
 */

/** Placeholder for keypair generation. */
export function generateKeyPair(): never {
  throw new Error('@rnota/crypto: generateKeyPair is not implemented (MS4 / M14).');
}

/** Placeholder for Ed25519 detached signing over canonical JSON. */
export function sign(_payload: Uint8Array, _privateKey: Uint8Array): never {
  throw new Error('@rnota/crypto: sign is not implemented (MS4 / M14).');
}

/** Placeholder for signature verification (CLI `ota doctor` parity with M5). */
export function verify(
  _payload: Uint8Array,
  _signature: Uint8Array,
  _publicKey: Uint8Array,
): never {
  throw new Error('@rnota/crypto: verify is not implemented (MS4 / M14).');
}
