package org.worldspeaker.bootstrap;

import org.junit.jupiter.api.Test;
import org.worldspeaker.runtime.TickMetrics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SmokeFirstPublicDispatchTest {
    @Test
    void failedSendAddsNoSampleAndFirstSuccessfulDispatchUsesOriginalCaptureExactlyOnce() {
        TickMetrics metrics = new TickMetrics();
        SmokeFirstPublicDispatch invocation = new SmokeFirstPublicDispatch(1_000_000_000L);
        TickMetrics.FirstPublicSource source = TickMetrics.FirstPublicSource.LOCAL_ACKNOWLEDGEMENT;
        TickMetrics.FirstPublicDisposition disposition = TickMetrics.FirstPublicDisposition.ACKNOWLEDGED;

        assertFalse(invocation.recordDispatch(false, metrics, 1_500_000_000L, source, disposition),
                "a failed public send must not enter the successful-dispatch histogram");
        assertFalse(invocation.isRecorded());
        assertEquals(0, total(metrics.firstPublicDispatchHistogramSnapshot()));

        assertTrue(invocation.recordDispatch(true, metrics, 1_800_000_000L, source, disposition));
        assertFalse(invocation.recordDispatch(true, metrics, 2_400_000_000L,
                TickMetrics.FirstPublicSource.INTEGRATION_ANSWER, TickMetrics.FirstPublicDisposition.ANSWERED),
                "a late answer must not double-count an invocation already acknowledged");

        long[][][] histograms = metrics.firstPublicDispatchHistogramSnapshot();
        assertEquals(1, histograms[source.ordinal()][disposition.ordinal()][3],
                "the original t0 gives 800 ms, in the 1 s bucket");
        assertEquals(0, totalExcept(histograms, source.ordinal(), disposition.ordinal()));
        assertEquals(1, total(histograms));
    }

    @Test
    void distinctCoalescedInvocationKeepsItsOwnCaptureTimeAndDisposition() {
        TickMetrics metrics = new TickMetrics();
        SmokeFirstPublicDispatch original = new SmokeFirstPublicDispatch(10_000_000_000L);
        SmokeFirstPublicDispatch coalesced = new SmokeFirstPublicDispatch(12_000_000_000L);

        assertTrue(original.recordDispatch(true, metrics, 10_500_000_000L,
                TickMetrics.FirstPublicSource.LOCAL_ACKNOWLEDGEMENT,
                TickMetrics.FirstPublicDisposition.ACKNOWLEDGED));
        assertTrue(coalesced.recordDispatch(true, metrics, 12_250_000_000L,
                TickMetrics.FirstPublicSource.LOCAL_COALESCED,
                TickMetrics.FirstPublicDisposition.COALESCED));

        long[][][] histograms = metrics.firstPublicDispatchHistogramSnapshot();
        assertEquals(1, histograms[TickMetrics.FirstPublicSource.LOCAL_ACKNOWLEDGEMENT.ordinal()]
                [TickMetrics.FirstPublicDisposition.ACKNOWLEDGED.ordinal()][1]);
        assertEquals(1, histograms[TickMetrics.FirstPublicSource.LOCAL_COALESCED.ordinal()]
                [TickMetrics.FirstPublicDisposition.COALESCED.ordinal()][0]);
        assertEquals(2, total(histograms));
    }

    private static long total(long[][][] histograms) {
        long count = 0;
        for (long[][] source : histograms) {
            for (long[] disposition : source) {
                for (long bucket : disposition) count += bucket;
            }
        }
        return count;
    }

    private static long totalExcept(long[][][] histograms, int excludedSource, int excludedDisposition) {
        long count = 0;
        for (int source = 0; source < histograms.length; source++) {
            for (int disposition = 0; disposition < histograms[source].length; disposition++) {
                if (source == excludedSource && disposition == excludedDisposition) continue;
                for (long bucket : histograms[source][disposition]) count += bucket;
            }
        }
        return count;
    }
}
