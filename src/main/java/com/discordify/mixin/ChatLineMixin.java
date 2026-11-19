package com.discordify.mixin;

import com.discordify.chat.EmbedManager;
import net.minecraft.client.GuiMessage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(targets = "net.minecraft.client.gui.components.ChatComponent$1")
public abstract class ChatLineMixin {
    @Inject(method = "accept(Lnet/minecraft/client/GuiMessage$Line;IF)V", at = @At("HEAD"))
    private void captureOpacity(GuiMessage.Line line, int index, float opacity, CallbackInfo ci) {
        EmbedManager.INSTANCE.onLineOpacity(line.addedTime(), opacity);
    }
}
