# React Native OTA — System Architecture

> **Status:** Design approved — pre-implementation
> **Last updated:** 2026-07-18
> **Scope:** RN 0.82+ (New Architecture, Hermes), Android + iOS
> **Grounding:** All boot-path claims verified against RN `0.82-stable` sources mirrored in `.rn-src/` (see `docs/internals/` companions).

This document is the contract for the implementation phases. Nothing in `packages/` should contradict it; if implementation reveals a flaw, update this document in the same PR.

---

## 0. The Five Load-Bearing Principles

Every decision downstream derives from five facts established by reading RN 0.82 source:

1. **The boot path is sacred.** The resolver (`getJSBundleFile()` / `bundleURL()`) runs synchronously on the launch critical path and may be called once per process (Android) or once per JS instance (iOS reload). → *Anything on the boot path is native, synchronous, pure, and crash-proof. Everything else runs after the app is alive.*
2. **OTA is JS + assets only.** Native code is compiled into the binary; Hermes bytecode is version-locked to the installed `libhermes`. → *Every update is gated by `runtimeVersion`; incompatible updates must be unrepresentable, not just discouraged.*
3. **The device is always mid-crash.** The process can die at any byte of any file write. → *All state transitions are atomic (write-temp + fsync + rename); the system must recover to a bootable state from any interruption point.*
4. **The network is hostile.** The bundle executes with full app privileges. → *End-to-end signing: the publisher signs offline, the device verifies against a public key baked into the binary. The server and CDN are untrusted couriers.*
5. **We are an open-source library, not a service.** → *Self-hostable server, pluggable storage, documented protocol so anyone can reimplement either side.*

### The master decision: the Resolver/Manager split

```
┌────────────────────────────────────────────────────────────────────┐
│  RESOLVER (native, sync, ~1ms, on boot path)                        │
│  "Which bundle path do I return, right now, without failing?"       │
│  Reads one small file. Never touches network. Never throws.         │
├────────────────────────────────────────────────────────────────────┤
│  MANAGER (JS + native helpers, async, after boot)                   │
│  Check → download → verify → stage → schedule apply → report.       │
│  Can fail freely; failure never affects the current boot.           │
└────────────────────────────────────────────────────────────────────┘
```

### Platform asymmetry the design must absorb (verified)

| Concern | Android | iOS |
|---|---|---|
| Resolver API | `getJSBundleFile(): String?` | `bundleURL() -> URL?` |
| Resolution timing | **once**, snapshotted by `DefaultReactNativeHost.toReactHost()` | closure, re-called at `RCTHost` start **and every reload** |
| Instant apply | custom `ReactHostDelegate` with computed `jsBundleLoader` + `reload()` | free via `bundleURL` + `RCTTriggerReloadCommandListeners` |
| Custom byte loading | `JSBundleLoader` subclass | `RCTHostDelegate.loadBundleAtURL` |

The resolver contract is therefore: **may be called once per process or once per JS instance — must be pure, instant, and idempotent.**

---

## 1. System Overview — Three Planes

```
┌──────────────────────────── DEVELOPER PLANE ────────────────────────────┐
│   CLI (M13)          Signing Keys (M14)         CI integration           │
│   bundle · sign · publish · promote · rollout · rollback                 │
└───────────────┬─────────────────────────────────────────────────────────┘
                │ Management REST API (authenticated)
┌───────────────▼───────────────── SERVER PLANE ──────────────────────────┐
│   Release Service (M10)      Storage/CDN Adapter (M11)                   │
│   Analytics Ingestion (M12)  Postgres (schema §7)                        │
└───────────────┬─────────────────────────────────────────────────────────┘
                │ Device REST API (anonymous) + CDN GET
┌───────────────▼───────────────── DEVICE PLANE ──────────────────────────┐
│  JS SDK:      Public API (M9) · Update Manager (M3) · Policy (M7)        │
│               Telemetry (M8)                                             │
│  Native SDK:  Downloader (M4) · Verifier (M5) · Bundle Store (M2)        │
│               Rollback Engine / Boot Watchdog (M6)                       │
│  Boot path:   Native Resolver (M1)  ← the only code RN startup touches   │
└──────────────────────────────────────────────────────────────────────────┘
```

---

## 2. Client Modules

### M1 — Native Bundle Resolver

- **Purpose:** answer RN's "where is the JS?" question, instantly and infallibly.
- **Responsibilities:** read the Bundle Store's pointer state; validate the pointed-at bundle *exists* (single `stat`, no hashing — too slow for boot); consult M6's crash-loop verdict; return a path or null (null → embedded bundle). On Android, also expose the optional custom `ReactHostDelegate` for instant-apply mode; on iOS, implement the `bundleURL` resolution called by `RCTHost` at start *and* reload.
- **Inputs:** `ota/state.json` (pointer file), M6's boot-status file, binary version + runtimeVersion baked at build time.
- **Outputs:** absolute bundle path or null; a "resolution record" (which slot, why) written for M8 to report later.
- **Dependencies:** M2 (file layout contract), M6 (verdict). **No JS, no network, no crypto.**
- **Failure cases:** pointer file missing/corrupt (→ embedded, mark for M6 cleanup); pointed file deleted (→ embedded); state written by a *newer* binary after app downgrade (→ schema-version check, embedded); any exception (→ catch-all, embedded). The failure mode of every branch is identical: fall back to embedded. The resolver cannot brick the app.
- **Testing:** unit tests per platform with fabricated state files (missing, corrupt, truncated mid-write, future schema); startup-time regression benchmark (< 2ms budget); monkey test that randomly corrupts state between launches and asserts the app always boots.

### M2 — Bundle Store

- **Purpose:** own the on-disk lifecycle of update packages with atomicity guarantees.
- **Responsibilities:** maintain the store layout; atomic pointer flips; garbage collection; quota enforcement.

```
<app-data>/ota/
├── state.json            ← THE pointer (schemaVersion, activeSlot, pendingSlot,
│                            previousSlot, installedBinaryVersion)
├── state.json.tmp        ← write-then-rename staging
├── slots/
│   ├── a1b2c3.../        ← slot named by release id
│   │   ├── manifest.json ← signed metadata
│   │   ├── bundle.hbc
│   │   └── assets/...    ← Metro-resolved assets, mirrored layout
│   └── d4e5f6.../
└── boot.json             ← M6's watchdog state (attempts, lastVerdict)
```

  Retention: **active + previous + at most one pending** (embedded bundle is the implicit third fallback — the CodePush-proven minimum that bounds disk use while always leaving a rollback target).
- **Inputs:** verified packages from M5; flip/promote/abandon commands from M3/M6.
- **Outputs:** `state.json` transitions; slot paths; GC events.
- **Dependencies:** platform FS APIs only. *Deliberately zero knowledge of network or crypto.*
- **Failure cases:** crash mid-download (partial slot never referenced by pointer → GC'd); crash mid-flip (rename is atomic on APFS and ext4/f2fs — pointer is either old or new, never torn); disk full (abort stage, keep serving active; report `E_DISK_FULL`); user "Clear Data" wipes everything (embedded bundle still boots — by design the store is always disposable).
- **Testing:** crash-injection harness that kills the process at every fsync/rename boundary and asserts invariants (pointer always references a complete slot); concurrent-access tests (two flips racing); disk-full simulation; property test: *any* sequence of operations + crashes leaves a bootable state.

### M3 — Update Manager (JS orchestrator)

- **Purpose:** the brain; drives the update state machine after boot.
- **Responsibilities:** own the canonical state machine `IDLE → CHECKING → AVAILABLE → DOWNLOADING → VERIFYING → STAGED → (apply per policy) → CONFIRMED | ROLLED_BACK`; call `notifyAppReady()` handshake to M6; sequence M4→M5→M2; expose progress to M9; ensure single-flight (no concurrent checks).
- **Inputs:** triggers (app start, foreground, timer, manual `checkForUpdate()`); server responses; policy verdicts from M7.
- **Outputs:** state transitions + events to M9/M8; commands to M4/M5/M2.
- **Dependencies:** M2, M4, M5, M6 (via TurboModule), M7, M8; server Device API.
- **Failure cases:** app killed at any state (→ state machine is re-entrant from persisted store state; a `STAGED` slot found at next boot resumes at the apply step); server unreachable (→ silent no-op, offline-first); update for wrong runtimeVersion reaching the client despite server filtering (→ rejected client-side too, defense in depth); JS itself is from an OTA bundle that's about to be replaced (→ manager never mutates the *running* slot).
- **Testing:** exhaustive state-machine unit tests (every transition, every illegal transition rejected); integration tests against a mock server; soak test: 1000 randomized check/download/kill cycles must never corrupt state.

### M4 — Downloader

- **Purpose:** move bytes from CDN to disk reliably, including when the app is backgrounded.
- **Responsibilities:** resumable downloads (HTTP Range); background transfer via `WorkManager` (Android) / `URLSession` background configuration (iOS) — this is *why the module is native*, JS timers die with the app; write into a quarantine dir inside the target slot; enforce `Content-Length` vs manifest `packageSize` (reject oversize early — zip-bomb guard); Wi-Fi-only option; exponential backoff.
- **Inputs:** download descriptor from M3 (URL, expected size, expected hash, slot id); connectivity events.
- **Outputs:** completed file + download metrics; progress events.
- **Dependencies:** M2 (destination), platform networking. *Does not verify — that's M5's job; downloader treats bytes as opaque.*
- **Failure cases:** network flap mid-transfer (→ resume from offset); server sends wrong bytes (→ caught by M5, slot abandoned); background task killed by OS (→ WorkManager/URLSession resume semantics); captive portal returning HTML 200 (→ size/hash mismatch caught, plus content-type sanity check); battery saver deferring work (→ acceptable, updates are not time-critical).
- **Testing:** integration tests against a fault-injecting HTTP server (drops, stalls, wrong bytes, wrong length); airplane-mode toggling on device farm; background-kill scenarios; throughput/battery measurement on low-end Android.

### M5 — Verifier

- **Purpose:** the security gate — nothing reaches a bootable slot unverified.
- **Responsibilities:** verify the **Ed25519 signature** of `manifest.json` against the public key embedded in the binary at build time; verify SHA-256 of `bundle.hbc` and every asset against the signed manifest's hash list (making the manifest a signed Merkle-style root over the whole package); verify `runtimeVersion` and binary-version range against the running binary; safe zip extraction (path-traversal rejection, decompressed-size cap); mark slot `verified` only after *all* checks.
- **Inputs:** downloaded package + manifest; baked-in public key(s) (support key list for rotation).
- **Outputs:** verified slot or a typed rejection (`E_SIG`, `E_HASH`, `E_RUNTIME_MISMATCH`, `E_ZIP_UNSAFE`).
- **Dependencies:** M2 (quarantine → verified transition); platform crypto (`java.security` / `CryptoKit`) — Ed25519 chosen because it's small-key, fast, misuse-resistant, and natively available on both platforms.
- **Failure cases:** signature invalid (→ slot destroyed, high-severity telemetry — this is either an attack or a broken pipeline, alert loudly); hash mismatch on one asset (→ whole package rejected, no partial installs); clock-skewed device with expired manifest `validUntil` (→ configurable grace, expiry is optional metadata not a hard gate — offline support requirement); key rotation (→ try each key in the baked-in list).
- **Testing:** known-answer crypto tests (signed fixtures, tampered fixtures — every byte-flip position class); malicious-zip corpus (traversal, bombs, symlinks); fuzz the manifest parser; cross-platform determinism test (same package verifies identically on both OSes).

### M6 — Rollback Engine & Boot Watchdog

- **Purpose:** guarantee a bad update can never permanently break the app — the module that makes OTA *safe to ship on a Friday*.
- **Responsibilities:** two-phase commit: an applied update boots as `PENDING`; native watchdog records `bootAttempt++` in `boot.json` *before* the resolver returns the pending path; JS calls `notifyAppReady()` (via M9→M3→TurboModule) once the app renders → `CONFIRMED`. If attempts exceed threshold (default 2) without confirmation → verdict `ROLLBACK`: pointer flips to `previousSlot` (or embedded), failed slot quarantined, failure event queued for M8. Also: explicit `rollbackToEmbedded()` API, and server-commanded rollback (a check response can order retreat).
- **Inputs:** boot attempts from M1's path; `notifyAppReady()`; uncaught-crash signals (native crash handler hook, optional); server rollback orders.
- **Outputs:** verdicts consumed by M1; pointer flips via M2; rollback events.
- **Dependencies:** M1 (in-line on boot), M2, M8. Native — must work when JS never comes up *at all*.
- **Failure cases:** the defining one — **update crashes before JS runs** (Hermes rejects bytecode, top-level throw): watchdog counter is the only survivor, catches it at attempt N+1; app killed by user before `notifyAppReady` fires (→ *not* counted as failure — distinguish clean process exit from crash via exit-reason APIs: `ApplicationExitInfo` / `MXAppExitMetric`, else conservative attempt threshold); crash caused by the OS not the update (→ threshold + confirm-on-any-later-success limits false rollbacks); rollback target itself broken (→ fallback chain ends at embedded, which is always present).
- **Testing:** the hardest suite — instrumented apps with deliberately crashing bundles (crash-at-load, crash-after-3s, ANR); assert auto-recovery within N launches on device farm; unit tests for verdict logic across exit-reason matrices; "chaos" test where every component lies.

### M7 — Update Policy Engine

- **Purpose:** decide *whether* and *when* an available update applies — separating mechanism (M3) from policy.
- **Responsibilities:** interpret manifest policy fields: `mandatory` (block-and-install: M9 surfaces a blocking UX contract), `installMode` (`IMMEDIATE` = apply + reload now; `ON_NEXT_RESTART` = stage only; `ON_NEXT_RESUME` = apply when backgrounded > X min); respect developer overrides (frequency caps, metered-network rules, "no reload during checkout flow" API guard); on Android, decide between next-launch mode (stable hook) and instant mode (custom delegate + `reload()`), on iOS instant mode is native.
- **Inputs:** verified manifest policy; app-state signals (foreground/background, developer-declared critical sections); SDK config.
- **Outputs:** apply decisions + timing to M3.
- **Dependencies:** M3, M9. Pure logic otherwise — deliberately side-effect-free.
- **Failure cases:** mandatory update that fails verification (→ policy cannot force what M5 rejected; app continues on current, retries per backoff — availability beats freshness); user backgrounds mid-`IMMEDIATE`-reload (→ reload is scheduled on main thread post-commit, idempotent); conflicting config (mandatory + ON_NEXT_RESTART → mandatory wins, documented precedence).
- **Testing:** pure-function table tests over the full policy matrix; UX-timing integration tests (no reload while a developer-marked critical section is active).

### M8 — Telemetry & Analytics Client

- **Purpose:** give release managers the deployment funnel (offered → downloaded → installed → confirmed → rolled back) without compromising privacy.
- **Responsibilities:** queue events durably (survive offline for days — offline requirement); batch + retry to the ingestion API; attach anonymous stable `clientId` (random UUID at first run — never a hardware ID), release id, binary/runtime versions; expose opt-out and a "no telemetry" build flag (open-source requirement: the library must be fully functional with telemetry disabled).
- **Inputs:** events from M3/M5/M6; resolution records from M1.
- **Outputs:** batched POSTs to M12; local ring buffer (bounded, self-pruning).
- **Dependencies:** M12's API contract; storage for the queue.
- **Failure cases:** ingestion down for a week (→ ring buffer caps, oldest dropped, counters preserved); event flood from crash loop (→ dedup + rate limit client-side); clock skew (→ server assigns authoritative time, client sends monotonic deltas).
- **Testing:** queue durability across restarts; batch/retry against flaky mock server; privacy audit test asserting the wire format contains no PII fields.

### M9 — Public JS API

- **Purpose:** the developer-facing surface — small, typed, hard to misuse.
- **Responsibilities:** expose the contract:

```
OtaClient.configure(options)        checkForUpdate(): RemoteRelease | null
sync(policyOverrides?)              notifyAppReady()   ← two-phase commit trigger
getCurrentRelease(): ReleaseInfo    rollbackToEmbedded()
addEventListener(ev, cb)            useOtaUpdate()     ← React hook (state + progress)
markCriticalSection(begin|end)      restartApp()
```

  Plus the TurboModule spec (codegen'd, New-Arch native module) that bridges to M2/M4/M5/M6.
- **Inputs:** developer calls; events from M3.
- **Outputs:** promises/events; TurboModule invocations.
- **Dependencies:** M3 (thin facade over it — no logic lives in M9).
- **Failure cases:** developer never calls `notifyAppReady()` (→ every update would roll back; mitigations: loud dev-mode warning, `sync()` auto-calls it by default); API called before native init (→ queued until ready); called from an OTA bundle older than the SDK's native side (→ versioned TurboModule contract, additive-only changes — the JS half of the SDK ships *inside* OTA bundles, so JS↔native SDK version skew is a permanent reality our API contract must absorb).
- **Testing:** TypeScript type tests; API-contract tests against mocked TurboModule; backward-compat suite running SDK JS vN-1 against native vN.

---

## 3. Server Modules

### M10 — Release Service

- **Purpose:** the source of truth for "which release should this device run?"
- **Responsibilities:** serve the Device API (§9): match `(app, channel, runtimeVersion, binaryVersion, currentRelease, clientId)` → correct release or `upToDate`; deterministic rollout bucketing (`bucket = hash(clientId + releaseId) % 100 < rolloutPercent` — stable per device per release, so a device never flaps in/out of a rollout); serve the Management API (publish, promote across channels, pause, rollback); enforce release invariants (immutable once published — promotion copies a pointer, never mutates).
- **Inputs:** device check requests; CLI management calls; Postgres.
- **Outputs:** update decisions with CDN URLs; audit log.
- **Dependencies:** M11, DB. *Never touches signing keys* — it stores signatures made offline by M14; a fully compromised server still can't forge an installable update.
- **Failure cases:** DB down (→ device API degrades to `upToDate`, devices keep working — fail-safe direction); thundering herd on release publish (→ responses are CDN-cacheable by design: decision inputs are in the query string, output carries `Cache-Control` + ETag); duplicate publish (→ idempotency keys); rollback of a release devices already confirmed (→ server orders retreat via `action: "rollback"` in check response).
- **Testing:** decision-matrix table tests (the targeting logic is the product — test it exhaustively); load tests on the check endpoint (it takes ~all traffic); contract tests shared with the client SDK (same fixtures both sides).

### M11 — Storage / CDN Adapter

- **Purpose:** package blobs at scale, decoupled from any vendor.
- **Responsibilities:** `StorageAdapter` interface (`put`, `getSignedUrl`, `delete`, `exists`) with reference implementations: local-disk (dev/self-host) and S3-compatible (covers AWS/GCS/R2/MinIO — the pragmatic OSS choice); content-addressed keys (`packages/<sha256>.zip` — immutable, infinitely cacheable, dedup for free).
- **Inputs:** package uploads from M10; URL requests.
- **Outputs:** durable blobs; time-limited or public URLs.
- **Dependencies:** backing store credentials.
- **Failure cases:** eventual consistency (upload → verify readback before the release goes live); orphaned blobs from failed publishes (→ GC job cross-checking DB references); signed-URL expiry mid-download (→ M4 re-requests via a fresh check — URLs in check responses are short-lived by design).
- **Testing:** adapter conformance suite every implementation must pass (this is the OSS extension point — the test suite *is* the spec); integrity round-trip tests.

### M12 — Analytics Ingestion

- **Purpose:** turn client events into the release-health dashboard and the auto-rollback signal.
- **Responsibilities:** high-write event endpoint; aggregate into per-release funnel metrics; expose query API for CLI/dashboard; **health rule engine**: `rollbackRate(release) > threshold within window → auto-pause rollout + alert` — closing the loop from M6's device-level rollbacks to fleet-level protection.
- **Inputs:** M8 batches.
- **Outputs:** aggregates; pause commands to M10; webhooks.
- **Dependencies:** DB (raw events partitioned + aggregate tables).
- **Failure cases:** ingestion overwhelmed (→ it's decoupled from M10, update serving unaffected; shed load by sampling); duplicate batches from client retries (→ batch UUIDs, idempotent insert); poisoned events (→ schema validation, per-app rate limits).
- **Testing:** ingest load tests; aggregation correctness against synthetic fleets; end-to-end auto-pause drill (synthetic crash-loop fleet must pause the rollout).

---

## 4. Tooling Modules

### M13 — CLI (`ota` command)

- **Purpose:** the entire release workflow in CI-friendly commands.
- **Responsibilities:** `ota bundle` — run Metro (`--dev false`) + the **exact matching `hermesc`** resolved from the app's installed RN version (the runtimeVersion lock makes reproducible-toolchain-resolution a correctness feature, not a convenience); collect assets; build `manifest.json`; `ota sign` (delegates to M14); `ota publish --app --channel --runtime-version --mandatory --rollout 5`; `ota promote staging production`; `ota rollout --set 50`; `ota rollback`; `ota release:list/info`; `ota keys:generate/rotate`; `ota doctor` (verify a package locally exactly as a device would — same verifier spec as M5).
- **Inputs:** app project (Metro config, RN version), server credentials, private key (file/env/KMS reference — never written to config).
- **Outputs:** signed `.zip` package; server API calls; human + `--json` output.
- **Dependencies:** Metro/hermesc from the *app's* `node_modules` (never vendored — must match the binary), M14, Management API.
- **Failure cases:** hermesc/RN version mismatch (→ hard error with explanation — the #1 foot-gun, worth an opinionated failure); publishing runtime-incompatible bundle (→ server double-checks manifest against channel's known binaries, warns); accidental prod publish (→ confirmation gate + `--yes` for CI); missing key (→ actionable error, docs link).
- **Testing:** e2e against a local server + example app (publish → device-sim check → verify); golden-file tests for manifest/package format; cross-RN-version matrix in CI (0.82, 0.83…).

### M14 — Signing & Key Management

- **Purpose:** the trust root; the spec + tooling for keys, deliberately tiny.
- **Responsibilities:** define the canonical signing payload (canonical-JSON manifest → Ed25519 detached signature); key generation; public-key embedding into the app at build time (Gradle resource + Xcode plist, injected by our config plugin); rotation protocol (binaries carry a key *list*; new releases signed with new key still verify on old binaries during overlap → rotation without a store release only if old key still in list — document the tradeoff honestly).
- **Inputs:** entropy; rotation commands.
- **Outputs:** keypairs; signatures; embedded-key build artifacts.
- **Dependencies:** none at runtime (verification lives in M5); CLI at build time.
- **Failure cases:** **private key leak** — the catastrophic one (→ docs mandate KMS/env storage, never repo; rotation runbook; server-side release freeze as a stopgap while a binary release rotates keys); key lost (→ cannot sign updates until next store release ships a new public key — state this loudly in docs); wrong key for app (→ M5 rejects everything; `ota doctor` diagnoses).
- **Testing:** cross-platform sign/verify vectors (CLI-signed → Android verifier, iOS verifier, `doctor` — byte-identical verdicts); rotation scenario tests; canonical-JSON determinism fuzzing (two serializers, one byte difference = broken signatures — pin the canonicalization spec with test vectors).

---

## 5. Component Diagram (device plane)

```
      ┌────────────────────────── JS (ships inside OTA bundles) ─────────────────────────┐
      │  App code ──► M9 Public API ──► M3 Update Manager ──► M7 Policy                   │
      │                    │                   │        └──► M8 Telemetry ──► (M12)       │
      └────────────────────┼───────────────────┼──────────────────────────────────────────┘
                    TurboModule spec (codegen, versioned, additive-only)
      ┌────────────────────▼───────────────────▼─────────── native (ships in binary) ────┐
      │   M4 Downloader ──► M5 Verifier ──► M2 Bundle Store ◄── M6 Rollback/Watchdog      │
      │   (WorkManager /        (Ed25519 +       (slots +           (boot.json,           │
      │    URLSession bg)        SHA-256)         state.json)        notifyAppReady)      │
      │                                              ▲                    ▲               │
      ├──────────────────────────────────────────────┼────────────────────┼───────────────┤
      │  BOOT PATH ONLY:            M1 Resolver ─────┘────────────────────┘               │
      │  Android: OtaReactNativeHost.getJSBundleFile()          [next-launch mode]        │
      │           OtaReactHostDelegate.jsBundleLoader (computed) [instant mode] + reload()│
      │  iOS:     AppDelegate.bundleURL() closure — re-called on reload  + RCTTrigger…    │
      │  Byte-load hooks (verify-on-load, future encryption):                             │
      │           Android: JSBundleLoader subclass · iOS: RCTHostDelegate.loadBundleAtURL │
      └──────────────────────────────────────────────────────────────────────────────────┘
```

## 6. Sequence Diagrams

### A. Happy path — check → stage → apply (ON_NEXT_RESTART)

```
App(JS)      M9        M3         Server(M10)   M4        M5        M2         M6
 │ boot done  │         │              │          │         │         │          │
 ├─ sync() ──►│─ ready ─►│                                                       │
 │            │         ├─ notifyAppReady ────────────────────────────────────► confirm
 │            │         │              │          │         │         │      PENDING→CONFIRMED
 │            │         ├─ GET /update?channel&runtimeVersion&current&clientId   │
 │            │         │◄─ 200 {updateAvailable, manifest, url} ─┤              │
 │            │         ├─ M7.decide → proceed    │         │         │          │
 │            │         ├─ download(url, size, hash) ──────► │ (bg, resumable)   │
 │            │         │              │          ├─ bytes → quarantine in slot  │
 │            │         │◄─ complete ──┤          │         │         │          │
 │            │         ├─ verify(slot) ────────────────────►│ sig? hashes?      │
 │            │         │              │          │          │ runtime? zip-safe?│
 │            │         │◄─ verified ──────────────────────┤ │         │         │
 │            │         ├─ stage(slot) ──────────────────────────────► │ state.json:
 │            │         │              │          │         │          │ pending=slot (atomic)
 │            │◄─ event: UPDATE_STAGED ┤          │         │          │         │
 │  ...user quits; next launch: M1 sees pending → returns new path; M6 boot.json attempt=1
 │  app renders → notifyAppReady → CONFIRMED; M8 reports installed ✓
```

### B. Bad update auto-rollback (the failure path that justifies the design)

```
Launch 1: M1 reads state.json → pending slot → M6 writes boot.json{attempts:1} → returns new path
          Hermes executes bundle → CRASH before notifyAppReady
Launch 2: M1 → M6: attempts:1, unconfirmed → still under threshold → try again, attempts:2
          CRASH again
Launch 3: M1 → M6: attempts ≥ 2 → VERDICT: ROLLBACK
          M6→M2: pointer := previousSlot (atomic), quarantine bad slot
          M1 returns previous path → app boots healthy
          M3 (now alive) → M8: report ROLLBACK{release, reason} → M12 health rules
          Fleet: rollbackRate crosses threshold → M10 auto-pauses rollout → alert
```

---

## 7. Folder Structure (monorepo)

```
react-native-ota/
├── packages/
│   ├── client/                        # the npm library (react-native-ota)
│   │   ├── src/                       # M3, M7, M8, M9 (TS)
│   │   │   ├── api/  manager/  policy/  telemetry/  native/ (TurboModule spec)
│   │   ├── android/src/main/java/com/rnota/
│   │   │   ├── resolver/              # M1: OtaReactNativeHost, OtaReactHostDelegate
│   │   │   ├── store/  downloader/  verifier/  rollback/   # M2, M4, M5, M6
│   │   │   └── OtaTurboModule.kt
│   │   ├── ios/RNOta/
│   │   │   ├── Resolver/              # M1: OtaBundleResolver (+ AppDelegate glue)
│   │   │   ├── Store/  Downloader/  Verifier/  Rollback/
│   │   │   └── OtaTurboModule.mm
│   │   └── react-native-ota.podspec / build.gradle
│   ├── protocol/                      # shared truth: manifest schema, API types,
│   │   └── src/ + fixtures/           #   canonical-JSON spec, signed test vectors
│   ├── crypto/                        # M14 spec + JS impl (CLI-side signing)
│   ├── cli/                           # M13 (ota command)
│   │   └── src/commands/{bundle,sign,publish,promote,rollout,rollback,doctor,keys}.ts
│   └── server/                        # M10-M12 reference server (self-hostable)
│       ├── src/{device-api,mgmt-api,ingestion,health,storage/{s3,local}}/
│       └── migrations/
├── example/                           # RN 0.82 app wired to a local server
├── e2e/                               # device-farm suites incl. crash-rollback drills
└── docs/                              # architecture, threat model, self-hosting, runbooks
```

## 8. Database Schema (Postgres)

```sql
CREATE TABLE apps (
  id            UUID PRIMARY KEY,
  name          TEXT NOT NULL,
  platform      TEXT NOT NULL CHECK (platform IN ('android','ios')),
  public_keys   JSONB NOT NULL,              -- active key list (rotation)
  created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE channels (                       -- Development / Staging / Production (+custom)
  id UUID PRIMARY KEY, app_id UUID NOT NULL REFERENCES apps(id),
  name TEXT NOT NULL, UNIQUE (app_id, name)
);

CREATE TABLE releases (                       -- immutable once published
  id              UUID PRIMARY KEY,
  app_id          UUID NOT NULL REFERENCES apps(id),
  runtime_version TEXT NOT NULL,             -- Hermes/native compat gate
  binary_range    TEXT,                      -- semver range of app binaries, optional
  package_hash    CHAR(64) NOT NULL,         -- sha256; also the CDN key
  package_size    BIGINT NOT NULL,
  manifest        JSONB NOT NULL,            -- full signed manifest (incl. asset hash list)
  signature       TEXT NOT NULL,             -- Ed25519 over canonical manifest
  notes           TEXT,
  created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE channel_releases (               -- publication = pointer, promotion = new row
  id UUID PRIMARY KEY,
  channel_id      UUID NOT NULL REFERENCES channels(id),
  release_id      UUID NOT NULL REFERENCES releases(id),
  mandatory       BOOLEAN NOT NULL DEFAULT false,
  install_mode    TEXT NOT NULL DEFAULT 'ON_NEXT_RESTART',
  rollout_percent SMALLINT NOT NULL DEFAULT 100 CHECK (rollout_percent BETWEEN 0 AND 100),
  status          TEXT NOT NULL DEFAULT 'active'
                  CHECK (status IN ('active','paused','rolled_back','superseded')),
  published_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_cr_lookup ON channel_releases (channel_id, status, published_at DESC);

CREATE TABLE events (                         -- raw telemetry, time-partitioned
  id BIGSERIAL, app_id UUID NOT NULL, release_id UUID,
  client_id UUID NOT NULL,                    -- anonymous
  type TEXT NOT NULL CHECK (type IN ('offered','download_started','downloaded',
       'verify_failed','staged','installed','confirmed','rolled_back','resolver_fallback')),
  binary_version TEXT, runtime_version TEXT, detail JSONB,
  batch_id UUID NOT NULL,                     -- client batch idempotency
  received_at TIMESTAMPTZ NOT NULL DEFAULT now()
) PARTITION BY RANGE (received_at);

CREATE TABLE release_health (                 -- rolled-up funnel per channel_release
  channel_release_id UUID PRIMARY KEY REFERENCES channel_releases(id),
  offered BIGINT DEFAULT 0, downloaded BIGINT DEFAULT 0, installed BIGINT DEFAULT 0,
  confirmed BIGINT DEFAULT 0, rolled_back BIGINT DEFAULT 0,
  updated_at TIMESTAMPTZ
);

CREATE TABLE api_keys (                       -- management auth (CLI/CI)
  id UUID PRIMARY KEY, app_id UUID REFERENCES apps(id),
  token_hash CHAR(64) NOT NULL, role TEXT NOT NULL CHECK (role IN ('admin','publisher','readonly')),
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(), revoked_at TIMESTAMPTZ
);
```

## 9. REST API Specification

### Device API (anonymous)

All responses CDN-cacheable where noted; trust comes from M5's signature verification, not from transport.

```
GET /v1/device/update
  ?app_id=…&channel=Production&platform=android
  &runtime_version=0.82.1-hermes&binary_version=2.4.0
  &current_release=<uuid|embedded>&client_id=<uuid>

200 — update available                     200 — up to date          200 — retreat order
{                                          { "action": "none" }      { "action": "rollback",
  "action": "update",                                                  "to_release": "<uuid|embedded>" }
  "release": {
    "id": "…", "runtime_version": "0.82.1-hermes",
    "mandatory": false, "install_mode": "ON_NEXT_RESTART",
    "package": { "url": "https://cdn…/packages/<sha256>.zip",
                 "size": 4194304, "hash": "<sha256>" },
    "manifest": { …full signed manifest… },
    "signature": "<base64 ed25519>"
  }
}
Headers: Cache-Control: public, max-age=60 · ETag on (channel_release, bucket-decision inputs)
Errors: 400 malformed · 404 unknown app/channel · 429 backoff hint

POST /v1/device/events                      # M8 batches
{ "batch_id": "<uuid>", "client_id": "<uuid>", "events": [ {type, release_id, at_offset_ms, detail}… ] }
202 always (idempotent by batch_id; never block a device on telemetry)
```

### Management API (Bearer token; audit-logged)

```
POST  /v1/apps                              create app (returns id; register public_keys)
POST  /v1/apps/{id}/channels                create channel
POST  /v1/apps/{id}/releases                register release: {manifest, signature, package_hash, size}
                                            → 201 + presigned upload URL (client uploads zip to M11,
                                              then POST /releases/{id}/finalize → server verifies
                                              readback hash before the release becomes publishable)
POST  /v1/channels/{cid}/publish            {release_id, mandatory, install_mode, rollout_percent}
PATCH /v1/channel-releases/{crid}           {rollout_percent | status: paused}
POST  /v1/channels/{cid}/rollback           re-point channel at previous release + emit retreat orders
GET   /v1/channel-releases/{crid}/health    funnel metrics (drives CLI + dashboard)
POST  /v1/apps/{id}/keys                    add public key (rotation); DELETE to retire
```

Design notes: the device API is **pull-only** (no push infrastructure — simpler, self-host-friendly; background checks approximate push), **all mutation is management-side**, and the check response embeds the full signed manifest so a device can make its *entire* accept/reject decision offline from one round trip.

---

## 10. Deliberately Deferred (decisions, not omissions)

- **Differential updates** — the content-addressed store and per-file manifest hashes are the prerequisites; diffing (bsdiff on HBC, per-asset delta) layers on later without schema changes. Building it now would double M4/M5 complexity before the base loop is proven.
- **Bundle encryption at rest** — the byte-load hooks (Android `JSBundleLoader` subclass / iOS `loadBundleAtURL`) are reserved for it; signing already covers integrity, and confidentiality of JS is weak protection anyway (the binary ships the same code).
- **Multi-bundle / split bundles** — `registerSegment` exists on both platforms; out of scope until core is stable.

## 11. Build Order

Falls out of the dependency graph:

1. **M2 → M1 → M6** — a bootable, rollback-safe store with no networking; testable entirely offline.
2. **M5 + M14** — the trust chain.
3. **M4 + M3 + M9** — the update loop.
4. **M13 → M10–M12** — ship it.
5. Policy/telemetry polish (M7, M8, health rules).
