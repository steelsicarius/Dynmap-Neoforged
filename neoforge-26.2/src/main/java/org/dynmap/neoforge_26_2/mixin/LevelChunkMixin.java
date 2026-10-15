package org.dynmap.neoforge_26_2.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import org.dynmap.neoforge_26_2.event.DynmapEvents;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Port of the upstream Fabric WorldChunkMixin: fires a block-change callback
 * every time a block state is successfully written into a full chunk, which
 * dynmap uses to trigger map tile updates.
 *
 * This mixin is common code (the class exists on both sides); the consumer
 * (DynmapPlugin.WorldTracker) filters to server levels.
 */
@Mixin(LevelChunk.class)
public abstract class LevelChunkMixin {
    @Inject(method = "setBlockState", at = @At("RETURN"))
    public void dynmap$setBlockState(BlockPos pos, BlockState state, int flags, CallbackInfoReturnable<BlockState> info) {
        if (info.getReturnValue() != null) {
            DynmapEvents.fireBlockChange(((LevelChunk) (Object) this).getLevel(), pos);
        }
    }
}
