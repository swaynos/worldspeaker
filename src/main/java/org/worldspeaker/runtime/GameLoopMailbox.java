package org.worldspeaker.runtime;

import org.worldspeaker.bootstrap.VanillaDialogue;
import org.worldspeaker.bootstrap.FirstReplyScheduler;
import org.worldspeaker.compat.CompanionConfig;
import org.worldspeaker.compat.PrivateConfigurationProblem;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/** Fixed typed handoffs between transport/configuration workers and Fabric's server thread. */
public final class GameLoopMailbox {
    private static final Consumer<Offer> NO_ACKNOWLEDGEMENT_OBSERVER = ignored -> {};
    private static final Consumer<Offer> NO_PROGRESS_OBSERVER = ignored -> {};
    private final AtomicReference<TerminalState> terminalState = new AtomicReference<>(TerminalState.EMPTY);
    private final AtomicReference<AcknowledgementState> acknowledgement = new AtomicReference<>();
    private final AtomicReference<ProgressState> progress = new AtomicReference<>();
    private final AtomicReference<BootstrapResult> bootstrap = new AtomicReference<>();
    private final AtomicBoolean unavailable = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final Consumer<Offer> acknowledgementOfferObserver;
    private final Consumer<Offer> progressOfferObserver;

    public GameLoopMailbox() {
        this(NO_ACKNOWLEDGEMENT_OBSERVER, NO_PROGRESS_OBSERVER);
    }

    /** Package-scoped observation point used by lifecycle fixtures to record actual ACK offer outcomes. */
    GameLoopMailbox(Consumer<Offer> acknowledgementOfferObserver) {
        this(acknowledgementOfferObserver, NO_PROGRESS_OBSERVER);
    }

    /** Package-scoped observation points used by lifecycle fixtures to record actual completion offers. */
    GameLoopMailbox(Consumer<Offer> acknowledgementOfferObserver, Consumer<Offer> progressOfferObserver) {
        this.acknowledgementOfferObserver = Objects.requireNonNull(acknowledgementOfferObserver);
        this.progressOfferObserver = Objects.requireNonNull(progressOfferObserver);
    }

    public void publishActive(ActiveRequest request) {
        if (closed.get()) return;
        TerminalState next = new TerminalState(Objects.requireNonNull(request), null, null);
        terminalState.set(next);
        if (closed.get()) terminalState.compareAndSet(next, TerminalState.EMPTY);
    }

    public void clearActive(ActiveRequest request) {
        if (request == null) return;
        while (true) {
            TerminalState current = terminalState.get();
            if (!request.equals(current.active)) return;
            if (terminalState.compareAndSet(current, TerminalState.EMPTY)) return;
        }
    }

    public Offer offerTerminal(TerminalFrame frame) {
        Objects.requireNonNull(frame);
        if (closed.get()) return Offer.STALE;
        if (!frame.isWellFormed()) return Offer.INVALID;
        while (true) {
            if (closed.get()) return Offer.STALE;
            TerminalState current = terminalState.get();
            if (current.active == null || !current.active.matches(frame)) return Offer.STALE;
            if (current.accepted != null) {
                if (current.accepted.sameContent(frame)) return Offer.DUPLICATE;
                markUnavailable();
                return closed.get() ? Offer.STALE : Offer.CONFLICT;
            }
            FirstReplyScheduler.Claim claim = current.active.scheduler == null || current.active.firstReplySlot == null
                    ? FirstReplyScheduler.Claim.FIRST_AVAILABLE
                    : current.active.scheduler.offerTerminal(current.active.firstReplySlot);
            TerminalState next = new TerminalState(current.active, frame, frame);
            if (terminalState.compareAndSet(current, next)) {
                if (closed.get()) return Offer.STALE;
                return claim == FirstReplyScheduler.Claim.LATE_TERMINAL ? Offer.LATE_TERMINAL : Offer.ACCEPTED;
            }
        }
    }

    public TerminalFrame takeTerminal() {
        while (true) {
            TerminalState current = terminalState.get();
            if (current.terminal == null) return null;
            TerminalState next = new TerminalState(current.active, null, current.accepted);
            if (terminalState.compareAndSet(current, next)) return current.terminal;
        }
    }

    public TerminalFrame peekTerminal() {
        return terminalState.get().terminal;
    }

    public ActiveRequest activeRequest() {
        return terminalState.get().active;
    }

    public Offer offerAcknowledgement(AcknowledgementCompletion completion) {
        Objects.requireNonNull(completion);
        while (true) {
            if (closed.get()) return reportAcknowledgementOffer(Offer.STALE);
            AcknowledgementState existing = acknowledgement.get();
            if (existing == null || !existing.identity.matches(completion)) {
                return reportAcknowledgementOffer(Offer.STALE);
            }
            if (existing.completion != null) {
                if (existing.completion.equals(completion)) return reportAcknowledgementOffer(Offer.DUPLICATE);
                markUnavailable();
                return reportAcknowledgementOffer(closed.get() ? Offer.STALE : Offer.CONFLICT);
            }
            if (acknowledgement.compareAndSet(existing, new AcknowledgementState(existing.identity, completion))) {
                return reportAcknowledgementOffer(closed.get() ? Offer.STALE : Offer.ACCEPTED);
            }
        }
    }

    private Offer reportAcknowledgementOffer(Offer outcome) {
        try {
            acknowledgementOfferObserver.accept(outcome);
        } catch (RuntimeException ignored) {
            // An observation callback cannot alter or interrupt the mailbox handoff.
        }
        return outcome;
    }

    /** Reserves the dedicated completion slot before submitting the acknowledgement frame. */
    public boolean expectAcknowledgement(String requestId, long serviceGeneration) {
        if (closed.get()) return false;
        AcknowledgementIdentity expected = new AcknowledgementIdentity(Objects.requireNonNull(requestId), serviceGeneration);
        while (true) {
            if (closed.get()) return false;
            AcknowledgementState existing = acknowledgement.get();
            if (existing != null) {
                if (existing.identity.equals(expected)) return existing.completion == null;
                unavailable.set(true);
                return false;
            }
            AcknowledgementState accepted = new AcknowledgementState(expected, null);
            if (acknowledgement.compareAndSet(null, accepted)) {
                if (!closed.get()) return true;
                acknowledgement.compareAndSet(accepted, null);
                return false;
            }
        }
    }

    public AcknowledgementCompletion takeAcknowledgement() {
        while (true) {
            AcknowledgementState existing = acknowledgement.get();
            if (existing == null || existing.completion == null) return null;
            if (acknowledgement.compareAndSet(existing, null)) return existing.completion;
        }
    }

    /** Claims only the completion that was inspected before the caller started bounded work. */
    public AcknowledgementCompletion takeAcknowledgement(AcknowledgementCompletion expected) {
        Objects.requireNonNull(expected);
        while (true) {
            AcknowledgementState existing = acknowledgement.get();
            if (existing == null || !expected.equals(existing.completion)) return null;
            if (acknowledgement.compareAndSet(existing, null)) return existing.completion;
        }
    }

    /** Non-destructive view used to decide whether a bounded operation may start. */
    public AcknowledgementCompletion peekAcknowledgement() {
        AcknowledgementState existing = acknowledgement.get();
        return existing == null ? null : existing.completion;
    }

    public void clearAcknowledgement(String requestId, long serviceGeneration) {
        AcknowledgementIdentity expected = new AcknowledgementIdentity(requestId, serviceGeneration);
        while (true) {
            AcknowledgementState existing = acknowledgement.get();
            if (existing == null || !existing.identity.equals(expected)) return;
            if (acknowledgement.compareAndSet(existing, null)) return;
        }
    }

    /** Reserves a progress-send completion independently of terminal and ACK context slots. */
    public boolean expectProgress(String requestId, String messageId, long serviceGeneration) {
        if (closed.get()) return false;
        ProgressIdentity expected = new ProgressIdentity(Objects.requireNonNull(requestId),
                Objects.requireNonNull(messageId), serviceGeneration);
        while (true) {
            if (closed.get()) return false;
            ProgressState existing = progress.get();
            if (existing != null) return existing.identity.equals(expected) && existing.completion == null;
            ProgressState accepted = new ProgressState(expected, null);
            if (progress.compareAndSet(null, accepted)) {
                if (!closed.get()) return true;
                progress.compareAndSet(accepted, null);
                return false;
            }
        }
    }

    public Offer offerProgress(ProgressCompletion completion) {
        Objects.requireNonNull(completion);
        while (true) {
            if (closed.get()) return reportProgressOffer(Offer.STALE);
            ProgressState existing = progress.get();
            if (existing == null || !existing.identity.matches(completion)) return reportProgressOffer(Offer.STALE);
            if (existing.completion != null) {
                if (existing.completion.equals(completion)) return reportProgressOffer(Offer.DUPLICATE);
                markUnavailable();
                return reportProgressOffer(closed.get() ? Offer.STALE : Offer.CONFLICT);
            }
            if (progress.compareAndSet(existing, new ProgressState(existing.identity, completion))) {
                return reportProgressOffer(closed.get() ? Offer.STALE : Offer.ACCEPTED);
            }
        }
    }

    private Offer reportProgressOffer(Offer outcome) {
        try {
            progressOfferObserver.accept(outcome);
        } catch (RuntimeException ignored) {
            // An observation callback cannot alter or interrupt the mailbox handoff.
        }
        return outcome;
    }

    public ProgressCompletion peekProgress() {
        ProgressState existing = progress.get();
        return existing == null ? null : existing.completion;
    }

    public ProgressCompletion takeProgress(ProgressCompletion expected) {
        Objects.requireNonNull(expected);
        while (true) {
            ProgressState existing = progress.get();
            if (existing == null || !expected.equals(existing.completion)) return null;
            if (progress.compareAndSet(existing, null)) return existing.completion;
        }
    }

    public void clearProgress(String requestId, String messageId, long serviceGeneration) {
        ProgressIdentity expected = new ProgressIdentity(requestId, messageId, serviceGeneration);
        while (true) {
            ProgressState existing = progress.get();
            if (existing == null || !existing.identity.equals(expected)) return;
            if (progress.compareAndSet(existing, null)) return;
        }
    }

    public void clearAll() {
        closed.set(true);
        terminalState.set(TerminalState.EMPTY);
        acknowledgement.set(null);
        progress.set(null);
        bootstrap.set(null);
        unavailable.set(false);
    }

    public boolean offerBootstrap(BootstrapResult result) {
        if (closed.get()) return false;
        BootstrapResult accepted = Objects.requireNonNull(result);
        if (!bootstrap.compareAndSet(null, accepted)) return false;
        if (!closed.get()) return true;
        bootstrap.compareAndSet(accepted, null);
        return false;
    }

    public BootstrapResult takeBootstrap() {
        return bootstrap.getAndSet(null);
    }

    public void markUnavailable() {
        if (!closed.get()) {
            unavailable.set(true);
            if (closed.get()) unavailable.set(false);
        }
    }

    public boolean takeUnavailable() {
        return unavailable.getAndSet(false);
    }

    public enum Offer { ACCEPTED, LATE_TERMINAL, DUPLICATE, STALE, INVALID, CONFLICT }

    public record ActiveRequest(String requestId, String invocationId, String characterId,
                                String audiencePlayerId, long serviceGeneration,
                                FirstReplyScheduler scheduler, FirstReplyScheduler.Slot firstReplySlot) {
        public ActiveRequest {
            Objects.requireNonNull(requestId);
            Objects.requireNonNull(invocationId);
            Objects.requireNonNull(characterId);
            Objects.requireNonNull(audiencePlayerId);
        }

        /** Legacy fixture constructor; production uses all immutable attribution fields. */
        public ActiveRequest(String requestId, String audiencePlayerId, long serviceGeneration) {
            this(requestId, requestId, "", audiencePlayerId, serviceGeneration, null, null);
        }

        public ActiveRequest(String requestId, String invocationId, String characterId,
                             String audiencePlayerId, long serviceGeneration) {
            this(requestId, invocationId, characterId, audiencePlayerId, serviceGeneration, null, null);
        }

        private boolean matches(TerminalFrame frame) {
            return requestId.equals(frame.requestId()) && invocationId.equals(frame.invocationId()) &&
                    (characterId.isEmpty() || characterId.equals(frame.characterId())) &&
                    audiencePlayerId.equals(frame.audiencePlayerId()) &&
                    serviceGeneration == frame.serviceGeneration();
        }
    }

    /** Contains wire values only; no server/player/world object crosses from a worker. */
    public record TerminalFrame(String requestId, String invocationId, String characterId,
                                String audiencePlayerId, String messageId,
                                String kind, String text, long serviceGeneration, long receivedAtNanos) {
        public TerminalFrame {
            Objects.requireNonNull(requestId);
            Objects.requireNonNull(invocationId);
            Objects.requireNonNull(characterId);
            Objects.requireNonNull(audiencePlayerId);
            Objects.requireNonNull(messageId);
            Objects.requireNonNull(kind);
            Objects.requireNonNull(text);
        }

        /** Legacy fixture constructor; production frames are created by the v2 parser. */
        public TerminalFrame(String requestId, String audiencePlayerId, String messageId,
                             String kind, String text, long serviceGeneration, long receivedAtNanos) {
            this(requestId, requestId, "", audiencePlayerId, messageId, kind, text, serviceGeneration, receivedAtNanos);
        }

        private boolean sameContent(TerminalFrame other) {
            return requestId.equals(other.requestId) && invocationId.equals(other.invocationId) &&
                    characterId.equals(other.characterId) && audiencePlayerId.equals(other.audiencePlayerId) &&
                    serviceGeneration == other.serviceGeneration && messageId.equals(other.messageId) &&
                    kind.equals(other.kind) && text.equals(other.text);
        }

        private boolean isWellFormed() {
            if (!isSafeTerminalField(requestId) || !isSafeTerminalField(invocationId) ||
                    !isSafeTerminalField(characterId) && !characterId.isEmpty() || !isSafeTerminalField(audiencePlayerId) ||
                    !isSafeTerminalField(messageId) || serviceGeneration < 0) return false;
            if ("answer".equals(kind)) return VanillaDialogue.isRenderableAnswerBody(text);
            return "failure".equals(kind) && text.isEmpty() && messageId.equals("failure:" + requestId);
        }

        private static boolean isSafeTerminalField(String value) {
            return value != null && !value.isBlank() && value.length() <= VanillaDialogue.MAX_RENDERED_LENGTH &&
                    value.codePoints().noneMatch(Character::isISOControl);
        }
    }

    public record AcknowledgementCompletion(String requestId, long serviceGeneration, boolean delivered) {
        public AcknowledgementCompletion {
            Objects.requireNonNull(requestId);
        }

    }

    public record ProgressCompletion(String requestId, String messageId, long serviceGeneration,
                                     ProgressDeliveryOutcome outcome) {
        public ProgressCompletion {
            Objects.requireNonNull(requestId);
            Objects.requireNonNull(messageId);
            Objects.requireNonNull(outcome);
        }
    }

    public enum ProgressDeliveryOutcome { SEND_COMPLETED, FAILED, UNKNOWN }

    private record TerminalState(ActiveRequest active, TerminalFrame terminal, TerminalFrame accepted) {
        private static final TerminalState EMPTY = new TerminalState(null, null, null);
    }

    private record AcknowledgementIdentity(String requestId, long serviceGeneration) {
        private boolean matches(AcknowledgementCompletion completion) {
            return requestId.equals(completion.requestId()) && serviceGeneration == completion.serviceGeneration();
        }
    }

    private record AcknowledgementState(AcknowledgementIdentity identity, AcknowledgementCompletion completion) {}

    private record ProgressIdentity(String requestId, String messageId, long serviceGeneration) {
        private boolean matches(ProgressCompletion completion) {
            return requestId.equals(completion.requestId()) && messageId.equals(completion.messageId()) &&
                    serviceGeneration == completion.serviceGeneration();
        }
    }

    private record ProgressState(ProgressIdentity identity, ProgressCompletion completion) {}

    public record BootstrapResult(CompanionConfig config, String diagnosticCode, PrivateConfigurationProblem problem) {
        public BootstrapResult(CompanionConfig config, String diagnosticCode) {
            this(config, diagnosticCode, null);
        }

        public static BootstrapResult problem(PrivateConfigurationProblem problem) {
            return new BootstrapResult(null, null, Objects.requireNonNull(problem));
        }

        public BootstrapResult {
            boolean hasFailure = diagnosticCode != null || problem != null;
            if ((config == null) == !hasFailure || diagnosticCode != null && problem != null ||
                    diagnosticCode != null && !diagnosticCode.matches("[A-Z0-9-]{1,64}")) {
                throw new IllegalArgumentException("bootstrap result must contain config or bounded safe diagnostic data");
            }
        }
    }
}
