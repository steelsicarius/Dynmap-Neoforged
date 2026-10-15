package org.dynmap.neoforge_26_3.event;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Minimal event holder for the callbacks that are fired from our mixins
 * (block changes and sign text changes), replacing the Fabric
 * EventFactory-based events of the upstream Fabric module.
 *
 * Listeners must tolerate being invoked on the server thread.
 */
public final class DynmapEvents {
    private DynmapEvents() {
    }

    @FunctionalInterface
    public interface BlockChangeCallback {
        void onBlockChange(Level level, BlockPos pos);
    }

    @FunctionalInterface
    public interface SignChangeCallback {
        void onSignChange(ServerLevel level, BlockPos pos, String[] lines, ServerPlayer player, boolean front);
    }

    private static final List<BlockChangeCallback> BLOCK_CHANGE_LISTENERS = new CopyOnWriteArrayList<>();
    private static final List<SignChangeCallback> SIGN_CHANGE_LISTENERS = new CopyOnWriteArrayList<>();

    public static void registerBlockChange(BlockChangeCallback callback) {
        BLOCK_CHANGE_LISTENERS.add(callback);
    }

    public static void registerSignChange(SignChangeCallback callback) {
        SIGN_CHANGE_LISTENERS.add(callback);
    }

    public static void clearListeners() {
        BLOCK_CHANGE_LISTENERS.clear();
        SIGN_CHANGE_LISTENERS.clear();
    }

    /** Called from LevelChunkMixin - do not rename (referenced by comment there). */
    public static void fireBlockChange(Level level, BlockPos pos) {
        for (BlockChangeCallback cb : BLOCK_CHANGE_LISTENERS) {
            cb.onBlockChange(level, pos);
        }
    }

    /** Called from ServerGamePacketListenerImplMixin - do not rename. */
    public static void fireSignChange(ServerLevel level, BlockPos pos, String[] lines, ServerPlayer player, boolean front) {
        for (SignChangeCallback cb : SIGN_CHANGE_LISTENERS) {
            cb.onSignChange(level, pos, lines, player, front);
        }
    }
}
