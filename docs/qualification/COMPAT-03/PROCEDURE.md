# COMPAT-03 isolated tick-load procedure

## Authority and scope

This procedure is for a future, separately authorized operator run. Source access,
this document, or a successful offline test is not authorization to start Minecraft,
accept its EULA, connect players, contact a host/provider, or incur provider spend.
No live run is authorized by this procedure's presence.

The implemented runner currently supports only `--peer fixture`: a loopback-only,
synthetic WebSocket peer. It does not install or start OpenClaw and makes no
provider calls. `--peer openclaw` is deliberately refused until separately
authorized host/provider support is implemented and reviewed. Do not work around
that refusal with another runner or workflow.

## Prerequisites

Obtain a fresh, unqualified candidate pair and separately recorded operator
approval for the isolated Minecraft start and EULA acceptance. Use the exact
pinned runtime: Ubuntu 24.04 x86-64, Temurin 21.0.13+9-LTS, Node 24.22.1, and
npm 11.19.1. The runner rejects a host/runtime mismatch before downloading
Fabric inputs or starting Minecraft.

Prepare a new absolute run directory outside the repository and a private player
file outside the repository. The player file is UTF-8, mode `0600`, at most 4 KiB,
and contains one line per real, explicitly authorized participant:

```text
<Minecraft UUID> <player name>
```

Use at most eight unique players; do not invent identities or use protocol bots.
Coordinate all participants before the run. Their clients must be able to
authenticate in Minecraft online mode. Keep the player file and complete run
directory private: the isolated server's whitelist, configuration, logs, world,
and any unsanitized partial files are not publication artifacts.

Build and verify the separate harness JAR, then invoke the runner with both
explicit runtime/EULA flags:

```sh
./gradlew --no-daemon compatLoadHarnessJar
node tools/compat/tick-load.mjs \
  --distribution build/distributions/worldspeaker-candidate \
  --workdir "$RUN" \
  --players-file "$PLAYERS" \
  --peer fixture \
  --start-server \
  --accept-eula
```

`$RUN` must be a new, non-existing absolute path outside the repository;
`$PLAYERS` must be the private authorized-player file. The runner verifies the
candidate and harness hashes/freshness, binds both server and fixture to
`127.0.0.1`, uses online authentication and a whitelist, disables RCON/query,
and bounds the test world, view distance, evidence, process waits, and each run
to 40 minutes. It writes an owner-only run record and retains partial evidence
on failure; it does not automatically replay a failed run.

## Participant timing and workload

Watch for `WS-TICK-LOAD-PLAYER-READY`, which prints the loopback Minecraft
address after the server, authorized-player list, and companion integration are
ready. Connect the authorized vanilla clients during the following 120-second
warmup. Keep all eight connected through the capacity phases; if eight
authorized players are not simultaneously online, the capacity case is
incomplete.

Each run uses two server epochs:

1. 120-second idle warmup; then 300 seconds each of measured idle READY, normal
   traffic (0.5 submissions/second, round-robin), overload (20 distinct
   invocations/second with a 30-second synthetic peer delay), and peer unavailable.
   Each overload text contains a bounded run-scoped attempt ID; it is synthetic
   request content, not a player identity or authentication value. The harness
   does not catch up with bursts after a slow tick. The runner requires 6,000
   attempted and distinct overload invocations, the recorded target rate, and
   captured admission-rejection and pending-expiry outcomes. The peer remains
   loopback-bound but rejects reconnects so the server can exhaust its bounded
   retry policy. The runner then stops the owned server and peer.
2. The runner restarts its isolated server/peer, records a new epoch, and uses a
   120-second recovery warmup followed by 300 seconds of measured recovery. In
   the recovery epoch, have one authorized participant disconnect and rejoin;
   the record stores only an aggregate count. The run then performs exactly
   seven intentionally identical same-player repeat submissions within six
   seconds plus another authorized player's distinct request, creates a delayed
   stop-backlog, and stops the server. This regression is separate from the
   unique-text overload workload.

The runner repeats this bounded workload three times. Reconnect after each
server restart. Keep all eight authorized clients online through overload. The
recorded lower-bound capacity evidence must reach one active request, 16 global
pending requests, and two pending for a player; it is inferred from accepted
submissions, admission/terminal counters, authorized-player aggregates, and a
fixture request held in flight, not read from a production queue gauge. Capacity
rejection and pending expiry must also be observed. Fewer than eight online
participants, incomplete phase/rate/volume, unavailable capture, missing
capacity/outcome evidence, a required rejoin, repeat, outage exhaustion,
sanitized JFR trace, or sample capture makes the run incomplete, never passed.
Do not use operator commands or alter the test world.

## Evidence handling and interpretation

Review `run-record.json` and only the explicitly named sanitized evidence:

- `runs/run-N/epoch-M/tick-samples.csv`
- `runs/run-N/epoch-M/tick-summary.json`
- `runs/run-N/epoch-M/harness-status.json`
- `runs/run-N/epoch-M/fixture-peer-status.json`
- `runs/run-N/epoch-M/thread-io-trace-redacted.jsonl`

The version-3 summary records tick duration and start-to-start interval
separately, phase histograms/percentiles, >50 ms ticks, sampled memory/GC,
queue/task high-water marks, attempted and distinct invocation counts/rates,
inferred active/global/per-player admission high-water marks, rejection/expiry
and lifecycle aggregates, bounded integration counters, and a
first-public-dispatch histogram by bounded source and disposition. The histogram
starts at each invocation's original monotonic capture timestamp and records only
its first successful public dispatch to the original player connection. It
distinguishes the local acknowledgement, ready integration answer/failure, and
local safe failure/rejection/unavailable/coalesced notices. The summary also
records the direct maximum dispatch latency (not a histogram bucket ceiling),
on-time and late dispatch counts, no-dispatch deadline/settlement counts,
definitely-unsent and unknown send counts, and aggregate deadline misses. A
successful dispatch at exactly 1,000 ms is on time; a dispatch strictly after
1,000 ms or no dispatch settled at the deadline is a miss. Any recorded miss
fails the first-reply criterion for that run; pending or incomplete evidence is
not a pass. The direct maximum is server-metrics-lifetime, not phase-specific.

An admission is not a dispatch, coalesced invocations retain their own
attribution, failed sends add no success sample, and a later answer cannot count
again after an acknowledgement. Missing or invalid metrics make the epoch
incomplete rather than a zero-latency/service-success result. This does not
measure client display. It does not contain player identity values, conversation
bodies, credentials, raw endpoints, or provider output. The raw JFR is removed
after sanitization. If an interrupted run leaves a raw `.jfr`, server log,
whitelist, or credential/config file, keep the directory private and do not
publish it.

Fixture replies are synthetic. This procedure cannot establish OpenClaw/provider
compatibility, useful conversation quality, a numeric tick SLO, live-host latency,
or release qualification. Report tick deltas against idle without inventing a
threshold; report unavailable upstream timing as unknown. A successful fixture
run is Minecraft load evidence only and requires separate QA review before any
acceptance claim.
