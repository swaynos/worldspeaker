# Open decisions and candidate defaults

The [product specification](specification.md) defines the full committed feature
set. This document separates selected designs, candidate settings, unresolved
choices, and missing qualification evidence. It is not a roadmap, ordered backlog,
milestone sequence, or claim that implementation/testing has occurred. No
Worldspeaker stack, integration, protocol, scheduling policy, or ability has yet
been qualified in this repository.

## Settled product direction

- Selected target: Minecraft Java **1.21.1**; Fabric Loader (server-only), Java 21 LTS.
  This replaces the earlier 1.21.11 target selection; neither is a tested release
  combination. Exact dependency/runtime builds still require qualification.
- OpenClaw is the agent harness, not a replaceable placeholder for a new runtime.
  Authenticated bidirectional WebSocket is the selected integration transport,
  not an assumed existing OpenClaw endpoint.
- One configurable character; God is a supplied optional preset.
- Persona is rough creative context. Abilities use deterministic, refined,
  default-deny server permissions and typed contracts.
- First character response within **1,000 ms**: a complete intentional OpenClaw
  public message may reply directly; otherwise a persona-configured, locally
  generated integration acknowledgement is accepted for admitted work. Partial
  streams and telemetry receipts never count. The architecture reserves a local
  fallback path and records its source separately from model responses.
- Clean player chat, including operator players; technical and administrative
  activity remains private.
- Conservative bounded behavior, explicit ability grants, and no unsolicited
  interventions by default.
- Initial ability catalogue: `worldspeaker.core.observe_player` (query) and
  `worldspeaker.core.strike_lightning` (controlled effect), contract v1. Both
  require explicit grants. Lightning defaults to visual-only; separately granted
  damage is capped, single-target, and non-lethal, without fire or secondary
  world mutations. Neither entry is available until implemented and tested.
- Specifications cover capabilities and acceptance, not delivery milestones.
- Local deployment identity and operational procedures stay outside this project.

## Decision register and qualification gaps

IDs are references, not priority or chronological order. **Selected** means a
design has been chosen, not that it works on a supported stack. **Partial** means
only part of the contract is selected. **Candidate** settings require experiments;
**Open** items still require a design choice. Qualification additionally requires
pinned versions, repeatable tests, and recorded results; no row currently claims
that evidence exists.

| ID | Design status | Selected design or remaining choice | Required qualification evidence |
| --- | --- | --- | --- |
| OD-STACK | Selected | Minecraft Java **1.21.1**, Fabric Loader server-only, Java 21 LTS. Exact loader, Java build, mappings/API dependencies, and companion versions remain unpinned. | A pinned, reproducible compatibility combination; clean server install; vanilla-client conversation/effect checks without a client mod or resource pack. |
| OD-OPENCLAW | Open | Which supported OpenClaw version, extension packaging/APIs, agent association, player-origin tool/context policy, lifecycle, and private administrator surface implement the design? WebSocket selection does not settle these APIs. | Supported API references and actual integration tests, including isolation from unrelated operator tools and private sessions; no reliance on an old internal endpoint. |
| OD-FIRST-REPLY | Selected | Accept complete OpenClaw dialogue or a locally cached persona-configured integration acknowledgement for admitted work. Starting fallback cutoff: 750 ms from original capture, with 250 ms reserved for dispatch; one publication gate, no partial-token shortcut. | Busy-agent and multi-player timing tests; direct/fallback cutoff races; actual admission/outage handling; maximum/miss reporting; reply source and server-dispatch versus client-display measurements. |
| OD-TRANSPORT | Selected | Authenticated bidirectional WebSocket; `ws://` only on loopback, otherwise validated `wss://`; distinct scoped bearer/pre-shared handshake credentials; explicit server/world/character/agent binding. | Threat boundary, endpoint ownership, credential provisioning/rotation/revocation, peer verification, reconnect authentication, replay handling, and denial tests. |
| OD-PROTOCOL | Partial | Versioned JSON frames, distinct correlation/action/public-message identities, and candidate dialogue/ability/heartbeat families. Exact schemas, negotiation, state transitions, acknowledgements, ordering, cancellation, and reconciliation operations remain undecided. | A complete protocol checked against pinned supported OpenClaw APIs, the first-response design, and the ability contract; malformed/reordered/replayed frames and interrupted-lifecycle tests. |
| OD-CONVERSATION | Open | Which generic command, address-recognition rules, follow-up behavior, memory/session boundaries, and default reply audience apply? | Documented behavior with authenticated identity, ambiguous addressing, shared context, reset/logout, and public/private audience tests. |
| OD-SCHEDULING | Selected policy; candidate limits | One substantive turn per character, round-robin between player queues, FIFO within each queue. Starting limits: 2 pending distinct requests per player and 16 globally, active work separate; local first replies do not wait for substantive turns. | Busy-agent, overflow, expiry, and seven-repetitions-in-six-seconds tests, including another player's useful completion. Reject the starting policy if it cannot demonstrate fair service. |
| OD-BUDGETS | Candidate | Starting request budgets: 15-second pending age, 30-second active execution, 2-second exact-repeat window, 10-second progress interval. Ability-specific query/effect deadlines are separate contract ceilings. | Finite values exercised under normal load, repetition, outages, cancellation, and stale context; tool-heavy work measured separately without indefinite budget renewal. |
| OD-CATALOGUE | Selected | `worldspeaker.core.observe_player` and `worldspeaker.core.strike_lightning`, contract v1, including bindings, delegation, numeric limits, freshness, accounting, and outcomes in the [ability contract](ability-contract.md). | Positive/denial/boundary/retry tests and authoritative game-state evidence, including health above 20, full-footprint protection, suppression of native lightning side effects, and crash-window reconciliation. Unsupported modes remain unavailable. |
| OD-PERMISSIONS | Partial | Default-deny named grants, complete-grant selection, explicit denial precedence, and v1 requester/target/damage delegation are selected. Grant/profile representation, staff controls, provider choice/mapping, and administration syntax remain open. | Deterministic allow/deny/ceiling precedence, no fragment union, requester delegation, revocation, provider outage, and concurrent resource-reservation tests. No third-party provider is assumed. |
| OD-STATE | Partial | Consequential identity/intent/outcomes survive resets, with no automatic evidence deletion in the initial contract and fail-closed storage capacity. Storage engine, finite ceiling values, migration/restore semantics, and reconciliation interface remain open. | Crash-window, unknown-outcome, agent-reset, full/corrupt/unavailable store, history-import, backup/restore, and migration tests. Unresolved evidence cannot disappear through retention. |
| OD-CONFIG | Open | What configuration format, validation/schema, finite capacity values, credential references, acknowledgement-template substitutions, and reload/restart behavior are supported? | Coherent application/failure tests, template validation, in-flight revocation behavior, persona/authority separation, and secrets absent from presets/examples. |
| OD-PERFORMANCE | Open | Which provider/model, hardware class, and representative load qualify ordinary completion, fairness, first-response budgets, and tick-health targets? | Recorded versions, workload, sample sizes, distributions, maxima, deadline misses, and failures. No provider-independent completion guarantee is assumed. |
| OD-DISTRIBUTION | Open | Which public platform, artifact packaging, and supported dependency combination are appropriate? | Current project-name/submission/dependency-license review and independent clean installation. Platform approval is not guaranteed. |

## Conservative candidate defaults

The product explicitly requests conservative defaults. The following are
**starting settings for qualification**, not service-level guarantees, tested
results, or substitutes for resolving the decisions above. Some reflect a selected
policy, while numerical capacities remain candidates. Exact configuration keys
and units must be formalized with the chosen schema. Ability-specific ceilings
in the ability contract are normative and cannot be raised by these defaults.

| Area | Candidate default | Constraint that cannot be relaxed |
| --- | --- | --- |
| Characters | One | Identity remains stable independently of display name. |
| Attention | Addressed requests and context needed for freshness only | No ambient-chat turn per line or unsolicited intervention. |
| Ability authority | No grants until explicitly configured | Persona and presets cannot silently authorize effects. |
| First-reply fallback cutoff | 750 ms after original server capture; reserve 250 ms for validation/dispatch | A complete OpenClaw message can reply earlier; partial streams do not suppress the local fallback. Misses remain failures, not an extended deadline. |
| Substantive agent concurrency | One active turn per character | First reply must not wait behind it; this default is rejected if it cannot meet interaction/fairness requirements. |
| Pending work per player | Two distinct requests | Preserve changed requests or explicitly refuse overflow. Active work is counted separately. |
| Global pending work | Sixteen distinct requests | Finite global and per-player bounds; overflow gets a prompt safe disposition. |
| Scheduling | Round-robin between players, with documented handling of relevant urgent context | One player's repetition cannot starve another; no uncontrolled effect ordering. |
| Duplicate burst window | Two seconds for exact repeats by the same player in the same context | Retain each invocation's disposition and original timestamps; never reset age forever or merge distinct intent. |
| Pending-work age | Fifteen seconds | Expire or revalidate obsolete work; never use the limit to delay the first reply. |
| Active execution budget | Thirty seconds, with an explicitly configured finite larger budget for tool-heavy work if needed | No indefinite self-renewal; timeout/cancellation must account for in-flight unknown effects. |
| Follow-up window | Thirty seconds after the relevant exchange | Player/world/character attribution and logout/reset checks still apply. |
| Progress interval | Ten seconds without an answer while work remains live | Truthful, useful, bounded dialogue, not invented progress or public telemetry. |
| Game effects | Disabled until granted and individually bounded | Freshness, authorization, deduplication, and outcome recording still apply. |
| Diagnostics | Private structured metadata, not full conversation bodies | No credentials, raw traces, or technical broadcasts in player chat. |

Pending expiry and active execution budgets are separate. Increasing a budget
does not grant more abilities or allow old facts to remain fresh. The one-second
first-response deadline is a required product property, not a configurable
candidate default that can be raised to accommodate a slow model.

## Acceptance evidence, not a delivery plan

A choice is qualified only when its assumptions and repeatable results are
recorded without private deployment details. Relevant evidence includes clean
installation, multi-player conversation, meaningful completion, timing misses,
authorization boundaries, safe effects, private chat routing, outages,
reconnection, uncertain outcomes, and recovery.

Live-environment access, maintenance windows, credentials, backup locations,
and authorization to test effects are local operations concerns. They are not
part of this repository's specification and must not be copied here. Resolving
a choice updates the relevant contract; it does not create a milestone sequence.
