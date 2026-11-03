package org.worldspeaker.bootstrap;

import org.worldspeaker.compat.CharacterChatStyle;

import java.util.function.BooleanSupplier;
import java.util.function.Predicate;

/** Publishes an answer before allowing its active request to become terminal. */
final class SmokeAnswerDelivery {
    private SmokeAnswerDelivery() {}

    static Outcome publishThenComplete(String characterDisplayName, String answer, Predicate<String> publishRendered,
                                       BooleanSupplier completeRequest, Runnable startNext) {
        return publishThenComplete(characterDisplayName, answer, CharacterChatStyle.PLAIN,
                publishRendered, completeRequest, () -> {}, startNext);
    }

    static Outcome publishThenComplete(String characterDisplayName, String answer, Predicate<String> publishRendered,
                                       BooleanSupplier completeRequest, Runnable afterComplete, Runnable startNext) {
        return publishThenComplete(characterDisplayName, answer, CharacterChatStyle.PLAIN,
                publishRendered, completeRequest, afterComplete, startNext);
    }

    static Outcome publishThenComplete(String characterDisplayName, String answer, CharacterChatStyle style,
                                       Predicate<String> publishRendered, BooleanSupplier completeRequest,
                                       Runnable afterComplete, Runnable startNext) {
        if (publishRendered == null) return Outcome.PLAYER_UNAVAILABLE;

        if (!VanillaDialogue.isRenderableAnswerBody(answer)) return Outcome.ANSWER_NOT_PUBLISHED;
        String rendered = VanillaDialogue.renderCharacterLine(characterDisplayName, answer, style);
        if (rendered.isEmpty() || !publishRendered.test(rendered)) return Outcome.ANSWER_NOT_PUBLISHED;
        if (!completeRequest.getAsBoolean()) return Outcome.REQUEST_NOT_COMPLETED;

        if (afterComplete != null) afterComplete.run();
        startNext.run();
        return Outcome.COMPLETED;
    }

    enum Outcome {
        PLAYER_UNAVAILABLE,
        ANSWER_NOT_PUBLISHED,
        REQUEST_NOT_COMPLETED,
        COMPLETED
    }
}
