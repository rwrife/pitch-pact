# Pitch Pact — Protocol (v0 draft)

Defines the wire contract between the iOS app, the Android app, and the hosted service
(`server/`, default host `pitchpact.infinityball.com`, self-hostable). Shared DTOs live in
the KMP `shared` module (`shared/src/commonMain/.../dto/`) and are reused verbatim by all
three consumers. Version negotiation happens in the envelope; all payloads below are illustrative.

## 1. Envelope

```json
{ "protocol": "v0", "type": "<dto-kind>", "idempotencyKey": "<uuid>", "payload": { } }
```

## 2. Auth model — capability secrets, no accounts

- **Team code** (`TEAM-XXXXXX`): a per-team secret granting exactly one capability — submitting a
  registry link request. Rotatable at any time; old codes die immediately. Registry-visible fields
  are opt-in only: club name, region, age group. **Never** guardian contacts, rosters, availability.
- **Link token** (`LNK-…`): issued after both teams accept a link request; scopes fixture
  coordination and consensus submissions for the link. Revocable by either side.
- **Broadcast session key** (`BSK-…`): random key the server generates at broadcast start. The
  manager's app authenticates with it to push events. The public never sees it.
- **Spectator code** (`ABC123`): short 5–6 char A–Z/0–9 code handed to the manager, shareable by
  text/poster/speech. Spectators present only this code, read-only, anonymously.

## 3. Broadcast (manager → server → spectators)

```
POST /v0/broadcasts            { teamLinkId, matchId, fieldPolicy: "full"|"number-only" }
  → { spectatorCode, broadcastSessionKey, expiresAt }
PATCH /v0/broadcasts/:key      { events: [MatchEvent…], clock: { state, elapsedSeconds, … },
                                 officialScore, pendingChanges }
  → { ackSeq }
GET   /v0/spectate/:code       (no auth)
  → { homeTeam, awayTeam, clock, officialScore, pendingChanges, events[] }
```

- Field policy `number-only` strips player names from every broadcast payload (server-side too).
- In M5, broadcast payloads transmit match facts (clock, events, officialScore, and labeled pendingChanges). Official score derives solely from accepted consensus decisions (arriving in M7); until accepted, captured goals remain pending and official score stays 0–0.
- Spectators are completely anonymous: no account, no token, read-only. No write path exists from spectator endpoints.
- Broadcasts expire at full time + 60 min grace (or 24 hr default). Stopped broadcasts immediately refuse spectators.
- Spectator reads are rate-limited per code per IP (120 req/min). Broadcast updates are rate-limited per key (120 req/min).


## 4. Consensus scoring

Each linked side submits score changes; neither side's change is official alone.

```
POST /v0/links/:linkId/consensus/changes        { change: { eventId, goalRef, action: add|remove|correct, side } }
POST /v0/consensus/changes/:id/accept | reject  (by the OPPOSING side's team key)
POST /v0/consensus/changes/:id/withdraw         (by the submitting side, before resolution)
```

Server stores every change with its full audit trail (`state`, `submittedBy`, `resolvedBy`,
timestamps). The spectator payload includes `officialScore` (accepted only) plus labeled
`pendingChanges`. Rejecting leaves the underlying event in the record ledger — the scoreboard
just never marks it official.

## 5. Registry + fixture coordination

```
POST /v0/registry/teams/:teamCode/link-requests  → { requestId }
POST /v0/registry/links/:requestId/accept        (other side)
POST /v0/links/:linkId/fixtures                  { proposed fixture (date, time, location, uniforms) }
POST /v0/links/:linkId/fixtures/:id/confirm      (both sides required before it shows in either schedule)
```

## 6. Idempotency, sync, privacy

Every mutation carries an `idempotencyKey`; replays return the original result. Offline queues
replay keys verbatim. The server stores only: opt-in registry fields, link/fixture state,
broadcast match facts, and consensus audit records — no guardian contacts, no availability, no
spectator identity or history. Deletion of a team deletes its server-side records.
