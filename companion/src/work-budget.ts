/** Process/account-lifetime permits for upstream promises. Cancellation never releases a permit. */
export class HostWorkBudget {
  private hostCalls = 0;
  private acknowledgementCalls = 0;

  constructor(
    readonly maxHostCalls = 16,
    readonly maxAcknowledgementCalls = 16,
  ) {
    if (!Number.isInteger(maxHostCalls) || maxHostCalls < 1 ||
        !Number.isInteger(maxAcknowledgementCalls) || maxAcknowledgementCalls < 1) {
      throw new RangeError('work permit limits must be positive integers');
    }
  }

  tryAcquire(kind: 'host' | 'acknowledgement'): (() => void) | null {
    if (kind === 'host') {
      if (this.hostCalls >= this.maxHostCalls) return null;
      this.hostCalls += 1;
      return once(() => { this.hostCalls -= 1; });
    }
    if (this.acknowledgementCalls >= this.maxAcknowledgementCalls) return null;
    this.acknowledgementCalls += 1;
    return once(() => { this.acknowledgementCalls -= 1; });
  }

  snapshot(): Readonly<{ hostCalls: number; acknowledgementCalls: number }> {
    return { hostCalls: this.hostCalls, acknowledgementCalls: this.acknowledgementCalls };
  }
}

function once(release: () => void): () => void {
  let released = false;
  return () => {
    if (released) return;
    released = true;
    release();
  };
}
