# Architecture and interaction design

This is a design supporting the [product specification](specification.md), not
a description of implemented APIs. The specification owns feature promises and
acceptance criteria; the [ability contract](ability-contract.md) owns the game
authorization boundary. [Open decisions](open-decisions.md) records unqualified
mechanisms and candidate defaults. Nothing here sequences delivery milestones.

## Responsibilities and authority

| Component | Responsibility | Not its authority |
| --- | --- | --- |
| Minecraft server mod | Capture attributed events; admit bounded work; deliver intentional public messages; expose bounded observations; validate and apply effects. | Model reasoning, model-provider credentials, or trusting an agent's claimed execution result. |
| OpenClaw integration | Authenticate and bind peers; route attributed conversations; manage requests, public messages, tools, connection state, and reconciliation. | Arbitrary administrative command proxying or inventing permissions from persona. |
| OpenClaw character agent | Reason, maintain conversational context, request advertised abilities, and emit deliberate acknowledgements, progress, and answers. | Current world truth, durable action evidence, or final permission decisions. |
| Administrator surfaces | Configure and operate through authorized local/server controls and supported private OpenClaw interaction. | Automatically publishing private activity or bypassing game authorization. |
| Durable-state subsystem | Preserve consequential intent/outcomes and resolve duplicates and interrupted work. | Treating a generated summary or missing receipt as proof of an effect. |
| Diagnostics subsystem | Correlate timing, health, service outcomes, and failures privately. | Sending tool traces to players or needing unrestricted game powers. |

The actor catalogue in the specification distinguishes players, authors,
administrators, delegated staff, operators, maintainers, and machine actors.
These boundaries are architectural requirements, not merely UI labels.

## Compatibility and server-only boundary

Minecraft Java **1.21.11** is the selected target. Loader, exact Java runtime,
OpenClaw version, companion packaging, and supported APIs require qualification.
Do not infer server compatibility from a client configuration or an old
prototype's internal endpoint.

Use vanilla-compatible chat, sounds, particles, entities, and other existing
mechanisms for the selected catalogue. Worldspeaker requires no client mod,
resource pack, custom rendering, or custom packets. Other server mods may have
their own independent client requirements.

Network/model waits, reconnects, and blocking persistence stay off the tick
thread. World work is scheduled through the selected loader's supported server
mechanism with finite bounds. When durable preparation precedes an effect,
complete that preparation asynchronously, then schedule and revalidate the game
work; do not make the tick thread wait on storage or a remote service.

## Logical interaction flow

```text
Authenticated player invocation
    -> early timestamp and event attribution
    -> recognition, authorization, bounded admission
    -> intentional first public reply within 1,000 ms
    -> scheduled substantive OpenClaw work
         -> bounded observations / validated effects as permitted
         -> deliberate progress when appropriate
    -> fresh, audience-checked answer or terminal notice

Private administrator <-> supported OpenClaw administrative surface
Private operator      <-> authorized configuration / diagnostics / recovery
```

The diagram describes required behavior, not chosen transport or thread topology.
A ready answer may be the first reply; it does not require an extra receipt.
The fast-response mechanism may be coordinated with substantive work, but it
must not wait for a previously active investigation to finish. It must not
create a second, mutually unaware character runtime.

Whether a persona-configured acknowledgement produced by the integration is
acceptable remains unresolved. Do not substitute one for an OpenClaw reply and
claim the one-second requirement has been met. If supported OpenClaw mechanisms
cannot satisfy the required interaction, report that conflict rather than
relabeling local queue telemetry as character dialogue.

The latency contract includes queue time and distinguishes server dispatch from
client display. Deadline misses, unavailable/busy outcomes, completed answers,
and acknowledgements are separate measurements.

## Addressing, follow-ups, and attention

- Provide a discoverable generic interaction command; `/pray` may be a God
  preset alias, not a fixed identity for the whole mod.
- Recognize configured names and aliases in natural direct address, including
  beginning, middle, and end of a sentence. Distinguish mention from invocation.
- Bound follow-up recognition by time and attributed conversation context.
  A message from another player must not inherit the first player's identity.
- Capture only the context needed for configured conversation and freshness.
  Subscription does not mean every event creates an agent turn.
- Do not turn ambient chat into an unbounded feed. Context-invalidating events
  can update pending work without asking the model to answer each event.
- Unsolicited interventions are disabled. Their behavior is a proposed extension,
  not an implied consequence of exposing an event subscription setting.

## Request scheduling and lifecycle

Logical request states distinguish captured, admitted, queued, active, terminal,
expired, and cancelled work. An action becoming unknown is not a successful
terminal answer to the player. Exact wire names and transitions remain to be
defined against supported OpenClaw APIs.

Required scheduling properties:

- Finite per-player and global pending counts, age limits, and execution budgets.
- Coalescing of identical repetitions while preserving changed requests or
  explicitly refusing them; no silent disposal of distinct work.
- Fair service between players, including the measured repetition regression.
- Original event identity, original time, requester, and world preserved in
  batches, retries, and deduplication.
- Truthful queued/active progress, explicit terminal outcomes, and no indefinite
  accepted work after a quick acknowledgement.
- Late results checked for expiry, cancellation, changed audience, and freshness.
- Controlled world mutation regardless of reasoning concurrency.

A single active substantive turn and round-robin player service are conservative
candidate defaults, not a reason to serialize the first-response path behind
long work. Exact concurrency must be supported and tested. Separate inboxes do
not imply separate character personalities or uncontrolled parallel effects.

## Character content and authority configuration

| Configuration area | Meaning |
| --- | --- |
| Stable identity | Character ID, display name, aliases, and vanilla-compatible chat presentation. |
| Persona | Rough prose for personality, premise, and behavioral guidance. Never executable policy. |
| Attention | Allowed context subscriptions and finite attention limits; unsolicited intervention remains disabled. |
| Abilities | Named, typed operations with default-deny grants, target/delegation policy, limits, and current-state checks. |
| Agent association | Qualified OpenClaw agent binding and explicitly separated routing/context. |
| Operational policy | Finite queue/budget settings, credential references, private diagnostics, and recovery behavior. |

One character per server is committed scope. A stable ID allows renaming without
changing historical association; it is not a promise of multi-character support.
God's optional roleplay conventions remain persona/preset content. Invalid
configuration is rejected coherently and privately, without widening policy or
partially applying grants. Reload/restart behavior must be documented.

## Public output versus private activity

Public character messages are explicit, bounded, audience-addressed messages of
four logical kinds: acknowledgement, progress, answer, and safe failure or
availability notice. Each is a complete intentional publication, not a raw
stream of model fragments or a tool trace. Output deduplication and lifecycle
checks apply independently of action deduplication.

Never forward RCON/console output, command receipts, raw command feedback,
request IDs, timings, queue telemetry, internal reasoning, private instructions,
credentials, or administrative conversation. This includes indirect command
feedback broadcasts received by operator players. Avoid the leaking execution
path; do not conceal it by globally muting unrelated server diagnostics.

Private OpenClaw interaction and public player interaction require separate
routing and context boundaries, not only persona instructions. Authenticated
admin text is still not automatically an instruction to publish or act in game.
Players, signs, books, and other world text are untrusted data. Formatting and
audience controls must not allow content to impersonate authoritative envelopes.
Qualify OpenClaw's tool/context policy for player-origin turns as well: the game
ability boundary is not sufficient if those turns inherit unrelated operator
tools or private administrative sessions through the harness.

## Game tools and current state

The [ability contract](ability-contract.md) defines required schemas, delegation,
deny precedence, quotas, revocation, action identity, and structured outcomes.
There is no unrestricted RCON, shell, or arbitrary-command character capability.
Even read-only tools must have explicit bounds and appropriate data permissions.

Minecraft is authoritative for current facts. Recheck relevant state before
dialogue and immediately before effects: death, movement, dimension changes,
departure, or another action may have invalidated the original request. Dialogue
and effects may have different expiry rules, but neither may use a new batch
timestamp to make old evidence fresh.

Discovery describes current availability, not an irrevocable grant. The server
checks current policy and material preconditions at execution and atomically
enforces applicable resource reservations. Effects have controlled ordering;
multi-step operations report real partial outcomes rather than assumed rollback.

## Authentication and transport boundary

Use supported OpenClaw extension APIs and qualify the exact lifecycle and
private/public surfaces. Select an authenticated transport bound to the
intended server/world/character/agent, with appropriate protection for its
network exposure. Document schema/version negotiation, replay handling,
timeouts, cancellation, and reconnect limits before claiming compatibility.

Model-provider credentials live in OpenClaw. Connection credentials are local
operational secrets, not persona or public examples. Public project docs explain
generic configuration and trust boundaries, never a private server's topology,
identity, access details, or deployment commands.

## Durability, unknown outcomes, and historical input

Identify invocations, requests, events, actions, public messages, and world
instances distinctly. Durable consequential outcome records are independent of
OpenClaw memory and survive agent-session resets. Record enough intent and
outcome evidence to reconcile interruption without collecting private dialogue
by default.

Idempotent handling returns known state for a repeated identical action ID and
rejects conflicting payloads. When a crash spans game execution and persistence,
execution may be unknown. Do not retry, refund, compensate, or announce success
without authoritative evidence and relevant authorization. Do not promise
exactly-once behavior across a boundary that cannot commit atomically.

Imports, recovered backlogs, and generated summaries retain historical origin.
Reading or replaying them cannot trigger new gameplay effects. Storage format,
retention, reconciliation surfaces, backup/restore semantics, and migrations
remain explicit decisions, including preservation of unresolved evidence.

## Private observability

Correlate these stages without requiring full private conversation bodies:

- Original game event and server capture.
- Recognition, admission/disposition, queue admission, and queue wait.
- First complete public reply and its origin.
- Agent-turn/model/tool activity where supported telemetry exposes it.
- Action validation, submission, execution, and known or unknown outcome.
- Progress, final reply, expiry, rejection, cancellation, and failure.

Report maxima and deadline misses as well as distributions, completions,
per-player fairness, queue count/age, coalescing, stale requests, failures, and
tick health. Unavailable timing remains unknown. Local durations use monotonic
clocks; cross-host measurements require explicit synchronization assumptions.
Only authorized operational surfaces receive telemetry.
