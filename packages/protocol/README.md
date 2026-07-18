# @rnota/protocol

Shared type contracts for the OTA system: manifests, device/management API shapes, and release metadata.

## Why this package exists

The client SDK, CLI, and server must agree on wire formats. Putting types in one package prevents drift and enables shared contract tests (ARCHITECTURE.md principle: documented protocol so either side can be reimplemented).

## Future responsibilities

- `ManifestV1` and canonical-JSON signing payload shape (M14 / M5)
- Device API request/response types (§9)
- Management API types
- Event batch types for telemetry (M8 / M12)
- Golden fixtures under `fixtures/` for cross-package tests

## Status

MS0: type **placeholders only** — no runtime validation.
