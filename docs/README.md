# react-native-ota — Documentation

Design contracts for implementation. MS0 (monorepo scaffold) is in progress; OTA runtime behavior starts at MS1.

| Document | What it contains |
|---|---|
| [ARCHITECTURE.md](ARCHITECTURE.md) | The approved system design: 14 modules (M1–M14) with purpose/responsibilities/failure cases/testing, resolver–manager split, diagrams, monorepo layout, Postgres schema, REST API spec, build order |
| [ROADMAP.md](ROADMAP.md) | Implementation plan: milestones MS0–MS10, Epic → Feature → Task → Subtask WBS, acceptance criteria, path to npm 1.0 |
| [specs/state-json.md](specs/state-json.md) | Normative `state.json` schema v1 (pointer file for M1/M2) |
| [internals/android-bundle-loading.md](internals/android-bundle-loading.md) | Verified RN 0.82 Android boot/bundle-loading chain, interception points P1–P7, hard rules |
| [internals/ios-bundle-loading.md](internals/ios-bundle-loading.md) | Verified RN 0.82 iOS boot/bundle-loading chain, interception points I1–I8, cross-platform resolver contract |

Reference sources: RN `0.82-stable` files mirrored under `.rn-src/` (android + ios) — all `file:line` citations in the internals docs point there.

Next phase per [ROADMAP.md](ROADMAP.md): **MS0 (scaffold)** then **MS1 — Android M2 Bundle Store + M1 Resolver only**.
