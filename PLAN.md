# Pitch Pact — PLAN

Origin: USER-DIRECTED idea (explicit user brief 2026-09-30; full brief embedded in README.md §PRODUCT CONTRACT). This plan realizes that brief. Related prior art in this fleet: `rwrife/wicket-tally` issues #22 (hosted game-score server with short broadcast code) and #23 (KMP migration + Android) — Pitch Pact adopts their intended patterns from day one instead of retrofitting them.

## Platform exception (explicit user authorization, 2026-09-30)

The fleet-wide mobile default is native Swift iPhone-only with shared frameworks prohibited. **This project is an explicit, user-authorized exception**: shared business logic in Kotlin Multiplatform, native UX per platform (SwiftUI on iOS, Jetpack Compose on Android), plus a hosted server component. The user brief's own words: "we will need this to be multi platform and support ios and android, so it should probably use kotlin multiplayform for shared logic and native ios/anroid code for the UX."

What remains in force from fleet policy (the exception is narrow):

- iOS app remains **iPhone-only** (`TARGETED_DEVICE_FAMILY = 1`, `native_ipad_support = false`); iPad support still requires explicit opt-in.
- iOS 26-or-newer SDK pin, exact Xcode measurement pin in `toolchain.json`, verified pre/post-build on the macOS CI lane.
- Bundle id `com.infinityball.pitchpact` (registered; verified `CREATED` in App Store Connect 2026-09-30). No other prefix anywhere.
- All four App Store Connect Actions secrets are configured on this repo by name (`ASC_KEY_ID`, `ASC_ISSUER_ID`, `ASC_KEY_P8`, `ASC_TEAM_ID`); values are never logged, printed, or committed.
- The TestFlight publish Action is the proven fleet template (port of `rwrife/cook-console` `.github/workflows/release.yml`), not a new signing flow.
- Other UI frameworks stay prohibited: no Flutter, React Native, Expo, .NET MAUI, or Unity. KMP is shared *logic* only — never UI (Compose Multiplatform UI is likewise out of scope; Android UX is Compose-on-Android, iOS UX is SwiftUI).
- Youth-privacy constraints in README §Privacy replace the fleet's usual zero-network posture, which cannot apply to a product whose core features are hosted broadcast and registry coordination. Where the old zero-network gate cannot be ported, it is replaced by an explicit *data-minimization gate* (see Testing strategy).

## Scope and architecture

### Shared domain core (`shared/`, Kotlin Multiplatform)

Pure Kotlin, no platform UI or platform DB imports. Targets: `iosArm64`/`iosSimulatorArm64` (XCFramework consumed by the SwiftUI app) and `androidTarget`. All business rules live here so iOS and Android cannot drift:

- **Entities:** `Team`, `Player` (jersey number, optional guardian contact — private fields), `UniformRequirement` (home/away kit colors, keeper kit, equipment checklist), `Location` (field name/address), `Tournament`, `Fixture` (tournament game or ad-hoc league game; date/time, location, home/away, uniforms for both sides), `AvailabilityResponse` (yes/no/maybe per player per fixture).
- **Match ledger:** append-only `MatchEvent` stream with per-side attribution — clock transitions (kickoff, halftime, full time, stoppage), goals (scorer, assist, type: open-play/penalty/own-goal/set-piece), cards, substitutions, and other tracked play-by-play categories. Corrections/withdrawals are appended, never mutated; full recompute from the ledger is idempotent.
- **Consensus scoring state machine** (the standout feature): each side submits `ScoreChange` records (add/correct/remove a goal). States: `pending → accepted | rejected | withdrawn`. The *official* scoreboard is derived only from accepted changes; `pending` changes render distinctly; every event from both sides remains in the ledger regardless of consensus outcome. Tie-breaking and final-result logic consume only the official view.
- **Clock model:** two halves + halftime + stoppage, monotonic anchors, foreground/background-safe, offline-replayable.
- **Sync DTOs:** wire formats (kotlinx.serialization) shared verbatim between the apps and the server for broadcast, registry, link/fixture coordination, consensus, and queue-resync with idempotency keys.
- **Offline queue:** deterministic merge rules when local unsent events meet server state; conflicts surface explicitly (never silently resolved by clock order).

### iOS app (`iosApp/`)

SwiftUI app, iOS 26 SDK pin per `toolchain.json`, iPhone-only, consuming the KMP XCFramework. Views never compute score/clock/consensus — they render `shared` state.

### Android app (`androidApp/`)

Kotlin + Jetpack Compose app over the same `shared` module. Same rule: Compose renders shared state; no duplicated business logic.

### Hosted service (`server/`)

Kotlin + Ktor on the JVM, reusing `shared` sync DTOs and consensus state machine server-side (one implementation, three consumers). Endpoints: broadcast session lifecycle (create → short code → spectator read → close), spectator read (anonymous, short code + random session key), global team registry (opt-in listing, team-code link requests, rotation/revocation), fixture coordination (propose/confirm with both-side consent), consensus submission/accept. Default public deployment planned at `pitchpact.infinityball.com`; the service must be self-hostable (single Docker image, no proprietary deps). Persistence: SQLite first, Postgres adapter later. Stateless auth = capability secrets (session keys, team codes), no user accounts.

### Protocol

`docs/protocol.md` defines the wire contract (auth model, short-code rules, event schema, consensus states, idempotency, rate limits, privacy guarantees). Apps and server implement the protocol version negotiated in the DTO envelope.

## Technology choices (rationale)

| Choice | Rationale |
|---|---|
| Kotlin Multiplatform | Explicit user directive for iOS+Android with one logic core; Kotlin/Native produces a real static XCFramework; kotlinx.serialization gives one DTO source for three targets + server. |
| SwiftUI (iOS UX) | User directive: "native ios... code for the UX"; also fleet familiarity (the iOS fleet is SwiftUI) and XCUITest toolchain already proven in this fleet. |
| Jetpack Compose (Android UX) | Android-native declarative UI; user directive "native... anroid code for the UX." |
| Ktor (server) | Kotlin server sharing `shared` DTOs and consensus logic directly; runs on any JVM; trivially containerized (user prefers Docker toolchains). |
| GRDB (iOS store) / Room (Android store) | Platform-native persistence each side already knows; KMM-SQLite *considered and rejected* for MVP — domain logic in `shared` with platform stores avoids the cross-platform schema drift this product fears most. |
| Capability secrets, no accounts | Registry team codes and broadcast short codes are secrets granting narrow capabilities; matches the anonymous-broadcast brief and minimizes youth-data exposure. |
| `com.infinityball.pitchpact` bundle/app id | Fleet convention (mandatory prefix); registered in App Store Connect 2026-09-30. |

## Milestones and dependency order

M1–M8 mirror README §Current status; executor issues map onto them.

1. **M1 skeleton (no deps):** KMP `shared` module with empty-but-tested ledger + consensus state machine + DTO envelope; iOS app target (SwiftUI launch screen, pinned toolchain, device-family/bundle-prefix gates); Android app target (Compose launch screen); `server/` Ktor stub; CI: ubuntu lane (`gradlew` shared tests + Android assemble + server tests) and macOS lane (Xcode build + policy asserts). `docs/protocol.md` v0.
2. **M2 team/roster/uniforms (M1):** CRUD on both apps over shared entities; local stores with versioned migrations; guardian-contact privacy fields clearly marked private; uniform requirement editor.
3. **M3 scheduling layer (M2):** tournaments + ad-hoc fixtures, locations, local reminders, double-booking warnings (explainable, never silent), availability RSVP capture and rollup.
4. **M4 match-day engine (M1 ledger):** clock UX, play-by-play capture, undo/corrections, offline queue + resync, per-team event attribution. Offline-first proof: full game scored on airplane mode, syncs after.
5. **M5 broadcast (M4 + `server/`):** broadcast session create/close, short code (~5–6 chars, A–Z/0–9, random), anonymous spectator read in both apps + a minimal web page; per-team broadcast field policy (number-only default for youth); self-host docs.
6. **M6 registry + coordination (M2, server):** opt-in global team registry, team-code generation/rotation, link request/accept, cross-team fixture propose/confirm with both-side consent and uniform confirmation; privacy review of every registry-visible field.
7. **M7 consensus scoring (M4, M5/M6 transport):** both-sideline scorer flow, submit/accept/reject/withdraw UX, official-vs-pending scoreboard rendering, full audit log views/export; consensus tests incl. adversarial sequences (both sides submit simultaneously, re-submit after reject, withdraw after accept-before-review).
8. **M8 release path (all):** backup/export/restore with previews, deletion cascades, `AppStore/description.txt` final pass, generated app icon wired into iOS asset catalog + Android mipmap set, TestFlight via ported release workflow, Play internal track, protocol v1 freeze.

## Testing strategy

- **shared (ubuntu CI, the load-bearing gate):** table-driven/property tests for ledger recompute (idempotent replay), clock state machine (background/restart/stoppage edges), consensus state machine (full state×action matrix incl. concurrent submissions), DTO round-trip golden vectors, merge-rule determinism.
- **server (ubuntu CI):** Ktor integration tests against ephemeral instances — broadcast lifecycle, code collision/rotation, anonymous read, registry link/fixture handshake, consensus endpoint authorization (a spectator can never submit), rate limiting.
- **iOS (macOS CI):** exact Xcode/SDK pin assertion; `xcodebuild` build; pre-build greps (`TARGETED_DEVICE_FAMILY = 1` everywhere, bundle prefix `com.infinityball.`, no Flutter/React Native/Expo/.NET MAUI/Unity references, no Compose Multiplatform UI imports); post-build `UIDeviceFamily == [1]` + `CFBundleIdentifier` check from built `Info.plist`; XCUITest launch smoke then feature journeys as milestones land.
- **Android (ubuntu CI):** `assembleDebug` + unit tests; Compose UI tests where valuable; emulator tests only when cheap, never blocking.
- **Data-minimization gate (replaces fleet zero-network gate):** an allowlist of network endpoints (default-host config, config-file-driven) — the CI check asserts no hard-coded third-party hosts, no analytics/ads SDKs, and that guardian-contact and availability types are absent from every broadcast/registry DTO (compile-time serialization tests).
- No native evidence is ever claimed from Linux checks; macOS-lane gaps are disclosed as pending, never fabricated.

## Packaging / distribution plan

- **iOS:** bundle id `com.infinityball.pitchpact`; `.github/workflows/release.yml` ported from the fleet-proven `rwrife/cook-console` template — `v*` tag or manual dispatch on macos-26, iOS 26+ SDK enforcement, signed IPA archive, TestFlight upload via App Store Connect API using the four repo secrets (names only), processing poll, GitHub release. App Store submission stays a human gate.
- **Android:** `applicationId com.infinityball.pitchpact`; release workflow builds a signed AAB and uploads to a Play internal track; signing keystore secrets added when the Play Console setup is approved (out of scope for MVP issues).
- **Server:** single Docker image (multi-stage Gradle build), `docs/protocol.md` + `docs/selfhost.md`; default deployment `pitchpact.infinityball.com` — infrastructure setup is an explicit separate step, not assumed.
- App Store listing copy lives in `AppStore/description.txt`; icon is generated per fleet policy into `AppStore/icon.png` and wired into the iOS asset catalog and Android mipmap resources.

## Risks

1. **KMP→iOS friction** (XCFramework size, kotlin native threading): mitigated by M1 proving the full loop (ledger logic tested from Swift via the framework) before any feature builds on it. If the loop stalls, the fallback is the server-side-authoritative model, not a framework swap.
2. **Two-scorer consensus UX complexity:** the state machine is in `shared` with an exhaustive transition matrix; UI shows only derived official/pending state — no client-side reconciliation logic.
3. **Youth-data exposure through broadcast/registry:** field-level DTO tests prove private types can't serialize into broadcast/registry payloads; number-only broadcast default recommended; registry shows opt-in fields only.
4. **Short-code enumeration / abuse:** 5–6 char code space paired with a random per-session server key; rate limits + no spectator-to-server write path; codes are single-purpose and expire at full time + grace.
5. **Server availability ≠ app availability:** every record-keeping feature is offline-first; consensus/broadcast degrade gracefully (score on-device, reconcile later).
6. **Scope size (registry + broadcast + consensus is a lot):** milestone order is strictly dependency-first; M5 (broadcast, the headline family feature) lands before M6/M7 (cross-team coordination).
7. **Offline merge conflicts:** idempotency keys + append-only ledgers mean the only real conflict class is score-change consensus, which is adjudicated by the explicit accept/reject handshake — not by merge heuristics.

## Explicit non-goals

See README §Non-goals — carried here verbatim in spirit: no video, no social layer, no messaging, no medical claims, no betting, no Flutter/React Native/Expo/.NET MAUI/Unity, no Compose-Multiplatform UI, no native iPad UI, no trademarks.
