package com.discordify.mixin;

import com.discordify.chat.EmbedManager;
import com.discordify.chat.GifUrlDetector;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.minecraft.client.GuiMessage;
import net.minecraft.client.GuiMessageTag;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MessageSignature;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ChatComponent.class)
public abstract class ChatComponentMixin {
    @Shadow private List<GuiMessage> allMessages;
    @Shadow private List<GuiMessage.Line> trimmedMessages;
    @Shadow private int chatScrollbarPos;

    @Shadow private int getLineHeight() { return 0; }

    @Shadow private int getWidth() { return 0; }

    @Shadow private double getScale() { return 0; }

    private String pendingUrl;
    private int pendingUrlX;

    @ModifyVariable(method = "addMessage(Lnet/minecraft/network/chat/Component;Lnet/minecraft/network/chat/MessageSignature;Lnet/minecraft/client/GuiMessageTag;)V",
            at = @At("HEAD"), argsOnly = true)
    private Component stripGifUrl(Component message) {
        Optional<String> found = GifUrlDetector.findGifUrl(message);
        if (found.isEmpty()) return message;
        String full = message.getString();
        String raw = found.get();
        int start = full.indexOf(raw);
        if (start < 0) {
            // ponytail: share links resolve to a different CDN URL, so strip the literal link token instead.
            Matcher matcher = Pattern.compile("https?://\\S+").matcher(full);
            while (matcher.find()) {
                String token = matcher.group().replaceAll("[.,;:!?)\\]}'\"]+$", "");
                start = full.indexOf(token);
                if (start >= 0) {
                    raw = token;
                    break;
                }
            }
        }
        if (start < 0) return message;
        pendingUrl = found.get();
        pendingUrlX = Minecraft.getInstance().font.width(full.substring(0, start));
        // ponytail: flattening drops per-sibling styles; only the removed URL span matters here.
        String rest = full.substring(0, start) + full.substring(start + raw.length());
        return Component.literal(rest).withStyle(message.getStyle());
    }

    @Inject(method = "addMessage(Lnet/minecraft/network/chat/Component;)V", at = @At("RETURN"))
    private void onAddMessage(Component message, CallbackInfo ci) {
        captureNewest();
    }

    @Inject(method = "addMessage(Lnet/minecraft/network/chat/Component;Lnet/minecraft/network/chat/MessageSignature;Lnet/minecraft/client/GuiMessageTag;)V", at = @At("RETURN"))
    private void onAddSignedMessage(Component message, MessageSignature signature,
                                    GuiMessageTag tag, CallbackInfo ci) {
        captureNewest();
    }

    private void captureNewest() {
        String url = pendingUrl;
        int urlX = pendingUrlX;
        pendingUrl = null;
        if (url == null || this.allMessages.isEmpty()) return;
        GuiMessage newest = this.allMessages.get(0);
        EmbedManager.INSTANCE.onMessage(url, newest.addedTime(), urlX);
    }

    @Inject(method = "render(Lnet/minecraft/client/gui/GuiGraphics;Lnet/minecraft/client/gui/Font;IIIZZ)V", at = @At("TAIL"))
    private void onRender(GuiGraphics graphics, Font font, int currentTick, int mouseX, int mouseY,
                          boolean focused, boolean changeCursorOnInsertions, CallbackInfo ci) {
        double scale = this.getScale();
        // ponytail: ChatComponent's pose maps local x=0 to screen x=4*scale and chat-local y by *scale.
        int chatX = (int) Math.round(4.0D * scale);
        EmbedManager.INSTANCE.render(graphics, this.trimmedMessages, this.getLineHeight(), scale,
                this.getWidth(), this.chatScrollbarPos, chatX);
    }
}
