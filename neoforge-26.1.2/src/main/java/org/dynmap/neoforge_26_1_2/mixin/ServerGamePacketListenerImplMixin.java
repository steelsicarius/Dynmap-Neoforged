package org.dynmap.neoforge_26_1_2.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.FilterMask;
import net.minecraft.network.protocol.game.ServerboundSignUpdatePacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.server.network.FilteredText;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import org.dynmap.neoforge_26_1_2.event.DynmapEvents;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Arrays;
import java.util.List;

/**
 * Port of the upstream Fabric ServerPlayNetworkHandlerMixin: lets dynmap see
 * (and, in principle, rewrite) sign text before it is applied, replicating the
 * vanilla updateSignText body with the raw lines routed through the
 * SIGN_CHANGE callback first.
 */
@Mixin(ServerGamePacketListenerImpl.class)
public abstract class ServerGamePacketListenerImplMixin {
    @Inject(method = "updateSignText", at = @At("HEAD"), cancellable = true)
    private void dynmap$updateSignText(ServerboundSignUpdatePacket packet, List<FilteredText> signText, CallbackInfo ci) {
        ServerPlayer player = ((ServerGamePacketListenerImpl) (Object) this).player;
        player.resetLastActionTime();
        ServerLevel level = player.level();
        BlockPos pos = packet.getPos();
        if (level.hasChunkAt(pos) && level.getBlockEntity(pos) instanceof SignBlockEntity sign) {
            // Pull the raw text from the (filtered) input.
            String[] rawTexts = new String[4];
            for (int i = 0; i < signText.size(); i++) {
                rawTexts[i] = signText.get(i).raw();
            }

            // Fire the event - listeners may rewrite rawTexts in place.
            DynmapEvents.fireSignChange(level, pos, rawTexts, player, packet.isFrontText());

            // Rebuild the sign text list with the (possibly edited) values and
            // apply it ourselves, mirroring the vanilla body.
            List<FilteredText> newSignText = Arrays.stream(rawTexts)
                    .map(raw -> new FilteredText(raw == null ? "" : raw, FilterMask.PASS_THROUGH))
                    .toList();
            sign.updateSignText(player, packet.isFrontText(), newSignText);
        }
        // We replicated the whole vanilla body: cancel the original call.
        ci.cancel();
    }
}
