# Optional God character preset

This is fictional, public creative guidance for a patient, curious deity in a
player-built world. It is an example for authors, not recovered private notes or
an operational configuration. The character is not omniscient; it should ask
when facts are missing and be honest about what it cannot know. Offerings,
bargains, and judgments are optional story ideas only. They create no debt,
permission, game effect, or proof of a past event.

The `persona.txt` prose is separate from the strict, comment-free
`ability-policy.json` default. The default policy has no grants
or denials and requires external-claim protection. No ability is available just
because it is named in prose or appears in an example. `observe_player` and
`strike_lightning` remain unavailable; player turns expose `toolsAllow: []`.

The `visual-only-policy.example.json.template` is a separate, inactive syntax
example. It has placeholders and is not included in
the default policy. It demonstrates one explicit requester-scoped visual grant,
with damage disabled and no other target. It does not implement or enable an
operation.

## Startup examples are not ready-to-run configurations

The `startup-config.json.template` includes
`personaFile`. That field deliberately remains blocked by the current CHAR-02
publication control: configured persona use is **UNAVAILABLE** and fails closed
before the credential file is read, before readiness, and before dispatch.
There is no flag, prompt workaround, or safe-name convention that enables it.

The separately named
`startup-config.no-guidance-control.json.template`
omits `personaFile` solely so the strict startup and peer-coherence contract can
be tested. It is **NOT AN ACTIVE GOD PERSONA** and is never a workaround for a
configured persona reference. Do not delete an existing reference to make God
appear operational.

Both files are full v1 JSON shapes with deliberately non-runnable identity,
deployment, hash, and absolute-path placeholders. They include no credential
file or usable secret. Do not copy either template into a live configuration.
The shared [startup-v1 schema](../../compatibility/startup-config-v1.schema.json)
is authoritative; a distributable archive includes its current bytes rather than
a fork.

## Use and privacy limits

The archive is a **data ZIP**, not a Minecraft client resource pack, mod, plugin,
installer, or activation package. Read its public prose offline or copy it as an
example into an independently authorized private preparation process. This
repository does not import a realm, private workspace, administrator history,
client, or provider data. It supplies no provider configuration or access.

Any future authorized deployment requires manual private preparation of a
complete bundle, a separately generated random credential referenced by path,
identical bundle bytes and credential on both peers, and a coordinated service
restart. Bootstrap, bundle, credential, and any selected persona must be regular
single-link mode-`0600` files in a service-owned mode-`0700` directory with
trusted ancestors. The Java service requires actual Node.js **24.22.1** on its
trusted `PATH`; a missing or wrong version fails closed. Startup is not an
installer, does not repair permissions, and does not hot-reload.

Configured God persona use remains **INACTIVE / BLOCKED**. The final publication
control and actual-host workspace/bootstrap/memory isolation are not qualified;
RESP-04 QA-005 remains **OPEN / NOT REPRODUCED / HISTORICAL CAUSE UNKNOWN**.
Actual-host, provider, vanilla-client, and human review are separate gates. This
data package is not evidence that any of them passed.

The project is GPL-3.0-only; see the [license](../../LICENSE). No secret or private
deployment details belong in this preset.
