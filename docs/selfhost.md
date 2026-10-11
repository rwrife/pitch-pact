# Pitch Pact — Hosted Service Runbook

Public deployment is user-owned and has not been performed. The planned host
`pitchpact.infinityball.com` is not a claim of an available service.

## Build and run

Run from the repository root; the server build needs `shared/` and the wrapper.

```bash
docker build -f server/Dockerfile -t pitchpact-server .
docker run -d --name pitchpact -p 127.0.0.1:8080:8080 \
  -v pitchpact-data:/data \
  -e BROADCAST_DATA_DIRECTORY=/data/broadcasts \
  -e MATCH_DATA_DIRECTORY=/data/matches \
  -e MATCH_ID=your-fixture-id \
  --env-file /secure/path/pitchpact.env \
  pitchpact-server
```

The protected environment file supplies a random `MATCH_CAPABILITY` of at least
32 characters. Never commit this file, log the key, or put it in spectator links.
The current operator gate scopes creation and match sync to `MATCH_ID`; multi-team
registry authentication belongs to M6/M7. The broadcast session key authorizes
updates/stop/rotation only for its broadcast. Spectator codes grant read only.

The current persistence is private atomic JSON files, not SQLite/Postgres. Use one
server process per volume. Files and directories have owner-only permissions;
writes fsync then atomically replace the previous snapshot. Back up the private
volume with an encrypted filesystem snapshot. No spectator identities are stored
on disk. Expired records are removed on read or service restart.

## Broadcast endpoints

- `POST /v0/broadcasts`, manager capability in `Authorization: Bearer …`; shared
  `BroadcastCreate` body (matchId, homeTeam, awayTeam, number-only fieldPolicy,
  idempotencyKey). Returns spectatorCode, broadcastSessionKey, expiresAt.
- `PATCH /v0/broadcasts/{code}`, broadcast key in Authorization; shared
  `BroadcastUpdate` contains seq, idempotencyKey, clock checkpoint, full retained
  events and pending decisions. Replay the **identical** request until ackSeq is
  verified. New writes require the next sequence. Conflicts return 409.
- `DELETE /v0/broadcasts/{code}` stops reading immediately; requires broadcast key.
- `POST /v0/broadcasts/{code}/rotate` replaces the private key; old keys stop working.
- `GET /v0/spectate/{code}` anonymous read-only JSON.
- `GET /spectate/{code}` anonymous HTML, refreshes every ten seconds with no
  JavaScript or tracking. `/` is the join-by-code page.

Wire schemas cannot represent player names, rosters, guardian contacts or
availability. Unknown inbound fields are discarded before persistence. Both full
and number-only policies currently expose jersey numbers only; full is not a
permission to publish youth names. The official score derives from accepted-only
shared rules. Broadcast capability cannot resolve consensus; M7 supplies opposing
side authorization. Until M7, captured goals are pending and official score stays
zero. Do not substitute pending score for official score.

Full time sets a fixed one-hour grace deadline; later updates cannot extend it.
Unfinished broadcasts have a 24-hour safety expiry. Stop retains the private match
facts until expiration. The local match ledger is unaffected by broadcast expiry.
Recent viewers are approximate unique network addresses over a 30-second window;
salted digests are kept briefly in bounded RAM, with no spectator identity/history
persisted. Multiple people behind one NAT may count as one viewer.

## Deployment boundary and abuse

Terminate TLS at your reverse proxy. Do not expose the plain HTTP listener publicly.
The app manager transport requires HTTPS (Android permits loopback for development).
The service ignores forwarded headers and rate-limits by direct peer address; behind
one proxy this conservatively shares a read bucket across viewers. Configure any
additional true-client-IP rate limit at the proxy; never trust arbitrary forwarded
headers. Limit request bodies at the proxy to 1 MiB and redact Authorization, paths
containing private keys, and request/response bodies from logs.

Create: 20/minute per peer; reads: 120/minute per code/peer; writes: 120/minute per
key. Spectator endpoints have no mutation route. Registry/team cascade deletion,
public deployment, and production TLS evidence remain separate milestones.
