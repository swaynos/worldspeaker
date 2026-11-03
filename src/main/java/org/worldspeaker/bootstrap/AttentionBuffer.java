package org.worldspeaker.bootstrap;

import org.worldspeaker.compat.AttentionPolicy;
import org.worldspeaker.compat.ConfiguredBinding;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/** Per-conversation bounded, requester-scoped inert observations and explicit loss snapshots. */
public final class AttentionBuffer {
    public static final int MAX_EVENTS_PER_PLAYER = 4;
    public static final int MAX_BYTES_PER_PLAYER = 4096;
    public static final int MAX_EVENT_BYTES = 2048;
    public static final int MAX_BATCH_EVENTS = 8;
    public static final int MAX_BATCH_BYTES = 8192;

    private final AttentionPolicy policy;
    private final ConfiguredBinding binding;
    private final UUID serverEpochId;
    private final Set<UUID> allowedPlayers;
    private final Map<UUID, PlayerBuffer> players = new HashMap<>();
    private final SaturatingCounters counters = new SaturatingCounters();
    private final TokenBucket globalRate;
    private int retainedCount;
    private int retainedBytes;
    private long nextSequence;
    private long lastDiagnosticAt = Long.MIN_VALUE;
    private long lastDiagnosticLossCount;

    public AttentionBuffer(AttentionPolicy policy, Set<UUID> allowedPlayers,
                           ConfiguredBinding binding, UUID serverEpochId) {
        if (policy == null || allowedPlayers == null || allowedPlayers.size() > 32 ||
                allowedPlayers.stream().anyMatch(java.util.Objects::isNull) || binding == null ||
                !binding.isComplete() || serverEpochId == null) {
            throw new IllegalArgumentException("attention buffer scope is invalid");
        }
        this.policy = policy;
        this.allowedPlayers = Set.copyOf(allowedPlayers);
        this.binding = binding;
        this.serverEpochId = serverEpochId;
        this.globalRate = new TokenBucket(policy.maxEventsPerSecond());
    }

    public boolean enabled() { return policy.enabled(); }

    /** Scope and rates are checked before the supplier allocates the optional event payload. */
    public boolean capture(UUID playerId, AttentionPolicy.Subscription subscription, long nowNanos,
                           Supplier<AttentionEvent> eventFactory) {
        if (!policy.enabled() || playerId == null || subscription == null || eventFactory == null ||
                !allowedPlayers.contains(playerId) || !policy.subscribes(subscription)) return false;
        PlayerBuffer slot = slot(playerId);
        if (!globalRate.canConsume(nowNanos) || !slot.rate.canConsume(nowNanos)) {
            recordLoss(slot, LossReason.RATE);
            counters.increment(Counter.RATE_DROP);
            return false;
        }
        globalRate.consume();
        slot.rate.consume();
        AttentionEvent event;
        try {
            event = eventFactory.get();
        } catch (RuntimeException invalidPayload) {
            recordLoss(slot, LossReason.SOURCE_UNAVAILABLE);
            counters.increment(Counter.SOURCE_UNAVAILABLE);
            return false;
        }
        if (event == null || !playerId.equals(event.playerId()) || !event.hasBinding(binding) ||
                !serverEpochId.equals(event.serverEpochId()) ||
                !AttentionEvent.EventKind.fromWire(event.eventKind()).subscription().equals(subscription)) {
            recordLoss(slot, LossReason.SOURCE_UNAVAILABLE);
            counters.increment(Counter.SOURCE_UNAVAILABLE);
            return false;
        }
        int bytes = event.encodedBytes();
        if (bytes > MAX_EVENT_BYTES) {
            recordLoss(slot, LossReason.BYTES);
            counters.increment(Counter.BYTE_DROP);
            return false;
        }

        expire(slot, nowNanos);
        while (!slot.events.isEmpty() && (slot.events.size() >= MAX_EVENTS_PER_PLAYER || slot.bytes + bytes > MAX_BYTES_PER_PLAYER)) {
            evict(slot, LossReason.CAPACITY);
        }
        while ((retainedCount >= policy.maxEvents() || retainedBytes + bytes > policy.maxBytes()) &&
                oldestBuffer() != null) {
            PlayerBuffer oldest = oldestBuffer();
            evict(oldest, LossReason.CAPACITY);
        }
        if (retainedCount >= policy.maxEvents() || retainedBytes + bytes > policy.maxBytes()) {
            recordLoss(slot, LossReason.BYTES);
            counters.increment(Counter.BYTE_DROP);
            return false;
        }
        StoredEvent stored = new StoredEvent(++nextSequence, event, bytes);
        slot.events.addLast(stored);
        slot.bytes += bytes;
        retainedCount++;
        retainedBytes += bytes;
        counters.increment(Counter.CAPTURED);
        return true;
    }

    /** Expiry work is limited to one player's fixed four-record cap. */
    public int expireOne(UUID playerId, long nowNanos) {
        PlayerBuffer slot = players.get(playerId);
        return slot == null ? 0 : expire(slot, nowNanos);
    }

    /** Selects the newest eligible records in capture order; queue acceptance is the only removal point. */
    public Selection select(UUID playerId, long nowNanos, int maxEvents, int maxBatchBytes) {
        if (playerId == null || maxEvents < 0 || maxEvents > MAX_BATCH_EVENTS || maxBatchBytes < 0 ||
                maxBatchBytes > MAX_BATCH_BYTES) throw new IllegalArgumentException("attention selection bound is invalid");
        if (!policy.enabled() || !allowedPlayers.contains(playerId)) {
            return Selection.empty(playerId, disabledStatus());
        }
        PlayerBuffer slot = slot(playerId);
        expire(slot, nowNanos);
        int eligibleCount = slot.events.size();
        ArrayList<StoredEvent> selectedNewestFirst = new ArrayList<>(Math.min(maxEvents, slot.events.size()));
        int bytes = 2;
        int byteOmissions = 0;
        var iterator = slot.events.descendingIterator();
        while (iterator.hasNext() && selectedNewestFirst.size() < maxEvents) {
            StoredEvent candidate = iterator.next();
            int addition = candidate.bytes + (selectedNewestFirst.isEmpty() ? 0 : 1);
            if (bytes + addition > maxBatchBytes) {
                recordLoss(slot, LossReason.FRAME);
                counters.increment(Counter.FRAME_OMISSION);
                byteOmissions++;
                continue;
            }
            selectedNewestFirst.add(candidate);
            bytes += addition;
        }
        for (int omitted = selectedNewestFirst.size() + byteOmissions; omitted < eligibleCount; omitted++) {
            recordLoss(slot, LossReason.FRAME);
            counters.increment(Counter.FRAME_OMISSION);
        }
        java.util.Collections.reverse(selectedNewestFirst);
        List<AttentionEvent> events = selectedNewestFirst.stream().map(StoredEvent::event).toList();
        ContextStatus status = status(slot);
        return new Selection(playerId, List.copyOf(selectedNewestFirst), events, status);
    }

    /** Commits only records selected by this snapshot and only the exact loss revision represented by it. */
    public void commit(Selection selection) {
        if (selection == null || selection.playerId == null || selection.committed) return;
        PlayerBuffer slot = players.get(selection.playerId);
        if (slot == null) return;
        for (StoredEvent selected : selection.records) {
            if (slot.events.remove(selected)) {
                slot.bytes -= selected.bytes;
                retainedCount--;
                retainedBytes -= selected.bytes;
                counters.increment(Counter.ATTACHED);
            }
        }
        if (slot.lossRevision == selection.status.lossRevisionValue && slot.pendingReasons.equals(selection.reasonsSnapshot)) {
            slot.pendingReasons.clear();
        }
        selection.committed = true;
    }

    public void recordTransportLoss(UUID playerId) {
        if (playerId == null || !allowedPlayers.contains(playerId) || !policy.enabled()) return;
        recordLoss(slot(playerId), LossReason.TRANSPORT);
        counters.increment(Counter.TRANSPORT_LOSS);
    }

    public void recordFrameLoss(UUID playerId) {
        if (playerId == null || !allowedPlayers.contains(playerId) || !policy.enabled()) return;
        recordLoss(slot(playerId), LossReason.FRAME);
        counters.increment(Counter.FRAME_OMISSION);
    }

    public void recordSourceUnavailable(UUID playerId) {
        if (playerId == null || !allowedPlayers.contains(playerId) || !policy.enabled()) return;
        recordLoss(slot(playerId), LossReason.SOURCE_UNAVAILABLE);
        counters.increment(Counter.SOURCE_UNAVAILABLE);
    }

    public void clear() {
        players.clear();
        retainedCount = 0;
        retainedBytes = 0;
    }

    /** Drops one player's unconsumed optional context without affecting other requesters. */
    public void clearPlayer(UUID playerId) {
        PlayerBuffer slot = playerId == null ? null : players.remove(playerId);
        if (slot == null) return;
        retainedCount -= slot.events.size();
        retainedBytes -= slot.bytes;
    }

    public int retainedCount() { return retainedCount; }
    public int retainedBytes() { return retainedBytes; }
    public long[] countersSnapshot() { return counters.snapshot(); }

    /** Value-free coalesced private summary; caller must enqueue its logging off the game thread. */
    public String diagnosticSnapshot(long nowNanos) {
        long totalLosses = counters.lossCount();
        if (totalLosses <= lastDiagnosticLossCount || lastDiagnosticAt != Long.MIN_VALUE &&
                nowNanos - lastDiagnosticAt < TimeUnit.SECONDS.toNanos(10)) return null;
        lastDiagnosticAt = nowNanos;
        lastDiagnosticLossCount = totalLosses;
        return "captures=" + counters.value(Counter.CAPTURED) + ",attached=" + counters.value(Counter.ATTACHED) +
                ",expired=" + counters.value(Counter.EXPIRED) + ",evicted=" + counters.value(Counter.EVICTED) +
                ",rate=" + counters.value(Counter.RATE_DROP) + ",bytes=" + counters.value(Counter.BYTE_DROP) +
                ",frame=" + counters.value(Counter.FRAME_OMISSION) + ",transport=" + counters.value(Counter.TRANSPORT_LOSS) +
                ",source=" + counters.value(Counter.SOURCE_UNAVAILABLE) + ",retained=" + retainedCount +
                ",retainedBytes=" + retainedBytes;
    }

    private PlayerBuffer slot(UUID playerId) {
        PlayerBuffer existing = players.get(playerId);
        if (existing != null) return existing;
        PlayerBuffer created = new PlayerBuffer(policy.maxEventsPerPlayerSecond());
        players.put(playerId, created);
        recordLoss(created, LossReason.EPOCH_START);
        return created;
    }

    private ContextStatus status(PlayerBuffer slot) {
        List<String> reasons = LossReason.ordered().stream().filter(slot.pendingReasons::contains)
                .map(LossReason::wireName).toList();
        return new ContextStatus(reasons.isEmpty() ? "bounded" : "incomplete", Long.toString(slot.lossRevision),
                slot.lossRevision, List.copyOf(reasons), EnumSet.copyOf(slot.pendingReasons));
    }

    private ContextStatus disabledStatus() {
        return new ContextStatus("disabled", "0", 0, List.of(), Set.of());
    }

    private void recordLoss(PlayerBuffer slot, LossReason reason) {
        slot.pendingReasons.add(reason);
        if (slot.lossRevision != Long.MAX_VALUE) slot.lossRevision++;
    }

    private int expire(PlayerBuffer slot, long nowNanos) {
        int removed = 0;
        var iterator = slot.events.iterator();
        while (iterator.hasNext()) {
            StoredEvent stored = iterator.next();
            long age = nowNanos - stored.event.capturedAtMonotonicNanos();
            if (age < 0 || age >= TimeUnit.SECONDS.toNanos(policy.maxAgeSeconds())) {
                iterator.remove();
                slot.bytes -= stored.bytes;
                retainedCount--;
                retainedBytes -= stored.bytes;
                recordLoss(slot, LossReason.EXPIRY);
                counters.increment(Counter.EXPIRED);
                removed++;
            }
        }
        return removed;
    }

    private void evict(PlayerBuffer slot, LossReason reason) {
        StoredEvent oldest = slot.events.pollFirst();
        if (oldest == null) return;
        slot.bytes -= oldest.bytes;
        retainedCount--;
        retainedBytes -= oldest.bytes;
        recordLoss(slot, reason);
        counters.increment(Counter.EVICTED);
    }

    private PlayerBuffer oldestBuffer() {
        PlayerBuffer oldestBuffer = null;
        long oldestSequence = Long.MAX_VALUE;
        for (PlayerBuffer candidate : players.values()) {
            StoredEvent first = candidate.events.peekFirst();
            if (first != null && first.sequence < oldestSequence) {
                oldestSequence = first.sequence;
                oldestBuffer = candidate;
            }
        }
        return oldestBuffer;
    }

    public static final class Selection {
        private final UUID playerId;
        private final List<StoredEvent> records;
        private final List<AttentionEvent> events;
        private final ContextStatus status;
        private final Set<LossReason> reasonsSnapshot;
        private boolean committed;

        private Selection(UUID playerId, List<StoredEvent> records, List<AttentionEvent> events, ContextStatus status) {
            this.playerId = playerId;
            this.records = records;
            this.events = events;
            this.status = status;
            this.reasonsSnapshot = Set.copyOf(status.reasonSet);
        }

        private static Selection empty(UUID playerId, ContextStatus status) {
            return new Selection(playerId, List.of(), List.of(), status);
        }

        public UUID playerId() { return playerId; }
        public List<AttentionEvent> events() { return events; }
        public ContextStatus status() { return status; }
    }

    public record ContextStatus(String state, String lossRevision, long lossRevisionValue, List<String> reasons,
                                Set<LossReason> reasonSet) {
        public ContextStatus {
            reasons = List.copyOf(reasons);
            reasonSet = Set.copyOf(reasonSet);
        }
    }

    public enum Counter {
        CAPTURED, ATTACHED, EXPIRED, EVICTED, RATE_DROP, BYTE_DROP, FRAME_OMISSION,
        TRANSPORT_LOSS, SOURCE_UNAVAILABLE
    }

    public enum LossReason {
        EPOCH_START("epoch_start"), EXPIRY("expiry"), CAPACITY("capacity"), RATE("rate"),
        BYTES("bytes"), FRAME("frame"), TRANSPORT("transport"), SOURCE_UNAVAILABLE("source_unavailable");

        private final String wireName;
        LossReason(String wireName) { this.wireName = wireName; }
        public String wireName() { return wireName; }
        private static List<LossReason> ordered() { return List.of(values()); }
    }

    private static final class PlayerBuffer {
        private final ArrayDeque<StoredEvent> events = new ArrayDeque<>(MAX_EVENTS_PER_PLAYER);
        private final TokenBucket rate;
        private final EnumSet<LossReason> pendingReasons = EnumSet.noneOf(LossReason.class);
        private int bytes;
        private long lossRevision;
        private PlayerBuffer(int playerRate) { rate = new TokenBucket(playerRate); }
    }

    private record StoredEvent(long sequence, AttentionEvent event, int bytes) {}

    private static final class TokenBucket {
        private final int capacity;
        private double tokens;
        private long lastRefillNanos;
        private boolean initialized;

        private TokenBucket(int capacity) {
            this.capacity = capacity;
            this.tokens = capacity;
        }

        private void refill(long nowNanos) {
            if (!initialized) {
                initialized = true;
                lastRefillNanos = nowNanos;
                return;
            }
            long elapsed = nowNanos - lastRefillNanos;
            if (elapsed <= 0) return;
            tokens = Math.min(capacity, tokens + elapsed * (double) capacity / TimeUnit.SECONDS.toNanos(1));
            lastRefillNanos = nowNanos;
        }

        private boolean canConsume(long nowNanos) {
            refill(nowNanos);
            return tokens >= 1.0;
        }

        private void consume() { tokens = Math.max(0, tokens - 1.0); }
    }

    private static final class SaturatingCounters {
        private final long[] values = new long[Counter.values().length];
        private void increment(Counter counter) {
            int index = counter.ordinal();
            if (values[index] != Long.MAX_VALUE) values[index]++;
        }
        private long value(Counter counter) { return values[counter.ordinal()]; }
        private long[] snapshot() { return values.clone(); }
        private long lossCount() {
            long total = 0;
            for (Counter counter : List.of(Counter.EXPIRED, Counter.EVICTED, Counter.RATE_DROP, Counter.BYTE_DROP,
                    Counter.FRAME_OMISSION, Counter.TRANSPORT_LOSS, Counter.SOURCE_UNAVAILABLE)) {
                long next = value(counter);
                total = Long.MAX_VALUE - total < next ? Long.MAX_VALUE : total + next;
            }
            return total;
        }
    }
}
