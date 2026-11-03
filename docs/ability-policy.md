# Private ability-policy administration

## Status and authority

The supported policy-change surface is the private startup bundle, edited by an
administrator who is explicitly authorized for the service account's operating
system files. The policy is not writable by players, staff status, character
persona, private agent conversation, an in-game command, or an OpenClaw tool.
There is no live policy-edit API, control UI, provider adapter, reload command,
or permission-plugin integration.

This is a configuration and evaluation surface only. A configured grant is a
conditional policy description; it is not an execution permit. Both
`worldspeaker.core.observe_player` and `worldspeaker.core.strike_lightning`
remain **UNIMPLEMENTED / UNQUALIFIED / UNAVAILABLE**. No ability tool is
registered, player requests retain `toolsAllow: []`, and every valid intent is
rejected with `effectStarted: false`. Nothing in this procedure starts a game
operation or establishes a world fact.

## Prepare and apply a change

1. Obtain service-account file authority through the approved operating-system
   procedure. Do not derive authority from player/operator status or a message
   in either game or administrator conversation.
2. Coordinate a stop of both the Minecraft service and companion before changing
   policy. Back up the existing bundle and credential privately; do not place
   either in source control, a preset, a ticket, or player-visible diagnostics.
3. Prepare a complete replacement bundle using startup schema v1. Change only
   the intended policy fields; keep the configured binding and deployment facts
   exact. Increment `abilityPolicy.revision` for every policy change. This
   integer is a snapshot revision, not authority by itself.
4. Retain the file boundary: a regular, single-link, service-owned `0600` file
   inside a service-owned `0700` directory, with trusted non-writable ancestors.
   Keep the credential in its separate owner-only file. Do not broaden file
   access or create a second policy source.
5. Restart both services together with the same exact startup-bundle bytes and
   credential. Startup loading is the validation step; inspect only the private,
   value-free startup diagnostic. If validation fails, keep the integration
   unavailable and repair the complete replacement. There is no partial install,
   old-policy fallback, or live dry-run endpoint.
6. Verify the new generation is using the intended revision and that the
   authenticated peers agree on the exact bundle bytes. The bundle SHA-256 is
   included in the version-1 HMAC handshake. Two bundles with the same policy
   revision but different bytes are not coherent and must not reach READY.

Editing a file does not update a running snapshot. A coordinated restart retires
the old peer generation, admitted requests, trusted handles, cached discovery,
and delayed callbacks before new admission. A file edit alone does not revoke
already captured authority. Cancellation is local and does not prove an upstream
agent or provider stopped work.

The startup files and handshakes are described in the
[startup bundle schema](../compatibility/startup-config-v1.schema.json) and
[compatibility guide](compatibility.md). The startup loaders reject unknown,
duplicate, null, coerced, malformed, or out-of-bound values. Do not work around a
loader refusal by deleting fields or weakening file permissions.

## Policy shape and defaults

`abilityPolicy` is required in the startup bundle and has this exact shape:

```json
{
  "schemaVersion": 1,
  "revision": 0,
  "grants": [],
  "denials": [],
  "protection": { "externalClaimsMode": "REQUIRED" }
}
```

An empty grant list is the recommended initial state and denies all selected
operations. Policy v1 allows at most 32 grants and 32 denials. IDs are unique
stable ASCII identifiers; a grant names exactly the bundle's bound
`characterId`, one supported ability and contract version `1`, at least one
canonical requester UUID, and one or more dimension identifiers. There are no
wildcards, partial grants, aliases, name selectors, inherited staff roles, or
permission-provider shortcuts. `otherTargetIds` is optional and defaults to an
empty list: one grant authorizes self-targeting by default, and another target
must be explicitly listed on that same grant.

`maxRangeBlocks`, when present, is a finite positive number no greater than 64;
omission uses 64. It is combined with the ability's global range ceiling. Other
quantity limits are fixed by the contracts and cannot be added to policy.

### Optional X/Z region

An optional `region` narrows the grant to a half-open X/Z rectangle. It has
exactly four signed mathematical JSON-safe integers, each from
`-9007199254740991` through `9007199254740991`; `minX < maxXExclusive` and
`minZ < maxZExclusive` are required. Fractional values, values outside the safe
integer domain, and tokens that cannot be represented exactly are rejected.
There is no Y coordinate: the rectangle spans the dimension's build height.
Omitting `region` applies no additional rectangle beyond the world border.

For observation, the target's feet position must be inside the rectangle:
`minX <= x < maxXExclusive` and `minZ <= z < maxZExclusive`. Lightning requires
every block column in its complete 9-by-9 footprint to fit inside the rectangle;
checking only the target or strike origin is insufficient. A grant region
never replaces or enlarges the current world border.

### Optional interval limits

`limits` contains only the fields for the grant's selected ability. Values are
integer milliseconds and are minimum intervals (no burst accumulation). Omitted
fields use their defaults. Values outside the ranges, fractional values, and
unknown or cross-ability keys reject the bundle; invalid values are not silently
clamped.

| Ability | Field | Default | Accepted range |
| --- | --- | ---: | ---: |
| `observe_player` | `requesterIntervalMs` | 2,000 ms | 2,000–300,000 ms |
| `observe_player` | `characterIntervalMs` | 250 ms | 250–300,000 ms |
| `strike_lightning` | `requesterCooldownMs` | 30,000 ms | 30,000–300,000 ms |
| `strike_lightning` | `targetCooldownMs` | 30,000 ms | 30,000–300,000 ms |
| `strike_lightning` | `characterIntervalMs` | 1,000 ms | 1,000–300,000 ms |

These configured intervals may narrow, never relax, the contract ceilings.
Observe retains fixed limits of one in-flight query per requester and sixteen
per character. Lightning retains one invocation per origin, one in-flight
operation per character, and shared visual/damage cooldown accounting. This
policy bundle does not configure accounting. ABIL-04 supplies a private,
process-local atomic hold ledger; its transient holds are not durable accounting,
execution authority, or evidence that either ability is implemented. Restart-safe
accounting and consequential state remain unavailable pending STATE evidence.

`protection.externalClaimsMode` accepts `REQUIRED` or `NONE` and defaults to
`REQUIRED` when omitted. `REQUIRED` does not mean an adapter exists or is
qualified. `NONE` is an explicit choice to use vanilla spawn protection and the
world border only; it is not an outage fallback and does not enable lightning.

## Complete-grant evaluation

Evaluation uses the immutable current startup snapshot and authenticated request
context. Applicable explicit denial for the ability/version wins over every
grant. A grant must be complete for the bound character, ability/version, and
authenticated requester. For an intent, its target must be self or explicitly
listed by that same grant. When trusted target-dimension facts are available,
that grant must also include the dimension.

If several complete grants remain, the resolver sorts their stable IDs in ASCII
lexical order and selects the first. It never combines fields from grants. It
applies that one grant's region, range, mode and interval restrictions; if a
restriction on the selected grant blocks the intent, it does not fall through to
a later or broader grant. A damage-mode lightning request additionally needs
`allowVisual: true`, `allowDamage: true`, and a finite `maxDamage` in `(0, 5]` on
the same grant. It is denied rather than silently changed to visual-only.

An intent decision keeps three spatial values distinct: `region` is the selected
grant's configured integer rectangle; `worldBorder` is the separately supplied,
valid current-world fact (null when that fact is unavailable); and
`effectiveSpatialBounds` is their known non-empty half-open intersection. With no
configured grant region but a known border, the effective bounds are the border.
With an unknown border, effective bounds remain null even if a grant region is
configured, and `world_border` remains unresolved; the resolver never promotes
configuration into a claimed world fact. A known empty intersection is null and
the intent is denied `out_of_scope`, while both source rectangles remain
available on the decision for explanation. The 9-by-9 lightning check continues
to require every column to fit both the configured region and current border;
the reported intersection does not weaken that full-footprint check.

Discovery before a target or dimension is resolved can show only bounded,
requester-matching complete-grant summaries; it does not select an execution
grant. Each request sees its own matching summaries, not another requester's
policy. Discovery remains metadata in the SDK-built `BodyForAgent` context, not
a world observation, authorization token, tool, or result. A policy revision
number or resolver decision copied into a message cannot authorize work.
Discovery is not extended with `worldBorder` or `effectiveSpatialBounds`: it does
not read current-world facts, and current border coordinates belong only to an
intent resolution that received those trusted facts.

## Request-bound checks and current game facts

Parsing an ability intent establishes only that its exact typed input is valid;
it does not create authority. Java issues an opaque handle only from the
`SmokeConversation` request owner after production admission and request
registration. The companion issues its independent handle only after
authenticated v5 validation and retained request membership. Handles are
owner-private, are never serialized or placed in model context, and cannot be
created by a seed supplied by a caller.

Each checked use supplies the expected request, binding, and original player
audience. The owner compares those values with its retained request, exact
configuration snapshot and hash, request/invocation IDs, requester, epoch and
service/gate/peer generations, capture time, and lifecycle membership. It
re-resolves the current policy and the exact proposed intent; no earlier
discovery, grant decision, or facts record is reusable authority. Terminal
retirement, cancellation, expiry, context invalidation, and generation change
revoke or stale the handle. A copied, forged, foreign, or mismatched handle
fails closed.

The Java facts adapter is internal to the active server service. It verifies
the service owner and thread boundary, then requires `MinecraftServer.isOnThread()`
before reading game objects. Requester and target are resolved by UUID through
the player roster—not by name, selector, entity scan, or alternate-target
search. The requester must still be the original authenticated online-mode
player, profile, connection generation, handler, and world. The selected target
must still match its UUID/profile and current roster entity, be connected,
alive, non-spectating, and in the exact same current `ServerWorld` object and
dimension. Feet coordinates, current border bounds, and bounded loaded/ticking
facts are freshly sampled for the intent. The adapter does not call a
chunk-loading getter or retain a prior `PlayerView` as authority.

### Phase-specific unresolved checks

An accounting hold is a distinct, deny-only phase before any future read, start,
or effect qualification. The private owner may attempt that hold only after the
current authenticated request and requester, current world, resolved target,
connection/liveness, same dimension, exact range, grant region, border, and
loaded/ticking facts are present and pass `AbilityWorkLimits.validateCurrentFacts`.
A complete selected grant is still required; known policy denial or a known
protection veto denies before the ledger. Missing required facts fail closed.

The pure resolver's unresolved checks have explicit hold-phase meaning:

| Unresolved check | Accounting-hold phase | Effect on a hold |
| --- | --- | --- |
| Authenticated request eligibility, requester connected, current world instance, target resolution/eligibility, same dimension, range, border, grant region, loaded/ticking target | Required current facts | Blocks the hold until fresh owner facts resolve it. |
| Lightning sky access, protected spawn, external claims authority, damage non-lethal qualification | Required current facts/qualification | Unknown sky, spawn, or required claims blocks even a visual hold; missing damage-health/non-lethal qualification blocks damage-mode holds. A known veto denies. |
| `ATOMIC_LIMIT_ADMISSION` | Checked by the hold phase | Does not block its own check: the owner performs the atomic ledger admission here. |
| Normative world-identity mapping, Observe snapshot freshness, execution-boundary policy recheck, durable preparation, tick-thread revalidation, native lightning side-effect suppression, authoritative outcome identity mapping, unknown-outcome persistence | Deferred beyond hold | May remain unresolved for accounting only; each remains a blocker to any future read/start/effect. |

This is an explicit allowlist, not a rule that ignores all unresolved checks;
unknown or unclassified checks fail closed. A successful current-path hold is
immediately released as `execution_unavailable`, with no charge, observation
read, effect, result frame, store access, or provider call. Observe needs no
snapshot result to attempt this accounting-only check, but its future snapshot
read/delivery freshness is still unqualified. Neither a successful hold nor
complete current facts can satisfy any deferred execution check.

The adapter itself still does not qualify sky visibility, protection claims,
spawn protection, damage suppression, or impact-column conditions; its bounded
loaded/ticking sample is not a qualification claim. There is still no named-node
permission provider. Both abilities remain **UNIMPLEMENTED / UNQUALIFIED /
UNAVAILABLE**; the Java gate always returns `execution_unavailable` after a
successful transient hold, keeps `effectStarted: false`, and the companion
exposes no ability tool or provider. `toolsAllow` and available features remain
empty.

## Examples

These are policy fragments for illustration, not complete startup bundles or
deployment instructions. The UUIDs and binding IDs are syntactically valid
examples only. Replace them only through the authorized private-file workflow.

### Bounded self-observation

With no `otherTargetIds`, the grant is self-targeting only. This example narrows
range, region, and the two observe intervals:

```json
{
  "schemaVersion": 1,
  "revision": 1,
  "grants": [
    {
      "id": "observe-example-self",
      "characterId": "example-character",
      "abilityId": "worldspeaker.core.observe_player",
      "contractVersion": 1,
      "requesterIds": ["00000000-0000-4000-8000-000000000041"],
      "dimensions": ["minecraft:overworld"],
      "maxRangeBlocks": 32,
      "region": { "minX": -128, "maxXExclusive": 128, "minZ": -128, "maxZExclusive": 128 },
      "limits": { "requesterIntervalMs": 5000, "characterIntervalMs": 1000 }
    }
  ],
  "denials": [],
  "protection": { "externalClaimsMode": "REQUIRED" }
}
```

### Explicit-other-target visual lightning

The requester and target are separately listed. The target UUID is not a
display-name alias, and visual permission does not include damage:

```json
{
  "schemaVersion": 1,
  "revision": 2,
  "grants": [
    {
      "id": "lightning-example-visual",
      "characterId": "example-character",
      "abilityId": "worldspeaker.core.strike_lightning",
      "contractVersion": 1,
      "requesterIds": ["00000000-0000-4000-8000-000000000041"],
      "otherTargetIds": ["00000000-0000-4000-8000-000000000042"],
      "dimensions": ["minecraft:overworld"],
      "maxRangeBlocks": 24,
      "allowVisual": true,
      "limits": { "requesterCooldownMs": 60000, "targetCooldownMs": 60000, "characterIntervalMs": 2000 }
    }
  ],
  "denials": [],
  "protection": { "externalClaimsMode": "NONE" }
}
```

### Optional bounded damage mode

Damage must be enabled on the same complete lightning grant as visual
presentation, and `maxDamage` must be finite and in `(0, 5]`. This narrows a
policy description only; damage execution remains unavailable:

```json
{
  "id": "lightning-example-bounded-damage",
  "characterId": "example-character",
  "abilityId": "worldspeaker.core.strike_lightning",
  "contractVersion": 1,
  "requesterIds": ["00000000-0000-4000-8000-000000000041"],
  "otherTargetIds": ["00000000-0000-4000-8000-000000000042"],
  "dimensions": ["minecraft:overworld"],
  "allowVisual": true,
  "allowDamage": true,
  "maxDamage": 2,
  "limits": { "requesterCooldownMs": 60000, "targetCooldownMs": 60000, "characterIntervalMs": 2000 }
}
```

### Conflicting explicit denial

An applicable denial still wins even if a matching complete grant is present:

```json
{
  "schemaVersion": 1,
  "revision": 3,
  "grants": [
    {
      "id": "observe-example-self",
      "characterId": "example-character",
      "abilityId": "worldspeaker.core.observe_player",
      "contractVersion": 1,
      "requesterIds": ["00000000-0000-4000-8000-000000000041"],
      "dimensions": ["minecraft:overworld"]
    }
  ],
  "denials": [
    { "id": "deny-observe-example", "abilityId": "worldspeaker.core.observe_player", "contractVersion": 1 }
  ],
  "protection": { "externalClaimsMode": "REQUIRED" }
}
```

## Explicitly unresolved and unavailable

The pure resolver does not look up players, read Minecraft state, map configured
ASCII binding IDs to normative world-instance UUIDs, check a live world, acquire
the tick scheduler, reserve atomic limits, call a protection provider, write a
durable action record, or execute either operation. Missing world facts stay
unresolved; no UUID, location, protection answer, quota result, effect, or
outcome is fabricated. The candidate's interfaces and offline fixtures do not
qualify those systems. See the [normative ability contract](ability-contract.md)
and [open decisions](open-decisions.md) for the remaining ABIL/WORLD/STATE and
runtime qualification work.
