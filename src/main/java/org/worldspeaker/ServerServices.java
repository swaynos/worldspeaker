package org.worldspeaker;

import org.worldspeaker.bootstrap.SmokeConversation;
import org.worldspeaker.bootstrap.AbilityLimitLedger;
import org.worldspeaker.bootstrap.FirstReplyScheduler;
import org.worldspeaker.bootstrap.InvocationRateLimiter;
import org.worldspeaker.compat.CompatibilityGate;
import org.worldspeaker.compat.CompanionConfig;
import org.worldspeaker.integration.BoundCompanionClient;
import org.worldspeaker.runtime.BoundedIoLane;
import org.worldspeaker.runtime.GameLoopMailbox;
import org.worldspeaker.runtime.ThreadBoundary;
import org.worldspeaker.runtime.TickMetrics;

import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/** Resources and runtime references owned by exactly one running server lifecycle. */
public final class ServerServices {
    private static final AtomicLong NEXT_GENERATION = new AtomicLong();

    private final long generation;
    private final ThreadBoundary.ServerEpoch threadBoundaryToken = ThreadBoundary.newServerEpochToken();
    private final Object serverIdentity;
    private final Path configPath;
    private final GameLoopMailbox mailbox = new GameLoopMailbox();
    private final TickMetrics metrics = new TickMetrics();
    private final FirstReplyScheduler firstReplyScheduler =
            new FirstReplyScheduler(System::nanoTime, System::currentTimeMillis);
    private final InvocationRateLimiter interactionRateLimiter;
    private final AbilityLimitLedger abilityLimitLedger;
    private final BoundedIoLane configIo = new BoundedIoLane("worldspeaker-config-io", 4);
    private final BoundedIoLane diagnosticIo = new BoundedIoLane("worldspeaker-private-log", 32);
    private final ShutdownDiagnosticSink shutdownDiagnostics;
    private final AtomicBoolean active = new AtomicBoolean(true);

    private volatile CompanionConfig config;
    private volatile CompatibilityGate gate;
    private volatile BoundCompanionClient companion;
    private volatile SmokeConversation conversation;

    public ServerServices(Path configPath, Object serverIdentity) {
        this(configPath, serverIdentity, new InvocationRateLimiter(), ignored -> {},
                AbilityLimitLedger.processOwnedLedger());
    }

    ServerServices(Path configPath, Object serverIdentity, Consumer<String> shutdownDiagnosticWriter) {
        this(configPath, serverIdentity, new InvocationRateLimiter(), shutdownDiagnosticWriter,
                AbilityLimitLedger.processOwnedLedger());
    }

    ServerServices(Path configPath, Object serverIdentity, InvocationRateLimiter interactionRateLimiter,
                   Consumer<String> shutdownDiagnosticWriter) {
        this(configPath, serverIdentity, interactionRateLimiter, shutdownDiagnosticWriter,
                AbilityLimitLedger.processOwnedLedger());
    }

    ServerServices(Path configPath, Object serverIdentity, InvocationRateLimiter interactionRateLimiter,
                   Consumer<String> shutdownDiagnosticWriter, AbilityLimitLedger abilityLimitLedger) {
        this.configPath = Objects.requireNonNull(configPath, "configPath");
        this.serverIdentity = Objects.requireNonNull(serverIdentity, "serverIdentity");
        this.interactionRateLimiter = Objects.requireNonNull(interactionRateLimiter, "interactionRateLimiter");
        this.abilityLimitLedger = Objects.requireNonNull(abilityLimitLedger, "abilityLimitLedger");
        this.shutdownDiagnostics = new ShutdownDiagnosticSink(shutdownDiagnosticWriter);
        long next = NEXT_GENERATION.incrementAndGet();
        if (next <= 0) throw new IllegalStateException("server lifecycle generation exhausted");
        this.generation = next;
    }

    public long generation() { return generation; }
    public ThreadBoundary.ServerEpoch threadBoundaryToken() { return threadBoundaryToken; }
    public Path configPath() { return configPath; }
    public boolean ownsServer(Object server) { return serverIdentity == server; }
    public boolean isActive() { return active.get(); }

    public GameLoopMailbox mailbox() { return mailbox; }
    public TickMetrics metrics() { return metrics; }
    public FirstReplyScheduler firstReplyScheduler() { return firstReplyScheduler; }
    public InvocationRateLimiter interactionRateLimiter() { return interactionRateLimiter; }
    /** Returns the process ledger only to the currently active service that owns this exact server object. */
    public AbilityLimitLedger abilityLimitLedgerFor(Object server) {
        return active.get() && ownsServer(server) && WorldspeakerServer.isActiveServiceOwner(this)
                ? abilityLimitLedger : null;
    }
    public BoundedIoLane configIo() { return configIo; }
    public BoundedIoLane diagnosticIo() { return diagnosticIo; }
    boolean reportShutdownDiagnostic(String code) { return shutdownDiagnostics.report(code); }
    boolean shutdownDiagnosticsClosedForTests() { return shutdownDiagnostics.isClosedForTests(); }
    boolean shutdownDiagnosticDrainAliveForTests() { return shutdownDiagnostics.drainThreadAliveForTests(); }
    boolean awaitShutdownDiagnosticsTerminationForTests(long timeout, java.util.concurrent.TimeUnit unit) throws InterruptedException {
        return shutdownDiagnostics.awaitTerminationForTests(timeout, unit);
    }
    public CompanionConfig config() { return config; }
    public CompatibilityGate gate() { return gate; }
    public BoundCompanionClient companion() { return companion; }
    public SmokeConversation conversation() { return conversation; }

    public synchronized boolean setInitialGate(CompatibilityGate gate) {
        if (!active.get()) return false;
        this.gate = Objects.requireNonNull(gate);
        return true;
    }

    /** Installs config and its matching gate together, and refuses late old-epoch results. */
    public synchronized boolean installConfiguration(CompanionConfig config, CompatibilityGate gate) {
        if (!active.get()) return false;
        this.config = Objects.requireNonNull(config);
        this.gate = Objects.requireNonNull(gate);
        return true;
    }

    public synchronized boolean setCompanion(BoundCompanionClient companion) {
        if (!active.get()) return false;
        this.companion = companion;
        return true;
    }

    public synchronized boolean setConversation(SmokeConversation conversation) {
        if (!active.get()) return false;
        this.conversation = conversation;
        return true;
    }

    public synchronized void clearLiveReferences() {
        conversation = null;
        companion = null;
    }

    /** Retires this epoch, closing normal resources and starting its finite shutdown-report drain. */
    public void retire() {
        synchronized (this) {
            if (!active.compareAndSet(true, false)) return;
        }
        mailbox.clearAll();
        configIo.shutdown();
        diagnosticIo.shutdown();
        shutdownDiagnostics.beginDrain();
        metrics.close();
    }

}
