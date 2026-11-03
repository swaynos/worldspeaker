package org.worldspeaker.integration;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.worldspeaker.bootstrap.AttributedEvent;
import org.worldspeaker.bootstrap.AttentionBuffer;
import org.worldspeaker.bootstrap.AttentionEvent;
import org.worldspeaker.compat.CompatibilityGate;
import org.worldspeaker.compat.CompanionConfig;
import org.worldspeaker.compat.ConfiguredBinding;
import org.worldspeaker.compat.PeerFacts;
import org.worldspeaker.compat.RuntimeFacts;
import org.worldspeaker.generated.DeploymentFacts;
import org.worldspeaker.runtime.BoundedIoLane;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.URI;
import java.net.http.WebSocket;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BoundCompanionClientHandshakeTest {
    private static final String CHALLENGE = "ERERERERERERERERERERERERERERERERERERERERERE";

    @Test
    void javaClientAnswersAuthenticatedChallengeAndAcceptsBoundWelcome() {
        byte[] secret = new byte[32];
        for (int index = 0; index < secret.length; index++) secret[index] = (byte) index;
        ConfiguredBinding binding = new ConfiguredBinding("server-test", "world-test", "character-test", "agent-test");
        CompanionConfig config = new CompanionConfig(URI.create("ws://127.0.0.1:18473"), secret, binding, "Hello {player}",
                "Archivist", org.worldspeaker.compat.InteractionPolicy.denyAll());
        RuntimeFacts local = new RuntimeFacts(
                DeploymentFacts.MINECRAFT_VERSION, DeploymentFacts.FABRIC_LOADER_VERSION, DeploymentFacts.FABRIC_API_VERSION,
                Integer.parseInt(DeploymentFacts.JAVA_MAJOR), DeploymentFacts.JAVA_VENDOR, DeploymentFacts.JAVA_RUNTIME_VERSION);
        PeerFacts peer = new PeerFacts(
                DeploymentFacts.DEPLOYMENT_ID, DeploymentFacts.MANIFEST_SHA256, DeploymentFacts.PROTOCOL,
                DeploymentFacts.OPENCLAW_VERSION, DeploymentFacts.NODE_VERSION, DeploymentFacts.COMPANION_VERSION,
                binding.serverId(), binding.worldId(), binding.characterId(), binding.agentId());
        CompatibilityGate gate = new CompatibilityGate(binding);
        gate.checkLocal(local);

        AtomicReference<String> sentFrame = new AtomicReference<>();
        WebSocket socket = fakeWebSocket(sentFrame);
        BoundCompanionClient client = new BoundCompanionClient(config, local, gate, ignored -> {}, ignored -> {});
        try {
            client.onOpen(socket);
            client.onText(socket, challengeFrame(config, CHALLENGE, peer).toString(), true).toCompletableFuture().join();

            awaitReference(sentFrame);
            JsonObject hello = JsonParser.parseString(sentFrame.get()).getAsJsonObject();
            String nonce = hello.get("nonce").getAsString();
            assertEquals("hello", hello.get("type").getAsString());
            assertEquals(CHALLENGE, hello.get("challenge").getAsString());
            assertEquals(PeerHandshake.clientProof(config, CHALLENGE, nonce, local), hello.get("proof").getAsString());

            client.onText(socket, welcomeFrame(config, CHALLENGE, nonce, peer).toString(), true).toCompletableFuture().join();
            assertTrue(client.isReady());
        } finally {
            client.close();
        }
    }

    @Test
    void javaClientRejectsDuplicateEscapedAuthorityFieldBeforeHandshake() {
        byte[] secret = new byte[32];
        for (int index = 0; index < secret.length; index++) secret[index] = (byte) index;
        ConfiguredBinding binding = new ConfiguredBinding("server-test", "world-test", "character-test", "agent-test");
        CompanionConfig config = new CompanionConfig(URI.create("ws://127.0.0.1:18473"), secret, binding, "Hello {player}",
                "Archivist", org.worldspeaker.compat.InteractionPolicy.denyAll());
        RuntimeFacts local = new RuntimeFacts(
                DeploymentFacts.MINECRAFT_VERSION, DeploymentFacts.FABRIC_LOADER_VERSION, DeploymentFacts.FABRIC_API_VERSION,
                Integer.parseInt(DeploymentFacts.JAVA_MAJOR), DeploymentFacts.JAVA_VENDOR, DeploymentFacts.JAVA_RUNTIME_VERSION);
        PeerFacts peer = new PeerFacts(
                DeploymentFacts.DEPLOYMENT_ID, DeploymentFacts.MANIFEST_SHA256, DeploymentFacts.PROTOCOL,
                DeploymentFacts.OPENCLAW_VERSION, DeploymentFacts.NODE_VERSION, DeploymentFacts.COMPANION_VERSION,
                binding.serverId(), binding.worldId(), binding.characterId(), binding.agentId());
        CompatibilityGate gate = new CompatibilityGate(binding);
        gate.checkLocal(local);

        AtomicReference<String> sentFrame = new AtomicReference<>();
        WebSocket socket = fakeWebSocket(sentFrame);
        BoundCompanionClient client = new BoundCompanionClient(config, local, gate, ignored -> {}, ignored -> {});
        try {
            client.onOpen(socket);
            String escapedServerId = "\\" + "u0073erverId";
            String duplicate = challengeFrame(config, CHALLENGE, peer).toString().replace(
                    "\"serverId\":\"server-test\"",
                    "\"serverId\":\"attacker\",\"" + escapedServerId + "\":\"server-test\"");
            client.onText(socket, duplicate, true).toCompletableFuture().join();
            assertNull(sentFrame.get(), "a duplicate authority field must not produce an authenticated hello");
        } finally {
            client.close();
        }
    }

    @Test
    void productionOutboundRequestSerializesTheSameStrictAttentionMetadataAsTheSharedContract() {
        ControlledSocket socket = new ControlledSocket();
        AuthenticatedClient authenticated = authenticatedClient(socket);
        BoundCompanionClient client = authenticated.client();
        UUID playerId = UUID.fromString("00000000-0000-4000-8000-000000000091");
        UUID epochId = UUID.fromString("00000000-0000-4000-8000-000000000092");
        AttributedEvent primary = new AttributedEvent("server-test", "world-test", "character-test", "agent-test",
                UUID.fromString("00000000-0000-4000-8000-000000000093"), epochId, 1_729_500_000_000L,
                Long.MIN_VALUE, "live", "command", playerId, "PlayerA", "minecraft:overworld", "Original prompt");
        AttentionEvent observation = new AttentionEvent("server-test", "world-test", "character-test", "agent-test",
                UUID.fromString("00000000-0000-4000-8000-000000000094"), epochId, 1_729_499_999_000L,
                Long.MAX_VALUE, "history", "server_callback", playerId, "PlayerA", "minecraft:overworld",
                "joined", AttentionEvent.Details.empty());
        AttentionBuffer.ContextStatus status = new AttentionBuffer.ContextStatus("incomplete", "1", 1,
                List.of("epoch_start"), Set.of(AttentionBuffer.LossReason.EPOCH_START));
        try {
            assertTrue(client.sendDialogueRequest("00000000-0000-4000-8000-000000000095",
                    "00000000-0000-4000-8000-000000000096", primary,
                    "00000000-0000-4000-8000-000000000097", null, null, List.of(observation), status));
            socket.awaitFrameCount(2);
            JsonObject frame = JsonParser.parseString(socket.frameAt(1)).getAsJsonObject();
            DialogueEnvelope.validateRequest(frame, playerId.toString(), authenticated.config().binding());
            assertEquals("Original prompt", frame.getAsJsonObject("event").get("text").getAsString());
            assertEquals(observation.toJson(), frame.getAsJsonArray("contextBatch").get(0).getAsJsonObject());
            assertEquals("-9223372036854775808", frame.getAsJsonObject("event")
                    .get("capturedAtMonotonicNanos").getAsString());
            assertEquals("9223372036854775807", frame.getAsJsonArray("contextBatch").get(0).getAsJsonObject()
                    .get("capturedAtMonotonicNanos").getAsString());
            assertEquals("incomplete", frame.getAsJsonObject("contextStatus").get("state").getAsString());
            socket.complete(1);
        } finally {
            client.close();
        }
    }

    @Test
    void controlledSocketAwaitCannotObservePartiallyPublishedSendRecord() throws Exception {
        ControlledSocket socket = new ControlledSocket();
        CountDownLatch publicationPaused = new CountDownLatch(1);
        CountDownLatch releasePublication = new CountDownLatch(1);
        CountDownLatch awaitStarted = new CountDownLatch(1);
        CountDownLatch awaitReturned = new CountDownLatch(1);
        AtomicReference<List<ControlledSocket.SendRecord>> observedRecords = new AtomicReference<>();
        socket.pauseNextPublicationForTests(publicationPaused, releasePublication);
        Thread sender = new Thread(() -> socket.socket.sendText("forced-interleave", true),
                "worldspeaker-test-controlled-socket-sender");
        Thread waiter = new Thread(() -> {
            awaitStarted.countDown();
            socket.awaitFrameCount(1);
            observedRecords.set(socket.snapshot());
            awaitReturned.countDown();
        }, "worldspeaker-test-controlled-socket-waiter");

        try {
            sender.start();
            assertTrue(publicationPaused.await(2, TimeUnit.SECONDS), "sender pauses inside the synchronized publication section");
            waiter.start();
            assertTrue(awaitStarted.await(2, TimeUnit.SECONDS), "waiter attempts to await the in-progress send");
            awaitThreadState(waiter, Thread.State.BLOCKED);
            assertEquals(1, awaitReturned.getCount(), "await cannot return while the record pair is unpublished");

            releasePublication.countDown();
            assertTrue(awaitReturned.await(2, TimeUnit.SECONDS), "waiter observes the record after atomic publication");
            List<ControlledSocket.SendRecord> records = observedRecords.get();
            assertEquals(1, records.size());
            assertEquals("forced-interleave", records.get(0).frame());
            assertTrue(records.get(0).completion() != null, "frame and completion future are one published record");
            assertFalse(records.get(0).completion().isDone(), "publication does not complete the pending send");
        } finally {
            releasePublication.countDown();
            sender.join(2_000);
            waiter.join(2_000);
        }
        assertFalse(sender.isAlive(), "the controlled sender exits");
        assertFalse(waiter.isAlive(), "the controlled waiter exits");
    }

    @Test
    void oneActiveSendAndSixteenWaitingFramesPreserveAckBeforeCancelOrder() {
        ControlledSocket socket = new ControlledSocket();
        AuthenticatedClient authenticated = authenticatedClient(socket);
        BoundCompanionClient client = authenticated.client();
        AtomicReference<BoundCompanionClient.AcknowledgementDelivery> delivery = new AtomicReference<>();
        AtomicInteger callbacks = new AtomicInteger();
        CountDownLatch callbackFinished = new CountDownLatch(1);
        try {
            assertTrue(sendRequest(client, 0));
            for (int index = 1; index < 16; index++) {
                assertTrue(sendRequest(client, index));
            }
            socket.awaitFrameCount(2);
            assertEquals(2, socket.frameCount(), "hello plus one active request; the other requests wait");

            assertEquals(BoundCompanionClient.AcknowledgementSubmission.ACCEPTED,
                    client.sendDialogueAcknowledgement("request-ack", "player", "message-ack", "Hello",
                            result -> {
                                 delivery.set(result);
                                 callbacks.incrementAndGet();
                                 callbackFinished.countDown();
                            }));
            assertNull(delivery.get(), "an accepted deferred frame has not completed its socket send");
            assertEquals(2, socket.frameCount(), "queue saturation must defer rather than drop the ack");

            socket.complete(1);
            socket.awaitFrameCount(3);
            assertEquals("dialogue.request", frameType(socket.frameAt(2)));
            assertNull(delivery.get(), "submission to the WebSocket is not completion");

            assertTrue(queueCancel(client));
            for (int index = 2; index <= 16; index++) {
                socket.awaitFrameCount(index + 1);
                socket.complete(index);
            }
            socket.awaitFrameCount(18);
            assertEquals("dialogue.ack", frameType(socket.frameAt(17)));
            socket.complete(17);
            assertTrue(await(callbackFinished));
            socket.awaitFrameCount(19);
            assertEquals(BoundCompanionClient.AcknowledgementDelivery.SEND_COMPLETED, delivery.get());
            assertEquals(1, callbacks.get());
            assertEquals("dialogue.cancel", frameType(socket.frameAt(18)), "a later cancel cannot overtake the ack");
            assertEquals(1, socket.maxConcurrentSends(), "the fake records no overlapping JDK text sends");
            assertEquals(1, callbacks.get(), "the bounded dispatch has no timer-driven retries");
        } finally {
            client.close();
        }
    }

    @Test
    void outboundPumpSubmissionContentionDefersTheAcceptedFrameWithoutPoisoningReadiness() throws Exception {
        ControlledSocket socket = new ControlledSocket();
        List<String> diagnostics = new CopyOnWriteArrayList<>();
        AuthenticatedClient authenticated = authenticatedClient(socket, 10_000, 10_000, diagnostics::add);
        BoundCompanionClient client = authenticated.client();
        ReentrantLock submissionLock = ownerSubmissionLock(client);
        CountDownLatch lockHeld = new CountDownLatch(1);
        CountDownLatch releaseLock = new CountDownLatch(1);
        Thread holder = new Thread(() -> {
            submissionLock.lock();
            try {
                lockHeld.countDown();
                await(releaseLock);
            } finally {
                submissionLock.unlock();
            }
        }, "worldspeaker-test-owner-submission-lock-holder");
        try {
            holder.start();
            assertTrue(lockHeld.await(2, TimeUnit.SECONDS), "the other thread holds the lane submission lock");
            try {
                assertTrue(sendRequest(client, 99),
                        "the finite outbound queue owns the frame while owner-lane submission contends");
                assertTrue(client.isReady(), "transient submission contention is not peer unavailability");
                assertTrue(diagnostics.contains("WS-COMPAT-IO-OWNER-CONTENDED"));
                assertFalse(diagnostics.contains("WS-COMPAT-IO-OWNER-SATURATED"));
            } finally {
                releaseLock.countDown();
            }
            holder.join(2_000);
            assertFalse(holder.isAlive(), "the deterministic contention holder exits");

            socket.awaitFrameCount(2);
            assertEquals("dialogue.request", frameType(socket.frameAt(1)),
                    "maintenance retries the bounded pump after the contention clears");
            assertTrue(client.isReady());
        } finally {
            releaseLock.countDown();
            holder.join(2_000);
            client.close();
        }
    }

    @Test
    void actualOwnerQueueSaturationStillFailsClosed() throws Exception {
        ControlledSocket socket = new ControlledSocket();
        CountDownLatch saturationReported = new CountDownLatch(1);
        AuthenticatedClient authenticated = authenticatedClient(socket, 10_000, 10_000, reason -> {
            if ("WS-COMPAT-IO-OWNER-SATURATED".equals(reason)) saturationReported.countDown();
        });
        BoundCompanionClient client = authenticated.client();
        BoundedIoLane owner = ownerLaneForTests(client);
        ReentrantLock submissionLock = ownerSubmissionLock(client);
        stopMaintenanceForTests(client);
        submissionLock.lock();
        submissionLock.unlock(); // Wait for any already-running maintenance submission to finish.
        assertTrue(client.awaitOwnerBarrierForTests(2, TimeUnit.SECONDS));
        CountDownLatch ownerEntered = new CountDownLatch(1);
        CountDownLatch releaseOwner = new CountDownLatch(1);
        CountDownLatch queuedWorkDrained = new CountDownLatch(owner.queueCapacity());
        try {
            assertTrue(owner.tryExecute(() -> {
                ownerEntered.countDown();
                await(releaseOwner);
            }));
            assertTrue(ownerEntered.await(2, TimeUnit.SECONDS));

            while (owner.queuedTasks() < owner.queueCapacity()) {
                BoundedIoLane.SubmissionResult result = owner.tryExecuteWithResult(queuedWorkDrained::countDown);
                assertEquals(BoundedIoLane.SubmissionResult.ACCEPTED, result);
            }
            assertEquals(owner.queueCapacity(), owner.queuedTasks());
            assertFalse(sendRequest(client, 100), "a genuinely full owner queue rejects pump submission");
            assertTrue(client.isReady(), "the hard saturation is retired on owner maintenance, not on the caller");

            releaseOwner.countDown();
            assertTrue(queuedWorkDrained.await(2, TimeUnit.SECONDS));
            queueMaintenanceForTests(client);
            assertTrue(client.awaitOwnerBarrierForTests(2, TimeUnit.SECONDS));
            assertEquals(0, saturationReported.getCount());
            assertFalse(client.isReady());
        } finally {
            releaseOwner.countDown();
            client.close();
        }
    }

    @Test
    void fullBoundedDeferredQueueRejectsAckExplicitlyInsteadOfDroppingIt() throws Exception {
        ControlledSocket socket = new ControlledSocket();
        AuthenticatedClient authenticated = authenticatedClient(socket);
        BoundCompanionClient client = authenticated.client();
        AtomicInteger callbacks = new AtomicInteger();
        try {
            stopMaintenanceForTests(client);
            assertTrue(client.awaitOwnerBarrierForTests(2, TimeUnit.SECONDS),
                    "drain authenticated transport work before measuring the outbound queue bound");

            assertTrue(sendRequest(client, 0));
            socket.awaitFrameCount(2);
            assertTrue(client.awaitOwnerBarrierForTests(2, TimeUnit.SECONDS),
                    "drain the initial send-pump task while its socket send remains pending");
            assertEquals("dialogue.request", frameType(socket.frameAt(1)),
                    "the first request is the one active send before waiters are added");
            for (int index = 1; index <= BoundCompanionClient.MAX_QUEUED_OUTBOUND_FRAMES; index++) {
                assertTrue(sendRequest(client, index));
            }
            assertTrue(client.isReady(), "filling the bounded waiter queue does not retire the connection");

            assertEquals(BoundCompanionClient.AcknowledgementSubmission.REJECTED,
                    client.sendDialogueAcknowledgement("request-ack", "player", "message-ack", "Hello",
                            ignored -> callbacks.incrementAndGet()));
            assertEquals(0, callbacks.get(), "rejection is synchronous and must be handled by the conversation owner");
            assertEquals(2, socket.frameCount(), "one active plus sixteen waiting; rejected ack was not written");
            assertTrue(client.isReady(), "a full client-side queue is an admission rejection, not peer unavailability");
        } finally {
            client.close();
        }
    }

    @Test
    void outboundQueueLockContentionIsAnExplicitRejectionWithoutRetiringReadiness() throws Exception {
        ControlledSocket socket = new ControlledSocket();
        BoundCompanionClient client = authenticatedClient(socket).client();
        ReentrantLock outboundLock = outboundQueueLockForTests(client);
        CountDownLatch lockHeld = new CountDownLatch(1);
        CountDownLatch releaseLock = new CountDownLatch(1);
        Thread holder = new Thread(() -> {
            outboundLock.lock();
            try {
                lockHeld.countDown();
                await(releaseLock);
            } finally {
                outboundLock.unlock();
            }
        }, "worldspeaker-test-outbound-lock-holder");
        try {
            stopMaintenanceForTests(client);
            assertTrue(client.awaitOwnerBarrierForTests(2, TimeUnit.SECONDS),
                    "drain authenticated transport work before forcing admission contention");
            holder.start();
            assertTrue(lockHeld.await(2, TimeUnit.SECONDS), "the other thread holds outbound admission briefly");

            assertFalse(sendRequest(client, 99), "nonblocking outbound admission reports lock contention synchronously");
            assertTrue(client.isReady(), "a rejected frame is not a peer/readiness failure");
            assertEquals(1, socket.frameCount(), "a synchronously rejected frame is not queued for a later send");

            releaseLock.countDown();
            holder.join(2_000);
            assertFalse(holder.isAlive(), "the deterministic contention holder exits");
            assertTrue(client.awaitOwnerBarrierForTests(2, TimeUnit.SECONDS));
            assertEquals(1, socket.frameCount(), "the rejected frame was never accepted or replayed");
            assertTrue(sendRequest(client, 100), "admission succeeds once the transient lock contention clears");
            socket.awaitFrameCount(2);
            assertEquals("dialogue.request", frameType(socket.frameAt(1)));
            assertTrue(client.isReady());
        } finally {
            releaseLock.countDown();
            holder.join(2_000);
            client.close();
        }
    }

    @Test
    void acceptedAcknowledgementReportsPermanentSocketFailureWithoutRetrying() {
        ControlledSocket socket = new ControlledSocket();
        AuthenticatedClient authenticated = authenticatedClient(socket);
        BoundCompanionClient client = authenticated.client();
        AtomicReference<BoundCompanionClient.AcknowledgementDelivery> delivery = new AtomicReference<>();
        AtomicInteger callbacks = new AtomicInteger();
        CountDownLatch callbackFinished = new CountDownLatch(1);
        try {
            assertTrue(sendRequest(client, 0));
            socket.awaitFrameCount(2);
            assertEquals(BoundCompanionClient.AcknowledgementSubmission.ACCEPTED,
                    client.sendDialogueAcknowledgement("request-ack", "player", "message-ack", "Hello", result -> {
                        delivery.set(result);
                        callbacks.incrementAndGet();
                        callbackFinished.countDown();
                    }));

            socket.complete(1); // the prior send completes and the deferred acknowledgement starts once
            socket.awaitFrameCount(3);
            assertEquals("dialogue.ack", frameType(socket.frameAt(2)));
            socket.fail(2, new IllegalStateException("fake permanent send failure"));

            assertTrue(await(callbackFinished));
            assertEquals(BoundCompanionClient.AcknowledgementDelivery.FAILED, delivery.get());
            assertEquals(1, callbacks.get());
            assertFalse(client.isReady());
            assertEquals(3, socket.frameCount(), "a failed send is not retried");
        } finally {
            client.close();
        }
    }

    @Test
    void disconnectWhileAckAndCancelAreDeferredFailsAckDeliveryExactlyOnce() {
        ControlledSocket socket = new ControlledSocket();
        AuthenticatedClient authenticated = authenticatedClient(socket);
        BoundCompanionClient client = authenticated.client();
        AtomicReference<BoundCompanionClient.AcknowledgementDelivery> delivery = new AtomicReference<>();
        AtomicInteger callbacks = new AtomicInteger();
        try {
            assertTrue(sendRequest(client, 0));
            socket.awaitFrameCount(2);
            assertEquals(BoundCompanionClient.AcknowledgementSubmission.ACCEPTED,
                    client.sendDialogueAcknowledgement("request-ack", "player", "message-ack", "Hello", result -> {
                        delivery.set(result);
                        callbacks.incrementAndGet();
                    }));
            assertTrue(queueCancel(client));

            client.onClose(socket.socket, WebSocket.NORMAL_CLOSURE, "test disconnect");

            assertEquals(BoundCompanionClient.AcknowledgementDelivery.FAILED, delivery.get());
            assertEquals(1, callbacks.get());
            assertEquals(2, socket.frameCount(), "neither deferred frame can leak onto the disconnected socket");
            socket.complete(1);
            assertEquals(1, callbacks.get(), "late send completion cannot resurrect a retired acknowledgement");
        } finally {
            client.close();
        }
    }

    @Test
    void queuedFrameResidenceDeadlineRetiresConnectionAndFailsAcknowledgementOnce() throws InterruptedException {
        ControlledSocket socket = new ControlledSocket();
        BoundCompanionClient client = authenticatedClient(socket, 100, 2_000).client();
        CountDownLatch deliveryFinished = new CountDownLatch(1);
        AtomicReference<BoundCompanionClient.AcknowledgementDelivery> delivery = new AtomicReference<>();
        AtomicInteger callbacks = new AtomicInteger();
        try {
            assertTrue(sendRequest(client, 0)); // holds the sole active send
            socket.awaitFrameCount(2);
            assertEquals(BoundCompanionClient.AcknowledgementSubmission.ACCEPTED,
                    client.sendDialogueAcknowledgement("request-ack", "player", "message-ack", "Hello", result -> {
                        delivery.set(result);
                        callbacks.incrementAndGet();
                        deliveryFinished.countDown();
                    }));

            assertTrue(deliveryFinished.await(2, TimeUnit.SECONDS), "queued residence is bounded");
            assertEquals(BoundCompanionClient.AcknowledgementDelivery.FAILED, delivery.get());
            assertEquals(1, callbacks.get());
            assertFalse(client.isReady());
            assertEquals(1, socket.abortCount());
            socket.complete(1); // late completion cannot restore or replay retired work
            assertEquals(1, callbacks.get());
        } finally {
            client.close();
        }
    }

    @Test
    void activeSendDeadlineRetiresGenerationAndFailsAllAcceptedFramesOnce() throws InterruptedException {
        ControlledSocket socket = new ControlledSocket();
        BoundCompanionClient client = authenticatedClient(socket, 2_000, 100).client();
        CountDownLatch deliveryFinished = new CountDownLatch(1);
        AtomicReference<BoundCompanionClient.AcknowledgementDelivery> delivery = new AtomicReference<>();
        AtomicInteger callbacks = new AtomicInteger();
        AtomicInteger deliveryCallbackSequence = new AtomicInteger();
        try {
            assertTrue(sendRequest(client, 0));
            socket.awaitFrameCount(2);
            assertEquals(BoundCompanionClient.AcknowledgementSubmission.ACCEPTED,
                    client.sendDialogueAcknowledgement("request-ack", "player", "message-ack", "Hello", result -> {
                        delivery.set(result);
                        callbacks.incrementAndGet();
                        deliveryCallbackSequence.set(socket.nextEventSequence());
                        deliveryFinished.countDown();
                    }));

            assertTrue(deliveryFinished.await(2, TimeUnit.SECONDS), "active JDK send has a finite deadline");
            assertEquals(BoundCompanionClient.AcknowledgementDelivery.FAILED, delivery.get());
            assertEquals(1, callbacks.get());
            assertFalse(client.isReady());
            assertTrue(socket.awaitAbort(), "the failed-delivery callback can publish before the owner aborts the socket");
            assertTrue(deliveryCallbackSequence.get() < socket.abortSequence(),
                    "connection-loss delivery is published before the subsequent socket abort");
            assertEquals(1, socket.abortCount());
            assertEquals(2, socket.frameCount(), "the queued acknowledgement is never replayed on the failed generation");
            socket.complete(1);
            assertEquals(1, callbacks.get());
        } finally {
            client.close();
        }
    }

    @Test
    void staleCompletionFromDisconnectedGenerationCannotAffectReauthenticatedSocket() throws Exception {
        ControlledSocket oldSocket = new ControlledSocket();
        AuthenticatedClient authenticated = authenticatedClient(oldSocket);
        BoundCompanionClient client = authenticated.client();
        ControlledSocket newSocket = new ControlledSocket();
        try {
            stopMaintenanceForTests(client);
            assertTrue(client.awaitOwnerBarrierForTests(2, TimeUnit.SECONDS),
                    "drain authenticated transport work before reconnect assertions");
            assertTrue(sendRequest(client, 0));
            oldSocket.awaitFrameCount(2);
            client.onClose(oldSocket.socket, WebSocket.NORMAL_CLOSURE, "reconnect test");
            assertFalse(client.isReady());

            client.onOpen(newSocket.socket);
            authenticateSocket(client, newSocket, authenticated.config(), authenticated.peer());
            assertTrue(client.awaitOwnerBarrierForTests(2, TimeUnit.SECONDS),
                    "drain the reauthenticated hello completion before stale-generation assertions");
            assertTrue(client.isReady());

            oldSocket.complete(1); // generation-tagged completion must be ignored
            assertTrue(client.awaitOwnerBarrierForTests(2, TimeUnit.SECONDS),
                    "drain the stale completion before admitting work on the new generation");
            assertTrue(client.isReady());
            assertTrue(sendRequest(client, 1));
            newSocket.awaitFrameCount(2);
            assertEquals(2, newSocket.frameCount(), "only the new handshake and new request reached the new socket");
            assertEquals("dialogue.request", frameType(newSocket.frameAt(1)));
        } finally {
            client.close();
        }
    }

    private static boolean sendRequest(BoundCompanionClient client, int index) {
        return client.sendDialogueRequest("request-" + index, "player", "Player", "1", "turn-" + index, null, null);
    }

    private static boolean queueCancel(BoundCompanionClient client) {
        String requestId = java.util.UUID.randomUUID().toString();
        String invocationId;
        do { invocationId = java.util.UUID.randomUUID().toString(); }
        while (requestId.equals(invocationId));
        return client.cancelDialogueRequest(requestId, invocationId, java.util.UUID.randomUUID().toString(),
                client.connectionGenerationForTests(), ignored -> {});
    }

    private static String frameType(String frame) {
        return JsonParser.parseString(frame).getAsJsonObject().get("type").getAsString();
    }

    private static AuthenticatedClient authenticatedClient(ControlledSocket socket) {
        return authenticatedClient(socket, 10_000, 10_000);
    }

    private static AuthenticatedClient authenticatedClient(ControlledSocket socket, long residenceTimeoutMs, long sendTimeoutMs) {
        return authenticatedClient(socket, residenceTimeoutMs, sendTimeoutMs, ignored -> {});
    }

    private static AuthenticatedClient authenticatedClient(ControlledSocket socket, long residenceTimeoutMs, long sendTimeoutMs,
                                                            java.util.function.Consumer<String> diagnostic) {
        byte[] secret = new byte[32];
        for (int index = 0; index < secret.length; index++) secret[index] = (byte) index;
        ConfiguredBinding binding = new ConfiguredBinding("server-test", "world-test", "character-test", "agent-test");
        CompanionConfig config = new CompanionConfig(URI.create("ws://127.0.0.1:18473"), secret, binding, "Hello {player}",
                "Archivist", org.worldspeaker.compat.InteractionPolicy.denyAll());
        RuntimeFacts local = new RuntimeFacts(
                DeploymentFacts.MINECRAFT_VERSION, DeploymentFacts.FABRIC_LOADER_VERSION, DeploymentFacts.FABRIC_API_VERSION,
                Integer.parseInt(DeploymentFacts.JAVA_MAJOR), DeploymentFacts.JAVA_VENDOR, DeploymentFacts.JAVA_RUNTIME_VERSION);
        PeerFacts peer = new PeerFacts(
                DeploymentFacts.DEPLOYMENT_ID, DeploymentFacts.MANIFEST_SHA256, DeploymentFacts.PROTOCOL,
                DeploymentFacts.OPENCLAW_VERSION, DeploymentFacts.NODE_VERSION, DeploymentFacts.COMPANION_VERSION,
                binding.serverId(), binding.worldId(), binding.characterId(), binding.agentId());
        CompatibilityGate gate = new CompatibilityGate(binding);
        gate.checkLocal(local);
        BoundCompanionClient client = new BoundCompanionClient(
                config, local, gate, ignored -> {}, diagnostic, residenceTimeoutMs, sendTimeoutMs);
        client.onOpen(socket.socket);
        authenticateSocket(client, socket, config, peer);
        assertTrue(client.isReady());
        try {
            assertTrue(client.awaitOwnerBarrierForTests(2, TimeUnit.SECONDS),
                    "complete the authenticated hello send before queue-capacity assertions");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted while draining the transport owner", interrupted);
        }
        return new AuthenticatedClient(client, config, local, peer);
    }

    private static ReentrantLock ownerSubmissionLock(BoundCompanionClient client) throws ReflectiveOperationException {
        Field lockField = ownerLaneForTests(client).getClass().getDeclaredField("submissionLock");
        lockField.setAccessible(true);
        return (ReentrantLock) lockField.get(ownerLaneForTests(client));
    }

    private static ReentrantLock outboundQueueLockForTests(BoundCompanionClient client) throws ReflectiveOperationException {
        Field lockField = BoundCompanionClient.class.getDeclaredField("outboundLock");
        lockField.setAccessible(true);
        return (ReentrantLock) lockField.get(client);
    }

    private static BoundedIoLane ownerLaneForTests(BoundCompanionClient client) throws ReflectiveOperationException {
        Field ownerField = BoundCompanionClient.class.getDeclaredField("owner");
        ownerField.setAccessible(true);
        return (BoundedIoLane) ownerField.get(client);
    }

    private static void stopMaintenanceForTests(BoundCompanionClient client) throws ReflectiveOperationException {
        Field schedulerField = BoundCompanionClient.class.getDeclaredField("scheduler");
        schedulerField.setAccessible(true);
        ((ScheduledThreadPoolExecutor) schedulerField.get(client)).shutdownNow();
    }

    private static void queueMaintenanceForTests(BoundCompanionClient client) throws ReflectiveOperationException {
        Method method = BoundCompanionClient.class.getDeclaredMethod("queueMaintenance");
        method.setAccessible(true);
        method.invoke(client);
    }

    private static void authenticateSocket(BoundCompanionClient client, ControlledSocket socket,
                                           CompanionConfig config, PeerFacts peer) {
        client.onText(socket.socket, challengeFrame(config, CHALLENGE, peer).toString(), true).toCompletableFuture().join();
        socket.awaitFrameCount(1);
        socket.complete(0);
        JsonObject hello = JsonParser.parseString(socket.frameAt(0)).getAsJsonObject();
        client.onText(socket.socket, welcomeFrame(config, CHALLENGE, hello.get("nonce").getAsString(), peer).toString(), true)
                .toCompletableFuture().join();
    }

    private static JsonObject challengeFrame(CompanionConfig config, String challenge, PeerFacts peer) {
        JsonObject frame = peerFields(peer);
        frame.addProperty("type", "challenge");
        frame.addProperty("challenge", challenge);
        frame.addProperty("proof", PeerHandshake.serverChallengeProof(config, challenge, peer));
        return frame;
    }

    private static JsonObject welcomeFrame(CompanionConfig config, String challenge, String nonce, PeerFacts peer) {
        JsonObject frame = peerFields(peer);
        frame.addProperty("type", "welcome");
        frame.addProperty("challenge", challenge);
        frame.addProperty("nonce", nonce);
        frame.addProperty("proof", PeerHandshake.serverProof(config, challenge, nonce, peer, peer.nodeVersion()));
        return frame;
    }

    private static JsonObject peerFields(PeerFacts peer) {
        JsonObject frame = new JsonObject();
        frame.addProperty("deploymentId", peer.deploymentId());
        frame.addProperty("manifestSha256", peer.manifestSha256());
        frame.addProperty("protocol", peer.protocol());
        frame.addProperty("hostVersion", peer.hostVersion());
        frame.addProperty("nodeVersion", peer.nodeVersion());
        frame.addProperty("companionVersion", peer.companionVersion());
        frame.addProperty("serverId", peer.serverId());
        frame.addProperty("worldId", peer.worldId());
        frame.addProperty("characterId", peer.characterId());
        frame.addProperty("agentId", peer.agentId());
        frame.addProperty("configurationHandshakeVersion", peer.configurationHandshakeVersion());
        frame.addProperty("configSetSha256", peer.configSetSha256());
        return frame;
    }

    private static WebSocket fakeWebSocket(AtomicReference<String> sentFrame) {
        return (WebSocket) Proxy.newProxyInstance(
                WebSocket.class.getClassLoader(), new Class<?>[]{WebSocket.class}, (proxy, method, args) -> {
                    if ("sendText".equals(method.getName())) sentFrame.set(args[0].toString());
                    if ("request".equals(method.getName())) return proxy;
                    if ("sendText".equals(method.getName()) || "sendClose".equals(method.getName())) {
                        return CompletableFuture.completedFuture(proxy);
                    }
                    if (method.getReturnType() == boolean.class) return false;
                    if (method.getReturnType() == long.class) return 0L;
                    return null;
                });
    }

    private static void awaitReference(AtomicReference<?> reference) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (reference.get() == null && System.nanoTime() < deadline) Thread.onSpinWait();
        assertTrue(reference.get() != null, "owner lane sends the handshake response within its bounded test deadline");
    }

    private static boolean await(CountDownLatch latch) {
        try { return latch.await(3, TimeUnit.SECONDS); }
        catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted waiting for owner callback", interrupted);
        }
    }

    private static void awaitThreadState(Thread thread, Thread.State expected) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (thread.getState() != expected && thread.isAlive() && System.nanoTime() < deadline) Thread.onSpinWait();
        assertEquals(expected, thread.getState(), "the waiter contends on the publication monitor");
    }

    private record AuthenticatedClient(BoundCompanionClient client, CompanionConfig config, RuntimeFacts local, PeerFacts peer) {}

    private static final class ControlledSocket {
        private final Object recordMonitor = new Object();
        private final List<SendRecord> records = new ArrayList<>();
        private int maxConcurrentSends;
        private PublicationPause nextPublicationPause;
        private int aborts;
        private final AtomicInteger eventSequence = new AtomicInteger();
        private final AtomicInteger abortSequence = new AtomicInteger();
        private final CountDownLatch abortObserved = new CountDownLatch(1);
        private final WebSocket socket = (WebSocket) Proxy.newProxyInstance(
                WebSocket.class.getClassLoader(), new Class<?>[]{WebSocket.class}, (proxy, method, args) -> {
                    if ("sendText".equals(method.getName())) {
                        SendRecord record = new SendRecord(args[0].toString(), new CompletableFuture<>());
                        synchronized (recordMonitor) {
                            int pending = (int) records.stream().filter(existing -> !existing.completion().isDone()).count();
                            maxConcurrentSends = Math.max(maxConcurrentSends, pending + 1);
                            PublicationPause pause = nextPublicationPause;
                            nextPublicationPause = null;
                            if (pause != null) {
                                pause.entered().countDown();
                                if (!await(pause.release())) {
                                    throw new AssertionError("timed out waiting to release controlled send publication");
                                }
                            }
                            records.add(record);
                            recordMonitor.notifyAll();
                        }
                        return record.completion();
                    }
                    if ("request".equals(method.getName())) return proxy;
                    if ("sendClose".equals(method.getName())) return CompletableFuture.completedFuture(proxy);
                    if ("abort".equals(method.getName())) {
                        synchronized (recordMonitor) {
                            aborts++;
                            abortSequence.set(eventSequence.incrementAndGet());
                            recordMonitor.notifyAll();
                        }
                        abortObserved.countDown();
                        return null;
                    }
                    if (method.getReturnType() == boolean.class) return false;
                    if (method.getReturnType() == long.class) return 0L;
                    return null;
                });

        private void pauseNextPublicationForTests(CountDownLatch entered, CountDownLatch release) {
            synchronized (recordMonitor) {
                if (nextPublicationPause != null) throw new IllegalStateException("a publication pause is already configured");
                nextPublicationPause = new PublicationPause(entered, release);
            }
        }

        private int frameCount() {
            synchronized (recordMonitor) {
                return records.size();
            }
        }

        private String frameAt(int index) {
            synchronized (recordMonitor) {
                return records.get(index).frame();
            }
        }

        private int maxConcurrentSends() {
            synchronized (recordMonitor) {
                return maxConcurrentSends;
            }
        }

        private int abortCount() {
            synchronized (recordMonitor) {
                return aborts;
            }
        }

        private boolean awaitAbort() throws InterruptedException {
            return abortObserved.await(3, TimeUnit.SECONDS);
        }

        private int nextEventSequence() {
            return eventSequence.incrementAndGet();
        }

        private int abortSequence() {
            return abortSequence.get();
        }

        private List<SendRecord> snapshot() {
            synchronized (recordMonitor) {
                return List.copyOf(records);
            }
        }

        private void complete(int index) {
            synchronized (recordMonitor) {
                records.get(index).completion().complete(socket);
                recordMonitor.notifyAll();
            }
        }

        private void fail(int index, Throwable failure) {
            synchronized (recordMonitor) {
                records.get(index).completion().completeExceptionally(failure);
                recordMonitor.notifyAll();
            }
        }

        private void awaitFrameCount(int expected) {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            synchronized (recordMonitor) {
                while (records.size() < expected) {
                    long remaining = deadline - System.nanoTime();
                    if (remaining <= 0) throw new AssertionError("timed out waiting for outbound frame " + expected);
                    try {
                        TimeUnit.NANOSECONDS.timedWait(recordMonitor, remaining);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new AssertionError("interrupted waiting for outbound frame", interrupted);
                    }
                }
            }
        }

        private record SendRecord(String frame, CompletableFuture<WebSocket> completion) {}
        private record PublicationPause(CountDownLatch entered, CountDownLatch release) {}
    }
}
