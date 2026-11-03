package org.worldspeaker.load;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import org.worldspeaker.WorldspeakerServer;
import org.worldspeaker.runtime.BoundedIoLane;
import org.worldspeaker.runtime.ThreadBoundary;
import org.worldspeaker.runtime.TickMetrics;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.StandardCopyOption;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** Optional isolated Fabric mod: samples real server ticks and submits only authorized player requests. */
public final class WorldspeakerTickLoadHarness implements ModInitializer {
    private static final int MAX_AUTHORIZED_PLAYERS = 8;
    private static final int SAMPLE_STRIDE = 5;
    private static final int MAX_SAMPLE_RECORDS = 8_192;
    private static final int REPEAT_ATTEMPT_LIMIT = 7;
    private static final int ACTIVE_REQUEST_CAPACITY = 1;
    private static final int GLOBAL_PENDING_CAPACITY = 16;
    private static final int PER_PLAYER_PENDING_CAPACITY = 2;
    private static final long OVERLOAD_CAPACITY_OBSERVATION_NANOS = TimeUnit.SECONDS.toNanos(14);
    private static final long MAX_OUTPUT_BYTES = 64L * 1024 * 1024;
    private static final long[] HISTOGRAM_UPPER_NANOS = {
            250_000, 500_000, 1_000_000, 2_000_000, 5_000_000,
            10_000_000, 20_000_000, 50_000_000, 100_000_000, Long.MAX_VALUE
    };
    private static final List<String> PHASES = List.of(
            "idle", "normal", "overload", "unavailable", "recovery", "repeat", "stop-backlog");
    private static final List<String> METRIC_NAMES = java.util.Arrays.stream(TickMetrics.Counter.values())
            .map(Enum::name).toList();
    private static final String[] FIRST_PUBLIC_DISPATCH_SOURCE_NAMES = TickMetrics.firstPublicDispatchSourceNames();
    private static final String[] FIRST_PUBLIC_DISPATCH_DISPOSITION_NAMES = TickMetrics.firstPublicDispatchDispositionNames();
    private static final long[] FIRST_PUBLIC_DISPATCH_UPPER_NANOS = TickMetrics.firstPublicDispatchUpperNanos();
    private static final int REQUESTS_ADMITTED_INDEX = TickMetrics.Counter.REQUESTS_ADMITTED.ordinal();
    private static final int ANSWERS_DELIVERED_INDEX = TickMetrics.Counter.ANSWERS_DELIVERED.ordinal();
    private static final int PENDING_EXPIRED_INDEX = TickMetrics.Counter.PENDING_EXPIRED.ordinal();
    private static final int UPSTREAM_REJECTIONS_INDEX = TickMetrics.Counter.UPSTREAM_REJECTIONS.ordinal();
    private static final int UNAVAILABLE_DISPOSITIONS_INDEX = TickMetrics.Counter.UNAVAILABLE_DISPOSITIONS.ordinal();

    private final BoundedIoLane io = new BoundedIoLane("worldspeaker-load-evidence-io", 2, false, 1);
    private final ScheduledThreadPoolExecutor controlReader = new ScheduledThreadPoolExecutor(1, runnable -> {
        Thread thread = new Thread(runnable, "worldspeaker-load-control-io");
        thread.setDaemon(true);
        return thread;
    });
    private final AtomicReference<List<UUID>> authorizedIds = new AtomicReference<>(List.of());
    private final Map<UUID, ServerPlayerEntity> onlineAuthorized = new ConcurrentHashMap<>();
    private final Set<UUID> disconnectedAuthorized = ConcurrentHashMap.newKeySet();
    private final AtomicReference<PhaseControl> control = new AtomicReference<>(PhaseControl.IDLE);
    private final ArrayBlockingQueue<String> samples = new ArrayBlockingQueue<>(MAX_SAMPLE_RECORDS);
    private final AtomicBoolean writerQueued = new AtomicBoolean();
    private final AtomicBoolean captureIncomplete = new AtomicBoolean();
    private final AtomicBoolean stopping = new AtomicBoolean();
    private final AtomicReference<String> pendingStatus = new AtomicReference<>();
    private final AtomicBoolean authorizedIdsLoaded = new AtomicBoolean();
    private final long[][] histograms = new long[PHASES.size()][HISTOGRAM_UPPER_NANOS.length];
    private final long[] warmupHistogram = new long[HISTOGRAM_UPPER_NANOS.length];
    private final long[] phaseTicks = new long[PHASES.size()];
    private long warmupTicks;
    private long warmupElapsedNanos;
    private final long[] phaseMaxTickNanos = new long[PHASES.size()];
    private final long[] phaseOverFiftyMillis = new long[PHASES.size()];
    private long warmupMaxTickNanos;
    private long warmupOverFiftyMillis;
    private final long[] phaseCommands = new long[PHASES.size()];
    private final long[] phaseNoPlayer = new long[PHASES.size()];
    private final long[] phaseConnectionJoins = new long[PHASES.size()];
    private final long[] phaseConnectionLeaves = new long[PHASES.size()];
    private final long[] phaseAuthorizedRejoins = new long[PHASES.size()];
    private final int[] phaseOnlineHighWater = new int[PHASES.size()];
    private final long[] phaseAttempts = new long[PHASES.size()];
    private final long[] phaseDistinctInvocations = new long[PHASES.size()];
    private final long[] phaseRepeatAttempts = new long[PHASES.size()];
    private final long[] phaseRepeatAccepted = new long[PHASES.size()];
    private final long[] phaseRepeatRejected = new long[PHASES.size()];
    private final long[] phaseRejected = new long[PHASES.size()];
    private final long[] phaseDistinctOtherRequests = new long[PHASES.size()];
    private final long[] phaseDistinctOtherAttempts = new long[PHASES.size()];
    private final long[] phaseReadyTicks = new long[PHASES.size()];
    private final long[] phaseElapsedNanos = new long[PHASES.size()];
    private final long[] phaseSampleRecords = new long[PHASES.size()];
    private final long[] phaseOutstandingAdmissionHighWater = new long[PHASES.size()];
    private final long[] phasePerPlayerAdmissionHighWater = new long[PHASES.size()];
    private final int[] phasePlayersAtTwoAdmissionsHighWater = new int[PHASES.size()];
    private final int[] phaseCapacityOnlineHighWater = new int[PHASES.size()];
    private final long[] phaseCapacityPerPlayerAdmissionHighWater = new long[PHASES.size()];
    private final int[] phaseCapacityPlayersAtTwoAdmissionsHighWater = new int[PHASES.size()];
    private final long[] phaseCapacityObservationTicks = new long[PHASES.size()];
    private final long[][] phaseAcceptedByPlayer = new long[PHASES.size()][MAX_AUTHORIZED_PLAYERS];
    private final long[][] phaseIntegrationCounters = new long[PHASES.size()][METRIC_NAMES.size()];
    private final long[][][][] phaseFirstPublicDispatchHistograms = new long[PHASES.size()]
            [FIRST_PUBLIC_DISPATCH_SOURCE_NAMES.length]
            [FIRST_PUBLIC_DISPATCH_DISPOSITION_NAMES.length]
            [FIRST_PUBLIC_DISPATCH_UPPER_NANOS.length];
    private final List<GarbageCollectorMXBean> garbageCollectors = ManagementFactory.getGarbageCollectorMXBeans();
    private volatile Path playersPath;
    private volatile Path controlPath;
    private volatile Path samplePath;
    private volatile Path summaryPath;
    private volatile Path statusPath;
    private volatile long tickStartNanos;
    private volatile long startIntervalNanos;
    private volatile long lastTickStartNanos;
    private long tickSequence;
    private long warmupSampledRecords;
    private long sampledRecords;
    private long outputBytes;
    private long phaseOriginNanos;
    private long nextNormalSubmissionNanos;
    private long nextOverloadSubmissionNanos;
    private long nextRepeatSubmissionNanos;
    private String phaseKey = "";
    private int onlineHighWater;
    private int requestCursor;
    private long attemptSequence;
    private UUID repeatPlayerId;
    private int sampleQueueHighWater;
    private int evidenceTaskHighWater;
    private long[] lastIntegrationCounters;
    private long[][][] lastFirstPublicDispatchHistograms;
    private boolean firstPublicDispatchMetricsAvailable = true;
    private boolean sampleFileCreated;
    private boolean statusFileCreated;
    private boolean repeatOtherSubmitted;

    @Override
    public void onInitialize() {
        playersPath = absoluteProperty("worldspeaker.load.players");
        controlPath = absoluteProperty("worldspeaker.load.control");
        samplePath = absoluteProperty("worldspeaker.load.samples");
        summaryPath = absoluteProperty("worldspeaker.load.summary");
        statusPath = absoluteProperty("worldspeaker.load.status");
        if (playersPath == null || controlPath == null || samplePath == null || summaryPath == null || statusPath == null) {
            captureIncomplete.set(true);
        } else if (!io.tryExecute(this::loadAuthorizedIds)) {
            captureIncomplete.set(true);
        }
        controlReader.setRemoveOnCancelPolicy(true);
        controlReader.scheduleAtFixedRate(this::readControl, 100, 100, TimeUnit.MILLISECONDS);

        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            UUID playerId = handler.player.getUuid();
            if (!authorizedIds.get().contains(playerId)) return;
            onlineAuthorized.put(playerId, handler.player);
            countConnectionEvent(true);
            if (disconnectedAuthorized.remove(playerId)) {
                int index = PHASES.indexOf(control.get().phase);
                if (index >= 0) phaseAuthorizedRejoins[index] = saturatedIncrement(phaseAuthorizedRejoins[index]);
            }
        });
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            UUID playerId = handler.player.getUuid();
            if (!authorizedIds.get().contains(playerId)) return;
            onlineAuthorized.remove(playerId, handler.player);
            disconnectedAuthorized.add(playerId);
            countConnectionEvent(false);
        });
        ServerTickEvents.START_SERVER_TICK.register(server -> {
            ThreadBoundary.assertServerThread();
            long now = System.nanoTime();
            tickStartNanos = now;
            startIntervalNanos = lastTickStartNanos == 0 ? 0 : Math.max(0, now - lastTickStartNanos);
            lastTickStartNanos = now;
        });
        ServerTickEvents.END_SERVER_TICK.register(this::endTick);
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> stopCapture());
    }

    private void loadAuthorizedIds() {
        ThreadBoundary.assertNotServerThread();
        try {
            if (playersPath == null || Files.isSymbolicLink(playersPath) ||
                    !Files.isRegularFile(playersPath, java.nio.file.LinkOption.NOFOLLOW_LINKS) || Files.size(playersPath) > 4096) {
                throw new IOException("player file type or size invalid");
            }
            Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(playersPath);
            if (permissions.stream().anyMatch(permission -> permission.name().startsWith("GROUP_") || permission.name().startsWith("OTHERS_"))) {
                throw new IOException("player file is not owner-only");
            }
            List<String> lines = Files.readAllLines(playersPath, StandardCharsets.UTF_8);
            List<UUID> parsed = new ArrayList<>(MAX_AUTHORIZED_PLAYERS);
            for (String line : lines) {
                String value = line.trim();
                if (value.isEmpty() || value.startsWith("#")) continue;
                String[] fields = value.split("\\s+", -1);
                if (parsed.size() == MAX_AUTHORIZED_PLAYERS || fields.length != 2 ||
                        !fields[1].matches("[A-Za-z0-9_]{3,16}")) throw new IOException("player list exceeds finite bound or has invalid shape");
                UUID id = UUID.fromString(fields[0]);
                if (parsed.contains(id)) throw new IOException("duplicate player identity");
                parsed.add(id);
            }
            if (parsed.isEmpty()) throw new IOException("player list is empty");
            authorizedIds.set(List.copyOf(parsed));
            authorizedIdsLoaded.set(true);
        } catch (IOException | RuntimeException failure) {
            captureIncomplete.set(true);
        }
    }

    private void readControl() {
        if (controlPath == null) return;
        try {
            if (!Files.isRegularFile(controlPath) || Files.size(controlPath) > 1024) throw new IOException("control file invalid");
            JsonObject object = JsonParser.parseString(Files.readString(controlPath, StandardCharsets.UTF_8)).getAsJsonObject();
            if (!Set.of("schemaVersion", "runId", "phase", "warmup").equals(object.keySet()) ||
                    object.get("schemaVersion").getAsInt() != 1 || !object.get("runId").getAsString().matches("[a-f0-9-]{1,64}") ||
                    !PHASES.contains(object.get("phase").getAsString()) || !object.get("warmup").isJsonPrimitive() ||
                    !object.get("warmup").getAsJsonPrimitive().isBoolean()) throw new IOException("control shape rejected");
            control.set(new PhaseControl(object.get("runId").getAsString(), object.get("phase").getAsString(),
                    object.get("warmup").getAsBoolean()));
        } catch (IOException | RuntimeException failure) {
            captureIncomplete.set(true);
        }
    }

    private void endTick(MinecraftServer server) {
        ThreadBoundary.assertServerThread();
        if (stopping.get()) return;
        long sequence = ++tickSequence;
        PhaseControl phase = control.get();
        String nextKey = phase.runId + ":" + phase.phase + ":" + phase.warmup;
        if (!nextKey.equals(phaseKey)) {
            phaseKey = nextKey;
            phaseOriginNanos = System.nanoTime();
            nextNormalSubmissionNanos = phaseOriginNanos;
            nextOverloadSubmissionNanos = phaseOriginNanos;
            nextRepeatSubmissionNanos = phaseOriginNanos;
            repeatPlayerId = null;
            repeatOtherSubmitted = false;
        }
        int phaseIndex = PHASES.indexOf(phase.phase);
        int onlineCount = onlineAuthorized.size();
        onlineHighWater = Math.max(onlineHighWater, onlineCount);
        phaseOnlineHighWater[phaseIndex] = Math.max(phaseOnlineHighWater[phaseIndex], onlineCount);
        if (!phase.warmup) {
            runWorkload(phase, phaseIndex);
            observeOverloadCapacity(phase, phaseIndex, System.nanoTime(), onlineCount);
        }

        long endedAt = System.nanoTime();
        long duration = Math.max(0, endedAt - tickStartNanos);
        if (phase.warmup) {
            warmupTicks = saturatedIncrement(warmupTicks);
            warmupElapsedNanos = Math.max(warmupElapsedNanos, Math.max(0, endedAt - phaseOriginNanos));
            warmupMaxTickNanos = Math.max(warmupMaxTickNanos, duration);
            if (duration > 50_000_000L) warmupOverFiftyMillis = saturatedIncrement(warmupOverFiftyMillis);
        } else {
            phaseTicks[phaseIndex] = saturatedIncrement(phaseTicks[phaseIndex]);
            phaseElapsedNanos[phaseIndex] = Math.max(phaseElapsedNanos[phaseIndex], Math.max(0, endedAt - phaseOriginNanos));
            phaseMaxTickNanos[phaseIndex] = Math.max(phaseMaxTickNanos[phaseIndex], duration);
            if (duration > 50_000_000L) phaseOverFiftyMillis[phaseIndex] = saturatedIncrement(phaseOverFiftyMillis[phaseIndex]);
        }
        boolean integrationReady = WorldspeakerServer.integrationReady();
        if (phase.warmup) {
            lastIntegrationCounters = WorldspeakerServer.compatibilityCountersSnapshot();
            lastFirstPublicDispatchHistograms = WorldspeakerServer.compatibilityFirstPublicDispatchSnapshot();
            if (!validFirstPublicDispatchSnapshot(lastFirstPublicDispatchHistograms)) {
                firstPublicDispatchMetricsAvailable = false;
                captureIncomplete.set(true);
                lastFirstPublicDispatchHistograms = null;
            }
        } else {
            if (integrationReady) phaseReadyTicks[phaseIndex] = saturatedIncrement(phaseReadyTicks[phaseIndex]);
            accumulateIntegrationCounters(phaseIndex);
        }
        long[] histogram = phase.warmup ? warmupHistogram : histograms[phaseIndex];
        for (int bucket = 0; bucket < HISTOGRAM_UPPER_NANOS.length; bucket++) {
            if (duration <= HISTOGRAM_UPPER_NANOS[bucket]) {
                histogram[bucket] = saturatedIncrement(histogram[bucket]);
                break;
            }
        }
        if (sequence % SAMPLE_STRIDE == 0) {
            Runtime runtime = Runtime.getRuntime();
            long usedHeap = Math.max(0, runtime.totalMemory() - runtime.freeMemory());
            long gcCount = 0;
            for (GarbageCollectorMXBean collector : garbageCollectors) {
                long count = collector.getCollectionCount();
                if (count > 0) gcCount = saturatedAdd(gcCount, count);
            }
            sampleQueueHighWater = Math.max(sampleQueueHighWater, samples.size());
            evidenceTaskHighWater = Math.max(evidenceTaskHighWater, io.queuedTasks());
            String row = "sample," + phase.runId + ',' + phase.phase + ',' + phase.warmup + ',' +
                    authorizedIdsLoaded.get() + ',' + integrationReady + ',' + sequence + ',' +
                    startIntervalNanos + ',' + duration + ',' + phaseAttempts[phaseIndex] + ',' + phaseCommands[phaseIndex] + ',' +
                    phaseRejected[phaseIndex] + ',' + onlineCount + ',' + phaseOnlineHighWater[phaseIndex] + ',' +
                    samples.size() + ',' + usedHeap + ',' + gcCount + ',' + SAMPLE_STRIDE + '\n';
            pendingStatus.set("{\"schemaVersion\":1,\"runId\":\"" + phase.runId + "\",\"phase\":\"" +
                    phase.phase + "\",\"warmup\":" + phase.warmup + ",\"tick\":" + sequence +
                    ",\"authorizedListLoaded\":" + authorizedIdsLoaded.get() + ",\"integrationReady\":" +
                    integrationReady + ",\"onlineAuthorized\":" + onlineCount + ",\"authorizedCount\":" +
                    authorizedIds.get().size() + "}\n");
            if (warmupSampledRecords + sampledRecords >= MAX_SAMPLE_RECORDS || !samples.offer(row)) captureIncomplete.set(true);
            else {
                if (phase.warmup) warmupSampledRecords++;
                else {
                    sampledRecords++;
                    phaseSampleRecords[phaseIndex] = saturatedIncrement(phaseSampleRecords[phaseIndex]);
                }
            }
            queueWriter();
        }
    }

    private void runWorkload(PhaseControl phase, int phaseIndex) {
        long now = System.nanoTime();
        if ("normal".equals(phase.phase) || "unavailable".equals(phase.phase) || "recovery".equals(phase.phase)) {
            if (now >= nextNormalSubmissionNanos) {
                nextNormalSubmissionNanos = now + TimeUnit.SECONDS.toNanos(2);
                submitOne(phase, phaseIndex, false);
            }
        } else if ("overload".equals(phase.phase) || "stop-backlog".equals(phase.phase)) {
            if (now >= nextOverloadSubmissionNanos) {
                nextOverloadSubmissionNanos = now + TimeUnit.MILLISECONDS.toNanos(50);
                submitOne(phase, phaseIndex, false);
            }
        } else if ("repeat".equals(phase.phase) && phaseRepeatAttempts[phaseIndex] < REPEAT_ATTEMPT_LIMIT &&
                now - phaseOriginNanos < TimeUnit.SECONDS.toNanos(6) &&
                now >= nextRepeatSubmissionNanos) {
            nextRepeatSubmissionNanos = now + TimeUnit.MILLISECONDS.toNanos(850);
            submitOne(phase, phaseIndex, true);
            if (!repeatOtherSubmitted) submitDistinctOther(phase, phaseIndex);
        }
    }

    private void submitOne(PhaseControl phase, int phaseIndex, boolean repeated) {
        phaseAttempts[phaseIndex] = saturatedIncrement(phaseAttempts[phaseIndex]);
        String text;
        if (repeated) {
            phaseRepeatAttempts[phaseIndex] = saturatedIncrement(phaseRepeatAttempts[phaseIndex]);
            if (phaseRepeatAttempts[phaseIndex] == 1) {
                phaseDistinctInvocations[phaseIndex] = saturatedIncrement(phaseDistinctInvocations[phaseIndex]);
            }
            text = "COMPAT-03 synthetic repeat regression same-player identical text";
        } else {
            String attemptId = nextAttemptId();
            if (attemptId == null) return;
            phaseDistinctInvocations[phaseIndex] = saturatedIncrement(phaseDistinctInvocations[phaseIndex]);
            text = uniqueInvocationText(phase.runId, attemptId);
        }
        List<UUID> ids = authorizedIds.get();
        if (ids.isEmpty()) {
            phaseNoPlayer[phaseIndex] = saturatedIncrement(phaseNoPlayer[phaseIndex]);
            return;
        }
        ServerPlayerEntity player = repeated && repeatPlayerId != null
                ? onlineAuthorized.get(repeatPlayerId) : nextOnlinePlayer(ids);
        if (player == null) {
            phaseNoPlayer[phaseIndex] = saturatedIncrement(phaseNoPlayer[phaseIndex]);
            return;
        }
        if (repeated && repeatPlayerId == null) repeatPlayerId = player.getUuid();
        try {
            if (WorldspeakerServer.submit(player, text, System.nanoTime())) {
                phaseCommands[phaseIndex] = saturatedIncrement(phaseCommands[phaseIndex]);
                if (repeated) phaseRepeatAccepted[phaseIndex] = saturatedIncrement(phaseRepeatAccepted[phaseIndex]);
                recordAcceptedPlayer(phaseIndex, player.getUuid());
            } else {
                phaseRejected[phaseIndex] = saturatedIncrement(phaseRejected[phaseIndex]);
                if (repeated) phaseRepeatRejected[phaseIndex] = saturatedIncrement(phaseRepeatRejected[phaseIndex]);
            }
        } catch (RuntimeException failure) {
            captureIncomplete.set(true);
        }
    }

    private void submitDistinctOther(PhaseControl phase, int phaseIndex) {
        phaseAttempts[phaseIndex] = saturatedIncrement(phaseAttempts[phaseIndex]);
        phaseDistinctOtherAttempts[phaseIndex] = saturatedIncrement(phaseDistinctOtherAttempts[phaseIndex]);
        String attemptId = nextAttemptId();
        if (attemptId == null) {
            repeatOtherSubmitted = true;
            return;
        }
        phaseDistinctInvocations[phaseIndex] = saturatedIncrement(phaseDistinctInvocations[phaseIndex]);
        String text = uniqueInvocationText(phase.runId, attemptId);
        if (repeatPlayerId == null) {
            phaseNoPlayer[phaseIndex] = saturatedIncrement(phaseNoPlayer[phaseIndex]);
            repeatOtherSubmitted = true;
            return;
        }
        for (UUID id : authorizedIds.get()) {
            if (id.equals(repeatPlayerId)) continue;
            ServerPlayerEntity player = onlineAuthorized.get(id);
            if (player == null) continue;
            try {
                if (WorldspeakerServer.submit(player, text, System.nanoTime())) {
                    phaseCommands[phaseIndex] = saturatedIncrement(phaseCommands[phaseIndex]);
                    phaseDistinctOtherRequests[phaseIndex] = saturatedIncrement(phaseDistinctOtherRequests[phaseIndex]);
                    repeatOtherSubmitted = true;
                    recordAcceptedPlayer(phaseIndex, player.getUuid());
                } else {
                    phaseRejected[phaseIndex] = saturatedIncrement(phaseRejected[phaseIndex]);
                    repeatOtherSubmitted = true;
                }
            } catch (RuntimeException failure) {
                captureIncomplete.set(true);
            }
            return;
        }
        phaseNoPlayer[phaseIndex] = saturatedIncrement(phaseNoPlayer[phaseIndex]);
        repeatOtherSubmitted = true;
    }

    private String nextAttemptId() {
        if (attemptSequence == Long.MAX_VALUE) {
            captureIncomplete.set(true);
            return null;
        }
        return Long.toString(++attemptSequence);
    }

    private static String uniqueInvocationText(String runId, String attemptId) {
        return "COMPAT-03 synthetic run=" + runId + " attempt=" + attemptId;
    }

    private void recordAcceptedPlayer(int phaseIndex, UUID playerId) {
        int playerIndex = authorizedIds.get().indexOf(playerId);
        if (playerIndex < 0 || playerIndex >= MAX_AUTHORIZED_PLAYERS) {
            captureIncomplete.set(true);
            return;
        }
        long accepted = phaseAcceptedByPlayer[phaseIndex][playerIndex] =
                saturatedIncrement(phaseAcceptedByPlayer[phaseIndex][playerIndex]);
        phasePerPlayerAdmissionHighWater[phaseIndex] =
                Math.max(phasePerPlayerAdmissionHighWater[phaseIndex], accepted);
        int playersAtTwoAdmissions = 0;
        for (long playerAccepted : phaseAcceptedByPlayer[phaseIndex]) {
            if (playerAccepted >= PER_PLAYER_PENDING_CAPACITY) playersAtTwoAdmissions++;
        }
        phasePlayersAtTwoAdmissionsHighWater[phaseIndex] =
                Math.max(phasePlayersAtTwoAdmissionsHighWater[phaseIndex], playersAtTwoAdmissions);
    }

    private void observeOverloadCapacity(PhaseControl phase, int phaseIndex, long now, int onlineCount) {
        if (!"overload".equals(phase.phase) || now - phaseOriginNanos >= OVERLOAD_CAPACITY_OBSERVATION_NANOS) return;
        long[] counters = WorldspeakerServer.compatibilityCountersSnapshot();
        if (counters.length != METRIC_NAMES.size()) {
            captureIncomplete.set(true);
            return;
        }
        long admitted = counters[REQUESTS_ADMITTED_INDEX];
        long completed = saturatedAdd(counters[PENDING_EXPIRED_INDEX], counters[ANSWERS_DELIVERED_INDEX]);
        completed = saturatedAdd(completed, counters[UPSTREAM_REJECTIONS_INDEX]);
        completed = saturatedAdd(completed, counters[UNAVAILABLE_DISPOSITIONS_INDEX]);
        long outstanding = admitted >= completed ? admitted - completed : 0;
        phaseOutstandingAdmissionHighWater[phaseIndex] =
                Math.max(phaseOutstandingAdmissionHighWater[phaseIndex], outstanding);
        phaseCapacityOnlineHighWater[phaseIndex] = Math.max(phaseCapacityOnlineHighWater[phaseIndex], onlineCount);
        long maxPlayerAdmissions = 0;
        int playersAtTwoAdmissions = 0;
        for (long playerAccepted : phaseAcceptedByPlayer[phaseIndex]) {
            maxPlayerAdmissions = Math.max(maxPlayerAdmissions, playerAccepted);
            if (playerAccepted >= PER_PLAYER_PENDING_CAPACITY) playersAtTwoAdmissions++;
        }
        phaseCapacityPerPlayerAdmissionHighWater[phaseIndex] =
                Math.max(phaseCapacityPerPlayerAdmissionHighWater[phaseIndex], maxPlayerAdmissions);
        phaseCapacityPlayersAtTwoAdmissionsHighWater[phaseIndex] =
                Math.max(phaseCapacityPlayersAtTwoAdmissionsHighWater[phaseIndex], playersAtTwoAdmissions);
        phaseCapacityObservationTicks[phaseIndex] = saturatedIncrement(phaseCapacityObservationTicks[phaseIndex]);
    }

    private ServerPlayerEntity nextOnlinePlayer(List<UUID> ids) {
        for (int index = 0; index < ids.size(); index++) {
            UUID next = ids.get(Math.floorMod(requestCursor++, ids.size()));
            ServerPlayerEntity candidate = onlineAuthorized.get(next);
            if (candidate != null) return candidate;
        }
        return null;
    }

    private void countConnectionEvent(boolean joined) {
        int index = PHASES.indexOf(control.get().phase);
        if (index < 0) return;
        if (joined) phaseConnectionJoins[index] = saturatedIncrement(phaseConnectionJoins[index]);
        else phaseConnectionLeaves[index] = saturatedIncrement(phaseConnectionLeaves[index]);
    }

    private void accumulateIntegrationCounters(int phaseIndex) {
        long[] current = WorldspeakerServer.compatibilityCountersSnapshot();
        if (current.length != phaseIntegrationCounters[phaseIndex].length) {
            captureIncomplete.set(true);
            return;
        }
        if (lastIntegrationCounters == null) {
            lastIntegrationCounters = current;
            return;
        }
        for (int index = 0; index < current.length; index++) {
            long delta = current[index] >= lastIntegrationCounters[index] ? current[index] - lastIntegrationCounters[index] : 0;
            phaseIntegrationCounters[phaseIndex][index] = saturatedAdd(phaseIntegrationCounters[phaseIndex][index], delta);
        }
        lastIntegrationCounters = current;
        long[][][] currentFirstPublic = WorldspeakerServer.compatibilityFirstPublicDispatchSnapshot();
        if (!validFirstPublicDispatchSnapshot(currentFirstPublic)) {
            firstPublicDispatchMetricsAvailable = false;
            captureIncomplete.set(true);
            lastFirstPublicDispatchHistograms = null;
            return;
        }
        if (lastFirstPublicDispatchHistograms == null) {
            lastFirstPublicDispatchHistograms = currentFirstPublic;
            return;
        }
        for (int source = 0; source < currentFirstPublic.length; source++) {
            for (int disposition = 0; disposition < currentFirstPublic[source].length; disposition++) {
                for (int bucket = 0; bucket < currentFirstPublic[source][disposition].length; bucket++) {
                    long currentBucket = currentFirstPublic[source][disposition][bucket];
                    long previous = lastFirstPublicDispatchHistograms[source][disposition][bucket];
                    long delta = currentBucket >= previous ? currentBucket - previous : 0;
                    phaseFirstPublicDispatchHistograms[phaseIndex][source][disposition][bucket] = saturatedAdd(
                            phaseFirstPublicDispatchHistograms[phaseIndex][source][disposition][bucket], delta);
                }
            }
        }
        lastFirstPublicDispatchHistograms = currentFirstPublic;
    }

    private void queueWriter() {
        if (stopping.get()) return;
        if (!writerQueued.compareAndSet(false, true)) return;
        if (!io.tryExecute(this::writeQueuedEvidence)) {
            writerQueued.set(false);
            captureIncomplete.set(true);
        }
    }

    private void writeQueuedEvidence() {
        ThreadBoundary.assertNotServerThread();
        try {
            if (!samples.isEmpty()) {
                List<String> batch = new ArrayList<>(MAX_SAMPLE_RECORDS);
                samples.drainTo(batch, MAX_SAMPLE_RECORDS);
                StringBuilder text = new StringBuilder(Math.min(4 * 1024 * 1024, batch.size() * 128));
                for (String row : batch) text.append(row);
                byte[] bytes = text.toString().getBytes(StandardCharsets.UTF_8);
                if (outputBytes + bytes.length > MAX_OUTPUT_BYTES) throw new IOException("sample output limit exceeded");
                if (!sampleFileCreated) {
                    createPrivate(samplePath, bytes);
                    sampleFileCreated = true;
                } else {
                    Files.write(samplePath, bytes, StandardOpenOption.WRITE, StandardOpenOption.APPEND);
                }
                outputBytes += bytes.length;
            }
            writePendingStatus();
        } catch (IOException | SecurityException failure) {
            captureIncomplete.set(true);
        } finally {
            writerQueued.set(false);
            if (!stopping.get() && (!samples.isEmpty() || pendingStatus.get() != null)) queueWriter();
        }
    }

    private void writePendingStatus() throws IOException {
        String status = pendingStatus.getAndSet(null);
        if (status == null) return;
        byte[] bytes = status.getBytes(StandardCharsets.UTF_8);
        if (outputBytes + bytes.length > MAX_OUTPUT_BYTES) throw new IOException("status output limit exceeded");
        if (!statusFileCreated) {
            createPrivate(statusPath, bytes);
            statusFileCreated = true;
        } else {
            Path temporary = statusPath.resolveSibling(statusPath.getFileName() + ".tmp");
            FileAttribute<Set<PosixFilePermission>> permissions = PosixFilePermissions.asFileAttribute(
                    EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
            Files.createFile(temporary, permissions);
            Files.write(temporary, bytes, StandardOpenOption.WRITE, StandardOpenOption.APPEND);
            try {
                Files.move(temporary, statusPath, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, statusPath, StandardCopyOption.REPLACE_EXISTING);
            }
        }
        outputBytes += bytes.length;
    }

    private static void createPrivate(Path path, byte[] bytes) throws IOException {
        if (path == null) throw new IOException("private output path is unavailable");
        FileAttribute<Set<PosixFilePermission>> permissions = PosixFilePermissions.asFileAttribute(
                EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
        Files.createFile(path, permissions);
        Files.write(path, bytes, StandardOpenOption.WRITE, StandardOpenOption.APPEND);
    }

    private void stopCapture() {
        if (!stopping.compareAndSet(false, true)) return;
        controlReader.shutdownNow();
        if (!io.tryExecuteControl(this::writeFinalEvidence)) captureIncomplete.set(true);
        io.shutdown();
    }

    private void writeFinalEvidence() {
        ThreadBoundary.assertNotServerThread();
        try {
            if (!samples.isEmpty()) {
                List<String> batch = new ArrayList<>(MAX_SAMPLE_RECORDS);
                samples.drainTo(batch, MAX_SAMPLE_RECORDS);
                StringBuilder text = new StringBuilder(Math.min(4 * 1024 * 1024, batch.size() * 128));
                for (String row : batch) text.append(row);
                byte[] bytes = text.toString().getBytes(StandardCharsets.UTF_8);
                if (outputBytes + bytes.length > MAX_OUTPUT_BYTES) throw new IOException("sample output limit exceeded");
                if (!sampleFileCreated) {
                    createPrivate(samplePath, bytes);
                    sampleFileCreated = true;
                } else {
                    Files.write(samplePath, bytes, StandardOpenOption.WRITE, StandardOpenOption.APPEND);
                }
                outputBytes += bytes.length;
            }
            writePendingStatus();
        } catch (IOException | SecurityException failure) {
            captureIncomplete.set(true);
        }
        try {
            byte[] summary = renderSummary().getBytes(StandardCharsets.UTF_8);
            if (outputBytes + summary.length > MAX_OUTPUT_BYTES) throw new IOException("summary output limit exceeded");
            createPrivate(summaryPath, summary);
            outputBytes += summary.length;
        } catch (IOException | SecurityException failure) {
            captureIncomplete.set(true);
        }
    }

    private String renderSummary() {
        StringBuilder output = new StringBuilder(4096);
        output.append("{\"schemaVersion\":3,\"sampleStride\":").append(SAMPLE_STRIDE)
                .append(",\"tickHistogramUpperNanos\":[");
        for (int bucket = 0; bucket < HISTOGRAM_UPPER_NANOS.length; bucket++) {
            if (bucket != 0) output.append(',');
            output.append(HISTOGRAM_UPPER_NANOS[bucket]);
        }
        output.append("],\"integrationCounterNames\":[");
        for (int counter = 0; counter < METRIC_NAMES.size(); counter++) {
            if (counter != 0) output.append(',');
            output.append('"').append(METRIC_NAMES.get(counter)).append('"');
        }
        output.append(']')
                .append(",\"workloadContract\":{\"overloadAttemptedPerSecond\":20,\"overloadDistinctPerSecond\":20")
                .append(",\"overloadDurationMs\":300000,\"overloadExpectedAttempts\":6000,\"overloadExpectedDistinctInvocations\":6000")
                .append(",\"repeatAttempts\":").append(REPEAT_ATTEMPT_LIMIT)
                .append(",\"activeCapacity\":").append(ACTIVE_REQUEST_CAPACITY)
                .append(",\"globalPendingCapacity\":").append(GLOBAL_PENDING_CAPACITY)
                .append(",\"perPlayerPendingCapacity\":").append(PER_PLAYER_PENDING_CAPACITY).append('}')
                .append(",\"firstPublicDispatchMetricsAvailable\":").append(firstPublicDispatchMetricsAvailable)
                .append(",\"maxFirstPublicDispatchNanos\":").append(WorldspeakerServer.compatibilityFirstPublicDispatchMaxNanos())
                .append(",\"firstPublicDispatchSourceNames\":[");
        for (int source = 0; source < FIRST_PUBLIC_DISPATCH_SOURCE_NAMES.length; source++) {
            if (source != 0) output.append(',');
            output.append('"').append(FIRST_PUBLIC_DISPATCH_SOURCE_NAMES[source]).append('"');
        }
        output.append("],\"firstPublicDispatchDispositionNames\":[");
        for (int disposition = 0; disposition < FIRST_PUBLIC_DISPATCH_DISPOSITION_NAMES.length; disposition++) {
            if (disposition != 0) output.append(',');
            output.append('"').append(FIRST_PUBLIC_DISPATCH_DISPOSITION_NAMES[disposition]).append('"');
        }
        output.append("],\"firstPublicDispatchUpperNanos\":[");
        for (int bucket = 0; bucket < FIRST_PUBLIC_DISPATCH_UPPER_NANOS.length; bucket++) {
            if (bucket != 0) output.append(',');
            output.append(FIRST_PUBLIC_DISPATCH_UPPER_NANOS[bucket]);
        }
        output.append(']')
                .append(",\"warmupTicks\":").append(warmupTicks)
                .append(",\"warmupElapsedNanos\":").append(warmupElapsedNanos)
                .append(",\"warmupMaxTickNanos\":").append(warmupMaxTickNanos)
                .append(",\"warmupOverFiftyMillis\":").append(warmupOverFiftyMillis)
                .append(",\"warmupTickHistogram\":[");
        for (int bucket = 0; bucket < warmupHistogram.length; bucket++) {
            if (bucket != 0) output.append(',');
            output.append(warmupHistogram[bucket]);
        }
        output.append(']')
                .append(",\"sampleRecords\":").append(sampledRecords)
                .append(",\"warmupSampleRecords\":").append(warmupSampledRecords)
                .append(",\"sampleQueueHighWater\":").append(sampleQueueHighWater)
                .append(",\"evidenceTaskHighWater\":").append(evidenceTaskHighWater)
                .append(",\"authorizedListLoaded\":").append(authorizedIdsLoaded.get())
                .append(",\"authorizedPlayerCount\":").append(authorizedIds.get().size())
                .append(",\"onlineAuthorizedHighWater\":").append(onlineHighWater)
                .append(",\"captureIncomplete\":").append(captureIncomplete.get())
                .append(",\"phases\":[");
        for (int phase = 0; phase < PHASES.size(); phase++) {
            if (phase != 0) output.append(',');
            output.append("{\"name\":\"").append(PHASES.get(phase)).append("\",\"ticks\":")
                    .append(phaseTicks[phase]).append(",\"commands\":").append(phaseCommands[phase])
                    .append(",\"maxTickNanos\":").append(phaseMaxTickNanos[phase])
                    .append(",\"overFiftyMillis\":").append(phaseOverFiftyMillis[phase])
                    .append(",\"elapsedNanos\":").append(phaseElapsedNanos[phase])
                    .append(",\"sampleRecords\":").append(phaseSampleRecords[phase])
                    .append(",\"attempts\":").append(phaseAttempts[phase])
                    .append(",\"distinctInvocations\":").append(phaseDistinctInvocations[phase])
                    .append(",\"repeatAttempts\":").append(phaseRepeatAttempts[phase])
                    .append(",\"repeatAccepted\":").append(phaseRepeatAccepted[phase])
                    .append(",\"repeatRejected\":").append(phaseRepeatRejected[phase])
                    .append(",\"rejected\":").append(phaseRejected[phase])
                    .append(",\"distinctOtherAttempts\":").append(phaseDistinctOtherAttempts[phase])
                    .append(",\"distinctOtherRequests\":").append(phaseDistinctOtherRequests[phase])
                    .append(",\"outstandingAdmissionHighWater\":").append(phaseOutstandingAdmissionHighWater[phase])
                    .append(",\"perPlayerAdmissionHighWater\":").append(phasePerPlayerAdmissionHighWater[phase])
                    .append(",\"playersAtTwoAdmissionsHighWater\":").append(phasePlayersAtTwoAdmissionsHighWater[phase])
                    .append(",\"capacityOnlineHighWater\":").append(phaseCapacityOnlineHighWater[phase])
                    .append(",\"capacityPerPlayerAdmissionHighWater\":").append(phaseCapacityPerPlayerAdmissionHighWater[phase])
                    .append(",\"capacityPlayersAtTwoAdmissionsHighWater\":").append(phaseCapacityPlayersAtTwoAdmissionsHighWater[phase])
                    .append(",\"capacityObservationTicks\":").append(phaseCapacityObservationTicks[phase])
                    .append(",\"noPlayerTicks\":").append(phaseNoPlayer[phase])
                    .append(",\"integrationReadyTicks\":").append(phaseReadyTicks[phase])
                    .append(",\"authorizedOnlineHighWater\":").append(phaseOnlineHighWater[phase])
                    .append(",\"authorizedJoins\":").append(phaseConnectionJoins[phase])
                    .append(",\"authorizedLeaves\":").append(phaseConnectionLeaves[phase])
                    .append(",\"authorizedRejoins\":").append(phaseAuthorizedRejoins[phase])
                    .append(",\"integrationCounters\":[");
            for (int counter = 0; counter < phaseIntegrationCounters[phase].length; counter++) {
                if (counter != 0) output.append(',');
                output.append(phaseIntegrationCounters[phase][counter]);
            }
            output.append("],\"firstPublicDispatchHistograms\":[");
            for (int source = 0; source < FIRST_PUBLIC_DISPATCH_SOURCE_NAMES.length; source++) {
                if (source != 0) output.append(',');
                output.append('[');
                for (int disposition = 0; disposition < FIRST_PUBLIC_DISPATCH_DISPOSITION_NAMES.length; disposition++) {
                    if (disposition != 0) output.append(',');
                    output.append('[');
                    for (int bucket = 0; bucket < FIRST_PUBLIC_DISPATCH_UPPER_NANOS.length; bucket++) {
                        if (bucket != 0) output.append(',');
                        output.append(phaseFirstPublicDispatchHistograms[phase][source][disposition][bucket]);
                    }
                    output.append(']');
                }
                output.append(']');
            }
            output.append("],\"tickHistogram\":[");
            for (int bucket = 0; bucket < HISTOGRAM_UPPER_NANOS.length; bucket++) {
                if (bucket != 0) output.append(',');
                output.append(histograms[phase][bucket]);
            }
            output.append("]}");
        }
        output.append("]}\n");
        return output.toString();
    }

    private static Path absoluteProperty(String key) {
        String value = System.getProperty(key);
        if (value == null || value.isBlank()) return null;
        Path path = Path.of(value);
        return path.isAbsolute() ? path.normalize() : null;
    }

    private static long saturatedIncrement(long value) {
        return value == Long.MAX_VALUE ? value : value + 1;
    }

    private static boolean validFirstPublicDispatchSnapshot(long[][][] snapshot) {
        if (snapshot == null || snapshot.length != FIRST_PUBLIC_DISPATCH_SOURCE_NAMES.length) return false;
        for (long[][] source : snapshot) {
            if (source == null || source.length != FIRST_PUBLIC_DISPATCH_DISPOSITION_NAMES.length) return false;
            for (long[] disposition : source) {
                if (disposition == null || disposition.length != FIRST_PUBLIC_DISPATCH_UPPER_NANOS.length) return false;
            }
        }
        return true;
    }

    private static long saturatedAdd(long left, long right) {
        if (right <= 0) return left;
        return left > Long.MAX_VALUE - right ? Long.MAX_VALUE : left + right;
    }

    private record PhaseControl(String runId, String phase, boolean warmup) {
        private static final PhaseControl IDLE = new PhaseControl("unconfigured", "idle", true);
    }
}
