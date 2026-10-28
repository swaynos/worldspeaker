# Deterministic ability contract

## Purpose and status

This document is normative for the ability and world-action stories in the
[product specification](specification.md). It defines the required behavior of
the authorization boundary and the selected initial catalogue, not an implemented
API or final wire serialization. The two contract-v1 entries below are design
selections and remain unavailable until implemented and qualified. Exact loader
hooks, protection integrations, serialization, and test evidence remain tracked
in [open decisions](open-decisions.md).

**Persona describes a character. Ability policy authorizes operations.** A
persona may be rough prose. An ability must be a named, typed, versioned,
server-enforced contract, comparable to a refined server permission rather than
an instruction to the model to behave responsibly.

Deterministic means validation, authorization, limits, and outcome semantics
are explicit and testable against authoritative inputs and current state. It
does not mean model wording or a changing Minecraft world is deterministic.

## Contract required for every ability

Queries and effects both require contracts. Being read-only does not authorize
unbounded scans or access to private information.

| Contract element | Required definition |
| --- | --- |
| Identity and version | Stable ability ID, contract version, query/effect classification, human-readable purpose. Renaming a character does not rename its abilities. |
| Input schema | Typed fields, required/optional values, units, finite numeric/string/list bounds, supported enumerations, and rejection of unknown or authority-bearing extra fields. |
| Result schema | Typed success, denial, failure, partial, and unknown information as applicable; authoritative observation time and world identity where relevant. |
| Principal binding | Intended server, world, character, agent connection, originating request, and authenticated requester, taken from trusted envelopes rather than model arguments. |
| Permission policy | Named grants, explicit denial, requester/delegation rules, applicable staff permissions, and supported permission-provider behavior. |
| Target policy | Allowed target types, how authenticated identities resolve, dimension and spatial scope, target count, eligibility, and unavailable-target behavior. |
| Preconditions | Required current world conditions, freshness rules, live-event eligibility, and checks repeated at execution. |
| Work limits | Maximum query work/result size or effect magnitude, rate, quota, cooldown, concurrency constraints, and timeout. Every applicable bound is finite. |
| Execution semantics | Tick-thread scheduling, resource reservation, ordering/conflict handling, cancellation boundary, and whether partial effects are possible. |
| Reliability | Action identity, immutable intent, deduplication scope and retention, durable recording requirements, safe retry/reconciliation behavior, and crash-window treatment. |
| Audit and privacy | Required private evidence, access restrictions, sensitive fields excluded from logs/results, and retention behavior. |
| Public presentation | Whether any deliberate player-facing narration is appropriate. Raw execution output is never automatically public. |

The ability's description and typed schema are discoverable by the associated
agent. Server-side validation remains mandatory even if OpenClaw also validates
tool arguments. Tool discovery is not a permanent authorization token.

## Principals and delegation

The server must be able to distinguish:

- The authenticated connection and its configured agent/character association.
- The player or authorized administrative action that originated the request.
- The intended targets and the current server/world instance.
- The current policy revision and whether the source event is live or historical.

The model may supply an allowed target selector as an argument; it cannot supply
a replacement requester, effective operator level, private routing destination,
policy revision override, or assertion that a historical request is live.

An ability must state its delegation model. For example, a character may have a
limited power that ordinary players do not personally possess, while only
specified players may request it or be valid targets. Alternatively an ability
may require the requester's own permission. These are explicit server rules,
not an automatic union of player and agent privileges and not an automatic
assumption that either identity may impersonate the other.

Private administrator conversation is not an implicit override. A privileged
administrative game operation, if supported, requires its own authenticated,
authorized request path and the same execution safeguards.

## Grant evaluation

1. Resolve the exact supported ability and contract version; unknown versions
   and malformed inputs are denied before game work.
2. Resolve trusted principals, current targets, origin, and current policy.
3. Apply explicit denials. Any applicable denial blocks the operation.
4. Require a complete applicable allow grant. Do not combine unrelated fragments
   of different grants into a broader permission that no grant actually allows.
5. Apply global ceilings, ability ceilings, and the selected grant's restrictions
   together. A narrower applicable ceiling cannot be enlarged by a role or persona.
6. Validate delegation, target scope, freshness, and current preconditions.
7. Check and reserve applicable quotas/cooldowns atomically with respect to
   competing requests, then schedule bounded execution.
8. Recheck revocable authority and material preconditions at the execution
   boundary. If they changed, reject or cancel safely rather than using stale permission.

No applicable allow means deny. An unavailable authority source, invalid policy,
unresolved identity, or ambiguous widening of authority fails closed. Explicit
denials take precedence regardless of role/profile composition. If several
complete grants qualify, selection and resource accounting must follow a
documented deterministic rule, not model preference or iteration order.

Named profiles may bundle grants for convenient administration, but are not a
second permission system with different semantics. If a third-party permission
provider is selected, its mapping and precedence must preserve these rules.
No particular provider is currently required or qualified.

Wildcard syntax, if supported, must have documented finite registry semantics;
it must not silently grant new future abilities after an upgrade. Giving a
character operator status or unrestricted command access is not a substitute
for implementing a grant.

## Execution boundary

Before mutation, the server validates the current target, dimension, spatial
scope, limits, preconditions, and freshness. It performs required reads and
effects through supported, bounded server-thread scheduling. Network and model
waits never occur on that thread.

Each effect has an action identity scoped to the server/world/character and
bound to immutable validated intent. A repeated identity with different intent
is rejected. A repeated identical action returns the known state or outcome
instead of executing again. Changing an ID is not a way to bypass quotas,
cooldowns, request eligibility, or explicit denial.

Consequential execution requires durable evidence. If the required store is
unavailable, do not perform an unjournaled consequential effect. Because world
mutation and durable storage may not share a transaction, a crash can still
leave execution unknown. Preserve that uncertainty; do not claim universal
exactly-once execution or replay the action blindly.

Contracts specify when quota/cooldown reservations are charged, committed,
released, or retained after rejection, cancellation, partial execution, and
uncertainty. Cancellation before execution prevents the effect; cancellation
after execution is not rollback. Compensation, where supported, is a separate
authorized operation rather than an assumed inverse.

Multi-step operations must define step ordering and partial outcomes. A failure
at step two does not prove step one never happened. If outcome cannot be
established from authoritative evidence, the affected portion remains unknown.

## Structured lifecycle and results

These are required logical distinctions, not final serialized enum names.
Request admission, action submission, and dialogue completion are separate
lifecycles and must not be conflated.

| Action state or outcome | Meaning | Safe handling |
| --- | --- | --- |
| Rejected | Invalid, unauthorized, out of scope, or inadmissible before execution. | Return a structured reason; no effect occurred through this attempt. |
| Admitted/reserved | Validation/admission or a resource reservation has occurred. | Not proof of execution; remain subject to revocation and current preconditions. |
| Submitted | Work has reached the execution boundary but completion is not confirmed. | Do not announce success or retry merely because the result is delayed. |
| Executed | Authoritative evidence confirms the defined effect. | Return the known outcome; this is not proof a player saw a message or heard a sound. |
| Failed | Authoritative evidence establishes the failure and any known consequences. | Describe actual state; do not imply rollback or absence of effects without evidence. |
| Partially executed | Known steps/effects succeeded and others did not. | Return per-step facts; no automatic retry of successful portions. |
| Expired/cancelled | Work was retired with a known execution boundary. | State whether execution was prevented; unresolved in-flight execution remains unknown. |
| Unknown | Execution may have occurred but available evidence cannot establish the outcome. | Reconcile by identity; no blind retry, refund, compensation, or success claim. |

Private results include the action identity, contract version, applicable policy
revision, current lifecycle/outcome, relevant observation/execution times,
structured reason codes, and sufficient safe facts to support reconciliation.
Sensitive internal diagnostics stay in authorized operator channels. The agent
gets usable structured outcomes, not console dumps.

Public dialogue may describe a confirmed outcome or uncertainty in appropriate
language, but tool results are never automatically copied to chat. Raw command
feedback must not leak to operator players through administrative broadcasts.

## Observation and effect catalogue requirements

The delivered product must include a small documented catalogue containing both:

- Useful bounded observations of relevant players and local world state.
- Useful, structured, explicitly granted vanilla-compatible game effects.

Each actual catalogue entry must instantiate every applicable contract element
above and have positive, denial, boundary, freshness, and retry tests. The initial
standard catalogue selects two baseline capabilities. The following common rules
are part of both contracts; they are logical schemas, not an implemented parser
or a claim that OpenClaw already exposes these tools.

### Common v1 bindings, schemas, and authority

- **Typed input:** Each entry accepts exactly its listed fields. UUIDs use the
  canonical 36-character representation; strings are not name selectors or
  commands. Unknown fields, null required fields, coercions, non-finite numbers,
  and authority-bearing arguments are rejected. The complete ability request
  and result are each limited to 4 KiB of UTF-8 JSON; no arbitrary NBT or lists
  of entities are returned.
- **Trusted binding:** The server supplies server/world/character identity,
  authenticated agent connection, originating invocation/request, requester UUID,
  live/history origin, policy revision, and original capture time. New executions
  require a live, admitted, non-expired player request and a currently connected
  requester. Historical input and private administrative conversation cannot
  start these v1 executions. An existing action's authorized outcome lookup may
  outlive its request and target eligibility, but cannot create new game work;
  check current access to the recorded intent/outcome, not whether the old target
  is still online or nearby. A separate administrative effect path is not selected.
- **Delegation:** The named ability grant is granted to the bound character for
  an explicitly selected set of requesters. Player operator status, conversation,
  and unrelated player permissions confer no implicit grant. A complete grant
  defaults to self-targeting; targeting another player requires that same grant
  to explicitly include the target's UUID. A grant must state its allowed
  dimensions; its spatial scope defaults to the world border and may be narrowed,
  never enlarged past the common range/border ceilings. If multiple complete
  grants qualify, select the lexicographically smallest stable grant ID after
  applying explicit denials; do not union their fields or choose a broader grant
  when the selected grant's limits block work. No applicable complete grant means
  deny. Provider mapping remains unqualified; provider failure is not an
  authorization fallback.
- **Target scope:** Resolve one UUID by authoritative online-player lookup. The
  requester and target must be in the same server/world instance and dimension,
  no more than 64 blocks apart by Euclidean distance measured between their exact
  floating-point feet coordinates (`Entity.getPos()`) at execution
  ($\sqrt{(x_1 - x_2)^2 + (y_1 - y_2)^2 + (z_1 - z_2)^2} \le 64.0$), within the
  grant's spatial scope and the world border. Self-targeting satisfies the range
  check but not the grant requirement. Neither entry loads chunks or searches
  for an alternate target. Offline, hidden-by-policy, unresolved, unloaded, or
  out-of-scope targets produce a safe structured denial, not fabricated state.
- **Revalidation:** Resolve policy and eligibility on admission and again on the
  tick thread immediately before reading or mutating. Disconnect, dimension
  change, revocation, cancellation, or expiry prevents new work. A new tool or
  action ID never refreshes the original invocation time.
- **Time and identity:** Every result identifies `serverId`, `worldId`,
  `characterId`, `requestId`, ability ID, contract version `1`, policy revision,
  and the query/action identity. The `Id` identity fields use UUIDs; ability IDs
  are namespaced strings no longer than 256 ASCII characters. Policy revisions
  are nonnegative JSON-safe integers, and recorded results retain the revision
  used for their operation even when a later access check uses a newer policy.
  A dimension identifier is not a world-instance identity. `serverEpochId`
  identifies the server process clock epoch;
  `observedAt`/`executedAt`, when known, are nonnegative integer monotonic
  milliseconds within that epoch, bounded to the JSON safe-integer range.
  Reconnects/restarts cannot make old timestamps current or comparable across
  epochs. The wire representation of these bindings remains OD-PROTOCOL work.
- **Outcome schema:** `state` is one of the logical values `rejected`, `admitted`,
  `submitted`, `executed`, `failed`, `partial`, `expired`, `cancelled`, or `unknown`,
  with the semantics in the lifecycle table. `reasonCode` is one of `none`,
  `invalid_input`, `unsupported_contract`, `unsupported_state`,
  `permission_denied`, `target_unavailable`, `out_of_scope`, `protected_area`,
  `stale_context`, `rate_limited`, `concurrency_limited`, `deadline_expired`,
  `cancelled`, `storage_unavailable`, `execution_unavailable`, `execution_failed`,
  or `outcome_unknown`. `facts` is the entry's typed payload or null; missing
  evidence is null, never a success-shaped default. Only a known rate/cooldown
  restriction may include `retryAfterMs` (integer 0–30,000), which is not a grant
  to retry. Other outcomes use null. A completed query uses `executed` to mean a
  completed snapshot, not a game mutation; expired queries have no reusable
  snapshot. Partial/unknown effects return only known per-step facts. Logical
  field names/values here still require mapping into the eventual wire envelope.
- **Audit/privacy:** Store access-controlled identity, policy, timing, disposition,
  and quota metadata; consequential records additionally retain the immutable
  intent and known outcomes needed for recovery. Do not log chat bodies, full
  inventories, item NBT, secrets, or precise coordinates in routine diagnostics.
  Query snapshots are not retained as conversational/audit bodies by default.
  Consequential coordinates, if needed for reconciliation, stay in the private
  action record. Returning cached results still requires current authorization.
- **Presentation:** Results go privately to the associated agent. Any public
  narration must use the separate intentional message channel, its authorized
  audience, and current lifecycle/freshness checks. Neither result delivery nor
  server-side presentation dispatch proves that a player saw the effect.

### 1. Query: `worldspeaker.core.observe_player` (Contract v1)

- **Purpose:** Inspect grant-authorized physical status and local biome of one
  active player. These facts are not assumed public merely because they are
  read-only.
- **Input schema:**
  - `targetPlayerId` (UUID, required): An online target within the common scope.
- **Grant node:** `worldspeaker.ability.observe_player` (default: deny).
- **Preconditions/freshness:** Target is connected, alive, non-spectating, and in
  an already loaded, ticking chunk. The request must remain live. The snapshot
  must be read within 1 second of server acceptance of this query and delivered
  only while no more than 2 seconds old and still authorized. Death, departure,
  or a dimension/world change before delivery invalidates it. A snapshot is not
  cached authority for a later effect; relevant state must be refreshed again.
- **Work limits:** One target lookup and a fixed set of player/biome reads; no
  inventory iteration, chunk load, entity scan, or world scan. At most one
  in-flight query per requester and sixteen per character. Limit new snapshots
  to one per 2 seconds per requester and four per second per character, without
  burst accumulation. Narrower configured ceilings win.
- **Execution/accounting:** Reserve an in-flight slot and rate allowance
  atomically after validation. Read a consistent snapshot in one bounded
  tick-thread task without network or persistence waits. Charge the allowance
  when reading starts, including a failure after that point; release a reservation
  cancelled or rejected before reading. A timed-out queued task must recheck its
  deadline and cannot read later. Counters survive connection/agent resets; after
  a server restart without recoverable rate state, wait 2 seconds before new
  snapshots rather than assuming all prior allowances were unused. Query failure
  has no game-mutation consequence.
- **Authoritative success payload:** In addition to the common result fields:
  - `targetPlayerId`: The resolved UUID, and `observedAt` in the server epoch.
  - `health`: Finite, nonnegative float32 health points, no greater than the
    simultaneously observed `maxHealth`; absorption is not included.
  - `maxHealth`: Finite, positive float32 current maximum health, including
    applicable attributes/status effects, up to the finite float32 maximum.
    Values above 20 are valid and must not be clamped to a default player maximum.
  - `dimension`, `biome`, `holdingItem`: Registry identifiers, each 1–256 ASCII
    characters in Minecraft identifier syntax. Bare hand is `minecraft:air`.
  - An unsupported/non-finite health state or oversized registry value returns
    a structured unsupported-state failure, not a truncated or fabricated value.
  Precise coordinates, item counts/NBT, other inventory contents, and private
  configuration are excluded.
- **Retry/reliability:** Assign one query identity to immutable target/binding
  intent before submission. While a snapshot is fresh, an identical retry returns
  that snapshot after authorization and invalidation checks, without a new read
  or charge; conflicting intent is rejected. After 2 seconds, return an expired
  result rather than silently re-reading under that identity. Cache loss or a
  server-epoch change expires the old query. Retain query-identity metadata for
  the request lifetime even if its snapshot is evicted; loss of that metadata
  invalidates the affected outstanding request context rather than treating old
  identities as new queries. A new query may read again only under current limits.
  Read-only results need no consequential action journal.

### 2. Effect: `worldspeaker.core.strike_lightning` (Contract v1)

- **Purpose:** Present one controlled lightning strike at a deterministically
  resolved player location, optionally applying narrowly granted damage. This
  is not an unrestricted vanilla lightning entity or arbitrary summon command.
- **Input schema:**
  - `targetPlayerId` (UUID, required): An online target within the common scope.
  - `visualOnly` (boolean, optional, default: `true`): `false` requests the
    separately authorized damage mode; it never enables fire or secondary effects.
- **Grant node:** `worldspeaker.ability.strike_lightning` (default: deny).
- **Mode/delegation policy:** The complete grant must explicitly allow visual
  presentation. Damage additionally requires `allowDamage: true` in that same
  grant and a finite `maxDamage` greater than zero and no more than 5 health
  points. These are server policy fields, not model arguments; absent damage
  authority denies `visualOnly: false` rather than silently downgrading it.
  The common requester, explicit-other-target, dimension, and range rules apply
  independently to both modes. Operators do not bypass them.
- **Placement:** At admission, resolve the target's current feet block
  `[x, y, z]` and dimension from server state. The presentation origin is exactly
  `[x + 0.5, y, z + 0.5]`. There is no random offset, nearby-location search,
  lightning-rod redirection, model-supplied position, or chunk loading. Bind this
  resolved placement to the immutable action intent. If the target changes feet
  block or dimension before execution, reject as stale rather than chasing it.
- **Protected footprint:** The nine-by-nine block columns `x - 4` through `x + 4`
  and `z - 4` through `z + 4`, over the dimension's build height, form the
  conservative authorization footprint (81 columns, at most four chunks).
  The complete footprint must be inside the world border and grant region,
  loaded, and permitted by configured protection policy. Any intersection with
  protected spawn or a protected claim denies the action, even when the target's
  own block is unprotected. Check both the footprint and target authorization.
  This margin is not permission for native side effects inside it.
- **Protection sources:** Respect server-configured protected regions and spawn
  protection without an operator exemption. The installation must explicitly
  declare whether external claims are authoritative using the configuration key
  `protection.externalClaimsMode`:
  - `NONE`: Validate vanilla spawn protection and the world border only; do not
    require an external claim adapter.
  - `REQUIRED`: Require a qualified external claim integration for all configured
    claim providers; fail closed if an adapter is missing, unsupported,
    unavailable, or indeterminate.
  The example configuration defaults to `REQUIRED`. An administrator must set
  `NONE` explicitly on servers with no claim plugins. Protection changes are
  rechecked at the execution boundary using bounded local lookups rather than
  remote tick waits.
- **Preconditions/freshness:** Target is online, alive, non-spectating, and in a
  ticking chunk; the impact column has sky access as determined from current
  loaded server state. The original invocation is at most 15 seconds old, its
  request remains active, and at most 5 seconds have elapsed since first server
  admission of the action. Retries do not reset either deadline. Recheck all of
  these conditions, the resolved placement, authority, and protection immediately
  before presentation/damage. Missing authoritative state fails closed.
- **Magnitude and side effects:** One vanilla-compatible lightning presentation,
  no more than one second of presentation lifetime, no natural re-strikes, and
  no more than one damage attempt against the named player. In visual mode there
  is no damage. In damage mode, the attempted amount is at most the grant's
  `maxDamage`, 5 health points, and `max(0, currentHealth - 1)`, whichever is
  smallest. Here, `currentHealth` is the target player's base red health points
  only (`LivingEntity.getHealth()`), excluding absorption. To guarantee non-lethal
  behavior, the server must apply a dedicated damage source that suppresses
  secondary reflection (such as the Thorns enchantment), or measure health after
  mitigation and abort the damage step before application if remaining health
  would fall below 1.0 health point. Respect normal damage mitigation, absorption,
  invulnerability, and protection vetoes; never bypass them. The operation is
  non-lethal and reports actual damage rather than assuming the requested amount
  was applied. A target already below 1 health point is never healed or further
  damaged by this operation. Qualification must establish that applicable damage
  modifiers/hooks cannot amplify actual loss past these ceilings or make the
  operation lethal; unsupported combinations disable damage mode.
- **Prohibited mechanics:** In both modes, prevent fire placement/burning,
  knockback, damage to bystanders/mobs/items, entity transformations, explosions,
  copper changes, lightning-rod/redstone activation, weather changes, and other
  gameplay mutations from the presentation. Optional impact audio is at most
  one sound per authorized recipient within 32 blocks, volume at most 1, with
  at most 32 recipients and no global thunder broadcast. Ordinary distant visual
  rendering is not an authorization to damage or mutate anything there.
  Suppression must be verified on the selected stack: setting a cosmetic flag
  alone is not evidence that all native lightning behavior is suppressed. If
  either mode cannot enforce these bounds, that mode remains unavailable; do not
  temporarily substitute an unrestricted bolt or globally change game rules.
- **Rate/concurrency limits:** One effect per originating invocation; cooldown
  of 30 seconds per requester and per target, shared across visual/damage mode;
  at most one execution per character per second and one admitted/in-flight
  lightning action per character. These are ceilings, not charges that can be
  bypassed with another grant, target alias, action ID, or connection. Narrower
  policy ceilings win.
- **Execution/accounting:** Atomically reserve invocation eligibility, in-flight
  capacity, and cooldowns. Durably prepare immutable intent off-thread before
  scheduling one bounded Fabric server-thread task. Check cancellation and all
  material conditions again in that task. Before the first presentation/damage
  operation, commit the reservations; then dispatch presentation followed by the
  optional damage attempt, capturing per-step facts without blocking storage
  writes on the tick thread. Persist those facts asynchronously before returning
  a confirmed durable outcome. An interrupted/failed outcome write is handled
  according to available authoritative evidence, never assumed to mean no effect.
  Definite rejection, expiry, or cancellation before any operation releases
  reservations. Once any operation may have occurred, retain charges and
  invocation consumption; no automatic refund or retry follows partial, failed,
  or unknown execution.
  Unknown actions block conflicting requester/target work until reconciled, even
  after the nominal cooldown. Cooldowns survive reconnect/restart; if remaining
  time cannot be established safely, retain the full interval.
- **Identity/durability:** The integration assigns `actionId` once before the
  first submission and reuses it on retries, not once per delivery or tick task.
  Identity is scoped to server/world/character and bound to originating request,
  requester, target, normalized mode, and server-resolved placement. Look up an
  existing identity before deriving a new placement: identical duplicates use
  the stored intent and return known state after authorized lookup even if the
  target has since moved. Changed intent is rejected. Never spawn again because
  a receipt was lost. Retain consequential identity/intent/outcome evidence across
  agent and server resets. The initial retention policy does not automatically
  delete these records or unresolved outcomes; at a configured finite storage
  ceiling, stop new effects rather than discard safety evidence. Storage engine,
  ceiling values, migration, and
  recovery-interface qualification remain OD-STATE/OD-CONFIG work. Unavailable
  required storage prevents execution, and an interrupted commit/mutation
  boundary remains unknown until authoritative reconciliation.
- **Authoritative success/partial payload:** In addition to the common fields,
  return `actionId`, `targetPlayerId`, dimension identifier, `executedAt` when
  known, integer impact `coordinates: [x, y, z]` within the qualified world's
  border/build-height bounds, and the normalized `visualOnly` boolean. Report
  presentation dispatch separately from damage in `steps`, a list of at most two
  records. Each has `kind` (`presentation` or `damage`) and `state` (`prevented`,
  `confirmed`, `failed`, or `unknown`). Confirmed damage facts include
  `healthRemoved` and `absorptionRemoved` (finite floats, each 0–5 and sum at most
  the allowed attempted damage), and `damageDisposition` (`applied`, `mitigated`,
  `invulnerable`, `protection_veto`, or `non_lethal_limit`). Unknown numeric facts
  are null rather than zero. Zero confirmed damage can be a valid outcome, not
  proof of a failed presentation.
  A presentation followed by failed damage is partial, not rollback; unavailable
  evidence is unknown. No coordinates or success facts are invented for a
  rejected/unresolved placement, and server dispatch is not a client receipt.

Persona guidance such as "be a generous god" cannot override these schemas or grant policies.

## Required verification

Tests linked to ABIL-01 through ABIL-05, WORLD-01 through WORLD-04, and the
recovery stories must include:

- Unknown ability/version, missing required field, invalid type, extra identity
  override, oversized result request, and invalid target.
- Missing grant, explicit denial, conflicting profiles, permission-provider
  unavailability where applicable, and inability to widen a global ceiling.
- Requester/target scope, other players' identities, another world, historical
  origin, and instruction-like text claiming to be an administrator.
- Revocation after discovery or queueing, changed world state, expired context,
  concurrent quota reservations, and attempt to bypass limits with fresh IDs.
- Duplicate action IDs with identical and conflicting payloads, lost replies,
  crash windows, partial effects, and unresolved outcomes.
- Private structured results and public dialogue checked independently for
  ordinary players and operator players; no raw telemetry is public.

Catalogue-specific qualification must additionally include:

| Contract | Required positive, boundary, and failure evidence |
| --- | --- |
| Common v1 | Self and explicitly allowed other target; denied unlisted requester/target; 64-block boundary; dimension/world mismatch; extra fields and oversized frames; policy revocation before execution and before cached-result delivery; restart clock-epoch separation. |
| Observe player | Authoritative health/max-health above 20, including a valid health boost; bare-hand `minecraft:air`; biome/item identifier bounds; no inventory/NBT/coordinates; dead/offline/unloaded target; exact rate/concurrency boundaries; cancellation before read; duplicate fresh snapshot, expiry, and cache loss without an uncharged re-read. |
| Lightning placement/protection | Exact feet-block placement; movement/death/dimension/disconnect during durable preparation; 15-second original-age and 5-second action-age boundaries; grant/world-border edges; a claim touching the footprint but not the target; unavailable/unsupported protection sources; no chunk load or alternate strike position. |
| Lightning magnitude | Visual-only default; denial of damage without the complete damage grant; capped/non-lethal damage with mitigation, absorption, and invulnerability; zero damage; nearby bystanders, mobs, items, flammable blocks, copper, lightning rods, and redstone remain unaffected; no delayed fire, re-strike, or global audio. |
| Lightning reliability | Simultaneous requester/target reservations; cooldown sharing across modes/grants/connections; pre-operation reservation release; post-operation charge retention; identical/conflicting IDs; lost result after execution; partial presentation/damage; crash windows and unknown-outcome blocking; restart, full/unavailable store, and inert historical replay. |

Schema validation alone is not enough: integration tests must verify actual
server enforcement and authoritative game outcomes for the selected catalogue.
