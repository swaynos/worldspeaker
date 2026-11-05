# Next iteration: finish ability accounting verification

Checkpoint: 2026-11-02. This is an execution handoff, not release approval.

## Starting position

- Nineteen of 49 stories have had stakeholder review. That is review coverage,
  not a claim that 19 stories meet live acceptance.
- All 24 escaped defects in [the defect ledger](../TEMP-R01-BUGS.md) have scoped
  offline fix verification. Open QA findings are separate from escaped defects.
- ABIL-04 accounting code and its repairs are present. Do not rebuild them from
  scratch. Final combined QA and stakeholder review are still pending.
- Both abilities remain **UNIMPLEMENTED / UNQUALIFIED / UNAVAILABLE**. Keep
  `toolsAllow: []`, empty available features, and deny-only execution.
- CHAR-02 private persona use remains **INACTIVE / BLOCKED**.
- No story has final runtime acceptance. Existing work remains uncommitted.

Use [the specification](specification.md) and the entire
[ability contract](ability-contract.md) as requirements. Use each QA report for
current finding dispositions; old build counts and ledger introductions are
historical snapshots, not the current acceptance decision.

## 1. Finish ABIL-04 with the original QA agent

Read these before any edit or test:

- [ABIL-04 plan](r01/plans/ABIL-04.md)
- [Recovery/build checkpoint](r01/build/ABIL-04.md)
- [Latest QA report](r01/qa/ABIL-04.md)
- Fix records `docs/r01/fixes/R01-ABIL-04-QA-001.md` through `005.md`

Resume original QA session **`ses_e6596897fffep4fDYdBW0oHoBm`**. The recovery
builder is `ses_e6896081bffeLhDFaPpPe9MxIs`. Do not silently replace the original QA
agent or start a second build/test process after an uncertain interruption.

Latest independent checkpoint:

| Check | Verified scope |
| --- | --- |
| Subprocess helper | 10/10 tests |
| Toolchain guard | 5/5 tests; nested Java test run 545/545 |
| Server-only verifier tests | 26/26 tests |
| ACK/outage lifecycle regression | Five separate passing repetitions |
| Focused Java regression suite | 74/74 tests |

These do **not** replace a fresh full `check`, companion run, or complete serial
tooling run after the latest repairs. The last attempt to resume those gates
failed at the agent backend with `getaddrinfo ENOTFOUND chatgpt.com`; it did not
produce a new project test result.

### Remaining QA work

- [ ] Run the complete gates below, sequentially, with actual exit codes.
- [ ] Exercise the remaining owner-level shared-limit matrix: different grants,
  aliases, visual/damage mode, fresh query IDs, connections and sessions must not
  reset requester/target/character limits or invocation consumption.
- [ ] Check cancellation/revocation versus start, charged failures, unknown task
  liveness, unknown outcomes beyond cooldown, and rejection without partial holds.
- [ ] Confirm the normal private issuer performs a transient accounting hold and
  release, without charges, world work, result publication or an execution permit.
- [ ] Retain failed child logs and JUnit XML before reruns. Record untested cases
  explicitly. Do not waive an earlier failure because a later run passes.
- [ ] Have the original QA agent record per-finding dispositions and decide
  whether the offline scope is clear for stakeholder review.

Run the next fresh stakeholder review only after code defects clear. Log new
escapes in `TEMP-R01-BUGS.md`, with expected behavior, observed behavior and exact
reproduction steps. Give each defect a fresh builder and return its fix to the
same original QA agent. Keep runtime acceptance separate from offline clearance.

## 2. Build ABIL-05 after the ABIL-04 handoff

Use [the existing ABIL-05 plan](r01/plans/ABIL-05.md); refresh its stale dependency
and temporary-runtime notes before implementation. Do not repeat completed work.

Deliver bounded Java result encoding, exact Java/TypeScript byte parity, closed
local decisions and fail-closed delivery eligibility. Keep these concepts separate:

1. A local parse/policy/limit decision.
2. An authoritative query result or action outcome.
3. A delivery attempt, handoff, or uncertain delivery.

Do not manufacture authoritative UUIDs from configured ASCII binding IDs. Do not
emit success from typed validation, reservation or submission alone. Keep private
results out of shared gameplay history and keep production result delivery
unavailable until its identity, WORLD/STATE and supported SDK isolation prerequisites
are satisfied. Complete the story's builder, QA, repair and stakeholder cycle.

## 3. Prepare WORLD-01, then continue the catalogue

Use [the existing WORLD-01 plan](r01/plans/WORLD-01.md). Reconcile its older
active-builder instructions with the actual completed ABIL-04/05 interfaces.

Before authoritative publication, record decisions for:

- Stable server/world-instance/character UUID mapping, including restore,
  migration, conflict and mapping-loss behavior.
- The v1 `holdingItem` projection. The current contract has one identifier and
  uses `minecraft:air` for empty; do not silently add off-hand or null fields.
- A supported, qualified request-scoped private result path in OpenClaw. The
  current shared group history and targeted player chat do not prove isolation.

Implement bounded current-state reads behind the trusted owner/provider boundary;
never load chunks, scan inventory or expose protected facts through shared context
as a workaround. Keep activation blocked while required prerequisites are absent.

Then continue WORLD-02–04, CHAT-01–04, INT-01–04, STATE-01–05, OPS-01–05 and
DIST-01–04. Check dependency order against the specification before each story.
Existing code that touches a later story is not that story's completed review.

## 4. Keep these gates open

| Gate | Required treatment |
| --- | --- |
| RESP-04 QA-005 | Historical fairness failure; cause unknown. Withhold stakeholder review until genuinely resolved. |
| ABIL-03 QA-002 | Historical diagnostic/lifecycle failures; cause unknown. Withhold stakeholder review. |
| CHAR-02 | No qualified private-guidance publication control. Do not activate persona use. |
| WORLD/STATE | No qualified ability execution, durable action accounting, recovery or authoritative result production. |
| Actual-host privacy | Verify workspace/bootstrap/memory separation and private result access; prompts and sentinel tests are not guarantees. |
| Live qualification | Requires separate authorization for Minecraft/EULA, clients/accounts, providers/budget and human tests on the pinned platform. |

No retry may silently weaken these boundaries, enable a tool, invent a provider,
refund unknown effects, replay uncertain work or label blocked evidence passed.

## Verification procedure

The original temporary Node installation lost an npm file. The replacement below
was extracted from the same verified archive and checked as Node 24.22.1 / npm
11.19.1. Verify it again; temporary paths may disappear. If unavailable, provision
a fresh private verified runtime, not a version shim or a global configuration
change. The recorded Darwin ARM64 archive SHA-256 is
`89c682593d76cd44233fa53b1563264e7de9cbb189cad169135c5b4fadfbc0a7`.

From the repository root, run each command separately and in order:

```sh
NODE_BIN=/private/var/folders/ck/dt31559s06x5_t8wmv6ws8f00000gn/T/opencode/worldspeaker-node24-recovered-aypkuwz2/node-v24.22.1-darwin-arm64/bin
JAVA_DEV=/Users/jpswaynos/.sdkman/candidates/java/21.0.3-tem
env PATH="$NODE_BIN:$JAVA_DEV/bin:$PATH" JAVA_HOME="$JAVA_DEV" node --version
env PATH="$NODE_BIN:$JAVA_DEV/bin:$PATH" JAVA_HOME="$JAVA_DEV" npm --version
env PATH="$NODE_BIN:$JAVA_DEV/bin:$PATH" JAVA_HOME="$JAVA_DEV" java -version
env PATH="$NODE_BIN:$JAVA_DEV/bin:$PATH" JAVA_HOME="$JAVA_DEV" ./gradlew --offline --no-daemon --console=plain --max-workers=1 -PlocalDevelopment=true --rerun-tasks check compatLoadHarnessJar
env PATH="$NODE_BIN:$JAVA_DEV/bin:$PATH" JAVA_HOME="$JAVA_DEV" npm --prefix companion run typecheck
env PATH="$NODE_BIN:$JAVA_DEV/bin:$PATH" JAVA_HOME="$JAVA_DEV" npm --prefix companion test
env PATH="$NODE_BIN:$JAVA_DEV/bin:$PATH" JAVA_HOME="$JAVA_DEV" node --test --test-concurrency=1 tools/abilities/*.test.mjs tools/compat/*.test.mjs tools/dialogue/*.test.mjs tools/presets/*.test.mjs
git diff --check
```

For agent tools, environment changes do not persist between shell calls: set the
variables and apply the prefix in each invocation. Run no Gradle checks concurrently;
some Node tooling launches Gradle itself. Allow a finite adequate outer budget,
capture private bounded logs and the actual root exit status, and preserve failure
evidence. A `BUILD SUCCESSFUL` line without a completed process is not a full gate.
If interrupted, inspect ownership and existing results before retrying. Do not kill
unrelated processes or automatically replay uncertain non-idempotent actions.

These macOS/Temurin 21.0.3 checks are local development evidence only. They do not
qualify the pinned Linux x86-64 / Temurin 21.0.13 candidate or authorize deployment.

## Iteration workflow and handoff

- Fresh planner: GPT-6.1 Sol, high. Fresh builder and original QA: GPT-6 Luna, max.
- Fresh builder for each defect; reuse that story's original QA for its fix.
- Fresh stakeholder reviewer: GPT-6.1 Sol, default, after offline code clearance.
- Use short bounded work units and checkpoint completed work before long runs.
- Update [the status record](r01/STATUS.md), the owning QA/build reports and the
  escaped-defect ledger without erasing original failures or scope limits.
- Preserve user work. No staging, commits, release publication or live access is
  authorized by this handoff. Do not change `.gitignore` or force-add ignored reports.
- Exit this iteration with an honest ABIL-04 disposition and an explicit ABIL-05
  handoff, or a precise retained blocker. Do not call the overall product complete.
