package org.worldspeaker.mixin;

import net.minecraft.server.command.CommandOutput;
import net.minecraft.server.command.ServerCommandSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Narrow server-only access to the original output retained by vanilla-derived command sources. */
@Mixin(ServerCommandSource.class)
public interface ServerCommandSourceOutputAccessor {
    @Accessor("output")
    CommandOutput worldspeaker$getCommandOutput();
}
