# CONV-06 offline evaluation procedure

This tool is a bounded, test-only evidence organizer. It starts no Minecraft server or OpenClaw host, submits no prompts, makes no provider calls, and cannot grant acceptance. Scenario entries describe situations and observation goals; they are not scripts or expected answers.

## Storage and commands

Use a private directory with no symlink path components. The command creates a new run directory with mode `0700` and files with mode `0600`; it refuses an existing target. Run and entry files must be regular owner-only files in owner-only parent directories. On macOS, use the physical `/private/...` path rather than the `/var/...` alias if the latter is a symlink.

```sh
node tools/dialogue/evaluate.mjs init --out build/conv06-evaluation
node tools/dialogue/evaluate.mjs validate --run build/conv06-evaluation/run.json
node tools/dialogue/evaluate.mjs report --run build/conv06-evaluation/run.json --out build/conv06-report
```

`init` writes an empty `offline_fixture` schema-v3 run, blank player and independent-evaluator review forms, a public review codebook, and a blank `session-form.json`; it does not create a sample result. Directory creation is exclusive, initialization holds the run lock while writing the forms, and publishes `run.json` last. `validate` reports structural validity separately from acceptance. An initialized or offline-only run is incomplete and **NOT ACCEPTED**. `report` reads one complete run-file snapshot, creates `report.json` and `report.md` in a new owner-only directory, and refuses to overwrite it. Use a new output path for each report.

Schema-v1 and schema-v2 runs are legacy records. `validate` and `report` fail with `legacy-run-migration-required`; they do not reinterpret, rewrite, or export those runs. Preserve the original unchanged. To start over, initialize a separate v3 run, re-import the authorized original numeric source if it is still available, and create new coded reviews. Do not reconstruct/retype legacy prose or treat the old report as v3 evidence. If the original numeric source is unavailable, record the evidence gap rather than copying an old run into the new schema.

`init` and `record` serialize mutations through `.evaluation.lock` in the owner-only run directory. The lock is created atomically with exclusive-create semantics, mode `0600`, and a process id; an existing lock is a fail-fast `run-lock-busy` rejection with a retry/inspection hint. `record` holds the lock across reading, validating, normalizing, and committing the run. Before atomic replacement it compares both the original run bytes and file identity with the current file; an unexpected change is rejected as `run-version-conflict`. A failed operation releases only the lock inode it created. Reports and validation do not acquire this lock: they open and validate one file descriptor snapshot, while run updates publish with an atomic replacement, so a reader sees a complete old or new version and does not overwrite run data.

There is deliberately no automatic stale-lock deletion or update replay. A killed process or system crash can leave `.evaluation.lock` behind; its PID is diagnostic only and may have been reused. Confirm that no writer remains, inspect and validate `run.json` (especially after an interrupted commit), then remove only that exact lock file manually before deciding whether to retry. If lock cleanup itself fails or the process was interrupted around commit, inspect the run before retrying because the commit outcome may be unknown. Do not delete a lock while a writer may still be active.

## Import numeric evidence

The human-evaluation `record` path accepts only the body-free numeric snapshot format with `numericFormatVersion: 2`: one or two JSON snapshots with `counters`, `tickHistogram`, `firstPublicDispatchHistograms`, and `interactionRecords`. Monotonic timestamps and nanosecond durations are canonical base-10 strings representing signed Java `long` values; the reader validates them with `BigInt` and performs wrapped-long duration checks without converting raw timestamps to JavaScript `Number`. `null` means a stage timestamp is unavailable. A `MISSING_PACKET_RECEIPT` record has `t0Nanos: null`, a null receipt wall time, and null receipt-relative timing/deadline fields; its handler-entry time is not substituted for receipt time. Do not import logs, chat, screenshots, transcripts, private workspace data, or credentials. The importer hashes the exact input bytes, validates exact field sets and bounds, then retains only the numeric measurements and run-local aliases. It discards player UUIDs and server/world/character binding values; an owner-only keyed alias index lets later imports in this run join the same identities without retaining those source identifiers.

Unversioned legacy numeric snapshots are rejected by default. An operator may explicitly request `record --legacy-numeric-v1` for a preserved v1 source only when every legacy JSON integer is a safe JavaScript integer. The importer retains the old `-1` absence-sentinel interpretation during that explicit migration; it cannot recover a timestamp already rounded by JSON/JavaScript or disambiguate a legacy timestamp that collided with `-1`. Unsafe or ambiguous values are rejected, never coerced. Do not add the flag to v2 exports. Schema-v1/v2 run files themselves are not automatically migrated; preserve them and import the original source into a fresh v3 run when safe and authorized.

The input file must be at most 1 MiB and mode `0600`, in a mode `0700` directory. Keep it outside source control and copy it to a private directory before importing if its current location is not private.

```sh
node tools/dialogue/evaluate.mjs record \
  --run build/conv06-evaluation/run.json \
  --entry build/conv06-evaluation/approved-numeric-v2.jsonl
```

This human-run import example is for an already-preserved v2 snapshot with at most 64 distinct rows. Current Java `TickMetrics` exports operational numeric stream format v5 as manifest/chunk streams; do not downsample, rewrite, or submit those streams to `record`. Use the separate `operational-report` path below. The companion dialogue wire remains `worldspeaker.compat.v5`; its protocol version is independent of the metrics format, so metrics v5 is not a wire-protocol change. The resulting operational report explicitly does not constitute human evaluation of the larger source.

At most 30 minutes, 3 player aliases, 64 distinct invocations, 256 evidence/metadata entries, and 1 MiB total run/report content are accepted. Nothing is silently clipped or dropped. Repeated source hashes, duplicate invocation IDs within a snapshot, conflicting exact field sets, invalid ranges, and capacity overflows are rejected. Reports use the last retained snapshot per invocation for current summaries while preserving every imported snapshot in the report JSON.

## Operational numeric load reports (separate from human evaluation)

Current Java `TickMetrics` exports use operational numeric stream format v5: a bounded manifest followed by ordered, linked chunks of at most 64 records. The manifest carries aggregate counters/histograms, a snapshot UUID, source and retained invocation counts, chunk count, capture-drop count, explicit complete/incomplete capture status, and bounded pending-queue scan evidence. Each invocation row includes strict forwarding and pending-residence fields plus v5 cancellation fields: terminal cause, retirement-notice result, local-cancel status/send result, provider-cancel value, and late-output-rejection count. A snapshot retains at most 4,096 records and at most 8 MiB; a metrics file holds at most two snapshots and 64 MiB. Each chunk is validated against the manifest, its expected index/range, exact version-specific row fields, and cross-chunk invocation uniqueness. Do not reorder, merge, duplicate, or edit chunks. Legacy v3 and v4 streams remain readable. V3 predates queue-scan and forwarding evidence, so those fields are unknown, never zero; v4 includes that queue/forwarding evidence. Neither v3 nor v4 records v5 cancellation attribution: the report marks cancellation evidence `UNKNOWN_LEGACY_STREAM` and its missing cancellation measurements unknown, not zero. The reader does not infer or migrate those fields into legacy records.

Use the separate operational command for current v5 streams (the reader also accepts legacy v3 and v4):

```sh
node tools/dialogue/evaluate.mjs operational-report \
  --source build/private-metrics/tick-metrics-v5.jsonl \
  --out build/private-metrics/operational-report
```

Keep the source in a mode-`0700` directory and mode-`0600` file; the reader refuses symlinks, non-private inputs, and sources over 64 MiB. Each input line is capped at 256 KiB and the combined JSON/Markdown report at 1 MiB. The command hashes the exact source bytes, never modifies the source, writes to a new owner-only output directory, and does not include per-invocation IDs, player UUIDs, world/binding identifiers, or free-form text in its report. Up to two captures are summarized independently; top-level declarations refer to the last unique manifest considered, and repeated rows across captures are never summed. The reader retains only the two supported snapshot identities plus one bounded snapshot's worth of trailing diagnostic lines. A repeated or conflicting late chunk invalidates only the matching snapshot summary, including one already finalized; an unrelated later complete snapshot remains independently complete. Malformed, missing, duplicate, conflicting, or out-of-order chunk sets produce an `INCOMPLETE` diagnostic with declared source and retained counts, validated distinct observed rows, and omitted/unobserved counts where they can be derived. `declaredOmittedInvocationCount` preserves the producer's manifest value; top-level `omittedInvocationCount` is source rows minus validated observed rows and is null if those counts are unknown or inconsistent. A manifest beyond the supported snapshot count retains its known declared counts but has unknown observed rows because it is not scanned. Unread or invalid rows are not reported as observed, and invalid chunk sets are never summarized as complete. Trailing garbage or an unrecognized snapshot ID makes the overall report `INCOMPLETE` without attributing that data to an unrelated snapshot. A producer-retention overflow likewise reports the full captured source count and omitted rows and remains `INCOMPLETE`. An input beyond the 64 MiB ceiling is refused before import; preserve and inspect that original file rather than retrying by truncation.

An operational report describes numeric load outcomes only. It is not a human session, does not create human reviews or consent, and cannot establish quality or acceptance. The separate human-evaluation path remains limited to 64 turns, 3 anonymous player aliases, and 1 MiB. A 65-turn or 4,096-turn operational report explicitly records that the limited human evaluation was not performed and does not cover the whole load. Obtain the appropriate evaluation-records approval before capturing live numeric evidence; this command does not imply provider-transfer approval or human-review consent.

For v5, `cancellationEvidence` reports terminal-cause counts, retirement-notice results, local-cancel status/send-result counts, provider-cancel counts, and late-output rejections. This is body-free local evidence: a local cancel submission does not prove peer receipt or provider stop, and the producer does not claim provider cancellation. For v3/v4, `cancellationEvidence.availability` is `UNKNOWN_LEGACY_STREAM`; do not treat absent fields as `NOT_ATTEMPTED`, `providerCancel: false`, or zero rejections, and do not rewrite or automatically migrate old streams to invent v5 observations.

The report includes disposition totals, per-player all-invocation counts, admitted logical requests, coalesced and repeat-guard-refused counts, model-start count (`unknown` when not exposed), forwarding and queue-wait evidence, first-public-dispatch timing, confirmed technical `ANSWER` completion median/p95/maximum using nearest rank and sample count, progress outcomes, terminal outcomes/delivery results, and the imported counter snapshot. Queue-scan maxima are observed bounded samples, not proof about unseen wall-clock behavior. Repeat refusals link to their original admitted request when evidence is available; linked repeats inherit only that request's outcome context and never count as additional logical requests or completions. A completion requires an admitted invocation, terminal reason `ANSWER`, explicit `terminalDeliveryResult: "SUBMITTED"`, and measured terminal settlement latency. A direct submitted terminal answer can complete without an earlier acknowledgement; an ACK/first-dispatch timestamp alone cannot prove that the later terminal answer was submitted. `UNKNOWN`, `DEFINITELY_UNSENT`, `NO_ATTEMPT`, and missing terminal delivery evidence stay visible as attempts/outcomes but never enter the completion count, rate, or latency sample. The report shows both all-linked and all-admitted denominators; failures, pending records, unknown results, and missing results are retained rather than removed from those populations. Latency uses `terminalNanos` (capture-to-terminal settlement); the distinct `terminalAtNanos` event timestamp is not substituted as a duration. This operational report has no human scenario assignment and does not label completions ordinary or tool-heavy. First-public-dispatch latency is a **conditional distribution** over invocations with a measured successful public dispatch: its sample count is shown against all linked invocations, and its median, p95, maximum, and >1,000 ms rate do not describe the missing-dispatch population. The maximum is computed only from linked measured dispatch records; an aggregate counter or histogram does not supply an individual maximum.

Response completeness is reported separately over all linked invocations and all admitted invocations. Human numeric v2 evidence may carry the explicit terminal-delivery result; old records without that field remain `NOT_RECORDED`, never inferred as `SUBMITTED` from `terminalOutcome`, first-dispatch `sendResult`, or an ACK. Thus legacy `ANSWER` terminals without delivery evidence remain unconfirmed attempts and do not supply global or ordinary/tool-heavy/unknown/mixed class completion samples. An acknowledgement, refusal, rejection, coalesced request, unavailable outcome, failure, or unresolved lifecycle is not a successful completed answer. These outcomes remain visible in the disposition, terminal-reason, and terminal-delivery counts. Human classifications of usefulness remain subjective review evidence and do not change technical delivery status.

For records without a successful first public dispatch, the report gives counts and rates by interaction outcome and terminal lifecycle. An absent dispatch observed at or after the 1,000 ms deadline is a miss; a no-dispatch settlement before the deadline is not. A record with no terminal or settlement timestamp has unknown deadline exposure; do not infer that it was still live before the deadline, and do not count it as a miss without evidence that the deadline passed. If a claimed publication is first observed as no-dispatch at the deadline and later materializes as successfully submitted, the row retains that settlement timestamp and adds the actual first-dispatch timestamp, latency, source, disposition, publication identity, and `SUBMITTED` result. The operational reader reports non-dispatch-at-deadline observations separately from settled no-dispatch outcomes and counts a late dispatch plus its earlier deadline observation as one invocation miss. The source `FIRST_REPLY_DEADLINE_MISSES` counter is reconciled against that per-invocation union; `FIRST_REPLY_NO_DISPATCH` is reconciled against retained no-dispatch settlement timestamps. Unknown, definitely-unsent, cancelled, and pending outcomes never get a successful t1. A reconciliation mismatch, dropped interaction evidence, or non-comparable imported numeric sources is flagged as incomplete; do not assign aggregate misses to individual invocations or invent per-invocation timing. An aggregate mismatch does not change the measured-only maximum.

Server monotonic dispatch is not client display. Human-observed client display is a separate optional review field; absent measurements remain `unknown`.

The limited human-evaluation report derives each turn's work class only from the
selected catalog `scenarioId` attached to a human terminal classification. That
scenario must also appear in the same review's `scenarioIds`. Do not infer a class
from response speed, answer wording, or a requested ability. Missing assignments
or missing terminal classifications remain `unknown`; conflicting ordinary and
tool-heavy assignments across reviews are `mixed`. The report shows separate
capture-to-measured-`ANSWER` completion counts, rates, median, nearest-rank p95,
maximum, answer-attempt delivery results, and technical failures for ordinary,
tool-heavy, unknown, and mixed groups. Only `SUBMITTED` terminal answers enter
those completion statistics; missing, uncertain, and unsent results stay in the
class denominator and visible attempt counts. The
provisional ordinary median 5-second / p95 10-second targets are descriptive only.
Tool-heavy work remains **BLOCKED** until a publicly supported workload and its
approval exist; a catalog label or offline fixture is not implementation proof.

## Consent and human review

Before any live capture, obtain and record explicit approval for shared game history, provider transfer, and evaluation records. Verify and record actual-host private-admin isolation approval before a host-backed run. Do not use this tool to create approvals or discover private configuration. The run retains only anonymous role labels, non-identifying approval references, dates, scopes, public artifact hashes, exact public version labels, public provider/model label, hardware class, workload, clock boundary, attention settings, and an approved public persona-brief revision. Never enter names, player UUIDs, addresses, signatures, private persona text, credentials, costs, or authorization material.

Copy and complete `session-form.json` outside the run, then record it before capturing live human evidence. The explicit `humanAttestation: true` is an accountable human assertion, not an inferred label. The CLI requires all three consent records, a separate actual-host isolation approval, complete public version labels, a public persona brief revision, and a bounded workload before it records live provenance. The three consent scopes must be the matching fixed labels; approvals must include a non-identifying reference, date, and approver role. `discoveries` records up to three anonymous player roles and the unaided two-minute discovery result/time/help.

```sh
node tools/dialogue/evaluate.mjs record \
  --run build/conv06-evaluation/run.json \
  --entry build/conv06-evaluation/approved-session.json
```

Observe players in place; do not enable automatic screen, transcript, or audio recording. Test only approved public fictional material. Use `scenarios.json` for situations and observable goals. Include at least one free-choice exchange for each player. Do not prescribe prompts, expected wording, lore, a character stance, or a God bargain. Do not coach away discovery failures.

After importing numeric evidence, inspect only the run-local `turn-NNN` and `player-NNN` aliases to prepare human forms. Complete a copy of `review-form.json` for each player and `independent-review-form.json` for an independent evaluator who is neither builder nor character author. Use `review-codebook.json` for the fixed public code meanings. Each reviewer independently considers the evidence, records all five 1–5 ratings, selects 1–5 `reasonCodes`, and selects 1–5 `weaknessCodes`. `none_observed` must be the only weakness code when no listed weakness was observed. If a weakness is not listed, select `other_observed_issue`; the tool records only that category and intentionally has no field for details. The evaluator additionally makes an independent reasoned AC1–AC3 decision, selects 1–3 `decisionReasonCodes`, and may select up to three `prioritizedWeaknessCodes`. Player reviews must not include evaluator-only fields.

For every observed terminal, record a finite human classification and, when
unambiguous, the applicable selected catalog `scenarioId`; include that scenario
in the same review's `scenarioIds`. Leave it absent or null when unassigned rather
than guessing. Record display timing separately with `clientDisplay.observation`,
`elapsedMs`, and `method: "human_stopwatch"` only when measured. Missing display
times remain unknown. Human nuance needed to make an independent decision remains
with the people observing/reviewing in place: this tool does not request, capture,
store, or log narrative notes. Do not type notes into review forms, CLI arguments,
source files, or report annotations. The codes and ratings summarize selected
categories; they do not replace human reasoning, and no model/regex judge or
automatic quality pass is used.

```sh
node tools/dialogue/evaluate.mjs record \
  --run build/conv06-evaluation/run.json \
  --entry build/conv06-evaluation/player-1-review.json
node tools/dialogue/evaluate.mjs record \
  --run build/conv06-evaluation/run.json \
  --entry build/conv06-evaluation/player-2-review.json
node tools/dialogue/evaluate.mjs record \
  --run build/conv06-evaluation/run.json \
  --entry build/conv06-evaluation/independent-review.json
node tools/dialogue/evaluate.mjs validate --run build/conv06-evaluation/run.json
node tools/dialogue/evaluate.mjs report --run build/conv06-evaluation/run.json --out build/conv06-report
```

The v3 review schema accepts only closed-vocabulary reason, weakness, decision, and classification codes plus bounded ratings/observations; it rejects prose fields and unknown fields before storing an entry. The report includes each selected code with a canned public rendering, each reviewer’s scores, score disagreements, and the independent evaluator’s decision/reason codes. A person-name detector or general prose anonymizer is not used or claimed. `other_observed_issue` is a category only and carries no detail. This limits ordinary free-form-name leakage but cannot detect intentional covert encoding through permitted codes, ratings, or other structured values. A typed terminal, a fast acknowledgement, an average score, or a favorable model output is not a human quality result. ACK/progress/busy/failure/unresolved outcomes are never counted as useful answers. Disagreements and missing roles remain visible, useful answers and explained limitations are separate, and all rates retain their denominators.

## Interpretation

The JSON and Markdown reports always say `NOT_ACCEPTED` and `automaticPass: false`. They may list structural blockers and the independent evaluator’s decision, but acceptance remains an external human decision. Offline fixtures never become live evidence by changing a label. Missing consent, isolation, supported persona baseline, actual multiplayer review, independent review, useful completion, or client display evidence remains a blocker. This procedure cannot satisfy T3 or establish live provider/host/client behavior.
