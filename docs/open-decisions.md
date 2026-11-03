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
| OD-STACK | Selected | Minecraft Java **1.21.1**, Fabric Loader server-only, and Java 21 LTS. The candidate manifest pins Loader 0.20.1, Fabric API 0.116.17+1.21.1, Temurin 21.0.13+9-LTS Linux x86-64, and its companion/runtime pair; pins are not qualification. | A reproducible pinned build; clean server install; vanilla-client conversation/effect checks without a client mod or resource pack. |
| OD-OPENCLAW | Open | Exact host behavior remains unqualified. The candidate pins `openclaw@2026.10.8` and uses exported route/session-key and next-turn injection seams, but actual group-session persistence, tool policy, workspace/bootstrap/memory isolation, and private-admin behavior are not proven. | Actual-host tests for route persistence, ACK ordering, player/admin separation, and workspace/memory isolation. Stop if the host cannot prevent private-admin context from entering gameplay; do not invent a reset/isolation API. |
| OD-FIRST-REPLY | Selected design; runtime qualification open | Source captures an immutable receipt stamp at pinned packet-handler entry and carries it through packet/message copies or a scoped command runnable. One invocation-owned publication slot arbitrates complete finals, local ACKs, and safe dispositions. A 750 ms fallback cutoff and 1,000 ms deadline remain fixed; exact-boundary successful dispatch is on time, and no dispatch settled at the deadline is a miss. Offline tests cover capture handoff, ingress, mailbox arbitration, and shared-budget boundaries. | Rebuild the pinned Linux artifacts, verify Fabric mixin behavior in an authorized server, and obtain fresh receipt-to-client-display evidence under active work, cutoff races, queue load, and outage. Report direct maximum, every miss/no-dispatch outcome, dispatch source, and client-display observation separately. Offline tests and fixture load are not runtime proof. |
| OD-TRANSPORT | Selected | Authenticated bidirectional WebSocket; `ws://` only on loopback, otherwise validated `wss://`; distinct scoped bearer/pre-shared handshake credentials; explicit server/world/character/agent binding. | Threat boundary, endpoint ownership, credential provisioning/rotation/revocation, peer verification, reconnect authentication, replay handling, and denial tests. |
| OD-PROTOCOL | Selected dialogue contract; unqualified | Strict `worldspeaker.compat.v5` / `conv-06.1`, manifest revision `abil-01.1`, with immutable primary events, inert optional attention records/loss status, bounded dispatched-progress context, exact authenticated binding, captured `abilityPolicyRevision`, and no downgrade. The matching authenticated configuration bundle supplies policy contents; a frame revision alone is not authority. Deliberate text-only final answers are capped at 1,980 UTF-16 units without truncation. Player-only audience remains mandatory; ability wire operations remain unavailable. | Shared v5 schema fixtures, both production parsers, actual authenticated Java↔TypeScript discovery/revision exchange, retained v2/v3/v4 rejection evidence, revision/hash mismatch, replay/conflict and stale-audience negatives, and actual-host ordering/route qualification. |
| OD-ATTENTION | Selected bounded capture; unqualified live behavior | Restart-only subscriptions default empty and cover only connection, dimension, death, respawn, and sampled block-position changes for at most 32 granted UUIDs. Capture is bounded by count/bytes/age/rates; observations are inert and attach only to a separately admitted request by that requester. Mandatory freshness remains independent of subscriptions. | Offline flood, expiry, isolation, loss, freshness, queue-acceptance, and tick-budget tests; then authorized live callback-order, no-event-turn, useful-context, privacy, and tick-cost evidence. No continuous-motion or full WORLD-03 claim. |
| OD-CONVERSATION | Selected privacy boundary; host isolation open | One binding-derived shared game session for all admitted players on a server/world/character/agent; current primary invocation alone supplies authority; replies target only its original current connection. Command input and targeted replies are not confidential because they enter shared game context; natural/follow-up chat is vanilla-public. | A/B history and sender/audience tests, follow-up anchor tests, plus actual host workspace/bootstrap/memory isolation from private admin. Stop before deployment if that isolation cannot be enforced. |
| OD-SCHEDULING | Selected policy; live behavior unqualified | One substantive turn per character, round-robin between player queues, FIFO within each queue. Limits: 2 pending distinct requests per player and 16 globally, active work separate; local first replies do not wait for substantive turns. A queued head may be preceded by at most 15 other starts plus the active request; with only A/B, at most one additional A start may precede B. Queue order is not a wall-clock guarantee when active work exceeds B's 15-second pending lifetime. | Production ingress-to-render tests for repeat bursts, sustained A refills, queue rotation, and a bounded useful B completion; explicit over-budget expiry control. T3 host/provider/client/human evidence remains separate. Reject the policy if it cannot demonstrate fair service. |
| OD-BUDGETS | Selected conversation limits; live behavior unqualified | Pending age is fixed at 15 seconds from original capture. `executionBudgetSeconds` is an optional restart-only setting, default 30 and restricted to 1–30 seconds; it is snapshotted at admission, starts once at activation, and never renews. The absolute cap is original capture + 15 seconds + the snapshotted budget. One queued notice may start at capture+10 seconds; active notices may start at forwarding+10/+20 seconds only while live; max three per request and one per tick within the shared start budget. Expiry/cancellation revokes local delivery eligibility but does not prove upstream execution stopped. Tool-heavy work remains blocked without a publicly supported, approved workload. Ability-specific query/effect deadlines are separate contract ceilings. | Fake-clock boundary/race/saturation tests plus authorized live measurements under normal load, repetition, outages, cancellation, and stale context; no tool-heavy qualification until support and approval exist. |
| OD-CATALOGUE | Selected metadata contracts; execution blocked | `worldspeaker.core.observe_player` and `worldspeaker.core.strike_lightning`, contract v1, including bindings, delegation, numeric limits, freshness, accounting, and outcomes in the [ability contract](ability-contract.md). Both are `UNIMPLEMENTED`, `UNQUALIFIED`, and `UNAVAILABLE`. The two contracts are projected per admitted request as context metadata; neither is a registered tool or effect path. | Positive/denial/boundary/retry tests and authoritative game-state evidence, including health above 20, full-footprint protection, suppression of native lightning side effects, and crash-window reconciliation. Unsupported modes remain unavailable. |
| OD-PERMISSIONS | Partial | Default-deny named grants, complete-grant selection, explicit denial precedence, and v1 requester/target/damage delegation are selected. Per-request discovery exposes only the current requester's matching complete-grant summaries (never fragments or the full policy); the disposition is denied or conditional, not execution-authorized. Grant/profile representation, staff controls, provider choice/mapping, and administration syntax remain open. | Deterministic allow/deny/ceiling precedence, no fragment union, requester delegation, revocation, provider outage, and concurrent resource-reservation tests. No third-party provider is assumed. |
| OD-STATE | Partial | Consequential identity/intent/outcomes survive resets, with no automatic evidence deletion in the initial contract and fail-closed storage capacity. Storage engine, finite ceiling values, migration/restore semantics, and reconciliation interface remain open. | Crash-window, unknown-outcome, agent-reset, full/corrupt/unavailable store, history-import, backup/restore, and migration tests. Unresolved evidence cannot disappear through retention. |
| OD-CONFIG | Partial; implementation present, runtime qualification open | One strict UTF-8 startup bundle v1 is selected behind the two-key Java bootstrap and exact OpenClaw `enabled`/`configFile` section. It has an incompatible migration, fixed bounds, private credential references, exact-byte configuration handshake binding, and restart-only immutable snapshots. Java's off-tick startup reader uses a bounded, SHA-256-pinned packaged Node descriptor helper with `O_NONBLOCK`/`O_NOFOLLOW` opens and descriptor/path/ancestor metadata rechecks. It requires actual Node.js `24.22.1` from the trusted service-account `PATH`; only absolute entries are considered, absent/unusable `PATH` or missing/wrong-version Node fails closed, and startup does not install Node. This Node pin is also the OpenClaw host runtime candidate. These checks rely on the service-account trust boundary and do not isolate against a hostile process with the same service UID. Finite ability-policy validation does not enable either operation. | Pinned-Linux T4 runtime tests of the helper, service `PATH`/Node failure cases, and file-boundary behavior; cross-runtime actual-file handshake and mixed-generation checks; coordinated restart/retirement tests; document credential rotation, template, and private migration procedures. Do not infer deployed race-safety from offline FIFO/race regressions. Actual OpenClaw host/workspace isolation, vanilla-client testing, the CHAR-02 publication gate, and runtime ability testing remain blocked; both game abilities remain unavailable. Preserve the configured-persona hard block and keep RESP-04 QA-005 OPEN / historical cause unknown. |
| OD-PERFORMANCE | Open | Which provider/model, hardware class, and representative load qualify ordinary completion, fairness, first-response budgets, and tick-health targets? | Recorded versions, workload, sample sizes, distributions, maxima, deadline misses, and failures. No provider-independent completion guarantee is assumed. |
| OD-EVALUATION | Selected offline workflow; human decision required | Test-only bounded CLI imports body-free numeric evidence and sanitized, consent-based human review. Reports remain descriptive and can never assign an automatic quality pass. | Unit/schema/security tests offline; separate human-live multiplayer session, independent evaluator, actual-host isolation and client display evidence. Missing T3 evidence remains blocked. |
| OD-DISTRIBUTION | Open | Which public platform, artifact packaging, and supported dependency combination are appropriate? | Current project-name/submission/dependency-license review and independent clean installation. Platform approval is not guaranteed. |

## Conservative candidate defaults

The product explicitly requests conservative defaults. The following are
**starting settings for qualification**, not service-level guarantees, tested
results, or substitutes for resolving the decisions above. Some reflect a selected
policy, while numerical capacities remain candidates. Configuration keys, units,
and bounds are stated where implemented. Ability-specific ceilings in the ability
contract are normative and cannot be raised by these defaults.

| Area | Candidate default | Constraint that cannot be relaxed |
| --- | --- | --- |
| Characters | One | Identity remains stable independently of display name. |
| Attention | Optional subscription list empty by default; mandatory local freshness independent | No ambient-chat turn per line, implicit online-player subscription, or unsolicited intervention. |
| Ability authority | No grants until explicitly configured | Persona and presets cannot silently authorize effects. |
| First-reply fallback cutoff | 750 ms after original server capture; reserve 250 ms for validation/dispatch | A complete OpenClaw message can reply earlier; partial streams do not suppress the local fallback. Misses remain failures, not an extended deadline. |
| Substantive agent concurrency | One active turn per character | First reply must not wait behind it; this default is rejected if it cannot meet interaction/fairness requirements. |
| Pending work per player | Two distinct requests | Preserve changed requests or explicitly refuse overflow. Active work is counted separately. |
| Global pending work | Sixteen distinct requests | Finite global and per-player bounds; overflow gets a prompt safe disposition. |
| Scheduling | Round-robin between players, with documented handling of relevant urgent context | One player's repetition cannot starve another; no uncontrolled effect ordering. |
| Repeat suppression | Exact repeats of live, serviceable work coalesce for its full lifetime; after termination, same-scope exact repeats are refused as `RATE_LIMITED` / `REPEAT_GUARD` through original `t0 + 6 s`, inclusive. The in-memory guard holds at most 8 keys per UUID and 64 globally. | Fixed nonrenewable interval; no replay, silent merge, or eviction of unexpired keys. Refusal consumes no new-work token; changed input remains distinct. At capacity, explicitly refuse new work with a separate cause. |
| Pending-work age | Fifteen seconds | Expire or revalidate obsolete work; never use the limit to delay the first reply. |
| Active execution budget | `executionBudgetSeconds=30`; optional restart-only integer range 1–30 seconds | Snapshotted once; no renewal; total lifetime is capped at original capture + 15-second pending limit + the selected budget. Tool-heavy execution remains blocked until publicly supported and separately approved. |
| Follow-up window | Thirty seconds after the relevant exchange | Player/world/character attribution and logout/reset checks still apply. |
| Progress interval | Ten seconds without an answer while work remains live | Truthful, useful, bounded dialogue, not invented progress or public telemetry. |
| Game effects | Disabled until granted and individually bounded | Freshness, authorization, deduplication, and outcome recording still apply. |
| Diagnostics | Private structured metadata, not full conversation bodies | No credentials, raw traces, or technical broadcasts in player chat. |
| Game reply audience | Only the original current requester connection | Targeted delivery is not a private game transcript; admitted inputs and replies share gameplay context. |

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
