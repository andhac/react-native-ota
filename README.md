# react-native-ota

Production-grade Over-The-Air (OTA) update library for React Native — built from scratch.

> **Status:** MS0 foundation scaffold. No OTA runtime behavior is implemented yet.

## Documentation

| Document | Purpose |
|---|---|
| [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) | System design contract (modules M1–M14) |
| [docs/ROADMAP.md](docs/ROADMAP.md) | Implementation milestones MS0–MS10 |
| [docs/README.md](docs/README.md) | Docs index |

## Monorepo packages

| Package | npm name | Role |
|---|---|---|
| [`packages/client`](packages/client) | `react-native-ota` | Device SDK (JS + Android + iOS) |
| [`packages/protocol`](packages/protocol) | `@rnota/protocol` | Shared manifest & API types |
| [`packages/crypto`](packages/crypto) | `@rnota/crypto` | Signing / key tooling (CLI-side) |
| [`packages/cli`](packages/cli) | `@rnota/cli` | `ota` CLI |
| [`packages/server`](packages/server) | `@rnota/server` | Self-hostable reference server |

## Requirements

- Node.js ≥ 20
- npm ≥ 10 (workspaces)

## Scripts

```bash
npm install
npm run typecheck
npm run lint
npm run build
npm test
```

## What this is not (yet)

This repository does **not** yet download, verify, stage, or apply OTA updates. See the roadmap for the build order (MS1 onward).

## License

MIT
