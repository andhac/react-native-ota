# @rnota/server

Self-hostable reference server for release targeting, package storage, and analytics (modules **M10–M12**).

## Why this package exists

The library is open-source, not a hosted SaaS. Anyone must be able to run their own update backend. The server stores **signatures made offline** — it never holds private signing keys.

## Future responsibilities

- Device API: `GET /v1/device/update`, `POST /v1/device/events`
- Management API: apps, channels, publish, rollout, rollback, keys
- Storage adapters: local + S3-compatible (M11)
- Analytics ingestion + rollout auto-pause (M12)
- Postgres migrations (§8)

## Status

MS0: Express skeleton with `/healthz` only — **no OTA routes**.
