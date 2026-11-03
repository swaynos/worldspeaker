package org.worldspeaker.bootstrap;

import com.google.gson.JsonObject;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.function.BooleanSupplier;

/** Production seam that selects requester-only context and commits it only after queue acceptance. */
final class AttentionAttachment {
    private AttentionAttachment() {}

    static Prepared prepare(AttentionBuffer buffer, UUID requesterId, long nowNanos,
                            BiFunction<List<AttentionEvent>, AttentionBuffer.ContextStatus, JsonObject> frameFactory) {
        Objects.requireNonNull(frameFactory, "frameFactory");
        if (buffer == null) {
            AttentionBuffer.ContextStatus disabled = new AttentionBuffer.ContextStatus(
                    "disabled", "0", 0, List.of(), java.util.Set.of());
            return new Prepared(frameFactory.apply(List.of(), disabled), null, null);
        }

        int contextLimit = AttentionBuffer.MAX_BATCH_EVENTS;
        while (true) {
            AttentionBuffer.Selection selection = buffer.select(requesterId, nowNanos, contextLimit,
                    AttentionBuffer.MAX_BATCH_BYTES);
            try {
                JsonObject frame = frameFactory.apply(selection.events(), selection.status());
                return new Prepared(frame, buffer, selection);
            } catch (IllegalArgumentException frameBound) {
                if (contextLimit == 0) throw frameBound;
                buffer.recordFrameLoss(requesterId);
                contextLimit--;
            }
        }
    }

    static final class Prepared {
        private final JsonObject frame;
        private final AttentionBuffer buffer;
        private final AttentionBuffer.Selection selection;
        private boolean enqueueAttempted;

        private Prepared(JsonObject frame, AttentionBuffer buffer, AttentionBuffer.Selection selection) {
            this.frame = Objects.requireNonNull(frame, "frame");
            this.buffer = buffer;
            this.selection = selection;
        }

        JsonObject frame() { return frame.deepCopy(); }

        /** A rejected queue offer leaves both records and the loss snapshot available for retry. */
        boolean enqueue(BooleanSupplier queueOffer) {
            Objects.requireNonNull(queueOffer, "queueOffer");
            if (enqueueAttempted) throw new IllegalStateException("attention attachment was already offered");
            enqueueAttempted = true;
            if (!queueOffer.getAsBoolean()) return false;
            if (buffer != null && selection != null) buffer.commit(selection);
            return true;
        }
    }
}
