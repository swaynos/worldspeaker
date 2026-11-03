# Player compatibility

## Worldspeaker does not need to be installed on your client

For the selected Minecraft Java **1.21.1** candidate, use the ordinary,
unmodified Minecraft client. You do not install Worldspeaker, Fabric Loader, or
Fabric API on your client. Worldspeaker does not request a resource pack or add
custom rendering or a Worldspeaker Minecraft packet extension.

The exact Fabric Loader/API, Java, and OpenClaw companion versions listed in the
[server requirements](compatibility.md) apply to the dedicated server and its
operator, not to your client. The selected stack is still **unqualified**; these
instructions do not claim that a supported release or a successful client join
has been demonstrated. See the [latest qualification QA decision](r01/qa/COMPAT-01.md)
for current evidence and blockers.

## Optional God preset data (server-side example only)

The [God preset data ZIP](../build/presets/worldspeaker-god-preset-v1.zip) is a
bounded archive of public text, policy/configuration templates, the current
[startup-v1 schema](../compatibility/startup-config-v1.schema.json), and the
project [license](../LICENSE). Read the [preset instructions](../presets/god/README.md)
before using it. This is a data ZIP, **not** a Minecraft client resource pack,
mod, plugin, installer, or permission grant; players install nothing and need no
client access to it.

The main template includes `personaFile`, which remains unavailable and fails
startup before credential access under the unqualified CHAR-02 publication
control. The separately named NO-GUIDANCE-CONTROL template exists only for
startup/peer-coherence testing. It is not an active God persona and must never
be used by deleting an existing reference. God use is currently
**INACTIVE / BLOCKED**. The preset does not import a realm, private workspace,
administrator history, client, or provider data and supplies no provider access.

Any separately authorized future deployment needs manual private preparation, a
separately generated credential reference, identical bundle bytes and credential
on both peers, and a coordinated restart. The Java service needs actual Node.js
**24.22.1** on its trusted `PATH`; no file is activated or installed by this
public archive.

## Addressing the character

- Run `/worldspeaker` or `/worldspeaker help` for the configured character name
  and exact command. Run `/worldspeaker ask <text>` (up to 512 UTF-16 units) when
  you need input that is not broadcast as vanilla chat; the generic command is
  the same for every character and does not depend on God or on the display name.
  This keeps the command line out of public chat, not out of shared character
  context; see [Conversation context and privacy](#conversation-context-and-privacy).
- Run `/worldspeaker end` to close your local follow-up recognition. It requires
  the same conversation grant as asking. This does **not** clear OpenClaw memory
  or cancel work already admitted.
- Natural addressing recognizes the configured display name and up to eight
  configured aliases anywhere in one ordinary chat sentence. For example,
  `Could you, Archivist, explain this?` is addressed. The complete original
  message, including the name, is sent to the character without normalization.
- **Natural-address and recognized follow-up chat is normal public signed
  Minecraft chat, including after a private `/worldspeaker ask`.** Other players
  can see the complete line. Worldspeaker does not cancel, replace, or re-sign
  it. See [Conversation context and privacy](#conversation-context-and-privacy)
  for what this means for the shared character context.
  Only safely named input or an exact recognized follow-up
  leaves the server for the character; ambient and third-party mentions do
  not. Use `/worldspeaker ask <text>` when the input
  itself must not appear in public chat; a later natural follow-up is still
  public.
- Matching is literal and deterministic: NFC plus root-locale lowercase; no
  accent removal, transliteration, fuzzy matching, or homoglyph matching. A name
  must have a Unicode word boundary on both sides (letters, numbers, combining
  marks, and connector punctuation such as `_` count as word characters).
  Internal punctuation and spaces must match exactly. A decomposed accent can
  match its NFC form; `Archivists`, `_Archivist`, and a name next to a CJK letter
  do not match `Archivist`.
- A complete leading online-player profile token takes precedence over the
  character name: `Alex, ask Archivist` and `@Alex: Archivist?` are treated as
  mentions of another player only while that other Alex is online. The sender's
  own name and offline names do not suppress addressing; `Alexandra` is not
  `Alex`. Leading punctuation is not skipped, so `, Alex, ask Archivist` is not
  an Alex prefix. Quoted, possessive, or third-person character mentions without
  another online-player prefix may still invoke the character. These cases are
  intentionally rule-based, not inferred from sentence meaning.
- Follow-up recognition opens for the configured **30 seconds by default** only
  after a complete character answer was successfully sent for an admitted
  `/worldspeaker ask` or named natural-address request. `followupWindowSeconds`
  accepts `0` (disabled) through `30`; settings apply on restart. An ACK, failure,
  busy/denied notice, or unanswered request does not open a window. The deadline
  starts at successful answer dispatch, is fixed, and is closed at exactly the
  configured boundary. Follow-up lines and their answers never extend it.
- The unnamed continuation rule recognizes only these whole-line English
  phrases, with optional one `.`, `?`, or `!`: `why`, `how so`, `what do you
  mean`, `can you explain that`, and `tell me more`. Matching uses NFC and
  root-locale lowercase and strips Unicode spaces only at the edges. This is a
  small exact phrase list, **not semantic understanding**. `why?` can continue
  an open exchange; `yes`, `I found iron`, `why is Alex leaving?`, or another
  unnamed line is not forwarded. Other ordinary chat from that same player
  closes the window and any delayed opening candidate. Another online player's
  leading name or `@name` closes/skips it before follow-up matching.
- Natural chat and the explicit command are for authenticated online-mode
  players. Console, RCON, command blocks, and substituted command sources are
  refused. A conversation grant is required; operator status alone does not
  grant access.
- Character replies and safe notices are sent only to the requesting player's
  current connection. They are not broadcast as character messages and use the
  server-owned `[Worldspeaker] <name>:` label in vanilla non-overlay system chat,
  not a signed message from another player. The administrator may choose the
  bracketed name envelope `[Worldspeaker] [<name>]:` instead; the fixed origin
  marker remains in both styles. Help and command errors remain Worldspeaker
  guidance. If the companion is unavailable or a request cannot be served, a
  concise private notice may replace the character answer.
- The candidate first-response path measures from the original server-side
  packet receipt, including routing, admission, and queue time. A complete ready
  answer may be dispatched before the 750 ms fallback cutoff; otherwise the
  locally configured acknowledgement can be sent at that cutoff (or earlier
  when known queueing blocks the turn). A safe unavailable disposition replaces
  an acknowledgement when service cannot be used. The 1,000 ms limit is measured
  to local connection submission, not to when it appears on your screen; live
  Fabric behavior and client display timing are not yet qualified.
 - Longer work may receive a fixed notice that it is still queued or was forwarded
   and is still awaiting a complete answer. These notices do not mean that a model
   or tool is active and are not completed answers. They are bounded by the server's
   existing work limits; actual client display timing has not been qualified. One
   queued notice may start 10 seconds after capture; active notices may start 10
   and 20 seconds after forwarding, only while the request remains live, with at
   most three notices total.
 - Each admitted request snapshots the administrator's finite
   `executionBudgetSeconds` setting (default 30; allowed range 1–30 seconds).
   Pending age remains a separate, fixed 15 seconds from original capture; the
   active budget starts once when work is forwarded and cannot be renewed. Expiry
   or cancellation prevents a late answer from being delivered locally, but does
   not prove that OpenClaw or a provider stopped executing. A safe terminal notice
   may be unavailable if the original connection is gone.
- Natural addressing and follow-up phrase checks are bounded to 512 UTF-16 units
  and a complete 256-player online roster. If the roster is larger or cannot be
  checked safely, natural routing is disabled for that message; use the explicit
  command instead. Safely addressed malformed input receives only a fixed private
  refusal, never an echo. `/worldspeaker end` is a local recognition reset only;
  remote OpenClaw session reset is not implemented.
 - `worldspeaker.core.observe_player@1` and
   `worldspeaker.core.strike_lightning@1` are both unavailable. Chat and command
   support does not mean that either world ability exists or has been tested.
   For an admitted live request, the agent context includes the typed contract
   metadata and only the authenticated requester's matching complete-grant
   summaries. These summaries can include explicit other-target UUIDs, dimensions,
   range, and damage ceilings. They are metadata, not observations of the world,
   registered tools, or permission to cause an effect; credentials and private
   file paths are excluded. Both entries remain `UNIMPLEMENTED`, `UNQUALIFIED`,
   and `UNAVAILABLE`.

## Conversation context and privacy

`/worldspeaker ask <text>` keeps its text out of public Minecraft chat, but the
command input, local acknowledgements, and requester-targeted answers enter the
same shared character/game context. They are not confidential between players.
Natural-address and recognized follow-up input remains its original public
Minecraft chat line, visible to other players. Sending character replies only
to the requesting player's current connection does not make the conversation
private.

Private administration is intended to use a separate supported Control
UI/session and is not intentionally copied into gameplay. Actual isolation of
the pinned OpenClaw host's workspace, bootstrap, and memory from private
administration is unverified; separate session keys do not prove it. Stop before
deployment until actual-host evidence verifies that isolation. No model-level
confidentiality is promised.

Optional player attention is off unless the administrator explicitly enables
one or more restart-only subscriptions. If enabled, bounded join/leave,
dimension, death, respawn, or sampled block-change facts for granted players may
be attached to a later live request by that same player. The facts contain player
UUID and profile name, event kind, dimension, original capture times, and only
the small event-specific detail (origin dimension, copied-alive flag, or sampled
block-change basis). They contain no coordinates or world scans; sampled movement
is not continuous tracking. Capture is limited to the explicitly granted UUIDs
(at most 32), four retained events and 4 KiB per player, 2 KiB per event, and the
administrator's configured global count/byte/rate limits. Events expire locally
after the configured age (30 seconds by default, at most 60 seconds) and are
attached only to that player's next separately admitted live request. Attention
does not start a character reply, add authority, or announce each event in chat.

Selected facts enter the same shared gameplay context as player requests. The
configured OpenClaw agent can receive them and, depending on the administrator's
provider configuration, they may be transferred to that provider. They are not
private between players. Ask the administrator whether attention is enabled and
which provider is configured; local capture limits do not establish provider-side
retention or confidentiality.

## Conversation access and character name

The server administrator configures the public name, aliases, and conversation grants in
one strict startup bundle. The format is an incompatible migration: the former
inline Java properties are not accepted alongside version 1, and the loader does
not import, merge, rewrite, chmod, or fall back to them. Stop both services and
prepare a complete replacement privately before restarting.

```properties
# config/worldspeaker.properties: bootstrap only; no other keys are accepted.
schemaVersion=1
configFile=/absolute/private/path/startup-config.json
```

That absolute path is a placeholder, not a runnable configuration. The JSON
bundle has exactly `schemaVersion`, `binding`, `server`, `companion`,
`abilityPolicy`, and `credentialFile`, with optional `personaFile`. The OpenClaw
`channels.worldspeaker` section accepts exactly `enabled` and `configFile` and
points directly to the same bundle. Former endpoint, name/style, conversation,
follow-up, and attention properties move into the typed `server` object; stable
IDs move into `binding`. The old inline `bearerSecret` is replaced by a separate
owner-only file referenced by `credentialFile`. The bundle's `companion` values
must match the installed loopback listener and deployment. See the
[startup bundle schema](../compatibility/startup-config-v1.schema.json) and
[administrator migration notes](compatibility.md) for the exact contract.

Bootstrap, bundle, credential, and any optional persona references are literal,
canonical absolute local paths. Referenced content is strict UTF-8 and bounded;
files must be service-owned regular single-link `0600` files in a service-owned
`0700` directory with trusted non-writable ancestors. The bootstrap is limited to
4,096 bytes, bundle to 32,768 bytes, and credential to 43–86 ASCII bytes encoding
32–64 random bytes as canonical unpadded base64url. The strict v1 loader rejects
legacy/mixed formats, unknown or duplicate keys, invalid types, unsupported
versions, unsafe references, and invalid content with private diagnostics.

The accepted exact bundle bytes and validated values form an immutable snapshot
for one restart-only generation. Replacing or editing a referenced file does not
change a running process. Stage the full bundle and credential first, retire old
work by coordinating the server and companion restart, and then install the new
generation. The v1 HMAC handshake binds both peers to the bundle SHA-256; this is
separate from the deployment-manifest hash, and old handshake versions are not
accepted as fallback. Cancellation or restart does not prove upstream provider
work stopped. The finite policy describes syntax only: both abilities remain
unavailable and OpenClaw player turns keep an empty tool allowlist.

The `abilityPolicy` object is required even when all grants and denials are empty.
Do not treat policy validity as runtime authorization, protection-adapter
availability, or feature readiness. A configured `personaFile` continues to hit
the private CHAR-02 publication block before credential access; do not omit it as
a way to claim persona readiness. RESP-04 QA-005 remains **OPEN / NOT REPRODUCED /
HISTORICAL CAUSE UNKNOWN**.

`characterAliases` is optional; an absent or empty value means no aliases. It
accepts at most eight unique aliases, each 1–48 UTF-16 units, with a 384-unit
total limit. Entry-edge ASCII spaces are stripped. The display name and aliases
must be naturally addressable, visible single-line names: each has at least one
visible letter, number, punctuation mark, or symbol and contains no malformed
surrogates, controls, Minecraft formatting sign, bidi/format/default-ignorable
characters, or U+2800 BRAILLE PATTERN BLANK. In particular, U+200C ZWNJ,
U+200D ZWJ, variation selectors, and embedded U+2800 are rejected rather than
silently removed. Visible Unicode letters and attached combining marks are
supported. Reply text can still contain visible joined emoji such as `👩‍🚀`;
that sequence is not accepted as a configured natural-address name. Matching
uses NFC plus root-locale lowercase and does not strip hidden characters.
Normalized duplicates and a duplicate of the display name fail configuration.
Alias changes do not change the stable character or agent binding.

`characterChatStyle` is optional and defaults to `plain`. Its exact accepted
values are `plain`, which renders `[Worldspeaker] Archivist: body`, and
`bracketed`, which renders `[Worldspeaker] [Archivist]: body`. The origin marker
is permanent and the text is literal vanilla system chat; the style cannot
impersonate a player or grant authority. Blank, malformed, or unknown values
reject the whole configuration and produce only a private value-free diagnostic.
Like the display name and aliases, the style is restart-only and cannot change an
active conversation snapshot.

## Optional persona guidance (administrator setting; currently unavailable)

The companion configuration schema accepts an optional `personaFile` setting on
its default account.
It is intended to select one absolute local file containing concise free-form
personality, premise, and behavior prose. The literal path is limited to 1,024
UTF-16 units; no home/environment expansion, URL, shell, recursive lookup, include,
or player-selected reference is supported. The file must be regular, service-owned,
mode `0600`, and reside in a dedicated service-owned mode-`0700` directory whose
ancestors are trusted and not writable by unrelated users. It must contain 1–8,192
bytes of strict UTF-8 nonblank text. Paragraphs, CRLF, tabs, and ordinary Unicode
prose are allowed; invalid encoding, BOM, unsafe controls, symlinks, special or
hard-linked files, unsafe permissions, and detected replacement races are refused.
Only the selected file is in scope: this setting does not authorize `AGENTS`/`SOUL`
files, workspaces, private memory, or administrator history. Offline loading
returns an immutable snapshot; editing the file has no effect until a new snapshot
is loaded. Its private SHA-256 is only an exact-byte revision identifier, not a
secrecy or authority claim. Production does not load a persona snapshot while the
publication control is missing.

**Do not configure `personaFile` in production today.** The current final-output
gate accepts an echoed private note, and no qualified general publication control
is available. A configured account therefore fails closed with
`WS-COMPAT-PERSONA-PUBLICATION-CONTROL-UNQUALIFIED` before readiness or request
dispatch. Remove the setting to keep the previous behavior. No prompt-only or
sentinel/regex override enables persona use. Offline loader and SDK-projection
tests use fictional content and a trusted test-only seam; they do not demonstrate
production availability, privacy, paraphrase resistance, or model confidentiality.
The final-echo escape is a known **BLOCKED** negative control. Offline loader and
SDK-projection coverage does not satisfy AC1 runtime availability; AC3 remains
**BLOCKED** for model-derived disclosure and historical assertions.

Never put credentials, secrets, private administrative history, or real server
details in persona prose. If this feature is qualified and later activated, the
prose would enter the configured OpenClaw agent context and may be transferred to
its configured provider. Provider retention/confidentiality and actual-host
workspace/bootstrap/memory isolation remain separate deployment stop conditions.
Public acknowledgement templates are a separate surface and are never derived
from private persona prose.

Worldspeaker configures exactly one character and one default companion account;
unsupported plural character/account settings are rejected rather than selecting
an entry silently. The stable character key is the exact `serverId`, `worldId`,
and `characterId` tuple. `agentId` remains in the authenticated binding and shared
game route, while the public name, aliases, and style are presentation only.
Renaming means changing only the name, aliases, or style while keeping the four
binding IDs and default account. The local route inputs then stay the same, but
this does not prove the actual OpenClaw host preserves history. Changing a server,
world, or character ID is a different character key; changing the agent or
account changes the route. No durable outcome store or automatic migration exists.
Before any binding change, stop, back up and reconcile relevant external state,
and explicitly authorize any migration; never copy grants, pending work, outcomes,
or history based on a matching display name. `/worldspeaker end` closes only local
follow-up recognition and does not clear OpenClaw memory. Agent/session reset and
binding migration are separate administrator operations, not rename behavior.

Public reply-body Unicode categories and contextual emoji/joining properties
are pinned to the shared Unicode 15.0.0 tables
(`6fac8ff52897d02955f63e272c82f8a3fdaa6b6964d4502818c9581393656e23`) across
the Java server, companion, and v4 schema. Code points unassigned in that
version remain rejected until an explicit contract/data update; host runtime
Unicode upgrades do not silently broaden accepted replies. This is a bounded
text-visibility rule, not a guarantee that a particular client font displays
every accepted character.

The grant value is a comma-separated list of up to 32 unique canonical UUIDs;
player names, wildcards, and operator level are not grants. An omitted or empty
`conversationAllowedPlayers` value denies everyone. Invalid names, aliases, or
grants fail closed. These settings are applied coherently at startup and require
a server restart; there is no in-game reload command. Ask the administrator to
grant the UUID associated with your authenticated online-mode account.

`followupWindowSeconds` is optional: absent means `30`, `0` disables follow-up
recognition, and values `1`–`30` set the hard window in seconds. Only unsigned
whole-number values are accepted. It is applied on restart. The exact five-phrase
English continuation list and its limits are documented above; arbitrary
unnamed chat is never classified semantically.

`executionBudgetSeconds` is optional and defaults to `30`; it accepts only an
unsigned whole number from `1` through `30` and applies on restart. The pending
limit is fixed at 15 seconds from the original receipt. Once active, a request's
execution deadline is set once and cannot be extended by acknowledgements,
progress notices, retries, or reconnection. A hard absolute cap is also anchored
to the original receipt (15 seconds plus the snapshotted execution budget).
Cancellation controls local delivery only and is not evidence that upstream work
stopped. Progress is suppressed at expiry and when its interval falls beyond the
effective request deadline.

The attention properties are optional and apply only at restart. An empty
`attentionSubscriptions` value is the default; the only accepted exact lowercase
groups are `player_connection`, `player_dimension`, `player_death`,
`player_respawn`, and `player_movement`. The count is 1–64 (default 32), age
1–60 seconds (30), global retained bytes 1,024–8,192 (8,192), global rate 1–32
events/second (16), and per-player rate 1–8 (4 and never above the global rate).
Blank entries, duplicates, wildcards, or unknown names reject the configuration.
The attention grant is not a separate permission: records are limited to the
UUIDs in `conversationAllowedPlayers`, with no implicit subscription to online
players. Mandatory dialogue-freshness checks remain active even when optional
attention is disabled.

Offline tests exercise the production callback decision seam, parser, shared
ingress, grant checks, and private publication boundary with synthetic API/source
facts. They do not prove bootstrapped signed-envelope behavior, registered-event
delivery, live authentication, or a live Minecraft server/client display and
privacy check. This guide is not qualification evidence.

## Other mods may have separate client requirements

A server can run other mods that independently require a client mod, resource
pack, or specific client version. Those are not Worldspeaker requirements. If a
server asks you to install something, ask its administrator which mod requires
it and what exact version is needed. Do not remove security checks or install an
untrusted client package to work around a server's policy.

## Current status

The Minecraft 1.21.1/Fabric candidate is **selected and unqualified**, not a
supported release. Offline code and package checks cannot prove an unmodified
client can join, see a reply, or receive a useful provider-backed answer. The
[latest COMPAT-01 QA decision](r01/qa/COMPAT-01.md) remains FAIL for
qualification; this page is player guidance, not qualification evidence.
