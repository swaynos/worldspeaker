# Worldspeaker

A server-only Minecraft mod that brings customizable AI characters to your
world, powered by OpenClaw. Players interact in-game while admins manage
agents behind the scenes—no client installation required.

## Status

**Design stage.** This repository currently contains project documentation,
not a runnable mod. Minecraft versions, mod loaders, the OpenClaw integration
API, and installation instructions have not yet been qualified.

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
- **Simple customization.** God is the initial use case, not a hard-coded
  identity. Other servers should be able to supply a different character and
  choose its capabilities without building a full character-management system.

## Intended experience

Players should know how to address a character, receive prompt feedback that
their request was accepted, and be able to continue a conversation naturally.
One player's repeated messages should not prevent another player from being
heard. Characters should respond to relevant events without spending their
time processing every line of ambient chatter.

The first release is proposed to support one configurable character per
server, vanilla-compatible chat and effects, and a small set of meaningful
world queries and actions. Custom client rendering, voice interaction,
multi-character orchestration, and SillyTavern-style configuration are outside
the initial scope.

## Proposed architecture

```text
Players <-> Minecraft server mod <-> OpenClaw integration <-> Character agent
                                                               ^
                                                               |
                                                          Server admin
```

The mod owns game events, player feedback, world queries, and validated game
actions. OpenClaw owns agent activity. The integration connects the two and
keeps public character output separate from private operations.

Replacing RCON alone will not solve the observed delays: the prototype's
recorded game actions took about 140 ms, while replies could wait minutes.
Queueing, conversation recognition, and stale context must be redesigned too.

## Documentation

- [Architecture and interaction design](docs/architecture.md)
- [Measured latency baseline and failure analysis](docs/latency-baseline.md)
- [Development roadmap and open decisions](docs/roadmap.md)

## Distribution

The intended deliverables are a server mod JAR, its OpenClaw integration, a
God preset, and clear administrator documentation. A public alpha on
CurseForge and/or Modrinth is a goal, not an existing release or confirmed
platform approval.

## License

GNU General Public License v3.0. See [LICENSE](LICENSE).
