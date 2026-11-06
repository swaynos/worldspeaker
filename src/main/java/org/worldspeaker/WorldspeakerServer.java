package org.worldspeaker;

import com.google.gson.JsonObject;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.CommandOutput;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.worldspeaker.bootstrap.AskCommand;
import org.worldspeaker.bootstrap.AbilityLimitLedger;
import org.worldspeaker.bootstrap.AttentionListener;
import org.worldspeaker.bootstrap.CommandSourceOutputProvenance;
import org.worldspeaker.bootstrap.FirstReplyScheduler;
import org.worldspeaker.bootstrap.InteractionIngress;
import org.worldspeaker.bootstrap.InvocationCapture;
import org.worldspeaker.bootstrap.InvocationCaptureContext;
import org.worldspeaker.bootstrap.InvocationRateLimiter;
import org.worldspeaker.bootstrap.NaturalAddressListener;
import org.worldspeaker.bootstrap.PacketCaptureAccess;
import org.worldspeaker.bootstrap.SmokeConversation;
import org.worldspeaker.bootstrap.SmokeTickBudget;
import org.worldspeaker.bootstrap.VanillaDialogue;
import org.worldspeaker.compat.CompatibilityDiagnostic;
import org.worldspeaker.compat.CompatibilityGate;
import org.worldspeaker.compat.CompanionConfig;
import org.worldspeaker.compat.CharacterChatStyle;
import org.worldspeaker.compat.ConfiguredBinding;
import org.worldspeaker.compat.GateSnapshot;
import org.worldspeaker.compat.GateState;
import org.worldspeaker.compat.PrivateConfigurationException;
import org.worldspeaker.compat.PrivateConfigurationProblem;
import org.worldspeaker.compat.RuntimeFacts;
import org.worldspeaker.compat.StartupConfig;
import org.worldspeaker.integration.BoundCompanionClient;
import org.worldspeaker.mixin.ServerCommandSourceOutputAccessor;
import org.worldspeaker.runtime.GameLoopMailbox;
import org.worldspeaker.runtime.ThreadBoundary;
import org.worldspeaker.runtime.TickMetrics;

import java.io.IOException;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

public final class WorldspeakerServer implements ModInitializer {
    private static final Logger LOGGER = LoggerFactory.getLogger("Worldspeaker");
    private static final String PROCESS_EPOCH_ID = UUID.randomUUID().toString();
    private static RuntimeFacts localFacts;
    private static final AtomicReference<ServerServices> ACTIVE_SERVICES = new AtomicReference<>();
    private static final TickMetrics BOOTSTRAP_METRICS = new TickMetrics();
    private static final InvocationRateLimiter SERVER_INTERACTION_RATE_LIMITER = new InvocationRateLimiter();
    private static final AbilityLimitLedger ABILITY_LIMIT_LEDGER = AbilityLimitLedger.processOwnedLedger();
    private static Path configPath;

    @Override
    public void onInitialize() {
        FabricLoader loader = FabricLoader.getInstance();
        localFacts = new RuntimeFacts(
                version(loader, "minecraft"),
                version(loader, "fabricloader"),
                version(loader, "fabric-api"),
                integerSystemProperty("java.specification.version"),
                System.getProperty("java.vendor", "unknown"),
                System.getProperty("java.runtime.version", "unknown"));
        configPath = loader.getConfigDir().toAbsolutePath().normalize().resolve("worldspeaker.properties");

        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> AskCommand.register(dispatcher));
        AttentionListener.register(attentionAccess());
        NaturalAddressListener.register(naturalAddressAccess());
        ServerLifecycleEvents.SERVER_STARTED.register(WorldspeakerServer::startServices);
        net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents.END_SERVER_TICK.register(
                AttentionListener.createEndTickCallback(attentionAccess(), (server, epoch) -> {
            if (!(epoch instanceof ServerServices services) || servicesFor(server) != services) return;
            GameLoopMailbox.BootstrapResult bootstrap = services.mailbox().takeBootstrap();
            if (bootstrap != null) applyBootstrap(server, services, bootstrap);
            SmokeConversation current = services.conversation();
            if (current != null) current.tick();
            else {
                FirstReplyScheduler scheduler = services.firstReplyScheduler();
                long tickStarted = scheduler.monotonicNanos();
                SmokeTickBudget budget = new SmokeTickBudget(tickStarted);
                if (scheduler.pendingDispositionCount() > 0 && budget.mayCheckDeadline()) budget.deadlineChecked();
                scheduler.drainDispositions(budget, 0);
                if (budget.mayStartOperation(scheduler.monotonicNanos())) {
                    budget.operationStarted();
                    services.metrics().settleFirstReplyDeadlines(scheduler.monotonicNanos());
                }
                if (budget.operationsStarted() == SmokeTickBudget.MAX_OPERATIONS) {
                    services.metrics().increment(TickMetrics.Counter.TICK_BUDGET_EXHAUSTED);
                }
                services.metrics().recordTickNanos(scheduler.monotonicNanos() - tickStarted);
            }
        }));
        net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents.END_SERVER_TICK.register(
                ignored -> BOOTSTRAP_METRICS.settleFirstReplyDeadlines(System.nanoTime()));
        ServerLifecycleEvents.SERVER_STOPPING.register(WorldspeakerServer::stopServices);
    }

    /** Checked compatibility wrapper; all admission still runs through the shared ingress and policy. */
    public static boolean submit(ServerPlayerEntity player, String text, long capturedAtNanos) {
        if (player == null) return false;
        ServerCommandSource source = player.getCommandSource();
        InteractionIngress.Captured captured = new InteractionIngress.Captured(
                UUID.randomUUID(), capturedAtNanos, InteractionIngress.Origin.COMMAND);
        return InteractionIngress.execute(captured, source, "worldspeaker ask", InteractionIngress.Route.ASK,
                text, new ServerCommandPort(source)) == 1;
    }

    public static AskCommand.Access<ServerCommandSource> commandAccess() {
        return new AskCommand.Access<>() {
            @Override
            public InteractionIngress.Captured capture() {
                long handlerEnteredAtNanos = System.nanoTime();
                InvocationCapture packetCapture = InvocationCaptureContext.current();
                // Handoff-capacity rejection is an immutable non-null packet stamp, not missing evidence.
                if (packetCapture == null) {
                    return InteractionIngress.Captured.missingReceipt(InteractionIngress.Origin.COMMAND,
                            handlerEnteredAtNanos, System.currentTimeMillis());
                }
                return InteractionIngress.Captured.fromPacket(packetCapture, InteractionIngress.Origin.COMMAND,
                        handlerEnteredAtNanos);
            }

            @Override
            public int execute(ServerCommandSource source, String input, InteractionIngress.Route route,
                               String text, InteractionIngress.Captured captured) {
                return InteractionIngress.execute(captured, source, input, route, text, new ServerCommandPort(source));
            }
        };
    }

    private static NaturalAddressListener.Access naturalAddressAccess() {
        return new NaturalAddressListener.Access() {
            @Override public long monotonicNanos() { return System.nanoTime(); }
            @Override public long wallTimeMillis() { return System.currentTimeMillis(); }

            @Override
            public InteractionIngress.Captured capture(net.minecraft.network.message.SignedMessage message,
                                                        InteractionIngress.Origin origin) {
                long handlerEnteredAtNanos = System.nanoTime();
                InvocationCapture packetCapture = (Object) message instanceof PacketCaptureAccess carrier
                        ? carrier.worldspeaker$getInvocationCapture() : null;
                // Only an absent carrier is missing; capacity-rejected stamps keep their original receipt.
                if (packetCapture == null) {
                    return InteractionIngress.Captured.missingReceipt(origin, handlerEnteredAtNanos,
                            System.currentTimeMillis());
                }
                return InteractionIngress.Captured.fromPacket(packetCapture, origin, handlerEnteredAtNanos);
            }

            @Override public boolean isServerThread() { return ThreadBoundary.isServerThread(); }

            @Override
            public Object activeEpoch() {
                ServerServices services = ACTIVE_SERVICES.get();
                return isCurrent(services) ? services : null;
            }

            @Override
            public boolean isCurrentEpoch(Object epoch) {
                ServerServices services = currentNaturalServices(epoch);
                return services != null && ThreadBoundary.isServerThread(services.threadBoundaryToken());
            }

            @Override
            public Object currentServer(Object epoch, ServerPlayerEntity sender) {
                ServerServices services = currentNaturalServices(epoch);
                if (services == null || sender == null) return null;
                MinecraftServer server = sender.getServer();
                return server != null && services.ownsServer(server) && currentNaturalServices(epoch) == services
                        ? server : null;
            }

            @Override
            public CompanionConfig config(Object epoch) {
                ServerServices services = currentNaturalServices(epoch);
                return services == null ? null : services.config();
            }

            @Override
            public InteractionIngress.SourceFacts resolveSender(Object epoch, Object server,
                                                                 ServerPlayerEntity sender) {
                ServerServices services = currentNaturalServices(epoch);
                if (services == null || !(server instanceof MinecraftServer currentServer) ||
                        !services.ownsServer(currentServer)) return InteractionIngress.SourceFacts.nonPlayer();
                return resolveNaturalSender(currentServer, sender);
            }

            @Override
            public NaturalAddressListener.PlayerRoster onlineRoster(Object epoch, Object server) {
                ServerServices services = currentNaturalServices(epoch);
                if (services == null || !(server instanceof MinecraftServer currentServer) ||
                        !services.ownsServer(currentServer)) return null;
                var players = currentServer.getPlayerManager().getPlayerList();
                if (players == null) return null;
                return new NaturalAddressListener.PlayerRoster() {
                    @Override public int size() { return players.size(); }

                    @Override
                    public NaturalAddressListener.PlayerIdentity playerAt(int index) {
                        if (currentNaturalServices(epoch) != services) return null;
                        ServerPlayerEntity player = players.get(index);
                        if (player == null) return null;
                        var profile = player.getGameProfile();
                        return profile == null ? null :
                                new NaturalAddressListener.PlayerIdentity(player.getUuid(), profile.getName());
                    }
                };
            }

            @Override
            public InteractionIngress.Port<ServerPlayerEntity> ingress(Object epoch, Object server) {
                ServerServices services = currentNaturalServices(epoch);
                if (services == null || !(server instanceof MinecraftServer currentServer) ||
                        !services.ownsServer(currentServer)) return null;
                return new NaturalIngressPort(services, currentServer);
            }

            @Override
            public boolean followupOpen(Object epoch, Object server, InteractionIngress.SourceFacts source,
                                        long capturedAtNanos, long beforeIngressNanos) {
                ServerServices services = currentNaturalServices(epoch);
                if (services == null || !(server instanceof MinecraftServer currentServer) ||
                        !services.ownsServer(currentServer)) return false;
                SmokeConversation conversation = services.conversation();
                return conversation != null && conversation.isFollowupOpen(source, services,
                        capturedAtNanos, beforeIngressNanos);
            }

            @Override
            public org.worldspeaker.bootstrap.FollowupWindow.Anchor followupAnchor(
                    Object epoch, Object server, InteractionIngress.SourceFacts source,
                    long capturedAtNanos, long beforeIngressNanos) {
                ServerServices services = currentNaturalServices(epoch);
                if (services == null || !(server instanceof MinecraftServer currentServer) ||
                        !services.ownsServer(currentServer)) return null;
                SmokeConversation conversation = services.conversation();
                return conversation == null ? null : conversation.followupAnchor(source, services,
                        capturedAtNanos, beforeIngressNanos);
            }

            @Override
            public void invalidateFollowup(Object epoch, UUID playerId) {
                ServerServices services = currentNaturalServices(epoch);
                if (services == null || playerId == null) return;
                SmokeConversation conversation = services.conversation();
                if (conversation != null) conversation.invalidateFollowup(playerId);
            }
        };
    }

    private static AttentionListener.Access attentionAccess() {
        return new AttentionListener.Access() {
            @Override public long monotonicNanos() { return System.nanoTime(); }
            @Override public long wallTimeMillis() { return System.currentTimeMillis(); }
            @Override public boolean isServerThread() { return ThreadBoundary.isServerThread(); }

            @Override
            public Object activeEpoch() {
                ServerServices services = ACTIVE_SERVICES.get();
                return isCurrent(services) ? services : null;
            }

            @Override
            public boolean isCurrentEpoch(Object epoch) {
                return epoch instanceof ServerServices services && isCurrent(services) &&
                        ThreadBoundary.isServerThread(services.threadBoundaryToken());
            }

            @Override
            public boolean ownsServer(Object epoch, MinecraftServer server) {
                return epoch instanceof ServerServices services && isCurrentEpoch(epoch) && services.ownsServer(server);
            }

            @Override
            public MinecraftServer currentServer(Object epoch, ServerPlayerEntity player) {
                if (!(epoch instanceof ServerServices services) || !isCurrentEpoch(epoch) || player == null) return null;
                MinecraftServer server = player.getServer();
                return server != null && services.ownsServer(server) && isCurrentEpoch(epoch) ? server : null;
            }

            @Override
            public SmokeConversation conversation(Object epoch) {
                if (!(epoch instanceof ServerServices services) || !isCurrentEpoch(epoch)) return null;
                return services.conversation();
            }
        };
    }

    private static ServerServices currentNaturalServices(Object epoch) {
        if (!(epoch instanceof ServerServices services) || !isCurrent(services) ||
                !ThreadBoundary.isServerThread(services.threadBoundaryToken())) return null;
        return services;
    }

    public static boolean isOnlineOrLocalDev(MinecraftServer server) {
        if (server == null) return false;
        if (server.isOnlineMode()) return true;
        return Boolean.getBoolean("worldspeaker.localDevelopment") ||
                "true".equalsIgnoreCase(System.getenv("WORLDSPEAKER_LOCAL_DEVELOPMENT"));
    }

    /** Natural chat is authenticated from the live player object, never from decoration or text. */
    private static InteractionIngress.SourceFacts resolveNaturalSender(MinecraftServer server,
                                                                        ServerPlayerEntity player) {
        if (server == null || player == null || !isOnlineOrLocalDev(server) || player.getServer() != server) {
            return InteractionIngress.SourceFacts.nonPlayer();
        }
        UUID playerId = player.getUuid();
        var profile = player.getGameProfile();
        if (playerId == null || profile == null || !playerId.equals(profile.getId()) ||
                server.getPlayerManager().getPlayer(playerId) != player) {
            return InteractionIngress.SourceFacts.nonPlayer();
        }
        var connection = player.networkHandler;
        if (connection == null || connection.player != player || !connection.isConnectionOpen()) {
            return InteractionIngress.SourceFacts.nonPlayer();
        }
        var world = player.getWorld();
        if (world == null) return InteractionIngress.SourceFacts.nonPlayer();
        return new InteractionIngress.SourceFacts(true, true, true, playerId, profile.getName(),
                world.getRegistryKey().getValue().toString(), player, connection);
    }

    public static boolean integrationReady() {
        ServerServices services = ACTIVE_SERVICES.get();
        if (services == null || !services.isActive()) return false;
        BoundCompanionClient current = services.companion();
        CompatibilityGate currentGate = services.gate();
        return current != null && currentGate != null && current.isReady() && currentGate.snapshot().state() == GateState.READY;
    }

    public static long[] compatibilityCountersSnapshot() {
        ServerServices services = ACTIVE_SERVICES.get();
        long[] bootstrap = BOOTSTRAP_METRICS.countersSnapshot();
        if (services == null) return bootstrap;
        long[] current = services.metrics().countersSnapshot();
        for (int index = 0; index < current.length; index++) {
            current[index] = saturatedAdd(current[index], bootstrap[index]);
        }
        return current;
    }

    public static long[][][] compatibilityFirstPublicDispatchSnapshot() {
        ServerServices services = ACTIVE_SERVICES.get();
        long[][][] bootstrap = BOOTSTRAP_METRICS.firstPublicDispatchHistogramSnapshot();
        if (services == null) return bootstrap;
        long[][][] current = services.metrics().firstPublicDispatchHistogramSnapshot();
        for (int source = 0; source < current.length; source++) {
            for (int disposition = 0; disposition < current[source].length; disposition++) {
                for (int bucket = 0; bucket < current[source][disposition].length; bucket++) {
                    current[source][disposition][bucket] = saturatedAdd(current[source][disposition][bucket],
                            bootstrap[source][disposition][bucket]);
                }
            }
        }
        return current;
    }

    public static long compatibilityFirstPublicDispatchMaxNanos() {
        ServerServices services = ACTIVE_SERVICES.get();
        return Math.max(BOOTSTRAP_METRICS.maxFirstPublicDispatchNanos(),
                services == null ? 0 : services.metrics().maxFirstPublicDispatchNanos());
    }

    /** Called from the pinned packet seam; the counter is atomic and contains no packet content. */
    public static void recordCaptureHandoffOverflow() {
        ServerServices services = ACTIVE_SERVICES.get();
        TickMetrics metrics = services != null && services.isActive() ? services.metrics() : BOOTSTRAP_METRICS;
        metrics.increment(TickMetrics.Counter.CAPTURE_HANDOFF_OVERFLOWS);
    }

    private static long saturatedAdd(long left, long right) {
        return Long.MAX_VALUE - left < right ? Long.MAX_VALUE : left + right;
    }

    private static void startServices(net.minecraft.server.MinecraftServer server) {
        ServerServices previous = ACTIVE_SERVICES.getAndSet(null);
        if (previous != null) retireServices(previous);

        ServerServices services = newServerServices(configPath, server,
                diagnostic -> LOGGER.warn("{}", diagnostic));
        CompatibilityGate initialGate = new CompatibilityGate(new ConfiguredBinding(null, null, null, null));
        if (!services.setInitialGate(initialGate)) throw new IllegalStateException("new server services were already retired");
        GateSnapshot local = initialGate.checkLocal(localFacts);
        try {
            ThreadBoundary.beginServerEpoch(services.threadBoundaryToken());
        } catch (RuntimeException failure) {
            services.retire();
            ThreadBoundary.retireServerEpoch(services.threadBoundaryToken());
            throw failure;
        }
        activateServices(services);
        if (!"WS-COMPAT-COMPANION-MISSING".equals(local.reasonCode())) {
            logDiagnostic(services, new CompatibilityDiagnostic(local.reasonCode(), component(local.reasonCode()), "installed", "recorded candidate", "install the exact recorded server/runtime stack and restart"));
        }
        logInfo(services, "Worldspeaker loaded as a server-only, unqualified candidate; no game abilities are available.");
        loadConfigOffThread(services);
    }

    /** Shared production constructor path; each server epoch receives the same private process ledger. */
    static ServerServices newServerServices(Path path, Object serverIdentity, Consumer<String> diagnosticWriter) {
        return new ServerServices(path, serverIdentity, SERVER_INTERACTION_RATE_LIMITER,
                diagnosticWriter, ABILITY_LIMIT_LEDGER);
    }

    private static ServerServices servicesFor(net.minecraft.server.MinecraftServer server) {
        ServerServices services = ACTIVE_SERVICES.get();
        return services != null && services.isActive() && services.ownsServer(server) ? services : null;
    }

    private static boolean isCurrent(ServerServices services) {
        return services != null && services.isActive() && ACTIVE_SERVICES.get() == services;
    }

    /** Exact active-owner predicate used by server-thread authority adapters. */
    public static boolean isActiveServiceOwner(ServerServices services) {
        return isCurrent(services);
    }

    private static void stopServices(net.minecraft.server.MinecraftServer server) {
        ServerServices services = servicesFor(server);
        if (services != null) stopServices(services);
    }

    /** Resolve a player only when the retained original output still identifies the effective entity as issuer. */
    static InteractionIngress.SourceFacts resolveCommandSource(ServerCommandSource source, String input) {
        if (source == null || !InteractionIngress.isDirectCommandInput(input)) {
            return InteractionIngress.SourceFacts.nonPlayer();
        }

        if (!(source instanceof ServerCommandSourceOutputAccessor outputAccessor)) {
            return InteractionIngress.SourceFacts.nonPlayer();
        }
        CommandOutput commandOutput = outputAccessor.worldspeaker$getCommandOutput();
        if (!(commandOutput instanceof ServerPlayerEntity player) ||
                !CommandSourceOutputProvenance.outputIsCurrentEntity(commandOutput, source.getEntity())) {
            return InteractionIngress.SourceFacts.nonPlayer();
        }

        MinecraftServer server = source.getServer();
        if (server == null) return InteractionIngress.SourceFacts.nonPlayer();

        // Syntax is not provenance: function lines are freshly parsed with direct-looking input while
        // vanilla retains the original CommandOutput across execute-as entity substitution.
        if (source.getPlayer() != player || source.getWorld() != player.getWorld()) {
            return InteractionIngress.SourceFacts.nonPlayer();
        }

        var connection = player.networkHandler;
        boolean onlineMode = isOnlineOrLocalDev(server);
        boolean currentConnection = connection != null && connection.player == player &&
                connection.isConnectionOpen() && player.getServer() == server &&
                server.getPlayerManager().getPlayer(player.getUuid()) == player;
        return new InteractionIngress.SourceFacts(true, onlineMode, currentConnection,
                player.getUuid(), player.getGameProfile().getName(),
                player.getWorld().getRegistryKey().getValue().toString(), player, connection);
    }

    static void activateServices(ServerServices services) {
        ACTIVE_SERVICES.set(services);
    }

    static boolean stopServices(ServerServices services) {
        if (services == null || !ACTIVE_SERVICES.compareAndSet(services, null)) return false;
        retireServices(services);
        return true;
    }

    private static void retireServices(ServerServices services) {
        services.retire();
        try {
            if (ThreadBoundary.isServerThread(services.threadBoundaryToken())) {
                SmokeConversation current = services.conversation();
                if (current != null) current.close();
            }
        } finally {
            try {
                BoundCompanionClient current = services.companion();
                if (current != null) current.close();
            } finally {
                services.clearLiveReferences();
                ThreadBoundary.retireServerEpoch(services.threadBoundaryToken());
            }
        }
    }

    static void loadConfigOffThread(ServerServices services) {
        Path path = services.configPath();
        if (!isCurrent(services) || !services.configIo().tryExecute(() -> {
            try {
                services.mailbox().offerBootstrap(new GameLoopMailbox.BootstrapResult(CompanionConfig.load(path), null));
            } catch (PrivateConfigurationException invalid) {
                services.mailbox().offerBootstrap(GameLoopMailbox.BootstrapResult.problem(invalid.problem()));
            } catch (IOException | RuntimeException invalidConfig) {
                services.mailbox().offerBootstrap(GameLoopMailbox.BootstrapResult.problem(
                        PrivateConfigurationProblem.invalid(PrivateConfigurationProblem.Field.STARTUP_BUNDLE,
                                PrivateConfigurationProblem.Expected.V1_BUNDLE)));
            }
        })) {
            services.mailbox().offerBootstrap(new GameLoopMailbox.BootstrapResult(null, "WS-COMPAT-CONFIG-IO-UNAVAILABLE"));
        }
    }

    private static void applyBootstrap(net.minecraft.server.MinecraftServer server, ServerServices services,
                                       GameLoopMailbox.BootstrapResult bootstrap) {
        if (!isCurrent(services) || !services.ownsServer(server)) return;
        ThreadBoundary.assertServerThread(services.threadBoundaryToken());
        if (bootstrap.config() == null) {
            queueBootstrapDiagnostic(services, bootstrap, line -> LOGGER.warn("{}", line));
            return;
        }
        CompatibilityGate gate = new CompatibilityGate(bootstrap.config().binding(), bootstrap.config().configSetSha256());
        GateSnapshot local = gate.checkLocal(localFacts);
        if (!services.installConfiguration(bootstrap.config(), gate)) return;
        if (!"WS-COMPAT-COMPANION-MISSING".equals(local.reasonCode())) {
            logDiagnostic(services, new CompatibilityDiagnostic(local.reasonCode(), component(local.reasonCode()), "installed",
                    "recorded candidate", "install the exact recorded server/runtime stack and restart"));
        }
        startCompanion(server, services);
    }

    private static void startCompanion(net.minecraft.server.MinecraftServer server, ServerServices services) {
        CompanionConfig config = services.config();
        CompatibilityGate gate = services.gate();
        boolean localDev = Boolean.getBoolean("worldspeaker.localDevelopment") ||
                "true".equalsIgnoreCase(System.getenv("WORLDSPEAKER_LOCAL_DEVELOPMENT"));
        if (!isCurrent(services) || config == null || gate == null || (!localDev && (
                gate.snapshot().reasonCode().startsWith("WS-COMPAT-JAVA") ||
                gate.snapshot().reasonCode().startsWith("WS-COMPAT-MINECRAFT") ||
                gate.snapshot().reasonCode().startsWith("WS-COMPAT-LOADER") ||
                gate.snapshot().reasonCode().startsWith("WS-COMPAT-FABRIC-API")))) {
            return;
        }
        BoundCompanionClient companion = new BoundCompanionClient(
                config,
                localFacts,
                gate,
                frame -> {
                    if (!isCurrent(services)) return;
                    SmokeConversation current = services.conversation();
                    if (current != null) current.onDialogueMessage(frame);
                },
                privateDiagnosticCallback(services));
        if (!services.setCompanion(companion)) {
            companion.close();
            return;
        }
        SmokeConversation conversation = new SmokeConversation(server, config, gate, companion,
                services.mailbox(), services.metrics(), services.generation(), services, PROCESS_EPOCH_ID,
                attentionDiagnosticCallback(services), services.firstReplyScheduler());
        if (!services.setConversation(conversation)) {
            conversation.close();
            companion.close();
            return;
        }
        companion.start();
    }

    private static String version(FabricLoader loader, String modId) {
        return loader.getModContainer(modId)
                .map(ModContainer::getMetadata)
                .map(metadata -> metadata.getVersion().getFriendlyString())
                .orElse("missing");
    }

    private static int integerSystemProperty(String key) {
        try {
            return Integer.parseInt(System.getProperty(key, "0"));
        } catch (NumberFormatException exception) {
            return 0;
        }
    }

    private static String component(String reason) {
        if (reason.contains("JAVA")) return "java";
        if (reason.contains("FABRIC-API")) return "fabric-api";
        if (reason.contains("LOADER")) return "fabric-loader";
        if (reason.contains("MINECRAFT")) return "minecraft";
        return "deployment";
    }

    private static void logDiagnostic(ServerServices services, CompatibilityDiagnostic diagnostic) {
        if (!queuePrivateDiagnostic(services, diagnostic, line -> LOGGER.warn("{}", line))) {
            // Bounded queue saturation is deliberately fail-closed and value-free.
        }
    }

    /** Production conversion and bounded off-thread private publication seam; tests inject only the sink. */
    static boolean queueBootstrapDiagnostic(ServerServices services, GameLoopMailbox.BootstrapResult result,
                                            Consumer<String> privateLogSink) {
        if (result == null || result.config() != null || !isCurrent(services)) return false;
        return queuePrivateDiagnostic(services, bootstrapDiagnostic(result), privateLogSink);
    }

    private static boolean queuePrivateDiagnostic(ServerServices services, CompatibilityDiagnostic diagnostic,
                                                  Consumer<String> privateLogSink) {
        return isCurrent(services) && services.diagnosticIo().tryExecute(() -> privateLogSink.accept(diagnostic.privateLogLine()));
    }

    private static CompatibilityDiagnostic bootstrapDiagnostic(GameLoopMailbox.BootstrapResult bootstrap) {
        if (bootstrap.problem() != null) return CompatibilityDiagnostic.fromPrivateConfigurationProblem(bootstrap.problem());
        String code = bootstrap.diagnosticCode();
        return switch (code) {
            case "WS-COMPAT-ACK-TEMPLATE-INVALID" -> CompatibilityDiagnostic.invalidAcknowledgementTemplate();
            case "WS-COMPAT-CHARACTER-NAME-INVALID" -> CompatibilityDiagnostic.invalidCharacterDisplayName();
            case "WS-COMPAT-CONVERSATION-POLICY-INVALID" -> CompatibilityDiagnostic.invalidInteractionPolicy();
            case "WS-COMPAT-CHARACTER-ALIASES-INVALID" -> CompatibilityDiagnostic.invalidCharacterAliases();
            case "WS-COMPAT-FOLLOWUP-WINDOW-INVALID" -> CompatibilityDiagnostic.invalidFollowupWindow();
            case "WS-COMPAT-CHARACTER-CHAT-STYLE-INVALID" -> CompatibilityDiagnostic.invalidCharacterChatStyle();
            case "WS-COMPAT-EXECUTION-BUDGET-INVALID" -> new CompatibilityDiagnostic(code, "local-config", "invalid",
                    "executionBudgetSeconds from 1 to 30", "correct the finite execution budget and restart");
            case "WS-COMPAT-ATTENTION-POLICY-INVALID" -> new CompatibilityDiagnostic(code, "local-config", "invalid",
                    "explicit bounded attention policy", "correct attention settings and restart");
            case "WS-COMPAT-PERSONA-PUBLICATION-CONTROL-UNQUALIFIED" -> new CompatibilityDiagnostic(code,
                    "local-config", "configured persona is inactive", "qualified private-prose publication control",
                    "keep persona inactive; wait for qualified publication control");
            default -> new CompatibilityDiagnostic(code, "local-config", "unavailable",
                    "private schema-v1 configuration and pinned local reader", "repair the existing private configuration and restart");
        };
    }

    static Consumer<String> privateDiagnosticCallback(ServerServices services) {
        return reason -> {
            if (ShutdownDiagnosticSink.TIMEOUT.equals(reason) || ShutdownDiagnosticSink.INCOMPLETE.equals(reason)) {
                // Shutdown evidence is fixed and private; it intentionally outlives ACTIVE_SERVICES.
                services.reportShutdownDiagnostic(reason);
                return;
            }
            if (!isCurrent(services)) return;
            if ("WS-COMPAT-RECONNECT-EXHAUSTED".equals(reason)) {
                services.metrics().increment(TickMetrics.Counter.RECONNECT_EXHAUSTED);
            } else if ("WS-COMPAT-CONNECTION-FAILED".equals(reason) ||
                    "WS-COMPAT-COMPANION-DISCONNECTED".equals(reason)) {
                services.metrics().increment(TickMetrics.Counter.CONNECTION_FAILURES);
            }
            logDiagnostic(services, new CompatibilityDiagnostic(reason, "companion", "unavailable", "recorded host/plugin pair", "repair the local companion configuration and restart"));
            SmokeConversation current = services.conversation();
            if (current != null && services.gate().snapshot().state() != GateState.READY) current.onUnavailable();
        };
    }

    private static Consumer<String> attentionDiagnosticCallback(ServerServices services) {
        return summary -> {
            if (!isCurrent(services) || summary == null || !summary.matches("[a-zA-Z,=0-9]+")) return;
            String privateLine = "WS-COMPAT-ATTENTION-LOSS " + summary;
            services.diagnosticIo().tryExecute(() -> LOGGER.warn("{}", privateLine));
        };
    }

    private static final class ServerCommandPort implements InteractionIngress.Port<ServerCommandSource> {
        private final MinecraftServer server;
        private final ServerServices services;

        private ServerCommandPort(ServerCommandSource source) {
            this.server = source.getServer();
            this.services = servicesFor(server);
        }

        @Override
        public InteractionIngress.SourceFacts resolve(ServerCommandSource source) {
            return InteractionIngress.SourceFacts.nonPlayer();
        }

        @Override
        public InteractionIngress.SourceFacts resolve(ServerCommandSource source, String input) {
            return resolveCommandSource(source, input);
        }

        @Override
        public CompanionConfig config() {
            return serviceActive() ? services.config() : null;
        }

        @Override
        public TickMetrics metrics() {
            return serviceActive() ? services.metrics() : BOOTSTRAP_METRICS;
        }

        @Override public InvocationRateLimiter rateLimiter() { return SERVER_INTERACTION_RATE_LIMITER; }

        @Override
        public FirstReplyScheduler firstReplyScheduler() {
            return services == null ? FirstReplyScheduler.system() : services.firstReplyScheduler();
        }

        @Override
        public boolean serviceActive() {
            return isCurrent(services) && services.ownsServer(server);
        }

        @Override
        public InteractionIngress.Outcome submit(InteractionIngress.SourceFacts source, String text,
                                                  InteractionIngress.Invocation invocation) {
            if (!serviceActive() || services.conversation() == null) return InteractionIngress.Outcome.UNAVAILABLE;
            SmokeConversation.SubmitOutcome result = services.conversation().submit(
                    (ServerPlayerEntity) source.player(), text, invocation);
            return InteractionIngress.Outcome.valueOf(result.name());
        }

        @Override
        public org.worldspeaker.bootstrap.FollowupWindow.Candidate beginExplicit(
                InteractionIngress.SourceFacts source, InteractionIngress.Captured captured) {
            SmokeConversation current = services == null ? null : services.conversation();
            return serviceActive() && current != null ? current.beginExplicit(source, captured, services) : null;
        }

        @Override
        public void invalidateFollowup(InteractionIngress.SourceFacts source) {
            SmokeConversation current = services == null ? null : services.conversation();
            if (serviceActive() && current != null && source != null) current.invalidateFollowup(source.playerId());
        }

        @Override
        public void rejectOpeningCandidate(InteractionIngress.SourceFacts source,
                                           org.worldspeaker.bootstrap.FollowupWindow.Candidate candidate) {
            SmokeConversation current = services == null ? null : services.conversation();
            if (serviceActive() && current != null) current.rejectOpeningCandidate(candidate);
        }

        @Override
        public void endFollowup(InteractionIngress.SourceFacts source) {
            SmokeConversation current = services == null ? null : services.conversation();
            if (serviceActive() && current != null) current.endFollowup(source);
        }

        @Override public void closeAdmissionForEvidenceLoss() {
            SmokeConversation current = services == null ? null : services.conversation();
            if (serviceActive() && current != null) current.closeAdmissionForEvidenceLoss();
        }

        @Override public void closeAdmissionForDispositionOverflow() {
            SmokeConversation current = services == null ? null : services.conversation();
            if (serviceActive() && current != null) current.closeAdmissionForDispositionOverflow();
        }

        @Override
        public boolean publishPlayer(InteractionIngress.SourceFacts source, String message,
                                     boolean characterMessage, String characterDisplayName) {
            return submitPlayer(source, message, characterMessage, characterDisplayName) ==
                    org.worldspeaker.bootstrap.FirstReplyScheduler.PublicationResult.SUBMITTED;
        }

        @Override
        public org.worldspeaker.bootstrap.FirstReplyScheduler.PublicationResult submitPlayer(
                InteractionIngress.SourceFacts source, String message, boolean characterMessage, String characterDisplayName) {
            return submitPlayer(source, message, characterMessage, characterDisplayName, CharacterChatStyle.PLAIN);
        }

        @Override
        public org.worldspeaker.bootstrap.FirstReplyScheduler.PublicationResult submitPlayer(
                InteractionIngress.SourceFacts source, String message, boolean characterMessage, String characterDisplayName,
                CharacterChatStyle characterChatStyle) {
            if (source == null || source.playerId() == null || !(source.player() instanceof ServerPlayerEntity player) ||
                    source.connection() == null || player.networkHandler == null || player.getUuid() == null ||
                    !player.getUuid().equals(source.playerId()) || player.networkHandler != source.connection() ||
                    player.networkHandler.player != player ||
                    !player.networkHandler.isConnectionOpen() || player.getServer() != server || server == null ||
                    !isOnlineOrLocalDev(server) ||
                    server.getPlayerManager().getPlayer(source.playerId()) != player) {
                return org.worldspeaker.bootstrap.FirstReplyScheduler.PublicationResult.DEFINITELY_UNSENT;
            }
            String rendered = characterMessage
                    ? VanillaDialogue.renderCharacterLine(characterDisplayName, message, characterChatStyle)
                    : VanillaDialogue.renderLine(message);
            return VanillaDialogue.submitRendered(player, rendered);
        }

        @Override
        public FirstReplyScheduler.PublicationResult submitPlayerDisposition(
                InteractionIngress.SourceFacts source, String message, boolean characterMessage,
                String characterDisplayName, boolean requireCurrentGrant) {
            return submitPlayerDisposition(source, message, characterMessage, characterDisplayName,
                    CharacterChatStyle.PLAIN, requireCurrentGrant);
        }

        @Override
        public FirstReplyScheduler.PublicationResult submitPlayerDisposition(
                InteractionIngress.SourceFacts source, String message, boolean characterMessage,
                String characterDisplayName, CharacterChatStyle characterChatStyle, boolean requireCurrentGrant) {
            if (services != null && (!isCurrent(services) || !services.ownsServer(server))) {
                return FirstReplyScheduler.PublicationResult.DEFINITELY_UNSENT;
            }
            if (requireCurrentGrant) {
                CompanionConfig currentConfig = services == null ? null : services.config();
                if (source == null || source.playerId() == null || currentConfig == null ||
                        !currentConfig.interactionPolicy().allows(source.playerId(),
                                org.worldspeaker.compat.InteractionPolicy.CONVERSE_PERMISSION)) {
                    return FirstReplyScheduler.PublicationResult.DEFINITELY_UNSENT;
                }
            }
            return submitPlayer(source, message, characterMessage, characterDisplayName, characterChatStyle);
        }

        @Override
        public boolean publishSource(ServerCommandSource source, String message) {
            try {
                source.sendError(Text.literal(message));
                return true;
            } catch (RuntimeException failure) {
                return false;
            }
        }

        @Override
        public org.worldspeaker.bootstrap.FirstReplyScheduler.PublicationResult submitSource(
                ServerCommandSource source, String message) {
            return publishSource(source, message)
                    ? org.worldspeaker.bootstrap.FirstReplyScheduler.PublicationResult.SUBMITTED
                    : org.worldspeaker.bootstrap.FirstReplyScheduler.PublicationResult.UNKNOWN;
        }
    }

    private static final class NaturalIngressPort implements InteractionIngress.Port<ServerPlayerEntity> {
        private final ServerServices services;
        private final MinecraftServer server;

        private NaturalIngressPort(ServerServices services, MinecraftServer server) {
            this.services = services;
            this.server = server;
        }

        @Override public InteractionIngress.SourceFacts resolve(ServerPlayerEntity source) {
            return serviceActive() ? resolveNaturalSender(server, source) : InteractionIngress.SourceFacts.nonPlayer();
        }

        @Override public InteractionIngress.SourceFacts resolve(ServerPlayerEntity source, String input) {
            return resolve(source);
        }

        @Override public CompanionConfig config() {
            return serviceActive() ? services.config() : null;
        }

        @Override public TickMetrics metrics() {
            return serviceActive() ? services.metrics() : BOOTSTRAP_METRICS;
        }

        @Override public InvocationRateLimiter rateLimiter() { return services.interactionRateLimiter(); }

        @Override public FirstReplyScheduler firstReplyScheduler() {
            return services.firstReplyScheduler();
        }

        @Override public boolean serviceActive() {
            return currentNaturalServices(services) == services && services.ownsServer(server);
        }

        @Override
        public InteractionIngress.Outcome submit(InteractionIngress.SourceFacts source, String text,
                                                   InteractionIngress.Invocation invocation) {
            if (!serviceActive() || services.conversation() == null) return InteractionIngress.Outcome.UNAVAILABLE;
            return InteractionIngress.Outcome.valueOf(services.conversation().submit(
                    (ServerPlayerEntity) source.player(), text, invocation).name());
        }

        @Override
        public org.worldspeaker.bootstrap.FollowupWindow.Candidate beginExplicit(
                InteractionIngress.SourceFacts source, InteractionIngress.Captured captured) {
            SmokeConversation current = services.conversation();
            return serviceActive() && current != null ? current.beginExplicit(source, captured, services) : null;
        }

        @Override
        public void invalidateFollowup(InteractionIngress.SourceFacts source) {
            SmokeConversation current = services.conversation();
            if (serviceActive() && current != null && source != null) current.invalidateFollowup(source.playerId());
        }

        @Override
        public void rejectOpeningCandidate(InteractionIngress.SourceFacts source,
                                           org.worldspeaker.bootstrap.FollowupWindow.Candidate candidate) {
            SmokeConversation current = services.conversation();
            if (serviceActive() && current != null) current.rejectOpeningCandidate(candidate);
        }

        @Override public void closeAdmissionForEvidenceLoss() {
            SmokeConversation current = services.conversation();
            if (serviceActive() && current != null) current.closeAdmissionForEvidenceLoss();
        }

        @Override public void closeAdmissionForDispositionOverflow() {
            SmokeConversation current = services.conversation();
            if (serviceActive() && current != null) current.closeAdmissionForDispositionOverflow();
        }

        @Override
        public boolean publishPlayer(InteractionIngress.SourceFacts source, String message,
                                     boolean characterMessage, String characterDisplayName) {
            return submitPlayer(source, message, characterMessage, characterDisplayName) ==
                    org.worldspeaker.bootstrap.FirstReplyScheduler.PublicationResult.SUBMITTED;
        }

        @Override
        public org.worldspeaker.bootstrap.FirstReplyScheduler.PublicationResult submitPlayer(
                InteractionIngress.SourceFacts source, String message, boolean characterMessage, String characterDisplayName) {
            return submitPlayer(source, message, characterMessage, characterDisplayName, CharacterChatStyle.PLAIN);
        }

        @Override
        public org.worldspeaker.bootstrap.FirstReplyScheduler.PublicationResult submitPlayer(
                InteractionIngress.SourceFacts source, String message, boolean characterMessage, String characterDisplayName,
                CharacterChatStyle characterChatStyle) {
            if (!serviceActive() || source == null || source.playerId() == null ||
                    !(source.player() instanceof ServerPlayerEntity player) || source.connection() == null) {
                return org.worldspeaker.bootstrap.FirstReplyScheduler.PublicationResult.DEFINITELY_UNSENT;
            }
            if (!source.playerId().equals(player.getUuid()) || player.getServer() != server ||
                    player.networkHandler == null || player.networkHandler != source.connection() || player.networkHandler.player != player ||
                    !player.networkHandler.isConnectionOpen() || server.getPlayerManager().getPlayer(source.playerId()) != player) {
                return org.worldspeaker.bootstrap.FirstReplyScheduler.PublicationResult.DEFINITELY_UNSENT;
            }
            String rendered = characterMessage
                    ? VanillaDialogue.renderCharacterLine(characterDisplayName, message, characterChatStyle)
                    : VanillaDialogue.renderLine(message);
            return VanillaDialogue.submitRendered(player, rendered);
        }

        @Override
        public FirstReplyScheduler.PublicationResult submitPlayerDisposition(
                InteractionIngress.SourceFacts source, String message, boolean characterMessage,
                String characterDisplayName, boolean requireCurrentGrant) {
            return submitPlayerDisposition(source, message, characterMessage, characterDisplayName,
                    CharacterChatStyle.PLAIN, requireCurrentGrant);
        }

        @Override
        public FirstReplyScheduler.PublicationResult submitPlayerDisposition(
                InteractionIngress.SourceFacts source, String message, boolean characterMessage,
                String characterDisplayName, CharacterChatStyle characterChatStyle, boolean requireCurrentGrant) {
            if (requireCurrentGrant) {
                CompanionConfig currentConfig = services.config();
                if (source == null || source.playerId() == null || currentConfig == null ||
                        !currentConfig.interactionPolicy().allows(source.playerId(),
                                org.worldspeaker.compat.InteractionPolicy.CONVERSE_PERMISSION)) {
                    return FirstReplyScheduler.PublicationResult.DEFINITELY_UNSENT;
                }
            }
            if (server == null || !isOnlineOrLocalDev(server)) return FirstReplyScheduler.PublicationResult.DEFINITELY_UNSENT;
            return submitPlayer(source, message, characterMessage, characterDisplayName, characterChatStyle);
        }

        /** Natural chat never uses a command-source or operator-broadcast fallback. */
        @Override public boolean publishSource(ServerPlayerEntity source, String message) { return false; }
    }

    private static void logInfo(ServerServices services, String message) {
        if (!isCurrent(services) || !services.diagnosticIo().tryExecute(() -> LOGGER.info("{}", message))) {
            // Never block the game thread on logging or run a caller-runs fallback.
        }
    }
}
