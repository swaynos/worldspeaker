# Worldspeaker product specification

## Purpose and authority

This is the authoritative specification of the features Worldspeaker promises
to deliver. It describes the whole committed product scope through epics,
personas, system actors, and acceptance criteria. Epic order is organizational,
not a delivery sequence, priority ranking, or set of milestones.

**Status: specification, not implemented functionality.** The repository does
not yet contain a runnable mod. A requirement here is not evidence that it has
been implemented, tested, or qualified against OpenClaw.

- This document owns product scope, user stories, and shared acceptance rules.
- [The ability contract](ability-contract.md) specifies the deterministic
  authorization and execution boundary required by the ability stories.
- [Architecture](architecture.md) explains responsibilities and design constraints.
- [Open decisions](open-decisions.md) distinguishes unresolved choices and
  candidate defaults from settled requirements.
- [The latency baseline](latency-baseline.md) is evidence about the predecessor,
  not a benchmark of Worldspeaker.

An unresolved technical mechanism does not waive a product requirement. If a
supported integration cannot meet a requirement, report the conflict and obtain
an explicit scope decision; do not silently weaken the acceptance criteria.

## Product scope

Worldspeaker is a server-only Minecraft Java mod connecting players and one
configurable character to OpenClaw. The selected Minecraft target is **1.21.1**
on **Fabric Loader** (server-only) with **Java 21 LTS**. OpenClaw communicates
via an authenticated bidirectional WebSocket transport. These are selected
designs, not a qualified release combination. Exact loader, dependency, Java
build, OpenClaw version, and supported extension APIs still require recorded
compatibility evidence.

The committed scope includes addressed conversation, natural follow-ups,
responsive and fair request handling, configurable persona, deterministic
ability grants, bounded world observations and effects, clean public dialogue,
private administration, reliable state and recovery, operational diagnostics,
and independently usable distribution and documentation.

God is a supplied preset, not the generic mod's fixed identity. Persona is
flexible context; abilities are enforceable contracts. Neither the project nor
its installation instructions may depend on an original private deployment.
Private server names, addresses, credentials, topology, and deployment procedures
belong outside this project.

## Personas and system actors

Roles describe a context of interaction, not necessarily different people. One
person may be an administrator, operator, author, and player. Being an operator
player must not make private diagnostics appear in ordinary gameplay chat.

| ID | Persona or actor | Goals and responsibilities | Authority boundary |
| --- | --- | --- | --- |
| ACT-PLAYER | Player, including a bystander or an administrator currently playing | Address the character, follow the conversation, and understand outcomes without operational noise. | Authenticated player identity and actual granted player permissions; chat text confers no authority. |
| ACT-ADMIN | Server administrator or owner | Associate an agent, configure policy, grant abilities, and interact privately with OpenClaw. | Explicit administrative authorization; private conversation is not automatically public speech or permission to change the world. |
| ACT-STAFF | Delegated moderator or staff member | Perform only the operational or administrative actions delegated to them. | Least-privilege grants, not an assumption that all staff are full administrators. |
| ACT-AUTHOR | Character author | Supply identity, rough persona, world premise, and optional roleplay conventions. | Content authorship does not grant game capabilities, secrets, or operator privileges. |
| ACT-OPERATOR | Deployment and operations administrator | Install, observe, diagnose, upgrade, disable, and recover the service. | Authorized operational access; logs and local environment details remain private. |
| ACT-MAINTAINER | Developer, tester, or integration maintainer | Understand contracts, verify compatibility, and change the product without regressions. | Source and test access is not live server authorization. |
| ACT-AGENT | OpenClaw character agent | Receive attributed context, reason, request available abilities, and emit intentional public dialogue. | May propose only operations allowed by the server; memory and persona are not authoritative world state. |
| ACT-ADAPTER | OpenClaw integration | Authenticate, route messages, manage request lifecycles, and preserve public/private separation. | Bound to the configured server, world, character, and agent; not an unrestricted administrative proxy. |
| ACT-SERVER | Minecraft server and Worldspeaker runtime | Capture events, deliver chat, validate requests, observe the world, and apply permitted effects safely. | Authoritative for current world state and game policy; never trusts model assertions as execution evidence. |
| ACT-RECOVERY | Durable-state and recovery subsystem | Preserve outcomes, identify duplicates, and reconcile interrupted work. | Durable records are evidence; missing records and uncertain effects must not be invented into success or failure. |
| ACT-DIAGNOSTICS | Diagnostics subsystem | Measure health, timing, fairness, and failures without publishing private telemetry. | Read and report authorized operational facts; no implicit ability to perform game actions. |

System actors are logical responsibilities, not a promise of separate services.
Their stories use the same actor/action/outcome form and testable criteria as
human stories. Model providers are reached through OpenClaw, not directly by the
Minecraft mod. Other mods or permission providers are external integrations only
when a supported compatibility contract has been selected.

## Shared acceptance contracts

Every story inherits these rules. A story's local criteria add to them rather
than replacing them.

### Trust, authority, and public output

- Minecraft owns current game facts; server policy owns permission; OpenClaw
  owns agent reasoning and conversational memory.
- Player chat, signs, books, imported text, and other world content are untrusted
  data. They cannot change identities, routing, grants, or administrator status.
- Persona never grants an ability. A named, typed, server-validated contract is
  required for every game query or effect exposed to the agent.
- Public output must be a deliberately published acknowledgement, progress
  message, answer, or safe failure/availability notice. Internal reasoning,
  traces, commands, command feedback, and administrative discussion are not
  public output.
- Acknowledgements must describe the actual state. Admission is not agreement,
  queued work is not active investigation, and submission is not confirmed
  execution or player receipt.
- Network, model, and other blocking work must not run on the server tick
  thread. Tick-thread reads and changes must have bounded cost.

### Latency and responsiveness

**The required first-response deadline is 1,000 ms.** It is not an average or a
p95 allowance. A complete, intelligible character reply must begin the exchange
within that deadline; it may be the answer or an acknowledgement before more
work. The dialogue examples motivating this requirement are illustrative, not
fixed scripts or a requirement for an extra acknowledgement on every question.

| Measure | Requirement or target | Acceptance interpretation |
| --- | --- | --- |
| First character response | Required deadline: no more than 1,000 ms after a valid invocation. | Includes routing and queue wait. A quick completed answer counts; otherwise an intentional character acknowledgement is required. An internal receipt, first token, typing indicator, or debug banner does not count. |
| Busy, unavailable, invalid, or denied disposition | Required deadline: no more than 1,000 ms after capture. | Give a concise, safe response rather than accepting silently. Report these outcomes separately; they do not count as successful character service. |
| Ordinary completed answer | Provisional target: median at most 5 seconds and p95 at most 10 seconds on the qualified test setup. | Separate from the first-response deadline. Provider, workload, and achievable service envelope must be recorded. |
| Tool-heavy investigation | First response still meets 1,000 ms; execution has an explicit finite budget. | Final completion time depends on the work. Provide truthful progress and communicate timeout, failure, or cancellation; never leave an accepted request indefinitely unresolved. |
| Fairness under repetition | Another player still meets the first-response requirement while one player repeats an identical request seven times in six seconds. | Do not create a full turn per repetition or satisfy the test by rejecting all other players. Test useful completion as well as acknowledgement. |
| Tick safety | No blocking integration work on the tick thread; bounded game work. | Record tick-time distribution against an idle-integration baseline under representative traffic. |

The product goal is what the player experiences. For automated server-side
measurement, capture `t0` when the server receives the complete invocation,
before routing or admission, and `t1` when it submits the first complete public
message to the intended player's connection. This proxy includes all internal
queueing but excludes network transit and client rendering. It is not a client
read receipt. Where a test client can observe display, record end-to-end latency
as well. Do not claim the player-visible deadline has been proven from server
dispatch measurements alone.

Record the maximum, deadline-miss count and rate, distributions, and failures.
Do not exclude slow requests, busy outcomes, rejected traffic, or disconnects
without reporting them. Each invocation retains its attribution and disposition,
even when duplicates share one logical agent request. An outage response is not
an acknowledgement on behalf of an agent that has accepted work.

A complete, persona-configured integration acknowledgement is an accepted first
character reply for admitted work. It does not count as a fresh model response,
an OpenClaw acceptance receipt, or a completed answer. It must reflect actual
local admission and availability, never invent active investigation or approval.
An unavailable or unserviceable request instead receives the separately measured
safe disposition above.

The first-response path uses locally available, validated persona templates and
does not wait for a model, remote round trip, or active substantive turn. A
complete, intentionally published OpenClaw message may win the first-response
slot before the fallback cutoff. Otherwise the integration schedules the local
acknowledgement early enough to dispatch by the original deadline. The selected
starting cutoff is 750 ms after `t0`, reserving 250 ms for validation and delivery;
it is an unqualified scheduling budget, not evidence that dispatch always meets
the deadline. First tokens and incomplete streamed fragments never occupy the
slot. Exactly one path wins; late acknowledgements are suppressed, while a late
valid answer may still complete the exchange.

The [architecture](architecture.md) defines ownership and race handling. Tests
record whether the reply came from OpenClaw or the integration and exercise
cutoff races, busy agents, outages, and deadline misses. Neither this fast path
nor the one-second requirement has been demonstrated by an implementation yet.

### Conservative behavior

The default posture is one character, addressed conversation, bounded work,
least privilege, and no unsolicited intervention. Effects require explicit
grants. Unknown actions, invalid configuration, unavailable authorization, and
unresolved consequential execution fail closed rather than widening authority.

Candidate numerical defaults are collected in [open decisions](open-decisions.md).
They are starting values for qualification, not measured performance guarantees.
Changing a default cannot remove a required security or interaction property.

## Feature catalogue

| Epic | Feature | Stories |
| --- | --- | --- |
| E-COMPAT | Server-only compatibility and runtime safety | COMPAT-01 through COMPAT-03 |
| E-CONVERSATION | Discoverable, coherent, characterful conversation | CONV-01 through CONV-06 |
| E-RESPONSIVENESS | Responsive and fair interaction | RESP-01 through RESP-05 |
| E-CHARACTER | Persona and character configuration | CHAR-01 through CHAR-04 |
| E-ABILITIES | Deterministic abilities and permissions | ABIL-01 through ABIL-05 |
| E-WORLD | World awareness and safe effects | WORLD-01 through WORLD-04 |
| E-PRESENTATION | Clean dialogue and private administration | CHAT-01 through CHAT-04 |
| E-INTEGRATION | Supported and authenticated OpenClaw integration | INT-01 through INT-04 |
| E-RECOVERY | Durable outcomes and safe recovery | STATE-01 through STATE-05 |
| E-OPERATIONS | Private operations and observability | OPS-01 through OPS-05 |
| E-DISTRIBUTION | Independently usable distribution and verification | DIST-01 through DIST-04 |

## Epic: Server-only compatibility and runtime safety — E-COMPAT

**Outcome:** Supported players can use Worldspeaker without installing it, and
agent traffic does not compromise the Minecraft runtime.

### COMPAT-01 — Identify a supported deployment

**Actor:** ACT-ADMIN

**Story:** As a server administrator, I want to identify the supported deployment
contract, so that I can assess compatibility before installing Worldspeaker.

**Acceptance criteria**
- Minecraft Java 1.21.1 on Fabric Loader (server-only) with Java 21 LTS is
  identified as the selected target, not an already qualified release combination.
- Qualification records pin exact loader, dependencies, Java build, OpenClaw,
  and companion versions and include clean-install and vanilla-client tests.
- Published artifacts identify the qualified loader, Java, OpenClaw, and required
  companion versions; unsupported combinations are not advertised as supported.
- A detectable incompatibility produces an actionable private diagnostic and
  does not enable partially authorized game actions.

### COMPAT-02 — Join without a Worldspeaker client

**Actor:** ACT-PLAYER

**Story:** As a player, I want to interact through a normal compatible Minecraft
client, so that I do not need to install Worldspeaker to participate.

**Acceptance criteria**
- A vanilla-compatible client can join and use character conversation without
  a Worldspeaker client mod, resource pack, custom rendering, or custom packets.
- Character chat and effects use supported vanilla mechanisms and do not forge
  another player's authenticated identity.
- Documentation distinguishes Worldspeaker's requirements from unrelated
  client requirements imposed by other server mods.

### COMPAT-03 — Protect the game loop

**Actor:** ACT-SERVER

**Story:** As the Minecraft server, I want to keep integration work off the tick
thread, so that slow agents and external services do not stall gameplay.

**Acceptance criteria**
- Network calls, model waits, blocking persistence, and reconnect delays do not
  execute on the tick thread.
- World reads and changes use the loader's supported scheduling mechanism and
  enforce finite work, result, and spatial bounds.
- Load tests record tick behavior during normal traffic, overload, and an
  unavailable integration; no unbounded scan or queue can grow indefinitely.

## Epic: Discoverable and coherent conversation — E-CONVERSATION

**Outcome:** Players know how to speak to the character and continue an exchange
without unrelated chatter being treated as commands.

### CONV-01 — Discover an explicit interaction path

**Actor:** ACT-PLAYER

**Story:** As a player, I want to discover an explicit character interaction command,
so that I can address the character without guessing chat syntax.

**Acceptance criteria**
- Player-facing help identifies the installed character and the explicit
  interaction command; usage errors give concise guidance.
- The generic command is not tied to God. A preset may provide an alias such
  as `/pray` without making it mandatory for other personas.
- Command invocation uses the authenticated sender, enforces applicable
  permissions, and enters the same measured lifecycle as natural addressing.

### CONV-02 — Address the character naturally

**Actor:** ACT-PLAYER

**Story:** As a player, I want to address the character naturally using its
configured names and aliases, so that it does not ignore me because its name
was not the first word.

**Acceptance criteria**
- Tests include direct addressing at the beginning, middle, and end of a
  sentence, with documented case, punctuation, and word-boundary behavior.
- Mentioning the character to another player is tested separately from
  addressing it; the recognition policy and ambiguous cases are documented.
- Name and alias changes do not require source changes or change the stable
  character identity.

### CONV-03 — Continue an attributed conversation

**Actor:** ACT-PLAYER

**Story:** As a player, I want to continue an exchange within a bounded follow-up
window, so that I do not need to repeat the character's name in every message.

**Acceptance criteria**
- Follow-up recognition has a finite, documented window and enough context to
  distinguish an exchange from unrelated chat.
- Context remains bound to authenticated player identity, world, and character;
  another player's message cannot inherit the first player's authority.
- Expiry, departure, and conversation reset end or revalidate the window.
  Ordinary ambient chat is not indefinitely forwarded as character input.

### CONV-04 — Preserve shared context and reply audiences

**Actor:** ACT-AGENT

**Story:** As the character agent, I want to receive attributed events and use
explicit reply audiences, so that I do not confuse players or worlds.

**Acceptance criteria**
- Inputs retain event identity, original time, authenticated speaker, character,
  world identity, and relevant conversation context.
- Replies are bound to an authorized audience; the default audience and any
  public/private distinction are documented before compatibility is claimed.
- Batching does not rewrite old evidence as new, mix authority across players,
  or create independent character histories that silently contradict shared effects.

### CONV-05 — Attend to relevant context

**Actor:** ACT-ADMIN

**Story:** As a server administrator, I want to configure bounded event attention,
so that useful context reaches the character without processing every line of
ambient chatter.

**Acceptance criteria**
- Event subscriptions are explicit and bounded; unrelated high-volume events
  cannot automatically become one agent turn each.
- Events needed to invalidate stale work remain available even when they do not
  trigger a conversational turn.
- Unsolicited interventions are disabled by default. Capturing context does not
  imply permission to initiate dialogue or effects; autonomous intervention
  behavior remains outside the committed scope until explicitly specified.

### CONV-06 — Converse with a recognizable character

**Actor:** ACT-PLAYER

**Story:** As a player, I want to converse with a recognizable, context-aware
character, so that the experience is engaging rather than a series of generic
receipts and technical responses.

**Acceptance criteria**
- Representative dialogue is evaluated against the configured persona and world
  premise without prescribing exact wording or a mandatory script.
- Completed answers address the player's request or explain a limitation;
  acknowledgement-only loops are not considered successful conversation.
- Multi-player playtests assess conversational quality, discoverability, and
  enjoyment as well as technical correctness, recording weaknesses rather than
  claiming success merely because the mod loads or replies quickly.

## Epic: Responsive and fair interaction — E-RESPONSIVENESS

**Outcome:** Players are heard promptly, long work does not produce silence,
and one player's traffic cannot monopolize the character.

### RESP-01 — Receive a first reply within one second

**Actor:** ACT-PLAYER

**Story:** As a player, I want to receive a character response within 1,000 ms of
invoking an interaction, so that I know I have been heard even when the answer
takes longer.

**Acceptance criteria**
- The shared first-response deadline applies including admission, routing, and
  queue time, not only time after an agent turn starts.
- A ready answer is sent directly; otherwise an intentional acknowledgement is
  sent without pretending the request has already been solved or approved.
- The acknowledgement path is exercised while another investigation is active;
  it cannot wait behind that investigation's complete turn.
- Tests identify the reply's source and do not count generic local telemetry or
  integration-generated acknowledgements as fresh OpenClaw/model responses.
- A partial stream cannot suppress the fallback. Cutoff races produce one first
  reply, not duplicate acknowledgements, and a later valid answer is preserved.
- Local acknowledgements use validated persona templates and actual admission
  state; unavailable agents receive a safe disposition rather than fictitious
  acceptance. Neither path waits for a substantive turn or remote round trip.

### RESP-02 — Stay informed during longer work

**Actor:** ACT-PLAYER

**Story:** As a player, I want to receive truthful progress followed by an answer
or clear termination, so that a quick acknowledgement is not the last thing I hear.

**Acceptance criteria**
- The lifecycle supports multiple deliberately public messages for one request:
  acknowledgement, useful progress, and a terminal answer or notice.
- Progress reflects actual queued, active, or blocked state without exposing
  internal reasoning, raw tools, or fictitious investigation.
- A documented progress interval and finite execution budget govern longer work;
  expiry, cancellation, and failure produce an appropriate terminal outcome.
- Ordinary completion is measured separately from tool-heavy work and from the
  first response; fast acknowledgements alone do not demonstrate fast answers.

### RESP-03 — Receive an honest admission outcome

**Actor:** ACT-ADAPTER

**Story:** As the integration, I want to bound admission and reject unserviceable work,
so that I do not promise work the agent cannot serve.

**Acceptance criteria**
- Full inboxes, disabled service, authorization failures, and unavailable agents
  produce a safe disposition within the shared one-second deadline.
- Admitted, coalesced, rejected, expired, and unavailable outcomes are tracked
  separately; acceptance cannot be inferred merely from message capture.
- Invalid or abusive traffic does not create unbounded agent work or public
  acknowledgement spam. Rate limiting remains visible to the affected sender.

### RESP-04 — Serve players fairly under repetition

**Actor:** ACT-PLAYER

**Story:** As a player, I want to receive fair service despite other players'
repetitions, so that my distinct request cannot be prevented from being heard.

**Acceptance criteria**
- The seven-repetitions-in-six-seconds regression includes another player's
  distinct request, timely acknowledgement, and useful completion.
- Identical repetitions do not create a full agent turn each. Distinct changed
  requests are preserved or explicitly refused, never silently discarded.
- Pending count and age are bounded per player and globally, with a documented
  fairness rule; all invocation dispositions remain observable.
- Fairness is not claimed by issuing endless acknowledgements or rejecting all
  traffic except the currently active player's requests.

### RESP-05 — Stop obsolete work safely

**Actor:** ACT-SERVER

**Story:** As the Minecraft server, I want to retire stale and cancelled requests,
so that disconnected players and expired situations do not keep consuming work
or receiving obsolete effects.

**Acceptance criteria**
- Departure, expiry, and relevant material world changes cancel or revalidate
  pending work according to explicit policies for dialogue and effects.
- Late output is checked against lifecycle state before publication; cancelled
  work cannot resume merely because a network call returns.
- Cancellation does not claim to reverse an already executed effect. In-flight
  uncertain actions are reconciled under the recovery contract.

## Epic: Persona and character configuration — E-CHARACTER

**Outcome:** Administrators can customize one character without conflating
creative guidance with executable game authority.

### CHAR-01 — Configure identity independently of implementation

**Actor:** ACT-AUTHOR

**Story:** As a character author, I want to configure names, aliases, and chat
appearance, so that I can create a character other than God without changing code.

**Acceptance criteria**
- A stable character ID is distinct from display name, appearance, and aliases.
- One configured character is supported; renaming it preserves the intended
  association with state and its configured OpenClaw agent.
- Display configuration remains vanilla-compatible and cannot impersonate an
  authenticated player, administrator command, or private diagnostic channel.

### CHAR-02 — Supply rough persona context

**Actor:** ACT-AUTHOR

**Story:** As a character author, I want to describe personality, world premise,
and behavioral guidance in ordinary prose, so that customization does not require
a rigid character-management system.

**Acceptance criteria**
- Persona accepts concise free-form context and does not require a large
  character schema or a separate character-management UI.
- Editing persona changes guidance, not ability grants, target scopes, resource
  limits, credentials, or administrator permissions.
- Private persona instructions are not copied into public chat or presented as
  authoritative proof about historical game events.

### CHAR-03 — Validate configuration safely

**Actor:** ACT-ADMIN

**Story:** As a server administrator, I want to validate configuration before it
takes effect, so that a typo cannot broaden privileges or partially replace policy.

**Acceptance criteria**
- Identity, agent association, attention, and ability policy have documented
  configuration boundaries and validation errors.
- Unknown abilities, invalid limits, incompatible schemas, and conflicting
  authority settings fail closed with private, actionable diagnostics.
- Configuration application is coherent rather than partially applied; reload
  versus restart requirements and the effect on in-flight work are documented.
- Secrets are referenced through authorized local configuration, not embedded in
  persona, examples, or distributable presets.

### CHAR-04 — Use an optional God preset

**Actor:** ACT-ADMIN

**Story:** As a server administrator, I want to use a supplied God preset, so that
I can adopt the original concept without making every character follow its rules.

**Acceptance criteria**
- The distribution includes an understandable persona preset and an explicit
  ability-policy example with a least-privilege posture.
- Offerings, bargains, judgments, and other roleplay conventions are optional
  creative guidance, not hard-coded generic mechanics.
- Installing a preset does not silently grant destructive actions, operator
  status, arbitrary commands, or access to private administrative context.

## Epic: Deterministic abilities and permissions — E-ABILITIES

**Outcome:** Agent abilities behave like refined server permissions, with
explicit contracts rather than prompt-based promises of good behavior.

### ABIL-01 — Discover typed abilities

**Actor:** ACT-AGENT

**Story:** As the character agent, I want to discover machine-readable ability contracts,
so that I can request supported operations with valid arguments and understand outcomes.

**Acceptance criteria**
- Every exposed ability has a stable identifier, contract version, typed inputs
  and results, permission requirements, bounds, preconditions, and failure semantics.
- Discovery reflects the bound character's effective policy and does not imply
  that a previously advertised grant is valid forever.
- Unknown operations, extra authority-bearing fields, and malformed arguments
  are rejected deterministically without performing an effect.
- The [ability contract](ability-contract.md) applies to queries as well as mutations.

### ABIL-02 — Grant refined capabilities

**Actor:** ACT-ADMIN

**Story:** As a server administrator, I want to grant named abilities with scopes
and limits, so that I can give a character useful powers without giving it operator access.

**Acceptance criteria**
- Absence of a grant denies use. Explicit denial wins over a conflicting grant;
  ambiguous configuration cannot widen authority.
- Policy can constrain applicable characters, dimensions, targets, spatial
  bounds, rates, and operation-specific quantities where relevant.
- Persona text and player claims cannot alter grants. Unrestricted RCON, shell
  access, and arbitrary command execution are not character abilities.
- A permission provider may be integrated only through a documented supported
  contract; installing a particular third-party permission plugin is not assumed.

### ABIL-03 — Enforce requester and target policy

**Actor:** ACT-SERVER

**Story:** As the Minecraft server, I want to authorize the character, requester,
and targets under explicit server policy, so that players cannot borrow unintended
privileges by asking the character to act.

**Acceptance criteria**
- Requester identity comes from the authenticated event envelope, not model
  arguments. Each contract defines who may request it and whom it may affect.
- Delegation rules explicitly distinguish allowed character powers from actions
  requiring the requester's own permission; neither unlimited delegation nor
  a blind union of player and agent privileges is the default.
- Target resolution, current policy, range, dimension, and preconditions are
  validated on the server immediately before execution.
- Batching and private administrator discussion cannot transfer another actor's
  identity or grant authority to a public player request.

### ABIL-04 — Apply limits and revocations at execution

**Actor:** ACT-SERVER

**Story:** As the Minecraft server, I want to enforce limits and current grants
atomically at execution, so that concurrent or queued requests cannot bypass policy.

**Acceptance criteria**
- A grant revoked after planning is rechecked before effects; discovery and
  earlier model decisions are not cached authorization to execute later.
- Cooldowns, quotas, target counts, and other finite limits cannot be bypassed
  by concurrent calls, retries, alternate aliases, or duplicate action IDs.
- Charging, reservation, expiry, and release behavior is defined by the ability
  contract rather than inferred by the model.

### ABIL-05 — Return authoritative structured outcomes

**Actor:** ACT-AGENT

**Story:** As the character agent, I want to receive structured execution outcomes,
so that my dialogue distinguishes rejection, confirmed effect, and uncertainty.

**Acceptance criteria**
- Outcomes distinguish rejection, expiry/cancellation before execution,
  confirmed execution, confirmed failure, and unknown execution.
- Results identify relevant authoritative facts without leaking secrets or
  requiring raw commands, stack traces, or console text.
- An accepted or submitted action is never reported as confirmed execution, and
  confirmed execution is never described as a client read receipt.
- Unsupported concrete abilities remain unavailable; illustrative examples are
  not permission to execute an unimplemented operation.

## Epic: World awareness and safe effects — E-WORLD

**Outcome:** The character can understand relevant game circumstances and take
a small, useful set of verifiable actions without stale or unbounded behavior.

### WORLD-01 — Observe bounded current state

**Actor:** ACT-AGENT

**Story:** As the character agent, I want to query bounded player and world observations,
so that I can answer and act using current facts rather than assumptions from memory.

**Acceptance criteria**
- The supported catalogue includes meaningful observations of relevant players
  and local world state, with explicit fields, bounds, and authorization.
- The initial query is `worldspeaker.core.observe_player` contract v1, as defined
  in the ability contract. Health and current maximum health are authoritative;
  valid health boosts or attribute changes above 20 health points are not clamped.
- Results identify observation time and world context; unavailable, unloaded,
  or out-of-scope state is not fabricated or obtained through an unbounded scan.
- Only necessary allowed information is returned; private configuration,
  credentials, and unrelated administrative or player data are excluded.

### WORLD-02 — Apply useful, explicitly permitted effects

**Actor:** ACT-PLAYER

**Story:** As a player, I want to ask the character to perform supported game effects,
so that interaction can meaningfully affect the world rather than only describe it.

**Acceptance criteria**
- The delivered catalogue contains a small documented set of actual effects,
  each covered by a deterministic ability contract and vanilla-compatible presentation.
- Effects are unavailable until explicitly granted; neither a persona nor a
  natural-language request invents a new operation.
- The initial effect is `worldspeaker.core.strike_lightning` contract v1, as
  defined in the ability contract: deterministic placement, visual-only by
  default, and separately granted, capped, non-lethal damage to one target.
- This is controlled lightning presentation, not unrestricted vanilla lightning.
  Fire, secondary entity effects, and block mutations are prohibited. Protection
  checks cover the complete documented footprint, not just the target's block;
  unsupported or unavailable protection/suppression mechanisms fail closed.
- Inventory changes, teleportation, rewards, and other unlisted effects remain
  outside the selected catalogue. Selected abilities are not advertised as
  available until their implementation and authoritative tests pass.
- Tests verify authorized success and denied/out-of-scope attempts against
  authoritative game state, not just the character's text.

### WORLD-03 — Refresh before responding or acting

**Actor:** ACT-SERVER

**Story:** As the Minecraft server, I want to check material changes before
delivery and execution, so that the character does not act on an ended situation.

**Acceptance criteria**
- Tests include death, movement, dimension change, disconnect, and a need
  already satisfied while a response is being generated.
- Relevant facts are refreshed, or stale work is revised, rejected, or cancelled;
  an old triggering event alone is insufficient authority for an effect.
- Dialogue and effects have explicit freshness policies appropriate to their
  different consequences; old event times remain old after batching or reconnect.

### WORLD-04 — Preserve coherent effect ordering

**Actor:** ACT-SERVER

**Story:** As the Minecraft server, I want to apply effects in a controlled order,
so that parallel reasoning cannot produce conflicting or duplicated world changes.

**Acceptance criteria**
- World mutation occurs only through the validated server execution boundary,
  with bounded scheduling and a documented conflict/ordering policy.
- Preconditions and finite resource reservations are checked against the state
  at execution, not merely the state used during planning.
- A failed or uncertain step does not imply that earlier effects were rolled
  back; multi-step behavior reports actual partial outcomes.

## Epic: Clean dialogue and private administration — E-PRESENTATION

**Outcome:** Gameplay contains intentional character interaction, while
administrative and technical activity remains private.

### CHAT-01 — Keep operational noise out of chat

**Actor:** ACT-PLAYER

**Story:** As a player, I want to experience clean character dialogue, so that
RCON and other integration telemetry do not interrupt the experience.

**Acceptance criteria**
- Worldspeaker does not publish RCON output, command receipts, raw command
  feedback, tool traces, timings, queue details, request IDs, or raw errors.
- The same checks pass for ordinary players and operators currently playing,
  during successful actions, rejected actions, reconnects, and outages.
- Action narration is deliberate character dialogue, not an automatic console
  success line. Fast acknowledgements are not debug-style queue banners.
- The implementation avoids leaking through command-feedback broadcasts as
  well as direct chat forwarding. It does not globally disable unrelated server
  diagnostics or ordinary gameplay messages to conceal a routing defect.

### CHAT-02 — Publish only intentional public messages

**Actor:** ACT-AGENT

**Story:** As the character agent, I want to publish through an explicit
public-message channel, so that acknowledgements and answers can reach players
without exposing my private activity.

**Acceptance criteria**
- Public messages identify their request, character, audience, and semantic kind:
  acknowledgement, progress, answer, or safe failure/availability notice.
- Internal reasoning, incomplete model fragments, private instructions, tool
  output, and administrative messages are not forwarded as public dialogue.
- Public text obeys documented length, formatting, audience, and rate bounds;
  retries do not duplicate it or change its intended audience.

### CHAT-03 — Operate privately through OpenClaw

**Actor:** ACT-ADMIN

**Story:** As a server administrator, I want to interact privately with the agent
through OpenClaw, so that configuration and administrative discussion do not appear in game.

**Acceptance criteria**
- A supported OpenClaw administrative surface is documented without inventing
  an unqualified API or requiring the administrator to join the player conversation.
- Routing and context boundaries separate administrative conversation from
  player-facing output; privacy does not depend only on asking the model to be discreet.
- Administrative text is not automatically republished or mixed into public
  conversation history. Any explicit publication remains authorized and deliberate.
- The agent's game abilities remain server-validated even during private administration.

### CHAT-04 — Resist content-based privilege and output spoofing

**Actor:** ACT-SERVER

**Story:** As the Minecraft server, I want to treat world text as untrusted data,
so that players cannot impersonate operators or extract private integration output.

**Acceptance criteria**
- Tests place instruction-like text in player messages and supported world
  inputs; it cannot alter routing, identity, grants, or administrative authority.
- Fake tool results and quoted command success are not accepted as evidence of
  execution. Only the execution boundary supplies authoritative outcomes.
- Public-output tests cover attempts to reveal private instructions, traces,
  administrative conversations, credentials, and raw integration errors.

## Epic: Supported and authenticated OpenClaw integration — E-INTEGRATION

**Outcome:** Worldspeaker uses OpenClaw as its harness through a qualified,
bounded integration rather than introducing a competing agent runtime.

### INT-01 — Use supported extension contracts

**Actor:** ACT-MAINTAINER

**Story:** As an integration maintainer, I want to use pinned, supported OpenClaw APIs,
so that compatibility does not depend on undocumented prototype internals.

**Acceptance criteria**
- The supported OpenClaw version, extension packaging, agent routing, tool
  lifecycle, public-message mechanism, and private admin surface are documented
  with tested compatibility evidence.
- First-response behavior is tested through the supported mechanism, including
  an already busy agent; an assumed streaming capability is not evidence.
- Worldspeaker does not duplicate OpenClaw's model credentials, reasoning
  runtime, or conversational-memory system in the Minecraft mod.
- Player-origin turns have a documented, qualified tool/context policy and do
  not inherit unrelated operator tools or private administrative sessions.

### INT-02 — Authenticate and bind connections

**Actor:** ACT-ADAPTER

**Story:** As the integration, I want to authenticate and explicitly bind connections,
so that another process or server cannot use this character's game tools.

**Acceptance criteria**
- The selected transport authenticates the intended peers and binds sessions to
  configured server, world, character, and agent identities.
- Credentials, network exposure, replay protection, and reconnect authentication
  have a documented supported deployment policy; untrusted networks require
  appropriate transport protection.
- Invalid credentials and mismatched bindings deny access without exposing
  secrets or giving player chat raw diagnostic details.
- Model-provider credentials remain in OpenClaw, not clients, persona, or presets.

### INT-03 — Preserve a versioned lifecycle across components

**Actor:** ACT-ADAPTER

**Story:** As the integration, I want to exchange versioned messages and track
explicit request states, so that both components agree about attribution and completion.

**Acceptance criteria**
- The eventual wire contract defines event, invocation, request, action, and
  public-message IDs, original timestamps, identities, audience, and live/history origin.
- Lifecycle transitions distinguish capture, admission, queueing, execution,
  public responses, terminal outcome, and uncertain actions.
- Schema mismatch, malformed envelopes, and invalid transitions fail explicitly;
  model-provided identity fields cannot override authenticated envelopes.
- Monotonic local durations and cross-host clock assumptions are documented;
  timestamps from different clocks are not blindly subtracted.

### INT-04 — Reconnect without unsafe replay

**Actor:** ACT-ADAPTER

**Story:** As the integration, I want to recover within bounds after connection loss,
so that reconnecting does not duplicate dialogue or effects or revive expired work.

**Acceptance criteria**
- Disconnects change availability promptly; reconnect attempts and buffered work
  are bounded and cannot block the server tick thread.
- Outstanding requests and actions are reconciled by identity and current state
  before any retry; unknown effects are not blindly replayed.
- Repeated public-message delivery is deduplicated, and late output remains
  subject to audience, expiry, cancellation, and freshness checks.

## Epic: Durable outcomes and safe recovery — E-RECOVERY

**Outcome:** Consequential game facts outlive agent sessions, and interruptions
do not turn uncertainty into duplicate rewards or invented history.

### STATE-01 — Preserve authoritative outcomes independently of memory

**Actor:** ACT-RECOVERY

**Story:** As the recovery subsystem, I want to preserve durable consequential
action records, so that an OpenClaw reset does not erase evidence of game outcomes.

**Acceptance criteria**
- Records bind server/world/character and action identity to validated intent,
  execution state, policy context, relevant timestamps, and observed outcome.
- An agent-session reset leaves durable outcomes available through an authorized
  recovery/query path without treating conversational memory as the ledger.
- Generated summaries and stories remain derived material, not authoritative
  evidence for later judgments or new effects.

### STATE-02 — Make duplicate handling safe

**Actor:** ACT-RECOVERY

**Story:** As the recovery subsystem, I want to deduplicate actions by identity and intent,
so that reconnects and retries do not repeat consequential effects.

**Acceptance criteria**
- Repeating an action identity with the same immutable intent returns its known
  state or outcome instead of executing again.
- Reusing that identity with different intent is rejected; IDs are scoped so
  another world or character cannot inherit an unrelated prior action.
- Crash-window tests cover the boundary between durable recording and game
  execution. The product does not claim universal exactly-once behavior where
  the game and durable store cannot commit atomically.

### STATE-03 — Reconcile unknown execution honestly

**Actor:** ACT-OPERATOR

**Story:** As a server operator, I want to reconcile unknown outcomes explicitly,
so that I can resolve interrupted effects without blind retries or false refunds.

**Acceptance criteria**
- Unknown remains distinct from confirmed failure and success; automation does
  not infer an outcome merely from a timeout or missing reply.
- Reconciliation uses authoritative available evidence and records the basis
  of any resolution. If evidence is insufficient, uncertainty remains visible privately.
- Retry, compensation, refund, or manual resolution requires the relevant
  contract and authorization; none is an automatic consequence of uncertainty.

### STATE-04 — Keep historical material inert

**Actor:** ACT-RECOVERY

**Story:** As the recovery subsystem, I want to mark historical and recovered
events explicitly, so that importing context cannot trigger new gameplay effects.

**Acceptance criteria**
- Live events and historical/imported events are distinguishable throughout
  routing, storage, agent context, and action validation.
- Recovered backlogs do not become fresh live requests merely because they are
  read, rebatched, summarized, or delivered after reconnect.
- Any separately authorized new action must be a new live intent with current
  checks, not implicit execution of an old imported request.

### STATE-05 — Restore state without reopening unsafe work

**Actor:** ACT-OPERATOR

**Story:** As a server operator, I want to back up, restore, and migrate state
through documented procedures, so that maintenance does not silently reset safeguards.

**Acceptance criteria**
- Supported backup/restore and migration procedures describe world identity,
  durable outcomes, grants, and pending/uncertain work together.
- Missing or corrupt required state disables consequential effects safely and
  produces private diagnostics rather than silently starting a clean ledger.
- Retention and migration preserve unresolved outcomes and required deduplication
  evidence; restoring an older world does not automatically replay later effects.

## Epic: Private operations and observability — E-OPERATIONS

**Outcome:** Authorized operators can understand and control the service
without turning gameplay into a console or collecting unnecessary private content.

### OPS-01 — Correlate stages without exposing conversation bodies

**Actor:** ACT-DIAGNOSTICS

**Story:** As the diagnostics subsystem, I want to correlate lifecycle metrics,
so that operators can locate latency and reliability problems without public telemetry.

**Acceptance criteria**
- Measurements cover event capture, admission, queue wait, first response,
  agent/model/tool activity where exposed, action submission/completion, and
  final publication, expiry, cancellation, or failure.
- Diagnostics preserve invocation, request, and action correlation privately;
  unavailable upstream timing is marked unknown rather than attributed to inference.
- Useful default diagnostics do not require complete conversation bodies,
  private instructions, credentials, or raw provider responses.

### OPS-02 — Evaluate actual service quality

**Actor:** ACT-OPERATOR

**Story:** As a server operator, I want to inspect distributions and per-player
service outcomes, so that averages and quick acknowledgements cannot hide poor service.

**Acceptance criteria**
- Reports include deadline misses and maxima, completion latency, queue depth
  and age, coalescing, rejections, stale work, failures, and relevant tick metrics.
- Fairness and completion are evaluated under multi-player traffic; busy
  responses and acknowledgements are not counted as completed answers.
- Every result records the qualified versions, provider/model, hardware class,
  workload, sample size, measurement boundaries, and failures without exposing
  a private deployment's identity in public project material.

### OPS-03 — Delegate operational controls safely

**Actor:** ACT-STAFF

**Story:** As a delegated staff member, I want to use only my authorized controls,
so that I can assist without receiving unrestricted administrator or character powers.

**Acceptance criteria**
- Supported controls have explicit permissions distinguishing status access,
  service enable/disable, policy changes, and consequential recovery actions.
- Unauthorized attempts fail closed; inspecting diagnostics does not implicitly
  grant game abilities, private agent conversations, or credential access.
- Documentation identifies controls available through each supported surface;
  operator status is not used as a reason to broadcast diagnostics into gameplay chat.

### OPS-04 — Bound costs and resource use

**Actor:** ACT-ADMIN

**Story:** As a server administrator, I want to configure finite queues, execution
budgets, and attention limits, so that player activity cannot create unlimited cost or work.

**Acceptance criteria**
- Queue count/age, request execution, result sizes, and relevant attention and
  effect rates have documented finite bounds and safe overflow behavior.
- Tool-heavy work can use a documented configured budget; it cannot renew
  itself forever or evade cancellation by issuing more calls.
- Documentation explains model-provider costs and external service dependencies;
  provisional defaults are not presented as provider-independent latency guarantees.

### OPS-05 — Understand private data and configuration

**Actor:** ACT-ADMIN

**Story:** As a server administrator, I want to inspect data flow and retention policy,
so that I can make informed decisions about what leaves the server and what is stored.

**Acceptance criteria**
- Documentation identifies which allowed game data goes to OpenClaw and may
  reach its configured provider, and distinguishes memory, outcome records, and logs.
- Credential locations, access boundaries, retention, deletion constraints, and
  backup implications are explained without including real credentials.
- Public examples contain generic values only; private server identity, access
  details, and local deployment procedures remain outside the project.

## Epic: Independently usable distribution and verification — E-DISTRIBUTION

**Outcome:** A new administrator can install, understand, operate, and recover
the supported product without access to its original environment.

### DIST-01 — Install from a clean supported environment

**Actor:** ACT-ADMIN

**Story:** As a new server administrator, I want to install distributable artifacts
using a minimal setup guide, so that I do not need private deployment knowledge.

**Acceptance criteria**
- Deliverables include the server mod JAR, required OpenClaw integration,
  character preset, and versioned configuration documentation.
- A clean-install test follows only public instructions on the qualified stack,
  establishes authentication, and demonstrates vanilla-compatible conversation.
- Required external services, accounts, costs, runtime versions, ports in generic
  examples, and permissions are documented; unsupported combinations are not implied.

### DIST-02 — Operate and troubleshoot from documentation

**Actor:** ACT-OPERATOR

**Story:** As a server operator, I want to use complete administration guidance,
so that I can diagnose and recover the service without reading its source code.

**Acceptance criteria**
- Documentation covers installation, configuration, persona versus abilities,
  player addressing, private administration, latency measurement, privacy,
  troubleshooting, outages, recovery, and upgrade expectations.
- Troubleshooting separates queue, transport, model/tool, execution, and output
  problems rather than blaming RCON or inference without evidence.
- Operational examples keep diagnostics private and do not recommend globally
  silencing unrelated server feedback to hide Worldspeaker output leaks.

### DIST-03 — Verify the promised behavior repeatedly

**Actor:** ACT-MAINTAINER

**Story:** As a tester or maintainer, I want to run repeatable story-linked tests,
so that changes cannot silently regress interaction, security, or recovery.

**Acceptance criteria**
- Automated tests cover pure contracts, validation, lifecycle transitions,
  authorization, coalescing, bounds, replay, and idempotency where feasible.
- Supported-stack integration/playtests cover the regression matrix below,
  including operator and non-operator chat visibility and first-response timing.
- Reports distinguish simulated tests from real Minecraft/OpenClaw evidence;
  passing documentation or mock tests is not claimed as live compatibility.
- Any live-environment access and consequential testing require explicit local
  operational authorization; public test instructions assume no private server.

### DIST-04 — Distribute with accurate licensing and platform claims

**Actor:** ACT-MAINTAINER

**Story:** As a project maintainer, I want to distribute qualified, license-reviewed artifacts,
so that administrators receive a legally understandable and accurately described release.

**Acceptance criteria**
- The project's GPL-3.0 license is preserved, and dependency and bundled asset
  licensing is reviewed before distribution.
- Public alpha artifacts identify compatible versions and companion requirements,
  clearly stating that players do not install Worldspeaker on their clients.
- Project-name conflicts and current submission rules are checked for the chosen
  distribution platform; CurseForge and/or Modrinth remain candidate destinations,
  not guaranteed approvals or a promise to support both.

## Acceptance scenario coverage

These scenarios cross epics; they are not milestones. Each story also retains
its local positive, negative, and boundary criteria.

| Scenario | Required story coverage |
| --- | --- |
| Compatible client without Worldspeaker installed | COMPAT-01, COMPAT-02, DIST-01 |
| Name at beginning/middle/end, aliases, then unnamed follow-up | CONV-01, CONV-02, CONV-03 |
| Character-consistent, relevant dialogue and multi-player quality assessment | CONV-04, CONV-06, CHAR-02, CHAR-04 |
| Simple answer versus acknowledged long investigation | RESP-01, RESP-02, CHAT-02, INT-01 |
| First response while another request is already active | RESP-01, RESP-03, RESP-04, INT-01 |
| Seven repeated requests in six seconds plus another player's distinct request | RESP-03, RESP-04, OPS-02 |
| Same player changes the request rather than repeating it | RESP-04, CONV-04, ABIL-03 |
| Death, movement, dimension change, satisfied need, or disconnect during work | RESP-05, WORLD-03, WORLD-04 |
| Denied grant, invalid arguments, out-of-scope target, revoked grant, concurrent quota use | ABIL-01, ABIL-02, ABIL-03, ABIL-04 |
| Ordinary player and operator observe success, failure, and outage chat | CHAT-01, CHAT-02, CHAT-03, OPS-01 |
| Instruction-like player/world content and forged result or identity | CHAT-04, ABIL-03, INT-03 |
| Agent unavailable, connection lost, reconnect, duplicate output | RESP-03, INT-02, INT-03, INT-04 |
| Crash before/after execution, lost result, duplicate or conflicting action ID | ABIL-05, STATE-02, STATE-03, INT-04 |
| Agent reset, historical import, restore, corrupt ledger | STATE-01, STATE-04, STATE-05 |
| Invalid configuration, unauthorized staff action, persona-only change | CHAR-02, CHAR-03, ABIL-02, OPS-03 |
| Load and privacy measurement without full private conversation logging | COMPAT-03, OPS-01, OPS-02, OPS-04, OPS-05 |
| Independent installation, recovery, and release metadata | DIST-01, DIST-02, DIST-03, DIST-04 |

## Explicit exclusions

The following are not promised by this specification:

- Multiple simultaneous characters or multi-character orchestration.
- Voice interaction, custom client rendering, custom client packets, or a
  mandatory Worldspeaker client mod/resource pack.
- Broad multi-version or multi-loader support.
- A large persona-management UI or a replacement for OpenClaw.
- Unrestricted RCON, shell access, arbitrary command execution, or automatic
  operator privileges for a character.
- Fixed God-specific offering, judgment, bargain, economy, or reward mechanics.
- Unrestricted vanilla lightning, fire propagation, inventory changes,
  teleportation, rewards, or other effects beyond the selected bounded catalogue.
- Autonomous unsolicited interventions beyond the explicitly configured context
  handling described above; this remains a proposed extension, not hidden scope.
- Guaranteed fresh model generation in one second on arbitrary providers or
  guaranteed client receipt over an arbitrarily delayed connection.
- Universal exactly-once effects across non-transactional crash boundaries.
- Distribution-platform approval or support for every hosting environment.

Exclusions are scope boundaries, not a scheduled future backlog. Promoting an
extension into a promise requires explicit stories and acceptance criteria here.
