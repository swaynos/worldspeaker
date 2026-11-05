# Worldspeaker

A server-only Minecraft mod that brings customizable AI characters to your
world, powered by OpenClaw. Players interact in-game while admins manage
agents behind the scenes—no client installation required.

## Status

For the current development handoff, see [next iteration](docs/NEXT-ITERATION.md).
It records the ABIL-04 resume point, remaining verification and activation blockers.

**COMPAT-01 foundation implemented; candidate unqualified.** The selected
candidate is Minecraft Java **1.21.1**, Fabric Loader **0.20.1**, Fabric API
**0.116.17+1.21.1**, Temurin **21.0.13+9-LTS** on Linux x86-64, OpenClaw
**2026.10.8**, and Node/npm **24.22.1 / 11.19.1**. The server mod, native
OpenClaw channel companion, strict deployment contract, compatibility gate,
local preflight, candidate packaging, and release refusal checks are implemented.

The candidate is **not a supported or qualified release**. The local build and
unit checks passed on a different macOS/ARM64 platform with Java 21.0.3 and
Node/npm 24.15.0 / 11.12.1. No isolated server install, EULA acceptance,
actual OpenClaw host load, vanilla-client test, or provider-backed conversation
was performed. Both selected game abilities remain unavailable. See the
[current QA decision](docs/r01/qa/COMPAT-01.md): qualification fails, and the
existing candidate pair is not eligible for T3–T6 or qualification. A pinned
rebuild is required first. The [historical builder report](docs/r01/build/COMPAT-01.md)
preserves the earlier run and hashes; it is not current qualification evidence.
See the generated [administrator requirements](docs/compatibility.md).
Players can read the [client and command guide](docs/player-compatibility.md).

**Conversation privacy:** `/worldspeaker ask <text>` keeps its input out of public
chat, but command input, local acknowledgements, and requester-targeted answers
enter the same shared character/game context; they are not confidential between
players. Natural-address and recognized follow-up input remains its original
public Minecraft chat line, visible to other players. Reply targeting is not a
privacy guarantee. Private administration is intended to stay separate and is
not intentionally copied into gameplay, but actual OpenClaw
workspace/bootstrap/memory isolation is unverified; stop deployment until
actual-host evidence verifies it. See the [conversation context and privacy
section of the player guide](docs/player-compatibility.md#conversation-context-and-privacy).
Optional attention subscriptions are disabled by default; when explicitly enabled,
bounded player-event facts can be attached only to a later admitted request by the
same requester and enter that same shared gameplay context. Those facts may be
processed by the configured agent and provider; they are not a private player
channel. See the server and player guides for the captured fields, retention
limits, and privacy boundary.

Worldspeaker grows out of an existing RCON-based God character on a private
Minecraft server. That prototype demonstrated the idea, but also exposed slow
replies, a shared message backlog, and unintuitive rules for addressing the
character. The goal is to improve the experience, not simply repackage those
limitations as a mod.

## Project requirements

- **Server-only installation.** Custom logic runs on the server. Players do
  not need Worldspeaker installed on their clients.
- **OpenClaw as the agent harness.** OpenClaw handles agent reasoning, memory,
  and tool activity. Worldspeaker connects that activity to Minecraft rather
  than introducing a competing agent runtime.
- **Separate player and admin experiences.** Players interact with characters
  in the world. Admins operate with the agents through OpenClaw without exposing
  private instructions, internal reasoning, or administrative conversations in
  game chat.
- **Simple customization.** God is a supplied preset, not a hard-coded identity.
  Persona is flexible creative context. Abilities are deterministic, typed,
  server-enforced contracts with refined, default-deny permissions.
  For each admitted live request, the companion adds the two selected ability
  contracts and only that requester's matching complete-grant summaries to the
  SDK-built agent context. They are metadata, not world observations or registered
  tools; both operations remain `UNIMPLEMENTED`, `UNQUALIFIED`, and `UNAVAILABLE`.
  The public God data preset is optional; configured persona use remains
  **INACTIVE / BLOCKED** behind the unqualified CHAR-02 publication control.
- **A reply within one second.** A player should receive character dialogue
  within **1,000 ms**, including queue time. It may be an acknowledgement before
  a longer answer, not a silent wait for complete investigation.
- **Clean dialogue.** RCON output, command feedback, tool traces, queue details,
  timings, and other telemetry stay out of player chat, including for operators.
- **Safe, verifiable effects.** The server checks current permission and world
  state, records consequential outcomes, and handles retries and uncertainty
  without blindly duplicating effects.

The [product specification](docs/specification.md) is the authoritative feature
contract. It covers human personas and non-human actors through epics, user
stories, and acceptance criteria, not a sequence of delivery milestones.

## Intended experience

Players should know how to address a character and continue a conversation
naturally. A quick answer needs no extra receipt; a longer investigation needs
a timely acknowledgement, truthful progress, and an answer or clear termination.
One player's repetitions must not prevent another player from being heard.
Relevant context should reach the character without processing every line of
ambient chatter as a new agent turn.

Exact, same-scope repeats coalesce for the full lifetime of live, serviceable
work. If that request terminates, matching repeats are refused through the
original capture time plus six seconds, inclusive; the refusal does not replay
or extend the old request. Changed input stays distinct. The in-memory guard is
bounded to eight keys per player and 64 globally and does not evict an unexpired
key to admit new work. These are implementation limits, not a live fairness or
latency qualification.

Longer work has a finite execution budget (30 seconds by default, configurable
from 1 to 30 seconds) and a separate fixed 15-second pending limit. A live request
may receive at most three queued/awaiting-answer notices at the selected 10-second
intervals. Expiry or cancellation ends local answer-delivery eligibility; it does
not prove that upstream provider work stopped.

The committed scope supports one configurable character per server,
vanilla-compatible chat and effects, and a small set of meaningful world
queries and actions. Custom client rendering, voice interaction, simultaneous
multiple characters, broad multi-loader/version support, a large character UI,
and autonomous unsolicited interventions are not promised features.

The selected first-response design accepts a complete OpenClaw public message
or a persona-configured integration acknowledgement; partial model streams do
not count. The initial [ability catalogue](docs/ability-contract.md) selects a
bounded player observation and controlled lightning: visual-only by default,
with separately authorized, capped, non-lethal single-target damage and no fire
or secondary world mutations. Neither ability is implemented or available yet.

These are design choices, not demonstrated capabilities. Exact integration
mechanisms and qualification evidence remain tracked in
[open decisions](docs/open-decisions.md). Ordinary completion targets are
distinct from first-response timing.

## Proposed architecture

```text
Players <-> Minecraft server mod <-> OpenClaw integration <-> Character agent
                                                               ^
                                                               |
                                                          Server admin
```

The mod owns game events, player feedback, world queries, and validated game
actions. OpenClaw owns agent activity. The integration connects the two and
keeps intentional public acknowledgements, progress, and answers separate from
private operations.

Replacing RCON alone will not solve the observed delays: the prototype's
recorded game actions took about 140 ms, while replies could wait minutes.
Queueing, conversation recognition, and stale context must be redesigned too.

## Documentation

- [Product specification: actors, epics, stories, and acceptance criteria](docs/specification.md)
- [Deterministic ability and permission contract](docs/ability-contract.md)
- [Architecture and interaction design](docs/architecture.md)
- [Open technical decisions and conservative candidate defaults](docs/open-decisions.md)
- [Measured latency baseline and failure analysis](docs/latency-baseline.md)
- [Private ability-policy administration procedure](docs/ability-policy.md)
- [CONV-06 offline evaluation procedure](tools/dialogue/PROCEDURE.md) (test-only; not live acceptance evidence)

## Distribution

The current local candidate is produced by `./gradlew assembleCandidate` and
is explicitly unqualified. `./gradlew verifyReleaseCompatibility` refuses
release assembly without approved evidence bound to the exact artifact pair.
The reproducible [God preset data ZIP](build/presets/worldspeaker-god-preset-v1.zip)
is delivered as a candidate sidecar and inside the companion package. It is
ordinary data, not a client resource pack, installer, or activation mechanism;
see the [preset instructions](presets/god/README.md). Configured God persona use
is currently unavailable. A public alpha on CurseForge and/or Modrinth is a goal,
not an existing release or confirmed platform approval.

## License

GNU General Public License v3.0. See [LICENSE](LICENSE).
