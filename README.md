# Pitch Pact ⚽

**Pitch Pact** is a multi-platform (iOS + Android) team management and live-scoring app for recreational and club soccer — built first for youth soccer teams. Run teams and rosters, tournaments and ad-hoc games, uniforms, locations, reminders, availability, and match-day live play-by-play. A hosted service lets a team manager broadcast a game so anyone with the short code can follow the score and timing, and a global team registry with shareable team codes lets two teams coordinate a fixture and then score it together — with consensus scoring: every score change only counts on the official live scoreboard once the opposing side accepts it, while the play-by-play is always logged for record-keeping.

## PRODUCT CONTRACT (user brief 2026-09-30)

The user directed this product with the following brief. It is the product contract: regressions against it are blocking defects, not polish.

> a mobile soccer scoring app for rec/club soccer teams, primary targeted at youth soccer. the app should allow the user to setup a team, roster, etc, set tournaments, games (tournament and adhoc league games), track uniform requirements, locations, reminders, roster availability, game time, scores, live play-by-play updates, etc. the key feature is that we'll also have a hosted service that will allow the team manager to share the game so people can keep track of the stats or they can share so others can keep track of scoring and timing of the live game, this should work similar the features we're building out for wicket-tally app, one stand out feature is that we should have a global shared registry of teams and allow a user to share a team code with other teams so they can coordinate matches and schedules, then a scorer from each side would keep track of the play by play from the respective team, when a team submits or removes score, the other team would need to accept that score change before it's official on the live score board, the play by play would still be logged regardless for record keeping. we will need this to be multi platform and support ios and android, so it should probably use kotlin multiplayform for shared logic and native ios/anroid code for the UX

Contract highlights derived from the brief:

1. **Multi-platform is mandatory**: shared logic in Kotlin Multiplatform (KMP); native UX per platform (SwiftUI on iOS, Jetpack Compose on Android). This is an explicit user-approved platform exception for this project — see PLAN.md §Platform exception.
2. **Hosted broadcast service** (similar to the service planned in `rwrife/wicket-tally` issues #22/#23): the team manager broadcasts a game; spectators follow scoring and timing live in read-only mode via a short shareable code, anonymous by default.
3. **Global team registry + team codes**: teams register in a shared registry; a manager shares a team code with another team to link and coordinate matches and schedules.
4. **Consensus scoring**: each side's scorer keeps their own play-by-play. A score change (goal added/removed/corrected) submitted by one side is `pending` until the other side accepts; only accepted changes are official on the live scoreboard. Rejected or withdrawn changes remain in the audit log, and the play-by-play event stream is always recorded regardless of score-acceptance state.
5. **Youth-soccer privacy posture**: roster, guardian contacts, and availability are private; a broadcast publishes only match facts (score, clock, events), never private team data.

## Overview

One-sentence pitch: Multi-platform youth-soccer team manager and live scorer — rosters, tournaments, availability, uniforms, and play-by-play, with a hosted broadcast code for spectators and a team-code registry so two teams can schedule a match and co-officiate the score by mutual consent.

## Motivation

Recreational and club soccer at the youth level runs on group chats, paper rosters, and a parent with a stopwatch. Team managers juggle rosters, jersey/uniform rules per opponent, field locations, availability pleas, and tournament brackets in three different apps. Meanwhile the live score a family member actually wants — the grandparent on the sideline of the *other* team, the teammate recovering at home — either does not exist or lives on a club website updated at halftime.

And when two clubs' scorers disagree, nobody wins: the home team's feed says 2-1, the visitor's says 1-1, and both are "right." Pitch Pact's consensus model makes agreement the definition of an official score: each side records its own play-by-play, and a score becomes official on the shared scoreboard only when both sides accept it. Nothing is deleted — disputed and withdrawn changes stay in the log for the record.

## Target users

- Youth and recreational team managers, coaches, and assistant coaches (rec clubs, school teams, weekend leagues).
- Team scorers / scorekeeping parents on each sideline.
- Tournament organizers running small brackets and standings.
- Families and teammates who want to follow live games without an account.

## Concrete use cases

1. **Set up the club season.** Create a team with roster, jersey numbers, optional guardian contacts, and uniform requirements (home/away kit colors, keeper kit, shin guards, ball size). Create a tournament, register teams, schedule its games; add ad-hoc league games between tournaments.
2. **Coordinate a cross-club match.** Team A looks up Team B in the global registry (or Team B shares its team code), the link request is accepted, and a proposed fixture (date, time, location, home/away, uniforms) is confirmed by both sides. Everyone gets reminders.
3. **Check availability.** Players/guardians RSVP yes/no/maybe per game; the manager sees counts before lineup decisions. Availability data never leaves the private team space.
4. **Score from the sideline.** The match-day screen runs the clock (two halves, halftime, stoppage time) and captures play-by-play: goals (scorer + assist), own goals, penalty goals/misses, substitutions, yellow/red cards, corners, offsides, shots on target, saves, injury stoppages. Undo is first-class; the app works fully offline and queues events for sync.
5. **Co-officiate two scorers.** Each side's scorer submits score changes; the other side accepts or rejects. The live scoreboard shows `official` score plus any `pending` changes clearly labeled. The full event ledger from both sides is kept regardless.
6. **Share the game with everyone.** The manager taps Broadcast: the hosted service returns a short code (~5–6 chars); grandparents type the code (or tap a link) in the app or a web page and follow score, clock, goals, and cards read-only and anonymously.

## How to use (intended end-to-end workflow)

1. Create teams → add roster and uniform requirements → create or join a tournament.
2. Schedule tournament and ad-hoc games (date, time, location) → reminders go out locally.
3. For cross-club games: exchange team codes → link teams → propose and confirm the fixture with uniforms on both sides.
4. Collect availability RSVPs → build the lineup.
5. Match day: start the clock, log play-by-play; each side's scorer submits score changes for the other side to accept.
6. Manager broadcasts the game → spectators follow via the short code.
7. Full time: result becomes official once both sides accept the final score; export/backup from Settings.

## MVP feature list

- Teams, rosters (players, numbers, optional guardian contacts), uniform requirements, home locations.
- Tournaments (groups, fixtures, standings) and ad-hoc league games; local reminders; conflict warnings.
- Roster availability RSVPs per game with manager rollup.
- Match-day engine: two-half clock with halftime/stoppage, append-only play-by-play ledger with undo/corrections, per-team attribution.
- Consensus scoring: `pending`/`accepted`/`rejected`/`withdrawn` score-change states; official scoreboard shows accepted facts only; full audit log preserved always.
- Hosted broadcast service: anonymous session with random key, short share code, read-only spectator view (in-app and web page), self-hostable with a default public host.
- Global team registry: opt-in team listing, shareable/rotatable team code, link requests, fixture propose/confirm between linked teams.
- Data ownership: versioned backup/export, restore with preview, per-team and per-season deletion with cascade previews.
- iOS (SwiftUI, iPhone-only, iOS 26+ SDK) and Android (Jetpack Compose) native UX over one KMP `shared` module (domain, rules, ledger, sync DTOs).

## Non-goals

- No video streaming, video capture, or referee-style officiating: the app records what scorers log; consensus scoring is coordination, not a rules adjudication or DRS.
- No public player profiles, follower graphs, chat, or social feed. Spectators see only match facts.
- No guardian/social messaging beyond registry link requests and fixture confirmation; team-internal communication stays in the team's existing channels.
- No injury/health diagnosis, training-load advice, or medical claims; injury events are plain match log entries.
- No betting, odds, or live-wagering integrations, ever.
- No cross-platform UI frameworks: no Flutter, React Native, Expo, .NET MAUI, Unity, or equivalents. KMP is shared *logic* only (user-approved exception), never the UX.
- No native iPad UI (iPhone-only on the iOS side; iPad support requires explicit user opt-in, same as fleet policy).
- No trademarks of real clubs, leagues, or federations; no real-team imports.

## Privacy, permissions, and data storage

This is a youth-sports product; the privacy posture is strict by design.

- **Private by default:** rosters, player names, guardian contacts, availability RSVPs, and lineup data live in the user's local database and only sync to the hosted service when a feature requires it (broadcast, registry, co-officiating) — and then only the minimum fields that feature needs. A broadcast publishes score, clock, and match events; player display in broadcast is configurable per team (jersey number-only is the default recommendation for youth teams).
- **Registry = capability secrets:** a team code is a secret; sharing it grants exactly one thing (a link request). Codes are rotatable and links revocable. The global registry only shows what a team opts in to (club name, region, age group) — never contacts.
- **Anonymous broadcast:** a broadcast session uses a random session key server-side and a short code client-side; spectators need no account, and the service keeps no spectator history.
- **Consent & deletion:** export and account-level deletion are user-initiated with previewed cascades; server-side records for a team's broadcasts and registry entry are deleted with the team.
- Local-first: every scoring/record-keeping feature works with zero connectivity; queued events sync later with idempotency keys and deterministic merge.
- The hosted service's default deployment (`pitchpact.infinityball.com`, planned) and any self-hosted instance must document exactly what is stored; the service is designed to store only match facts, registry opt-ins, and link/consensus state.

## Current status and milestones

1. M1: KMP `shared` domain core + project skeletons + CI gates — **merged** (shared ledger + consensus state machine + v0 DTO envelope, iOS/Android/server targets, dual CI lanes)
2. M2: Team/roster/uniform UX on iOS and Android — **merged** (shared entities + validation, GRDB v1 + SwiftUI CRUD, Room v1 + Compose CRUD, private guardian-contact serialization gate; evidence: [docs/m2-evidence.md](docs/m2-evidence.md))
3. M3: Tournaments, scheduling, reminders, availability — **in review** (shared scheduling rules and privacy tests; native UX/CI evidence pending)
4. M4: Match-day scoring engine + play-by-play UX (offline-first)
5. M5: Hosted broadcast service + spectator read-only experience
6. M6: Global team registry, team codes, cross-team fixture coordination
7. M7: Two-scorer consensus scoring with audit ledger
8. M8: Backup/export/deletion + app-store/icon assets + TestFlight + Play internal tracks

Feature claims (broadcast, registry, consensus product UX, distribution) are intent, not shipped: no application code is claimed live until CI and device evidence exists for each milestone.

## Development / build quickstart (planned)

- Monorepo layout: `shared/` (KMP: Kotlin, targets iOS via Kotlin/Native XCFramework and Android), `iosApp/` (SwiftUI, pinned Xcode/iOS 26 toolchain per `toolchain.json`), `androidApp/` (Kotlin + Jetpack Compose), `server/` (Kotlin/Ktor, reusing shared DTOs), `docs/protocol.md` for the broadcast/registry/consensus contract.
- iOS: `TARGETED_DEVICE_FAMILY = 1` in every configuration (iPhone-only; native iPad support disabled); bundle id `com.infinityball.pitchpact` (registered in App Store Connect); iOS 26+ SDK with Xcode 26.0.1 (17A400) pinned by CI — a missing exact pin is an environment blocker, never a silent substitute.
- Android: `applicationId com.infinityball.pitchpact`; minSdk 27; target/compileSdk ≥ 36 (see `toolchain.json` floors); Compose UI only for new screens.
- Server: JVM 17+, Kotlin/Ktor; built and containerized with Docker (host toolchain parity via containers).
- CI lanes: ubuntu lane runs `./gradlew :shared:test :androidApp:assemble :server:test`; macOS lane builds the iOS app against the pinned Xcode and asserts the device-family and bundle-prefix policy pre- and post-build.
- Signing material is gitignored. The TestFlight/release workflow ports the proven fleet template (`rwrife/cook-console` `.github/workflows/release.yml`), using secret names `ASC_KEY_ID`, `ASC_ISSUER_ID`, `ASC_KEY_P8`, `ASC_TEAM_ID` — names only, values never logged.

## License

MIT — see LICENSE.
