# Development roadmap

Worldspeaker is currently documentation-only. The phases below are proposed
work, not completed functionality. The required direction is a server-only mod,
OpenClaw as the agent harness, and simple character customization.

## 1. Qualify the first integration target

- Verify the pilot server's actual Minecraft version, loader, Java runtime,
  and deployment constraints.
- Select one initial Minecraft version and loader rather than promising
  multi-loader support immediately.
- Verify the supported OpenClaw extension APIs, runtime version, agent routing,
  tool lifecycle, and private administrator interaction surface.
- Choose the authenticated transport and packaging boundary between mod and
  OpenClaw integration.
- Define the request lifecycle, bounded inbox, coalescing rules, expiry, and
  cross-component timing fields before reproducing the prototype's queue.

**Exit condition:** Documented, tested compatibility assumptions and a small
protocol proposal. Do not publish installation instructions for unqualified
combinations.

## 2. Build a conversation vertical slice

- Capture an explicitly addressed player message in the server mod.
- Provide immediate receipt or an honest unavailable/busy response.
- Route the request to a character through OpenClaw and return only its public
  final dialogue to Minecraft.
- Add configurable names and aliases, an explicit interaction command, and
  bounded follow-up recognition.
- Include fair scheduling, duplicate-message coalescing, stale-work handling,
  and timing instrumentation in this slice—not as a later optimization.
- Exercise two-player traffic, disconnects, and an unavailable agent.

**Exit condition:** A vanilla-compatible client can converse without a client
mod, and repeated messages from one player do not starve another player.

## 3. Add a small, meaningful capability set

- Expose bounded observations of relevant players and world state.
- Add a few structured, server-validated actions rather than raw commands.
- Keep personality separate from capabilities and enforce permissions, limits,
  target checks, and freshness on the server.
- Record outcomes and handle retries, reconnects, uncertain execution, and
  recovery without duplicate effects.
- Supply a God preset; make its special roleplay rules optional rather than
  mandatory for all characters.
- Decide which events justify proactive attention through playtesting.

**Exit condition:** The character can take useful, verifiable actions without
leaking admin context, accepting player text as authority, or falsely claiming
that an action succeeded.

## 4. Playtest the experience

Run the [baseline regression scenarios](latency-baseline.md#required-regression-scenarios)
with multiple players. Evaluate conversational quality, discoverability,
fairness, stale responses, failure feedback, and server tick health—not just
whether the mod loads.

Candidate performance targets, to validate against the chosen provider and
representative load:

| Area | Proposed acceptance target |
| --- | --- |
| Local receipt feedback | p95 within 250 ms of server-side request capture, without waiting on OpenClaw. |
| Simple conversation | Median response within 5 seconds and p95 within 10 seconds on the selected test setup; tool-heavy turns measured separately. |
| Fairness under repetition | Another player's request meets the conversation target while one player repeats an identical request seven times in six seconds. |
| Backpressure and freshness | Pending work remains bounded; obsolete work is expired, refreshed, or cancelled rather than replayed indefinitely. |
| Server responsiveness | No network/model waits on the tick thread; measure tick time under agent traffic against an idle-integration baseline. |

These are design targets, not measured Worldspeaker results or guarantees for
every model and hosting environment. Record the model/provider, server hardware,
player load, sample size, stage timings, and failures with each result.

**Exit condition:** Repeatable evidence that the new interaction is better than
the predecessor, including during overload and outages, plus a playtest judgment
that the character is enjoyable rather than merely technically functional.

## 5. Package a public alpha

- Produce the server mod JAR and separately documented OpenClaw integration.
- Document compatible Minecraft, loader, Java, and OpenClaw versions; explain
  that players do not install Worldspeaker on their clients.
- Include a minimal setup guide, character configuration reference, God preset,
  troubleshooting, privacy/data-flow explanation, recovery guidance, and upgrade
  expectations. Explain any model-provider costs and external service needs.
- Preserve the repository's GPL-3.0 license and review dependency and bundled
  asset licensing before distribution.
- Verify project-name conflicts and current CurseForge and/or Modrinth
  submission requirements, including how to describe the companion integration.
- Publish only after installation has been tested from a clean server setup.

**Exit condition:** A new administrator can install, configure, operate, and
recover the supported alpha without access to the original private deployment.

## Decisions still open

- First Minecraft version, loader, Java target, and OpenClaw version.
- Exact extension APIs, transport, authentication, and companion packaging.
- Generic interaction command and natural-addressing/follow-up rules.
- Conversation/session concurrency and fair scheduling details.
- Default event subscriptions, proactive behavior, actions, and limits.
- Request expiry, durable state format, retention, and reconciliation interface.
- Configuration format and first-release distribution platform.

Multiple simultaneous characters, voice, client-specific visuals, broad
multi-version support, and a large persona-management interface are not initial
release goals. Keep the first implementation small enough to test the core
experience well.
