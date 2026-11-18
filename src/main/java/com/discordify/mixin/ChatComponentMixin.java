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

    @Shadow public int getLinesPerPage() { return 0; }

    @Shadow private boolean isChatHidden() { return false; }

    private String pendingUrl;
    private String pendingPrefix;
    private int pendingUrlWidth;

    @ModifyVariable(method = "addMessage(Lnet/minecraft/network/chat/Component;)V",
            at = @At("HEAD"), argsOnly = true)
    private Component stripGifUrlSimple(Component message) {
        return stripGifUrl(message);
    }

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
        Font font = Minecraft.getInstance().font;
        pendingUrl = found.get();
        pendingPrefix = full.substring(0, start);
        pendingUrlWidth = font == null ? 0 : font.width(Component.literal(raw));
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
        String prefix = pendingPrefix;
        int urlWidth = pendingUrlWidth;
        pendingUrl = null;
        pendingPrefix = null;
        pendingUrlWidth = 0;
        if (url == null || this.allMessages.isEmpty()) return;
        GuiMessage newest = this.allMessages.get(0);
        EmbedManager.INSTANCE.onMessage(url, newest.addedTime(), prefix == null ? "" : prefix, urlWidth);
    }

    @Inject(method = "render(Lnet/minecraft/client/gui/GuiGraphics;Lnet/minecraft/client/gui/Font;IIIZZ)V", at = @At("TAIL"))
    private void onRender(GuiGraphics graphics, Font font, int currentTick, int mouseX, int mouseY,
                          boolean focused, boolean changeCursorOnInsertions, CallbackInfo ci) {
        EmbedManager.INSTANCE.prepare(font, this.trimmedMessages, this.allMessages,
                this.getLineHeight(), this.getScale(), this.getWidth(), this.chatScrollbarPos,
                this.getLinesPerPage(), this.isChatHidden());
        EmbedManager.INSTANCE.draw(graphics);
    }
}
