# Prototype latency baseline

## Scope and evidence

On October 20, 2026, a read-only investigation correlated Minecraft server logs
with an existing post-session backup of the earlier RCON bridge's event and
action ledger. The relevant session occurred on **October 19, approximately
10:40–10:55 p.m. PDT**, equivalent to **October 20, 05:40–05:55 UTC**. Minecraft
logged those events as approximately **01:40–01:55 EDT**.

This is one short, two-player session of the predecessor implementation, not a
Worldspeaker benchmark or a general measurement of OpenClaw performance. Raw
logs, player identities, private host details, and credentials are intentionally
not included in this repository.

## Measurements

| Measurement | Sample | Result |
| --- | --- | --- |
| Chat capture delay | 67 messages | About 1 second median; approximately 0.2–1.9 seconds. |
| Early successful replies, before the severe backlog | First 10 confirmed speech actions | 4.6–20.2 seconds; median 13.3 seconds. |
| All successful replies | 18 confirmed speech actions | Median 18.2 seconds; maximum 119.3 seconds. |
| All attempted replies, including failures after players left | 24 speech actions | Median 67.5 seconds; maximum 213.2 seconds. |
| Recorded server-action execution | 24 speech actions and 2 lightning actions | 137–185 ms; median 140 ms. |

Reply latency is measured from bridge capture of the triggering event to the
recorded completion of the corresponding action. It therefore excludes the
initial capture delay. Failed attempts are explicitly included only in the
all-attempts row; they are not delivered replies.

Capture estimates compare second-resolution Minecraft log timestamps with
bridge capture timestamps, so they are approximate. The action-execution
measurement covers the bridge's journaled execution portion, including its
RCON command/receipt work. It does not isolate RCON wire latency or include
upstream queueing, model work, and SSH connection setup.

A confirmed speech action establishes successful server submission, **not a
client read receipt**. The available records do not isolate model inference
time from all other work in an agent turn.

## Findings

### 1. A shared sequential queue amplified repeated messages

The existing integration awaited an entire agent turn before claiming the next
event. Addressed prayers were handled individually, while some ordinary chat
could be batched. There was no effective coalescing or player fairness for a
burst of addressed messages.

The intake did skip events more than five minutes old when claiming them.
That coarse cutoff did not prevent multi-minute delays or make an answer's
context fresh by the time it was submitted.

One player sent seven repetitions of the same addressed taunt in about six
seconds. These became separate agent turns, with replies progressively falling
further behind. Another player's **"GOD! DO SOMETHING!"** waited approximately
139 seconds before speech was attempted.

Both players had disconnected by then. Six late speech attempts failed after
the server became empty; the last was attempted approximately 213 seconds
after its triggering event. These were unsuccessful attempts, not evidence of
continued delivery to offline players.

**Implication:** Bound pending work, coalesce repeated requests, schedule fairly
between players, and explicitly handle expiry and player departure. A response
to each queued repetition is not a responsive conversation.

### 2. Address detection missed natural requests

The prototype marked a message as a prayer only if its trimmed text began with
`God` or `@God`, case-insensitively, at a word boundary or recognized punctuation.
These actual messages were instead classified as ordinary chatter:

- "hi god"
- "What do you think God? You created this world after all :D"
- "Save me from evil God!"
- "help me god!"

Ordinary chatter was still captured and could reach the agent, but followed a
path where silence was generally encouraged. Misclassification was therefore
a separate cause of apparent ignoring, not proof of dropped messages or RCON
failure.

**Implication:** Provide a discoverable explicit interaction path, recognize
natural addressing, and retain enough conversation context for follow-ups.

### 3. Queued responses used stale context

A warning about judging a player *if* they killed another player was submitted
after the killing had already been recorded. The two confirmed lightning
actions followed their triggering death events by approximately 11 and 36
seconds.

**Implication:** Refresh relevant world context before responses and effects.
Time-sensitive situations cannot rely solely on the event that originally
entered the queue.

### 4. The recorded RCON execution portion was not the dominant delay

The bridge completed its recorded game-action execution in roughly 140 ms,
while end-to-end reply attempts took seconds to minutes. Changing transport or
moving game logic into a mod will not, by itself, resolve a shared agent backlog
or missed addressing.

This does **not** establish that RCON had no overhead or that model inference
alone caused the remaining delay. Stage-level instrumentation is needed to
separate queue wait, transport setup, inference, and tool work.

## Required regression scenarios

- A player addresses the character with its name at the beginning, middle,
  and end of a message, then follows up without repeating the name.
- One player repeats the same request rapidly while another makes a distinct
  request; the second player must not wait behind a turn for every repetition.
- A death, movement, or other material world change occurs while a reply is
  being generated; the final response and action must account for it.
- A player leaves while work is pending; obsolete work must not keep draining
  through the model and server as if the player were still waiting.
- OpenClaw becomes unavailable, reconnects, or returns an uncertain action
  outcome; requests receive honest feedback and effects are not duplicated.

Use these scenarios alongside the proposed acceptance gates in the
[roadmap](roadmap.md). Do not compare only against this session's averages:
its light-load and overloaded periods behaved very differently.
