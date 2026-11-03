package org.worldspeaker.bootstrap;

import org.worldspeaker.runtime.TickMetrics;

/** Attributes at most one successful public dispatch to an invocation's original capture time. */
final class SmokeFirstPublicDispatch {
    private final long capturedAtNanos;
    private final InteractionIngress.Invocation invocation;
    private boolean recorded;
    private volatile FirstReplyScheduler.NoticePublication noticePublication;

    SmokeFirstPublicDispatch(long capturedAtNanos) {
        this(capturedAtNanos, null);
    }

    SmokeFirstPublicDispatch(long capturedAtNanos, InteractionIngress.Invocation invocation) {
        this.capturedAtNanos = capturedAtNanos;
        this.invocation = invocation;
    }

    boolean recordDispatch(boolean dispatched, TickMetrics metrics, long dispatchedAtNanos,
                           TickMetrics.FirstPublicSource source,
                           TickMetrics.FirstPublicDisposition disposition) {
        return recordResult(dispatched ? FirstReplyScheduler.PublicationResult.SUBMITTED
                        : FirstReplyScheduler.PublicationResult.DEFINITELY_UNSENT,
                metrics, dispatchedAtNanos, source, disposition);
    }

    boolean recordResult(FirstReplyScheduler.PublicationResult result, TickMetrics metrics, long dispatchedAtNanos,
                         TickMetrics.FirstPublicSource source,
                         TickMetrics.FirstPublicDisposition disposition) {
        if (result == FirstReplyScheduler.PublicationResult.DEFINITELY_UNSENT) {
            if (invocation != null) invocation.recordFailedDispatch(metrics);
            else if (metrics != null) metrics.increment(TickMetrics.Counter.FAILED_PUBLIC_DISPATCHES);
            return false;
        }
        if (result == FirstReplyScheduler.PublicationResult.UNKNOWN) {
            if (invocation != null) invocation.recordUnknownDispatch(metrics);
            else if (metrics != null) metrics.increment(TickMetrics.Counter.UNKNOWN_PUBLIC_DISPATCHES);
            return false;
        }
        if (recorded) return false;
        recorded = true;
        if (invocation != null) invocation.recordFirstPublicDispatch(metrics, dispatchedAtNanos, source, disposition);
        else if (metrics != null) metrics.recordFirstPublicDispatchNanos(
                Math.max(0, dispatchedAtNanos - capturedAtNanos), source, disposition);
        return true;
    }

    void linkNoticePublication(FirstReplyScheduler.NoticePublication publication) {
        if (noticePublication != null && noticePublication != publication) {
            throw new IllegalStateException("first public notice publication changed");
        }
        noticePublication = publication;
    }

    boolean isRecorded() {
        FirstReplyScheduler.NoticePublication publication = noticePublication;
        return recorded || publication != null &&
                publication.result().state() == FirstReplyScheduler.NoticeState.SUBMITTED;
    }
}
