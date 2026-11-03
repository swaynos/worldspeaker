# Architecture and interaction design

This is a design supporting the [product specification](specification.md), not
a description of implemented APIs. The specification owns feature promises and
acceptance criteria; the [ability contract](ability-contract.md) owns the game
authorization boundary. [Open decisions](open-decisions.md) records unqualified
mechanisms and candidate defaults. Nothing here sequences delivery milestones.

## Responsibilities and authority

| Component | Responsibility | Not its authority |
| --- | --- | --- |
| Minecraft server mod | Capture attributed events; admit bounded work; own the local first-response/publication gate; expose bounded observations; validate and apply effects. | Model reasoning, model-provider credentials, or trusting an agent's claimed execution result. |
| OpenClaw integration | Authenticate and bind peers; route attributed conversations; manage requests, public messages, tools, connection state, and reconciliation. | Arbitrary administrative command proxying or inventing permissions from persona. |
| OpenClaw character agent | Reason, maintain conversational context, request advertised abilities, and emit deliberate acknowledgements, progress, and answers. | Current world truth, durable action evidence, or final permission decisions. |
| Administrator surfaces | Configure and operate through authorized local/server controls and supported private OpenClaw interaction. | Automatically publishing private activity or bypassing game authorization. |
| Durable-state subsystem | Preserve consequential intent/outcomes and resolve duplicates and interrupted work. | Treating a generated summary or missing receipt as proof of an effect. |
| Diagnostics subsystem | Correlate timing, health, service outcomes, and failures privately. | Sending tool traces to players or needing unrestricted game powers. |

The actor catalogue in the specification distinguishes players, authors,
administrators, delegated staff, operators, maintainers, and machine actors.
These boundaries are architectural requirements, not merely UI labels.

## Compatibility and server-only boundary

Minecraft Java **1.21.1** is the selected target on **Fabric Loader** (server-only)
with **Java 21 LTS**. OpenClaw connects via an authenticated bidirectional WebSocket.
These are design selections, not verified compatibility. Exact Fabric Loader,
dependencies, Java build, OpenClaw version, extension packaging, and supported
APIs require qualification. Do not infer server compatibility from a client
configuration or an old prototype's internal endpoint.

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
    -> intentional requester-targeted reply within 1,000 ms
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

### First-response ownership and deadline

The integration owns one first-response decision per invocation. Its local
admission/delivery component in the Minecraft mod starts the deadline at server
capture, so the fallback requires neither a remote round trip nor a model call.
Validated persona templates are cached locally; supported substitutions contain
only audience-safe display data, not arbitrary model text, commands, private
instructions, or executable policy. Template/configuration failure makes service
unavailable rather than enabling an unvalidated fallback.

1. Record `t0`, attribution, and a bounded admission outcome. Admit only when
   the local queue and authenticated agent connection are serviceable; unknown
   availability, overflow, or denial gets a safe disposition, not an acceptance.
2. A complete, requester-targeted, audience-checked OpenClaw message may claim
   the first-response slot before `t0 + 750 ms`. Buffer private stream fragments;
   a first token or incomplete answer cannot claim that slot.
3. At the 750 ms cutoff, claim any still-empty slot for a local persona-configured
   acknowledgement of the actual queued/active state. Reserve the remaining
   250 ms for validation and dispatch by `t0 + 1,000 ms`; do not wait until the
   deadline to discover that model generation is slow. Emit earlier when known
   queueing makes waiting pointless. These timing budgets still need load tests.
4. Serialize slot selection, final lifecycle checks, and publication through one
   local delivery gate. A definite failure before dispatch is not recorded as
   delivered; if time and audience validity remain, send the safe alternative.
   An unknown dispatch outcome requires reconciliation by message identity, not
   an automatic alternative that may duplicate publication. Suppress a losing
   acknowledgement, but retain a late valid answer as terminal dialogue.
5. If availability or admission changes before dispatch, publish the truthful
   disposition instead. After an acknowledgement, later failure gets a terminal
   notice; it does not retroactively become successful character service.

The source implementation attaches an immutable receipt stamp to each pinned
serverbound chat/command packet and carries it through message copies or a scoped
command runnable. It does not correlate by message text. Handler-entry,
admission, queue, cutoff, dispatch, send, and deadline-settlement stages remain
separate; a missing receipt stamp fails admission rather than moving `t0` later.
One invocation-owned atomic first-publication slot is shared by ingress, the
terminal mailbox, and dispatch. A normal return from the local connection API is
the server-dispatch observation; an exception after entering that API is
`UNKNOWN`, not a retryable unsent result. A successful dispatch at exactly
1,000 ms is on time; no dispatch settled at that deadline is a miss.

The local scheduler retains the 750 ms fallback cutoff and 1,000 ms deadline,
scans at most 17 live records, and shares at most eight operation starts and a
1 ms start window across callbacks and tick work. When replies and reserved
expiry/context/forwarding work both need service, six starts are reserved for
replies and two for the latter; unused starts may be loaned. These are bounded
offline implementation properties, not a deadline guarantee under arbitrary
server stalls. Numeric evidence retains all invocation outcomes, direct maximum
dispatch latency, and deadline misses; histogram ceilings are not maxima.

No JUnit, local build, or synthetic load fixture proves that the pinned Fabric
mixins run in a live server, that a client displays the message, or that any
provider returns a fresh inference. Those remain separate runtime and human
qualification evidence.

Record publication identity and reply source, and supply locally generated
dialogue to the same attributed OpenClaw conversation before its next substantive
turn. Specifically, the initial invocation payload at `t0` supplies the candidate
local acknowledgement template and cutoff time to the OpenClaw integration; if the
local integration fires `dialogue.ack` at the 750 ms cutoff, the integration suppresses
redundant introductory greetings in any subsequent `dialogue.message`. It must not
create a second character memory or cause the model to repeat the acknowledgement.
Reconnect handling reconciles public messages separately from actions. Raw queue
telemetry and machine receipts never become dialogue.

The local timer cannot prove an absolute deadline under server stalls or client
network delays. Record misses rather than treating the selected fast-path design
as measured compliance. Both queued and active work still need useful completion.

The latency contract includes queue time and distinguishes server dispatch from
client display. Deadline misses, unavailable/busy outcomes, completed answers,
and acknowledgements are separate measurements.

## Addressing, follow-ups, shared gameplay context, and attention

- Provide a discoverable generic interaction command; `/pray` may be a God
  preset alias, not a fixed identity for the whole mod.
- Recognize configured names and aliases in natural direct address, including
  beginning, middle, and end of a sentence. Distinguish mention from invocation.
- Bound follow-up recognition by time and attributed conversation context.
  A message from another player must not inherit the first player's identity.
- Capture only the context needed for configured conversation and freshness.
  Subscription does not mean every event creates an agent turn.
- Optional attention is restart-only and disabled by default. It can capture only
  explicit player-connection, dimension, death, respawn, and sampled block-change
  facts for the at-most-32 granted UUIDs. Records are bounded, expire locally,
  and are attached only to a separately admitted live request by that requester;
  capture never starts a turn. Sampled movement is not continuous tracking.
- Attention records contain event kind, player identity, dimension, original
  capture times, and only contract-defined event details. They contain no
  coordinates or generalized world state. Loss reason/revision summaries and
  counters are private; they are not sent to players or emitted per event.
- Mandatory dialogue freshness is separate from attention and remains enabled
  with empty subscriptions, rate exhaustion, expiry, or capture loss. It rotates
  local player tokens on supported lifecycle events and block displacement,
  performs bounded round-robin sampling of at most four granted UUID slots per
  tick inside the existing eight-operation/1 ms start budget, and rechecks current
  identity/alive/dimension/block position before forwarding and final publication.
  This detects current displacement, not every between-sample round trip; it is
  not continuous movement tracking, full WORLD-03 need-satisfied detection, or
  effect freshness.
- All admitted players for one server/world/character/agent route to the same
  binding-derived `per-group` OpenClaw session. This deliberately shares game
  history; it is not a per-player memory or a confidential DM. Each primary
  invocation supplies its own current requester authority and requester-only
  reply audience. Earlier player text, operator claims, or remembered effects
  never grant authority to a later invocation.
- A locally dispatched acknowledgement is attributed and injected into that
  same shared session through the supported next-turn injection seam before a
  later substantive game turn. It does not create a separate character memory
  or an additional turn.
- `/worldspeaker ask` input is not broadcast as vanilla chat, but it and its
  targeted reply still enter shared gameplay context. Natural-address and
  recognized follow-up text remains public vanilla signed chat. Targeting output
  to one current connection does not make game dialogue confidential.
- Private administration uses a separate supported Control UI/session and is
  never intentionally copied into gameplay. However, the pinned host's
  workspace/bootstrap/memory isolation from private admin context is unverified;
  separate session keys do not prove isolation. Stop before deployment if the
  actual host cannot enforce this boundary. No model-level confidentiality is
  promised.
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

The selected scheduling policy is one active substantive turn per character and
round-robin service between non-empty player queues, FIFO within each player's
queue. At most two pending distinct requests per player and sixteen globally are
retained, excluding the active request. A newly queued player joins the rotation
without resetting another player's waiting age; a repeat cannot rotate a player
or renew age. For an admitted head, at most 15 other queued starts may precede
it, in addition to the active request. With only A and B queued, at most one
additional A start may precede B. These are queue-order bounds, not a wall-clock
completion guarantee: a long active request can consume B's fixed 15-second
pending lifetime, and expiry is not successful service.

An exact repeat of a still-live, serviceable request coalesces for that request's
entire live lifetime, not just an initial burst window. Once it terminates, a
repeat with the same player, connection/generation, world, binding, origin,
exact text, and follow-up anchor is refused as `RATE_LIMITED` / `REPEAT_GUARD`
through the original request's `t0 + 6 s`, inclusive. At `t0 + 6 s + 1 ns`,
terminal work no longer suppresses a new ask; live work still coalesces. Repeats
do not renew the fixed interval, replay a result, or consume new-work tokens.
Changed input is neither merged nor used to replace accepted work. The
in-memory guard is limited to eight keys per UUID and 64 globally; it never
evicts an unexpired key, and its capacity refusal is distinct from the repeat
refusal. Context-invalidating events may retire work immediately but do not
create extra substantive turns. Overflow receives an explicit bounded
disposition. These offline policy and queue-order properties remain unqualified
and must demonstrate useful fair completion, not merely fast acknowledgements.

The local first-response path never waits behind a substantive turn. Separate
inboxes do not imply separate character personalities or uncontrolled parallel
effects. Count, age, execution, and repeat-suppression policy are tracked in
[open decisions](open-decisions.md), not advertised as measured capacity.

## Character content and authority configuration

| Configuration area | Meaning |
| --- | --- |
| Stable identity | Character ID, display name, aliases, and vanilla-compatible chat presentation. |
| Persona | Optional rough prose for personality, premise, and behavioral guidance. Never executable policy; production loading stays blocked by the CHAR-02 publication gate. |
| Attention | Allowed context subscriptions and finite attention limits; unsolicited intervention remains disabled. |
| Abilities | Finite policy syntax with default-deny grants, target/delegation limits, and current-state requirements; the two selected operations remain unavailable. |
| Agent association | Configured OpenClaw agent binding and explicitly separated routing/context; the supported API/version still requires qualification. |
| Operational policy | Finite queue/budget settings, credential references, private diagnostics, and recovery behavior. |

One character per server is committed scope. A stable ID allows renaming without
changing historical association; it is not a promise of multi-character support.
God's optional roleplay conventions remain persona/preset content. Invalid
configuration is rejected coherently and privately, without widening policy or
partially applying grants. Reload/restart behavior must be documented.

Startup configuration is one strict UTF-8 JSON bundle, schema version 1. The Java
`config/worldspeaker.properties` file is an explicit bootstrap containing only
`schemaVersion=1` and one absolute `configFile` reference; the OpenClaw channel
section contains only boolean `enabled` and one absolute `configFile` reference
to that same bundle. The bundle has exact `binding`, `server`, `companion`, and
`abilityPolicy` objects plus one `credentialFile` and optional `personaFile`.
Inline legacy properties, mixed formats, duplicate/unknown fields, null or
coerced values, unsupported versions, and implicit fallback are rejected. The
format is an incompatible migration: operators prepare the whole bundle and
credential privately; the software does not import, merge, rewrite, or change
file modes. Exact schemas and finite bounds are in the
[startup bundle schema](../compatibility/startup-config-v1.schema.json).

The bootstrap is limited to 4,096 bytes; the bundle to 32,768 bytes; and the
credential to 86 bytes encoding 32–64 random bytes as canonical unpadded
base64url. Local references are canonical absolute paths, not URLs or expanded
paths. Files must be service-owned regular single-link mode-`0600` files under a
service-owned mode-`0700` directory with trusted non-writable ancestors. Java
configuration I/O runs off the tick thread; companion loading is asynchronous
before readiness. One validated immutable snapshot retains the SHA-256 of the
exact bundle bytes. That private revision identity is distinct from the installed
deployment-manifest hash and does not confer authority. Editing files does not
change an active generation; a complete staged replacement is applied only by a
coordinated restart that retires old admission, requests, callbacks, and
connection targets. Cancellation does not establish upstream completion or
effect rollback.

The Java startup reader delegates bootstrap, bundle, and credential reads to a
packaged Node descriptor helper on the configuration-I/O executor, off the tick
thread. The helper is capped at 16,384 bytes and checked against its embedded
SHA-256 pin. Java caps the request at 4,096 bytes, applies a two-second helper
exchange deadline, and validates the owner-only result. The helper requires
actual Node.js `24.22.1` from the trusted service-account `PATH`; only absolute
entries are considered, and absent/unusable `PATH`, missing Node, or a wrong
version fails configuration loading closed. Startup does not install Node. This
Node pin is also the OpenClaw host runtime candidate.

For referenced files the helper uses `O_NONBLOCK` and `O_NOFOLLOW`, performs
bounded reads, and compares descriptor, path, and ancestor metadata before and
after reading. Offline regression coverage includes FIFO rejection and
replacement, growth, hard-link, and ancestor changes. These checks rely on the
service-account trust boundary and do not isolate against a hostile process with
the same service UID.

This is implementation and offline test evidence, not runtime qualification.
Pinned-Linux T4, vanilla-client testing, actual OpenClaw host/workspace
isolation, the CHAR-02 publication gate, and runtime ability testing remain
blocked; both selected game abilities remain unavailable. Do not interpret
local FIFO/race tests as proof that any qualification gate passed.

The policy parser accepts only complete finite v1 grant/denial records and fixed
protection mode. For each admitted live request the companion projects the two
typed contracts and only that authenticated requester's matching complete-grant
summaries into the exported SDK `BodyForAgent` context. The summaries are bounded
to 32 per ability and omit credentials, paths, and config hashes. This is
model-readable metadata, not a world observation or execution grant. Both entries
stay `UNIMPLEMENTED`, `UNQUALIFIED`, and `UNAVAILABLE`; no ability tool is
registered, and player turns keep `toolsAllow: []`. Validity does not establish
runtime authorization, an external-claims adapter, or any game-effect safety
proof. A configured `personaFile` remains a hard startup failure before credential
access under CHAR-02; omitting it is not evidence that persona publication is
qualified.

## Public output versus private activity

Player-facing character messages are explicit, bounded, requester-addressed messages of
four logical kinds: acknowledgement, progress, answer, and safe failure or
availability notice. Each is a complete intentional publication, not a raw
stream of model fragments or a tool trace. Output deduplication and lifecycle
checks apply independently of action deduplication.

Never forward RCON/console output, command receipts, raw command feedback,
request IDs, timings, queue telemetry, internal reasoning, private instructions,
credentials, or administrative conversation. This includes indirect command
feedback broadcasts received by operator players. Avoid the leaking execution
path; do not conceal it by globally muting unrelated server diagnostics.

Private OpenClaw administration and shared gameplay use separate route keys and
must have separate context boundaries, not only persona instructions. Actual
host workspace/memory isolation remains an explicit blocker. Authenticated admin
text is still not automatically an instruction to publish or act in game.
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

Use supported OpenClaw extension APIs with an authenticated bidirectional
WebSocket transport bound to the intended server/world/character/agent. Plain
`ws://` is limited to loopback; every non-loopback connection requires `wss://`
with certificate/peer validation. Connections use a distinct, scoped bearer or
pre-shared credential on the handshake, never credentials in URLs or persona.
The exact bundle-byte SHA-256 and `configurationHandshakeVersion=1` are bound into
the challenge, client, and welcome HMAC transcripts. Both peers must have the
same digest before READY; a deployment-manifest hash is not a substitute, and
missing/old handshake fields fail without downgrade. This binds one local config
generation but is neither authority nor a credential. Same secret with different
bundle bytes and different secrets with matching bundle bytes fail coherence or
authentication. Credential provisioning, rotation, revocation, and reconnect
authentication still require a documented, tested lifecycle. This transport is
a Worldspeaker integration design, not a claim that an existing OpenClaw endpoint
implements it.

The selected, unqualified dialogue contract is strict
`worldspeaker.compat.v5` / `conv-06.1`, paired with manifest revision
`abil-01.1`: immutable primary events, inert attention events and loss status in
bounded context batches, one previously dispatched queued-progress record,
distinct event/invocation/request/public-message identities, a player-only reply
audience, exact schemas, and the captured `abilityPolicyRevision`. Every
production request takes that revision from the immutable startup policy
snapshot; the authenticated matching bundle supplies policy contents, and the
frame revision alone is not authority. A separate active progress frame is
attributed to its retained request and supplied to the same shared game context
before the next substantive turn. It cannot create a turn or complete a request.
Completed public answers are deliberate text-only finals, sanitized without
truncation and capped at 1,980 UTF-16 units. The contract preserves v2/v3/v4
schemas and rejection evidence and never downgrades. `ability.request` and
`ability.response` remain unavailable. Binding and requester fields originate
in authenticated components, never model arguments. The pinned SDK's exported
route/session-key and next-turn injection seams are implementation choices, not
actual-host qualification. Ordering, replay behavior, heartbeat expiry, bounded
reconnect/backoff, and requester-only publication still require tests; actual
shared-context privacy and host isolation remain unqualified.

Progress is intentionally bounded and integration-origin: one queued notice may
be sent 10 seconds after capture; active notices may be sent at 10 and 20 seconds
after forwarding. No request receives more than three notices, only one notice
may start per tick, and existing tick work limits remain in force. The fixed
notices say only that work remains queued or that it was forwarded and is still
awaiting a complete answer. They do not assert model/tool activity. Successful
progress dispatch is context evidence, not an additional model turn or useful
completion.

The current lifecycle validates `executionBudgetSeconds` at startup (integer
1–30, default 30) and snapshots it at admission. Pending age remains fixed at 15
seconds from original capture. Activation sets one nonrenewing deadline; the
absolute cap is original capture plus 15 seconds plus the snapshotted budget.
Every final/progress publication gate uses a strict expiry check, so a result at
or after the effective deadline cannot revive the request. A smaller budget may
expire before a later progress interval, in which case no artificial notice is
sent. Expiry/cancellation revokes local delivery eligibility and sends the
existing attributed v5 cancel for forwarded work. It is local cancellation only:
it does not prove that OpenClaw or a configured provider stopped its work. ACKs,
progress, retries, reconnection, and callbacks never extend these deadlines.

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
reconciliation surfaces, backup/restore semantics, and migrations remain explicit
decisions. The initial ability contract retains consequential identity/intent/
outcome evidence without automatic deletion and disables new effects at a finite
configured storage ceiling rather than discarding safety records. Ceiling values,
storage implementation, and any future pruning policy require qualification;
unresolved evidence cannot be silently dropped.

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
