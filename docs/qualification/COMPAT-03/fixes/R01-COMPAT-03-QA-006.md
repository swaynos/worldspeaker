# R01-COMPAT-03-QA-006 — bounded transport shutdown

## Finding

The previous client close path took the outbound lock on the caller and queued its
cleanup behind transport-owner work. If the owner was blocked, shutdown could
wait indefinitely for cleanup that never ran. The owner executor was only shut
down after that cleanup, so it was neither independently observed nor interrupted
at a finite deadline.

## Change

- `BoundCompanionClient.close()` atomically closes admission and advances the
  connection generation before returning. It stops maintenance scheduling and
  requests owner cleanup without taking the outbound lock or joining a thread.
- `BoundedIoLane.shutdownWithControl()` closes lane admission synchronously,
  prioritizes cleanup over queued work, and monitors cleanup plus executor
  termination on a separate, one-shot daemon watchdog. Normal lane shutdown is
  also asynchronous and bounded. Saturation continues to reject; no caller-runs
  fallback was added.
- At five seconds, an unsuccessful transport shutdown is marked
  `TIMED_OUT` (or `FAILED` if cleanup could not be scheduled/completed), the
  owner is interrupted, and the client best-effort cancels its connect future,
  stops HTTP workers, and aborts the socket off the caller/owner thread. The
  private diagnostic is the fixed value `WS-COMPAT-SHUTDOWN-TIMEOUT`; no endpoint,
  credential, or conversation data is included.
- Closed/generation-stale callbacks cannot reconnect or restore readiness. A
  late socket is aborted directly off-tick if the owner lane has already closed.
- Deterministic tests use latches and an injected monotonic clock to cover a
  stalled, interruption-ignoring owner, timeout reporting, cleanup success,
  repeated close, rejection after close, and watchdog exit. No Minecraft server
  or live companion is started.

## Shutdown guarantee semantics

`close()` is non-waiting: when it returns, new client/lane work is rejected and
the connection generation is retired; it does **not** claim that socket, HTTP,
or owner-thread cleanup has already finished. Owner cleanup is attempted on the
owner lane, with queued work discarded so cleanup is not trapped behind stale
maintenance or send requests. Success is reported only after that cleanup
returns and the owner executor is observed terminated before the five-second
deadline.

If the deadline expires, the result remains `TIMED_OUT` even if the owner later
exits. Interruption, future cancellation, executor shutdown, and socket abort
are best-effort. Java cannot forcibly terminate an uninterruptible native/JDK
operation; in that case a daemon owner thread or underlying resource may persist
after the timeout diagnostic. The implementation reports that limitation rather
than claiming successful cleanup. The one-shot watchdog exits after its outcome;
it is not a reconnect or retry mechanism.

## Verification

```text
./gradlew --no-daemon -PlocalDevelopment=true test --rerun-tasks
BUILD SUCCESSFUL
```

This was a local compile/test run only. It did not start Minecraft, run the
qualification harness, contact an external host/provider, or authorize EULA
acceptance.
