package org.worldspeaker.bootstrap;

/** Package bridge for staged publication ordering tests. */
public final class NoticePublicationTestAccess {
    private NoticePublicationTestAccess() {}

    public static boolean link(FirstReplyScheduler.Slot slot, FirstReplyScheduler.NoticePublication publication) {
        return slot.linkNoticePublication(publication);
    }

    public static boolean linkAndClaim(FirstReplyScheduler.Slot slot,
                                       FirstReplyScheduler.NoticePublication publication) {
        return slot.linkNoticePublication(publication) && publication.claim();
    }

    public static boolean complete(FirstReplyScheduler.NoticePublication publication,
                                   FirstReplyScheduler.PublicationResult result, long attemptedAtNanos) {
        return publication.complete(result, attemptedAtNanos);
    }

    public static boolean cancel(FirstReplyScheduler.NoticePublication publication) {
        return publication.cancel();
    }
}
