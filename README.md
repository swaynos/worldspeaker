# Worldspeaker

A server-only Minecraft mod that brings customizable AI characters to your
world, powered by OpenClaw. Players interact in-game while admins manage
agents behind the scenes—no client installation required.

## Status

**Specification stage.** This repository contains product and design
documentation, not a runnable mod. **Minecraft Java 1.21.11** is the selected
target. The loader, exact Java runtime, supported OpenClaw APIs, and installation
combination have not yet been qualified.

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

The committed scope supports one configurable character per server,
vanilla-compatible chat and effects, and a small set of meaningful world
queries and actions. Custom client rendering, voice interaction, simultaneous
multiple characters, broad multi-loader/version support, a large character UI,
and autonomous unsolicited interventions are not promised features.

The source of the fast acknowledgement, exact ability catalogue, and technical
integration choices remain explicit [open decisions](docs/open-decisions.md).
The one-second requirement is not yet a demonstrated capability, and ordinary
completion targets are distinct from first-response timing.

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

## Distribution

The intended deliverables are a server mod JAR, its OpenClaw integration, a
God preset, and clear administrator documentation. A public alpha on
CurseForge and/or Modrinth is a goal, not an existing release or confirmed
platform approval.

## License

GNU General Public License v3.0. See [LICENSE](LICENSE).
