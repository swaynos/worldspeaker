package org.worldspeaker.bootstrap;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.server.command.ServerCommandSource;
import org.worldspeaker.WorldspeakerServer;

/** Discoverable explicit command; production and offline tests execute this same Brigadier tree. */
public final class AskCommand {
    private AskCommand() {}

    public static void register(CommandDispatcher<ServerCommandSource> dispatcher) {
        register(dispatcher, WorldspeakerServer.commandAccess());
    }

    static <S> void register(CommandDispatcher<S> dispatcher, Access<S> access) {
        LiteralArgumentBuilder<S> root = LiteralArgumentBuilder.<S>literal("worldspeaker")
                .executes(context -> execute(context, access, InteractionIngress.Route.HELP, null))
                .then(LiteralArgumentBuilder.<S>literal("help")
                        .executes(context -> execute(context, access, InteractionIngress.Route.HELP, null))
                        .then(RequiredArgumentBuilder.<S, String>argument("tail", StringArgumentType.greedyString())
                                .executes(context -> execute(context, access, InteractionIngress.Route.INVALID,
                                        StringArgumentType.getString(context, "tail")))))
                .then(LiteralArgumentBuilder.<S>literal("ask")
                        .executes(context -> execute(context, access, InteractionIngress.Route.ASK, ""))
                        .then(RequiredArgumentBuilder.<S, String>argument("text", StringArgumentType.greedyString())
                                .executes(context -> execute(context, access, InteractionIngress.Route.ASK,
                                        StringArgumentType.getString(context, "text")))))
                .then(LiteralArgumentBuilder.<S>literal("end")
                        .executes(context -> execute(context, access, InteractionIngress.Route.END, null)))
                .then(RequiredArgumentBuilder.<S, String>argument("unknown", StringArgumentType.greedyString())
                        .executes(context -> execute(context, access, InteractionIngress.Route.INVALID,
                                StringArgumentType.getString(context, "unknown"))));
        dispatcher.register(root);
    }

    private static <S> int execute(CommandContext<S> context, Access<S> access,
                                   InteractionIngress.Route route, String argument) {
        InteractionIngress.Captured captured = access.capture();
        String input = context.getInput();
        return access.execute(context.getSource(), input, route, argument, captured);
    }

    public interface Access<S> {
        InteractionIngress.Captured capture();
        int execute(S source, String input, InteractionIngress.Route route, String text,
                    InteractionIngress.Captured captured);
    }
}
