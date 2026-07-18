# @rnota/crypto

Signing and key-management toolkit used by the CLI (module **M14**).

## Why this package exists

Publishers sign packages **offline**. The server never holds private keys. This package owns the JS implementation of canonical-JSON + Ed25519 so `ota sign` / `ota doctor` match what devices verify in native code (M5).

## Future responsibilities

- Canonical JSON serialization (deterministic; test-vector pinned)
- Ed25519 key generation, sign, verify
- Key rotation helpers (public key lists)
- Shared vectors consumed by Android/iOS verifiers

## Status

MS0: **placeholders only** — functions throw `not implemented`.
