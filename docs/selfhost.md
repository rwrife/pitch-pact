# Pitch Pact — Hosted Service Runbook

Status: **design complete, deployment not yet done**. This repo defines the
service; `docs/protocol.md` defines the wire contract; deployment is an
explicit, separate step owned by the user.

## Components

```
server/       Ktor (JVM 17+) — reuses KMP shared DTOs + consensus state machine
docs/         protocol + this runbook
```

Single Docker image (multi-stage Gradle build) with SQLite (default) or Postgres
(Postgres adapter = a follow-up milestone). No external services required to run.

## Hosting decision (open, user-owned)

The default public endpoint is planned as **`pitchpact.infinityball.com`**
(same infinityball domain already used for the wicket-tally broadcast plan).
Before any public deployment, the user picks the actual host. Options:

1. Self-host on the existing DGX Spark / home infra behind Tailscale-exit +
   a VPS reverse proxy (frp tunnel pattern already in use for other
   services). Cheapest; Spark is on residential internet — expect a tunnel.
2. Small VPS (Hetzner/DO, ~$5/mo) running the Docker image + Caddy TLS.
3. Managed (Fly.io / Railway / Render) running the image directly.

Whatever is chosen must support: HTTPS termination, persistent volume for the
DB (or managed Postgres), and outbound SMTP-free operation (no email needed —
capability secrets are shown in-app, never emailed).

## Deployment

```bash
# Build
docker build -t pitchpact-server server/

# Run (SQLite default)
docker run -d --name pitchpact -p 8443:8443 \
  -v pitchpact-data:/data \
  -e PITCHPACT_DB=sqlite:/data/pitchpact.db \
  -e PITCHPACT_PUBLIC_HOST=pitchpact.infinityball.com \
  pitchpact-server
```

TLS: reverse-proxy (Caddy/Traefik/nginx) terminates TLS; the service itself
listens plain-HTTP behind it and trusts `X-Forwarded-*` only from the proxy CIDR.

## Operational policy

- Backups: nightly `sqlite3 .backup` (or DB dump) to object storage;
  restore drill quarterly.
- Logs: match events are never logged; only access metadata (timestamp,
  endpoint, response code, code prefix not full code).
- Abuse: rate limits (spectator reads per code per IP; broadcast PATCH
  per session key); no spectator-to-server write path exists at all.
- Deletion: a team deleted in-app deletes its server-side registry entry,
  links, and broadcast history (documented in protocol §6).
- Broadcast data retention: match facts auto-expire 60 min after full time
  unless a manager explicitly archives them to their team record.
