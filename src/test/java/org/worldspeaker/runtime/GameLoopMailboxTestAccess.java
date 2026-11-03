package org.worldspeaker.runtime;

import java.util.Objects;
import java.util.function.Consumer;

/** Test-package bridge for observing actual acknowledgement mailbox offer outcomes. */
public final class GameLoopMailboxTestAccess {
    private GameLoopMailboxTestAccess() {}

    public static GameLoopMailbox observingAcknowledgementOffers(Consumer<GameLoopMailbox.Offer> observer) {
        return new GameLoopMailbox(Objects.requireNonNull(observer));
    }

    public static GameLoopMailbox observingCompletionOffers(Consumer<GameLoopMailbox.Offer> acknowledgementObserver,
                                                             Consumer<GameLoopMailbox.Offer> progressObserver) {
        return new GameLoopMailbox(Objects.requireNonNull(acknowledgementObserver),
                Objects.requireNonNull(progressObserver));
    }
}
