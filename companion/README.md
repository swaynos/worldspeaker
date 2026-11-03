# Worldspeaker OpenClaw channel

This package is the exact-version native channel companion for the `mc1211-fabric-r01`
candidate. It is not a general OpenClaw plugin: it accepts one authenticated peer,
checks the exact deployment/runtime facts and server/world/character/agent binding,
and never registers game tools. OpenClaw's channel ingress resolver and exact
host-context handoff are used for each player request. All players for one
server/world/character/agent use the same binding-derived `per-group` gameplay
session; player UUID is not part of that game-session key. The current request
alone supplies requester authority, and its final dispatcher targets only the
original player connection. Generic outbound sends are disabled. Player turns
are dispatched with an empty tool allowlist.

## Optional God preset data

This package contains the [God preset data ZIP](presets/worldspeaker-god-preset-v1.zip).
It is public data, not a Minecraft client resource pack, plugin, installer, or
activation mechanism. The ZIP contains fictional prose, inactive policy/config
examples, the current startup-v1 schema, and the GPL-3.0-only license. No
credential file or usable secret is included.

The main startup template includes `personaFile` and therefore remains blocked
before credential access or readiness by CHAR-02's unqualified publication
control. The separately named NO-GUIDANCE-CONTROL template is only for parser
and peer-coherence tests; it is not an active God persona and must not be used by
deleting an existing persona reference. God persona use remains
**INACTIVE / BLOCKED**. The archive does not import realm, private workspace,
administrator history, client, or provider data and supplies no provider access.
Any future authorized deployment requires manual private preparation, the same
exact bundle bytes and separately generated credential on both peers, and a
coordinated restart. Java's actual Node.js **24.22.1** startup prerequisite on
the trusted service `PATH` remains mandatory.

## One character and presentation-only names

The companion supports exactly one character on its `default` account. Unsupported
secondary accounts or plural account maps are rejected instead of silently picking
an entry. The stable character key is the exact `serverId`, `worldId`, and
`characterId` tuple. `agentId` remains in the authenticated binding and the
existing binding-derived shared-game route. Display name, aliases, chat style,
persona text, and appearance do not grant permissions, operator authority, or
player identity.

The startup bundle's restart-only `server.characterChatStyle` accepts exactly
`plain` (the default) or `bracketed`. Character output is literal vanilla system chat:

```text
[Worldspeaker] Archivist: body
[Worldspeaker] [Archivist]: body
```

The `[Worldspeaker]` origin is permanent. Style changes do not change the
character key, agent association, route, or shared context. Invalid, blank, or
unknown values reject the entire server configuration; there is no silent style
fallback. Names and aliases remain presentation/routing labels, not authority.

## Strict startup configuration v1

This is an incompatible migration from inline Java properties and account-level
settings. The v1 loader does not import legacy properties. Do not merge formats,
expect automatic import, or remove invalid fields to force startup. Before
migrating, stop both services, back up the old settings privately, and manually
prepare a complete replacement JSON bundle with exactly
`schemaVersion`, `binding`, `server`, `companion`, `abilityPolicy`, and
`credentialFile`; `personaFile` is optional. The shared contract and finite bounds
are in [`compatibility/startup-config-v1.schema.json`](../compatibility/startup-config-v1.schema.json).

The Java bootstrap at `config/worldspeaker.properties` contains exactly these
two entries (the path shown is a placeholder, not a runnable deployment):

```properties
schemaVersion=1
configFile=/absolute/private/path/startup-config.json
```

The OpenClaw `channels.worldspeaker` section accepts exactly a boolean `enabled`
and an absolute `configFile` path to that same JSON bundle. It rejects account
maps and secondary accounts. The Java bootstrap's `configFile` and OpenClaw's
channel `configFile` both identify the bundle; the bundle's `credentialFile`
identifies a separate credential file. Put references in a private service-owned
directory and use one service identity that can read the same owner-only files.
The bootstrap, bundle, and credential are regular single-link `0600` files under
a service-owned `0700` directory with trusted non-writable ancestors. Paths are
literal canonical absolute local paths; no expansion, URL, include, or fallback
is supported.

Legacy property names are not aliases and no values are copied automatically.
When carrying forward reviewed values, enter them manually at the v1 paths: the
endpoint, presentation, conversation, follow-up, attention, and execution-budget
settings belong in `server`; the four binding IDs belong in `binding`; and the
installed loopback listener/deployment identity must match the exact `companion`
object. Conversation and attention values use bounded JSON arrays/numbers, not
comma-separated property strings. Supply `abilityPolicy` explicitly, normally
with empty `grants` and `denials` and an empty `protection` object. That validates
policy syntax only. On each admitted live request, the companion adds both typed
contracts and at most 32 matching complete-grant summaries for that authenticated
requester to the SDK-built `BodyForAgent` context. A summary may include explicit
other-target UUIDs, dimensions, range, and applicable damage ceilings; credentials,
paths, and bundle hashes are excluded. This is model-readable metadata, not a world
observation, registered tool, permission to execute, or availability claim. Both
operations remain `UNIMPLEMENTED` / `UNQUALIFIED` / `UNAVAILABLE`, and player turns
retain `toolsAllow: []`.

The private, restart-only administrator workflow for complete grants, regions,
ability-specific interval limits, denial precedence, and coordinated revocation is
documented in the [ability-policy administration procedure](../docs/ability-policy.md).
Configured grants remain conditional metadata only; neither operation becomes
available through this configuration.

`credentialFile` is a root-level path reference to a separate private file, not a
place for credential contents. This README provides no credential value or
preset deployment values. Keep the credential separate; do not inline, copy into
the bundle, or commit its contents.

Policy v1 has at most 32 complete grants and 32 denials, exact stable IDs and
ability/version names, bound character/requester/dimension scopes, and no
wildcards or partial-field union. Explicit denial wins; future evaluation must
choose the lexicographically smallest complete matching grant before applying
its limits. `externalClaimsMode: REQUIRED` is not proof that a claim adapter is
installed. The normative ceilings—including observe at one in-flight query per
requester and sixteen per character, one per two seconds per requester and four
per second per character, and lightning at one in-flight/one per second per
character with 30-second requester and target cooldowns—are documented in the
[ability contract](../docs/ability-contract.md). These are policy requirements,
not implemented operations or runtime guarantees.

The bundle ceiling is 32,768 bytes; bootstrap is 4,096 bytes; credential content
is 43–86 ASCII bytes of canonical unpadded base64url encoding for 32–64 random
bytes. The loader rejects duplicate/unknown keys, nulls in place of values,
coercions, malformed UTF-8, BOM, unsupported versions, oversized content, and
unsafe file references. Files are read asynchronously before companion readiness
and off the server tick thread. Validated values and the SHA-256 of the exact
bundle bytes form one immutable restart-only snapshot. That digest is separate
from the installed deployment-manifest hash and is included in the version-1
configuration-bound HMAC handshake; there is no old-handshake downgrade. File
edits do not update a running generation. Stage a complete bundle and credential,
then coordinate a server and companion restart; cancellation does not prove that
upstream work stopped.

Configured `personaFile` remains blocked by CHAR-02's private publication gate.
It fails before credential access or listener readiness; do not treat omission as
persona qualification or configure it in production. RESP-04 QA-005 remains
**OPEN / NOT REPRODUCED / HISTORICAL CAUSE UNKNOWN**. These offline configuration
checks do not change either disposition.

## Migrating existing character settings

The old flat Java properties are unsupported; they cannot remain beside the v1
bootstrap or be copied as root-level JSON keys. The name, aliases, and chat style
are fields of the bundle's typed `server` object:

| Former flat property name | Startup v1 bundle path | Requirement |
| --- | --- | --- |
| `characterDisplayName` | `server.characterDisplayName` | Required. |
| `characterAliases` | `server.characterAliases` | Optional array; at most eight aliases. |
| `characterChatStyle` | `server.characterChatStyle` | Optional; exactly `plain` or `bracketed` (`plain` when omitted). |

Do not put these fields in `config/worldspeaker.properties`: that Java file
accepts only `schemaVersion=1` and `configFile`. In OpenClaw,
`channels.worldspeaker` accepts only `enabled` and `configFile`; point it to the
same bundle as the Java bootstrap. Build the complete bundle from the shared
schema and authoritative deployment/binding values. Do not bulk-copy a legacy
properties file, invent missing values, or remove invalid fields to force startup.

Stage the bundle and its separately referenced credential with the required
owner-only file protections, then coordinate the server and companion stop and
restart. The running processes use the validated startup snapshot: editing the
files alone does not update names, aliases, or style. A rename preserves the
local stable key and route inputs only when all four binding IDs and the default
account remain unchanged; retain an old address explicitly in
`server.characterAliases` if it should continue to work. This does not establish
that the actual OpenClaw host retains old history. Changing server/world/character
creates a different stable key; changing agent or account changes the gameplay
route. There is no durable outcome store, automatic migration, or binding-change
guard. Back up and reconcile relevant external state and explicitly authorize
any binding migration. Never copy grants, pending work, outcomes, or history by
display-name similarity.

`personaFile`, if present, is a root-level bundle reference, not a `server` field.
The production publication block is unchanged: configuring it fails startup
before credential access or listener readiness. Do not bypass or remove that
block as part of migration. Omitting it retains no-persona behavior only; it
does not qualify persona publication.

## Optional persona guidance: implemented, inactive

The startup bundle accepts one optional `personaFile` absolute
local path. The selected file is intended for concise free-form personality, premise, and
behavioral prose; it is not a schema, executable template, ability grant, target
scope, rate, credential, administrator permission, or evidence of game history.
The reference is limited to 1,024 UTF-16 units. The offline loader requires a regular
service-owned `0600` file in a dedicated service-owned `0700` directory, with
trusted non-writable ancestors; content is strict UTF-8, 1–8,192 bytes, and may
contain paragraphs, CRLF, tabs, and ordinary Unicode prose. Symlinks, special or
hard-linked files, unsafe controls, BOM, blank content, replacement races, and
invalid permissions are rejected without logging the path or prose. The loader
does not expand paths, run commands, fetch URLs, follow includes, or read any
other file such as `AGENTS`/`SOUL` or administrator history. Offline loading
returns an immutable snapshot and exact-byte SHA-256; edits require a fresh load.
The hash is revision identity only, not authority, secrecy, or proof of model
consumption. No production snapshot is loaded while the publication gate is absent.

**This capability is not currently available in production.** The existing final
publication gate accepts an echoed private note, and no qualified general
publication control has been selected. If `personaFile` is configured, account
startup fails with the value-free `WS-COMPAT-PERSONA-PUBLICATION-CONTROL-UNQUALIFIED`
diagnostic before listener readiness, file access, SDK dispatch, or model use.
Intentionally omitting `personaFile` retains no-guidance behavior only; it is not
an override or qualification. There is no user boolean, prompt-only override, or
sentinel filter that enables it. Offline tests
exercise the owner-only loader and the exact exported SDK supplemental-context
projection through a trusted test-only seam; they do not make the production
feature available or prove confidentiality. The current final-echo escape is a
known **BLOCKED** negative control, not a passing privacy test. Offline loader and
projection coverage does not satisfy AC1 runtime availability; AC3 remains
**BLOCKED** for model-derived disclosure and historical assertions.

Do not put secrets, credentials, private administrative history, or real server
details in a persona. If later qualified and enabled, prose would be sent through
the configured OpenClaw agent and may reach its configured provider; provider
retention/confidentiality and actual host workspace/bootstrap/memory isolation
remain separate deployment blockers. Public ACK templates remain an independent
configuration surface and are never derived from private persona prose.

`/worldspeaker end` is only a local follow-up reset; it does not clear remote agent
memory. Agent/session reset, rename, and explicit binding migration are distinct
operations.

The plugin API is experimental. This package is pinned to `openclaw@2026.10.8` only.
Its server binds to `127.0.0.1`; the candidate does not support a non-loopback
listener. Do not expose it through a plaintext proxy or public network.

The channel is disabled until its exact `enabled` and `configFile` settings are
present and valid. Generate a random bearer secret outside source control, encode
it as canonical unpadded base64url, and place it only in the referenced private
credential file. The same bundle and credential must be readable by the Java and
OpenClaw service processes. The secret is used only for HMAC challenge-response
and is never sent as a bearer value, written to logs, or placed in a URL.

The plugin does not own provider credentials. Model/provider configuration remains
in OpenClaw. No provider call, client installation, or game ability is included in
the offline package tests.

## Shared gameplay context and privacy boundary

`worldspeaker.compat.v5` carries the original capture time, event/invocation IDs,
authenticated speaker, dimension, conversation/follow-up anchor, bounded captured
context, explicit context-loss status, a player-only reply audience, and the exact
captured `abilityPolicyRevision`. It can carry one previously dispatched
queued-progress record in a request and accepts attributed active-progress frames
for context injection before the next substantive turn. Progress is not player
input, cannot create a turn, and cannot complete a request. The authenticated
matching bundle supplies the policy contents; the frame revision alone grants no
authority. Older peers, including v4, are rejected without downgrade. Ability
operations remain unsupported on the wire.
Optional subscribed attention events are inert context only, default-disabled,
and can never create a turn.
They are limited to the five documented player-event groups and at most 32
granted UUIDs; movement is sampled block-position change, not continuous tracking.
A `/worldspeaker ask` line is not
broadcast as vanilla chat, but the input, targeted acknowledgement, and answer are
included in one shared gameplay context and are not confidential between players.
Natural-address and recognized follow-up lines remain public signed Minecraft
chat. The shared context is not an administrator DM, and private admin text is
never intentionally injected into the game session. Selected context, including
optional attention facts, can be processed by the configured agent and may be
transferred to whichever provider the administrator configured; local capture
limits do not establish provider-side retention or confidentiality.

The pinned SDK exports route/session-key construction and next-turn ACK injection
seams, but this is not proof of actual-host behavior. The host workspace,
bootstrap, and memory isolation between private administration and gameplay have
not been verified. Separate session keys alone are insufficient. Stop before
deployment unless the actual host can enforce that isolation; no model-level
confidentiality is promised.

Only one complete, deliberate, safe public final is eligible for answer delivery.
Reasoning, commentary, errors, tool/status lanes, supplemental payloads, empty or
invisible-only text, and unsafe controls are suppressed. Vanilla line separators
and tabs are normalized; a final over 1,980 UTF-16 units is rejected rather than
truncated. Technical delivery is not a human relevance judgment. The player may
still receive a safe terminal failure through the existing bounded path.

## Cancellation and finite work

The server snapshots a 1–30 second execution budget for each admitted request
(30 seconds by default), separate from its fixed 15-second pending age. These are
local lifecycle limits, not new v4 wire fields. On expiry or cancellation the
server revokes that request's local answer-delivery eligibility and sends the
existing attributed cancel frame when the request was forwarded. A cancellation
does not prove that OpenClaw or a provider stopped executing. The companion's
30-second stale-request guard limits late output; it is not an upstream execution
timeout. Host-work permits remain charged until their promises actually settle,
including across cancellation and transport restart, and no request is retried
automatically.

The offline CONV-06 evaluation CLI and human procedure are in
[`tools/dialogue/PROCEDURE.md`](../tools/dialogue/PROCEDURE.md). It imports only
bounded body-free metrics and sanitized human judgments, stores new artifacts
owner-only, and always reports acceptance as **NOT ACCEPTED** pending live human
and provider-gated evidence. It is not included in the packed plugin.

## Offline compatibility diagnosis

Before investigating a mismatch, run the repository preflight from the Worldspeaker
source checkout root, with the same Node, Java, npm, and service-account environment
intended for the server/host. It starts no server and needs no credentials. This
example captures the complete result in a private file:

```sh
(
  set -eu
  diagnostics="$HOME/.local/state/worldspeaker/preflight.log"
  umask 077
  install -d -m 700 "${diagnostics%/*}"
  chmod 700 "${diagnostics%/*}"
  if node tools/compat/preflight.mjs --jar build/distributions/worldspeaker-candidate/worldspeaker-0.1.0-dev.1.jar --deployment compatibility/deployment.json >"$diagnostics" 2>&1; then
    preflight_status=0
  else
    preflight_status=$?
  fi
  chmod 600 "$diagnostics"
  printf 'offline preflight exit=%s; private output: %s\n' "$preflight_status" "$diagnostics"
  exit "$preflight_status"
)
```

`--jar` is the path to the exact JAR being checked. The script's default manifest
is `compatibility/deployment.json`; it uses `java` and `npm` on `PATH`, and the
Node executable that launches the script. Add `--java <java-executable>` or
`--npm <npm-executable>` for different service paths. To check a companion's
declared package/peer versions, add `--companion companion/package.json` (or an
extracted companion `package.json`); it does not inspect a TGZ or prove which
OpenClaw host is running. No secret or server config is an input.

Read the private file, not chat. `FAIL` lines name the failed local check and its
observed/expected values; `WS-PREFLIGHT-UNAVAILABLE` and exit 1 mean at least one
check failed. Correct the named Java/runtime/platform or JAR metadata mismatch,
then rerun the same command. `WS-PREFLIGHT-PASS` means only that the checks that
ran passed for those local runtime values and artifact declarations. It does not
check the installed Fabric Loader/API, loaded OpenClaw host, or qualify a build.

The selected values are Minecraft `1.21.1`, Fabric Loader `0.20.1`, Fabric API
`0.116.17+1.21.1`, Temurin HotSpot JDK `21.0.13+9-LTS` (Eclipse Adoptium,
Linux x86-64), Node `24.22.1` and npm `11.19.1`, plus OpenClaw `2026.10.8` and
companion `0.1.0-dev.1`. For a Java/runtime/platform mismatch, select those
recorded executables/environment. For JAR metadata, use a fresh candidate with
those declarations; do not patch an old JAR. For companion metadata or a host
code, use the exact companion/OpenClaw pair, then rerun preflight against the same
intended files and executable paths. Any remaining FAIL is a stop, not a reason to
start the server.

A JVM unable to load the mod bytecode or Fabric rejecting an absent/incompatible
Minecraft/Loader/API dependency prevents Worldspeaker from initializing. Those
earlier startup refusals appear in the private launcher/Loader log, not as a
Worldspeaker `WS-COMPAT-*` line. Once the mod loads, codes such as
`WS-COMPAT-JAVA-BUILD`, `WS-COMPAT-LOADER`, `WS-COMPAT-FABRIC-API`, or
`WS-COMPAT-PEER-MISMATCH` indicate a loaded-runtime or companion/deployment
mismatch. `WS-COMPAT-HOST-VERSION-MISMATCH` is from the private OpenClaw host log
and indicates its host/Node/companion pair differs. Use the code and safe
observed/expected/repair fields to choose what to correct; do not expose config,
endpoint, secret, or diagnostic output.

Keep administrative diagnostics in owner-only files/logs. Never relay them through
player chat, public command feedback, or operator-player chat; players may receive
only a brief availability notice. Repair and recheck offline before considering
any separately authorized runtime work. The current QA decision remains FAIL:
the historical candidate pair is stale and is not eligible for qualification or
T3–T6. A fresh pinned rebuild is required first; a passing preflight does not make
the old pair valid, prove an actual host, or qualify the rebuilt artifacts. This
safe-handling procedure is not proof of live player/operator-player chat privacy;
that runtime behavior remains unverified.
