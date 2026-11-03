package org.worldspeaker;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import net.minecraft.entity.Entity;
import net.minecraft.server.command.CommandOutput;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.math.Vec2f;
import net.minecraft.util.math.Vec3d;
import org.junit.jupiter.api.Test;
import org.worldspeaker.bootstrap.AskCommand;
import org.worldspeaker.bootstrap.CommandSourceOutputProvenance;
import org.worldspeaker.bootstrap.InteractionIngress;
import org.worldspeaker.mixin.ServerCommandSourceOutputAccessor;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ServerCommandSourceProvenanceTest {
    @Test
    void vanillaRedirectAndForkRefuseToTheRetainedOutputBeforeReadingTheSubstitutedEntity() throws Exception {
        RecordingOutput originalOutput = new RecordingOutput();
        ServerCommandSource original = new SourceReadGuard(originalOutput);
        ServerCommandSource unchangedEntity = original.withEntity(null);

        assertSame(original, unchangedEntity);
        assertEquals("Server", unchangedEntity.getName());
        assertUntrusted(WorldspeakerServer.resolveCommandSource(unchangedEntity,
                "execute as target run worldspeaker ask private-sentinel"));
        assertUntrusted(WorldspeakerServer.resolveCommandSource(unchangedEntity, "worldspeaker ask private-sentinel"));
        assertTrue(CommandSourceOutputProvenance.outputIsCurrentEntity(originalOutput, originalOutput),
                "the original output identifies its own issuer entity");
        assertFalse(CommandSourceOutputProvenance.outputIsCurrentEntity(originalOutput, new Object()),
                "a retained issuer output cannot attest a substituted target entity");

        CommandDispatcher<ServerCommandSource> dispatcher = new CommandDispatcher<>();
        AskCommand.register(dispatcher);
        dispatcher.register(LiteralArgumentBuilder.<ServerCommandSource>literal("execute")
                .then(LiteralArgumentBuilder.<ServerCommandSource>literal("as")
                        .then(RequiredArgumentBuilder.<ServerCommandSource, String>argument("target", StringArgumentType.word())
                                .then(LiteralArgumentBuilder.<ServerCommandSource>literal("run")
                                        .redirect(dispatcher.getRoot(), context -> context.getSource().withEntity(null))))));
        dispatcher.register(LiteralArgumentBuilder.<ServerCommandSource>literal("fork")
                .fork(dispatcher.getRoot(), context -> List.of(context.getSource().withEntity(null))));

        assertEquals(0, dispatcher.execute("execute as target run worldspeaker ask private-sentinel", original));
        dispatcher.execute("fork worldspeaker ask private-sentinel", original);

        assertTrue(originalOutput.messages.isEmpty(),
                "commands without packet-owned receipt evidence must fail closed before resolving or publishing a source");
    }

    private static void assertUntrusted(InteractionIngress.SourceFacts facts) {
        assertFalse(facts.playerSource());
        assertFalse(facts.authenticatedPlayer());
        assertNull(facts.playerId());
        assertNull(facts.playerName());
        assertNull(facts.worldId());
        assertNull(facts.player());
        assertNull(facts.connection());
    }

    private static final class RecordingOutput implements CommandOutput {
        private final List<String> messages = new ArrayList<>();

        @Override public void sendMessage(Text message) { messages.add(message.getString()); }
        @Override public boolean shouldReceiveFeedback() { return false; }
        @Override public boolean shouldTrackOutput() { return true; }
        @Override public boolean shouldBroadcastConsoleToOps() { return false; }
    }

    private static final class SourceReadGuard extends ServerCommandSource implements ServerCommandSourceOutputAccessor {
        private final CommandOutput commandOutput;

        private SourceReadGuard(CommandOutput output) {
            super(output, Vec3d.ZERO, Vec2f.ZERO, null, 4, "Server", Text.literal("Server"), null, null);
            this.commandOutput = output;
        }

        @Override
        public CommandOutput worldspeaker$getCommandOutput() {
            return commandOutput;
        }

        @Override
        public Entity getEntity() {
            throw new AssertionError("untrusted command sources must be refused before reading the substituted entity");
        }

        @Override
        public ServerPlayerEntity getPlayer() {
            throw new AssertionError("untrusted command sources must be refused before resolving a player target");
        }
    }
}
