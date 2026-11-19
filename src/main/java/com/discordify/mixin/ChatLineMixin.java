package com.discordify.mixin;

import com.discordify.chat.EmbedManager;
import net.minecraft.client.GuiMessage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(targets = "net.minecraft.client.gui.components.ChatComponent$1")
public abstract class ChatLineMixin {
    @Inject(method = "accept(Lnet/minecraft/client/GuiMessage$Line;IF)V", at = @At("HEAD"))
    private void captureOpacity(GuiMessage.Line line, int index, float opacity, CallbackInfo ci) {
        EmbedManager.INSTANCE.onLineOpacity(line.addedTime(), opacity);
        EmbedManager.INSTANCE.markLineDrawn(line.addedTime());
    }

    // ponytail: `index` is the row slot from the chat bottom, not pixels; shifting it by whole
    // rows keeps the chat panel untouched and every line exactly on the vanilla line grid.
    @ModifyVariable(method = "accept(Lnet/minecraft/client/GuiMessage$Line;IF)V",
            at = @At("HEAD"), argsOnly = true)
    private int reserveEmbedRows(int index) {
        return index + EmbedManager.INSTANCE.shiftRowsForSlot(index);
    }
}
