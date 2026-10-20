# Deterministic ability contract

## Purpose and status

This document is normative for the ability and world-action stories in the
[product specification](specification.md). It defines the required behavior of
the authorization boundary, not an implemented API, final wire schema, or final
catalogue of game powers. Concrete ability identifiers and serialization remain
[open decisions](open-decisions.md).

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
above and have positive, denial, boundary, freshness, and retry tests. The exact
fields, effect types, permission-node names, and numeric limits are not settled.
Selecting them is an explicit open decision, not permission to implement raw
commands temporarily or to advertise unimplemented powers.

### Illustrative shape, not a supported ability or configuration format

```yaml
abilityId: worldspeaker.example.observe-player
contractVersion: 1
kind: query
arguments:
  targetPlayerId: authenticated-player-identifier
authorization:
  namedGrantRequired: true
  default: deny
  delegation: explicitly-defined-by-server-policy
constraints:
  maxTargets: 1
  requireCurrentTarget: true
result:
  worldIdentity: authoritative-world-identifier
  observedAt: authoritative-observation-time
  fields: only-the-documented-authorized-fields
```

This shows separation of typed operation, authority, bounds, and result. It is
not a grant, executable payload, implemented parser, or final API. Persona
guidance such as "be a generous god" cannot change any field or requirement.

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

Schema validation alone is not enough: integration tests must verify actual
server enforcement and authoritative game outcomes for the selected catalogue.
