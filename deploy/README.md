# Deploying werwolf-clj

Two containers on one small VPS: the game server, and Caddy in front of it for
TLS. That is the whole production topology, and it is the same thing you run
locally for prod-parity.

## Local, prod-parity

```bash
bb docker                                        # uberjar, then the image
cp deploy/.env.example deploy/.env               # then set TOKEN_SECRET
docker compose -f deploy/docker-compose.yml up -d
open http://localhost                            # Caddy on :80, not :3000
```

Leave `WERWOLF_SITE_ADDRESS` unset and Caddy serves plain HTTP on `:80`. The app
container publishes no ports, so `localhost` is the only way in — deliberately,
because a published `:3000` is how you end up with a way to bypass TLS in
production and not notice.

`bb docker` is the entry point, not `docker build`. The image is runtime-only
and expects `target/*-standalone.jar` to exist; the bb task builds the release
CLJS, the minified CSS and the uberjar first. A bare `docker build` fails on the
`COPY`, which is the correct failure — it means the artifact does not exist.

## Hetzner

**CX22** (2 vCPU / 4 GB / 40 GB, ~€4–5/mo), Nuremberg or Falkenstein: low
latency for German players, EU data residency, 20 TB egress. The server is
genuinely tiny — a few MB per room, negligible CPU — so one CX22 handles dozens
of concurrent rooms.

1. Point an A record (and AAAA, if you took the IPv6) at the box. **Do this
   first.** Caddy's ACME HTTP-01 challenge is what fails otherwise, and failed
   attempts count against a Let's Encrypt rate limit.
2. Hetzner Cloud Firewall: inbound **80/TCP** and **443/TCP+UDP**. Nothing else.
   (M7 adds the LiveKit ports; they are listed in the plan and are the usual
   failure mode.)
3. Install Docker, clone the repo, then:

```bash
bb docker
cp deploy/.env.example deploy/.env
# TOKEN_SECRET=$(openssl rand -base64 48)
# WERWOLF_SITE_ADDRESS=werwolf.example.com
docker compose -f deploy/docker-compose.yml up -d
docker compose -f deploy/docker-compose.yml logs -f app
```

TLS needs no further configuration. Neither does the WebSocket: Caddy 2 forwards
protocol upgrades transparently, so `/api/ws` works with no `Connection`/
`Upgrade` headers set by hand — adding them, the reflex carried over from nginx,
actively breaks it.

### Updating

```bash
git pull && bb docker
docker compose -f deploy/docker-compose.yml up -d   # recreates the app container
```

Expect a few seconds of downtime, and see the next section for why that is not a
bug to be fixed with a rolling deploy. Games in progress survive it: the event
log is on a volume and `room/rehydrate!` folds unfinished rooms back on boot,
re-arming each timed phase with a fresh clock rather than an expired one.
Clients reconnect from their localStorage tokens.

## Load testing

`bb load` plays whole games against a running server with bots on real
WebSockets. It is the only thing in the repo that exercises the server over
sockets at scale; the simulation harness drives the engine in-process. Each bot
speaks the protocol exactly as the browser does and picks legal moves from its
own view the way `test/werwolf/sim/harness.cljc` does, so the games end.

```bash
bb load ws://localhost:3000/api/ws 2 6 smoke                          # two tables, a minute
ACT_MIN=500 ACT_MAX=2500 bb load wss://werwolf.example.com/api/ws 60 12 human
```

Arguments: url, rooms, players per room, a label for the output. `ACT_MIN` and
`ACT_MAX` are a bot's thinking time per move in ms: the default 50..300 is a
stress test, 500..2500 is about human pace. `STAGGER` spaces out the room
creations and `MAX_GAME_MS` gives up on a room; the defaults are in `bb.edn`.
Not against production with players on it: the bots are indistinguishable from
a few hundred people, which is the point.

The run ends with one `SUMMARY` line of JSON. What to look at, in order:

- `pingBaselineMs` and `pingUnderLoadMs`: the round trip of a separate socket
  that only pings, before the rooms start and during the run. Their difference
  is what the load costs a player who is just watching.
- `cmdToViewMs`: from sending a command to the view it produced. This is the
  lag a player feels on their own move, and the number to watch.
- `connectMs` and `welcomeMs`: socket open to `:server/hello`, and create or
  join to `:server/welcome`. On a TLS host the first is mostly the handshake.
- `errors`: by server error code, plus `http <status>` for upgrades the server
  refused. A 503 comes from the proxy, not the app. A few `:not-allowed` are
  normal — a bot acting on a view that went stale while it was thinking — and
  at stress pace there are many.
- `unexpectedCloses`, `reconnects`, `resumeFailures`: sockets that closed under
  a seated bot, and whether `:client/resume` got the seat back. All three
  should be zero.
- `stalls` and `aborted`: rooms that went quiet for 90 s, and rooms given up
  after `MAX_GAME_MS`. Zero on a healthy server; `gamesDone` says the rest.
- `eventLoopLagMaxMs`: the generator's own stalls. When it is large, the
  latencies above are partly the generator's fault, and the test wants a
  second process or a bigger machine rather than a faster server.

### Reference numbers

The first runs, on 2026-10-21, against the live 2 vCPU host from a laptop over
the internet (ping at rest 20–23 ms), so that later runs have a baseline:

| load                                    | ping p50 under load | move p50 | move p95 |
|-----------------------------------------|---------------------|----------|----------|
| ~300 players, human pace (500..2500 ms) | 21 ms               | 46 ms    | 1.3 s    |
| ~400 players acting every 50–300 ms     | 22 ms               | 313 ms   | 2.7 s    |

The app itself never dropped a socket. What did fail was joining: waves of
fifteen or more new connections per second were answered with 503s by Caddy,
whose health check decided the app was down while it was busy accepting them.
Fixed separately, in the Caddyfile; a run that shows `http 503` in `errors` is
a proxy regression, not a server one.

`test/load/rate.js` isolates the connection rate from the game. It opens
upgrades at a given rate with raw http requests, so a refused one reports the
server's status and the start of its body — which is what told the proxy's
503 from the app's.

```bash
node test/load/rate.js wss://werwolf.example.com/api/ws 20 10   # 20 per second for 10 s
```

## The jar needs Java 21+ — including locally

If you build the uberjar and run it with a bare `java -jar`, check which `java`
that is first:

```bash
java -version                                              # PATH java
clojure -M -e '(println (System/getProperty "java.version"))'  # the BUILD java
```

On this machine those disagree: `/usr/bin/java` is 17, while `clojure` is a Nix
wrapper carrying the system's JDK (nixpkgs' default is 21). That matters because http-kit 2.8 chooses its executor
with a macro that evaluates `(Thread/ofVirtual)` at **compile** time — so the JDK
that built the jar decides forever whether the jar wants virtual threads. Built
on 21, run on 17, you get:

```
NoSuchMethodError: Executors.newVirtualThreadPerTaskExecutor()
```

raised from inside `integrant/init`, which makes it look like a system-wiring
problem and not a JVM one. `bb serve` and `bb dev` are unaffected — they go
through `clojure`, so they compile and run on the same JDK.

The container runs `eclipse-temurin:25-jre`, and the nix package builds and runs
on JDK 25, so deployments are fine.

On the Mac the uberjar no longer runs at all, whichever `java`: it carries
sqlite-jdbc's native library for Linux x86_64 and aarch64 only, the two the image
can be (see build/build.clj), and cannot open the database anywhere else. To run
the packaged server here, use the nix package instead. It carries this
platform's library and its own JDK 25:

```bash
nix build
DB_PATH=./data/werwolf.db TOKEN_SECRET=$(openssl rand -base64 48) \
  result/bin/werwolf-clj
```

## Voice (optional, and add it second)

Voice is a Compose **profile**, so the default `up -d` is exactly the two services
that were verified working:

```bash
docker compose -f deploy/docker-compose.yml --profile voice up -d
```

Do this only once the plain deploy works. Self-hosting an SFU adds ICE, a UDP port
and a firewall rule — debuting those in the same step as the game's first
deployment means two unproven things at once, which is the plan's own advice about
not debugging game rules and NAT traversal together, applied to the deploy.

Set all five voice variables in `deploy/.env` (see `.env.example`). `LIVEKIT_URL`
points at **this** site with a `/livekit` path: Caddy proxies signalling there, so
voice reuses this certificate and needs no second DNS record or subdomain.

### Firewall — the part that fails silently

Media does **not** go through Caddy. It is UDP straight to the host, so beyond
80/443 you must also open:

- **7882/UDP** — media (one muxed port, see below)
- **7881/TCP** — fallback for networks that block UDP

Miss these and the symptom is the worst kind: the call connects, the UI looks
healthy, and nobody can hear anything. If that happens, this is the first thing to
check.

### One UDP port, not the 50000–60000 range

`deploy/livekit.yaml` sets `rtc.udp_port`, which multiplexes every participant
over a single port. The plan assumed a range, and a range is a bad fit for Docker:
publishing ten thousand ports makes `up` take minutes and costs a userland proxy
entry each. One port carries the whole table at this scale.

### Raise the UDP receive buffer

LiveKit warns about this on boot and it is a real production item, not noise:

```
UDP receive buffer is too small for a production set-up  {"current": 425984, "suggested": 5000000}
```

The container shares the host kernel, so it has to be set on the **host** — an
unprivileged container cannot raise it itself:

```bash
echo 'net.core.rmem_max=5000000' | sudo tee /etc/sysctl.d/99-livekit.conf
sudo sysctl --system
```

Undersized buffers show up as packet loss under load — choppy audio on a full
table, while a three-person test sounds perfect.

### No TURN yet

`livekit.yaml` ships no `turn:` block. The TCP fallback covers most restrictive
networks; if somebody genuinely cannot connect from a corporate network, that is
the point to add coturn on 443/TCP. Deliberate not-yet rather than oversight.

### Verifying it without listening

The credential path can be checked end to end without a microphone. LiveKit
answers `GET /rtc/validate?access_token=…` with `200 success` for a token it
honours, and Caddy strips the `/livekit` prefix, so:

```bash
curl -i "https://werwolf.example.com/livekit/rtc/validate"
# 401 "no permissions to access the room" — LiveKit's own reply, which proves
# the proxy path and prefix-stripping are right. A 404 means they are not.
```

Whether audio actually flows is a browser question, and needs two devices.

## The one constraint to respect

Room state is an in-memory atom plus a local SQLite file. Therefore **exactly
one app container, and no rolling deploy** — two would fight over the database
file and serve two halves of the same game to different players. `docker compose
up -d` stops the old container before starting the new one, which is what we
want. On k3s this is `replicas: 1` with `strategy: {type: Recreate}` and an RWO
PVC; `RollingUpdate` is the failure mode.

If you ever genuinely outgrow one node: shard rooms by consistent hash of room
id, or move to Postgres and a pub/sub bus. Not before — a Werwolf server is not
a scaling problem.

## Backups

The event log at `/data/werwolf.db` in the `werwolf-data` volume is the only
durable state. Games are ephemeral and the stakes are low, so a nightly copy to
a Hetzner Storage Box is plenty:

```bash
docker compose -f deploy/docker-compose.yml exec app \
  sh -c 'sqlite3 /data/werwolf.db ".backup /data/backup.db"'
```

Use `.backup` rather than `cp`: the database is open and WAL-mode, so copying
the file can capture a torn write.

Note the image has no `sqlite3` binary — it is a JRE image. Either install it in
the Dockerfile when you first need this, or stop the app container and copy the
volume, which is fine at this scale.

## GDPR

Nearly free here, and worth keeping that way: nicknames only, no recording, no
third-party CDN, EU region. Add a short privacy page before opening it beyond a
private group.

## Not here yet

**k3s** (`deploy/k3s/`) — the plan lists it as optional and a learning goal. On
a single node it buys nothing functionally over Compose, so it is deliberately
absent rather than half-written.

**LiveKit** — M7. It is the fiddly part: a UDP port range, `hostNetwork: true`
on k3s, and coturn on 443/TCP for players behind corporate firewalls. Build it
against LiveKit Cloud's free tier first; do not debug game rules and UDP NAT
traversal in the same session.
