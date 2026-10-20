# Architecture and interaction design

This is a proposed design, not a description of implemented APIs. The
[README](../README.md) defines the project requirements;
[the latency baseline](latency-baseline.md) explains the problems this design
must address.

## Responsibilities

| Component | Responsibility |
| --- | --- |
| Minecraft server mod | Capture game events; acknowledge player requests; provide bounded world queries; validate and apply permitted game actions. |
| OpenClaw integration | Route Minecraft conversations into OpenClaw; manage connection and request lifecycles; return public character dialogue and action results. |
| OpenClaw character agent | Reason about events, maintain conversational memory, select available tools, and produce in-character replies. |
| Administrator interface | Use OpenClaw's supported administrative surfaces to configure and operate the agent without joining the player-facing conversation. |

The integration must use supported OpenClaw extension APIs. Its exact packaging,
supported OpenClaw version, and transport have not been selected. Do not assume
that an internal API used by the earlier prototype is a stable public contract.

## Server-only boundary

The initial implementation should use vanilla-compatible chat, sounds,
particles, entities, and other existing game mechanisms. It must not require
client-side Worldspeaker code, custom rendering, or custom client packets.
This does not prevent other mods on the server from having their own client
requirements.

Model calls, network waits, and other blocking work must never run on the
Minecraft server tick thread. World reads and changes that require that thread
must be scheduled there safely, using bounded work.

## Player interaction

### Addressing and follow-ups

Provide an explicit, discoverable interaction command, with `/pray` as a
possible God-preset alias rather than the generic mod's fixed identity.
Also recognize configured character names and aliases in natural chat,
including forms such as "help me, God" and "what do you think, God?".

Mention detection needs context: mentioning a character to another player is
not always a request. Use a bounded conversational window for follow-ups,
with enough player identity and recent context to distinguish an ongoing
exchange from unrelated chat. Exact rules remain a playtest decision.

Accepted requests should receive immediate, lightweight receipt feedback.
That feedback must not pretend that the character has already considered or
agreed to the request. When the connection is unavailable or the inbox is
full, say so rather than silently accepting work that cannot be served.

### Fair scheduling

Do not reuse the prototype's unbounded, one-message-at-a-time shared backlog.
The scheduling design should:

- Bound the amount and age of pending work.
- Coalesce repeated messages from the same player instead of generating a full
  agent turn for each repetition.
- Preserve meaningful changes in a request; coalescing is not permission to
  discard a distinct request just because it came from the same player.
- Prevent one player's message rate from starving other players.
- Prioritize relevant, time-sensitive events over low-value repeated chatter.
- Keep original event timestamps and identities when batching messages; a new
  batch must not make old evidence appear fresh or transfer player authority.
- Preserve a coherent shared world conversation and a controlled ordering of
  effects, even if multiple requests are pending.

The exact concurrency model is open. Separate player inboxes do not require
separate, mutually unaware character personalities or uncontrolled parallel
world mutations.

### Freshness

A fast response based on obsolete context is still wrong. Before delivering a
reply or applying an action, consider whether the situation has materially
changed: the player may have died, moved, disconnected, or already received
help. Refresh relevant facts and revise or cancel stale work when necessary.
Conversation replies and consequential game actions may need different expiry
policies; those policies should be explicit rather than accidental.

## Character configuration

Start with one configurable character per server. Use a stable character ID
internally so names can change and additional characters remain possible later.
Keep the initial configuration small:

| Setting | Purpose |
| --- | --- |
| Identity | Display name, chat appearance, and addressing aliases. |
| Persona | Personality, world premise, and concise behavioral guidance. |
| Attention | Relevant event subscriptions and proactive-interaction policy. |
| Capabilities | Available world queries, actions, and server-enforced limits. |
| Agent association | The OpenClaw agent and conversation routing used by this character. |

Personality is not permission. Changing God to a forest spirit should not
require rewriting the integration, and a persona prompt must never grant new
server privileges. The God preset's offerings, bargains, judgments, or other
roleplay rules should not become mandatory mechanics for every character.

Start with addressed conversation. Occasional event-driven interventions are
a proposed extension; their triggers and frequency still need playtesting.

## Agent and administrator separation

Players interact with characters, not an operator console. Player messages,
signs, books, and other world content are untrusted inputs, never administrative
instructions. Use authenticated player identities for attribution and actual
administrator permissions for privileged operations.

Only deliberately public, final character output should reach players. Do not
forward internal reasoning, tool traces, private instructions, administrative
discussion, credentials, or integration error details into Minecraft chat.
Player-facing availability and failure notices should be intentional, safe
messages rather than raw OpenClaw diagnostics.

Model credentials belong in OpenClaw, not in the Minecraft client or character
configuration. Authenticate the mod-to-integration connection and limit it to
the intended server and agent. Worldspeaker should expose structured,
capability-checked game tools rather than unrestricted RCON, shell access, or
arbitrary command execution to the character.

## State and effects

Minecraft is authoritative for current world state; server policy is
authoritative for allowed effects. OpenClaw memory provides conversational
context, not proof that a game action succeeded.

Preserve the useful reliability boundaries of the earlier integration:

- Identify events, requests, world instances, and actions explicitly.
- Validate targets, permissions, limits, and current preconditions on the server.
- Record consequential action outcomes independently of conversational memory.
- Use idempotent action handling so reconnects and retries cannot duplicate
  rewards or other effects.
- Treat an unknown outcome as unknown. Reconcile it rather than blindly
  retrying, refunding, or claiming success.
- Distinguish server submission, confirmed execution, and actual player receipt.
- Keep historical imports and recovered event backlogs from triggering new
  gameplay effects.

Durable game facts should survive an OpenClaw session reset. The storage format,
retention policy, and recovery interface remain implementation decisions. Any
future generated story or summary must not silently become authoritative
evidence for later judgments.

## Observability

Correlate each request across both components and measure these stages
separately:

1. Game event occurrence and capture.
2. Local acknowledgment.
3. Queue admission and wait.
4. Agent-turn start, model activity, and tool activity.
5. Server action submission and completion.
6. Reply submission, expiry, failure, or cancellation.

Report distributions, queue depth, coalesced messages, per-player fairness,
stale requests, and failures—not just average response time. Cross-host timing
requires synchronized clocks; measure local durations with monotonic clocks
where possible. Timing diagnostics should not require recording full private
conversation bodies or secrets.
