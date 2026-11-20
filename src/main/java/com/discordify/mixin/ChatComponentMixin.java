package com.discordify.mixin;

import com.discordify.chat.EmbedManager;
import com.discordify.chat.ResourceDetector;
import com.discordify.chat.ResourceMatch;
import java.util.Comparator;
import java.util.List;
import net.minecraft.client.GuiMessage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MessageSignature;
import net.minecraft.client.GuiMessageTag;
import net.minecraft.util.ARGB;
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

    @Shadow private double getScale() { return 0; }

    @Shadow public int getLinesPerPage() { return 0; }

    @Shadow private int getWidth() { return 0; }

    @Shadow private boolean isChatHidden() { return false; }

    private List<ResourceMatch> pendingMatches;
    private String pendingPrefix;
    private int pendingUrlWidth;

    @ModifyVariable(method = "addMessage(Lnet/minecraft/network/chat/Component;)V",
            at = @At("HEAD"), argsOnly = true)
    private Component stripResourceSimple(Component message) {
        return stripResources(message);
    }

    @ModifyVariable(method = "addMessage(Lnet/minecraft/network/chat/Component;Lnet/minecraft/network/chat/MessageSignature;Lnet/minecraft/client/GuiMessageTag;)V",
            at = @At("HEAD"), argsOnly = true)
    private Component stripResources(Component message) {
        List<ResourceMatch> matches = ResourceDetector.findResources(message);
        if (matches.isEmpty()) return message;
        String full = message.getString();
        List<int[]> ranges = ResourceDetector.stripRanges(matches, full);
        // ponytail: addMessage(Component) delegates to the signed overload, so this runs twice.
        // Setting pending state only after a real media URL was stripped keeps pass one's GIF+PAGE
        // list intact when pass two sees only the trusted PAGE URL (kept in chat by design).
        if (ranges.isEmpty()) return message;
        int firstStart = full.indexOf(matches.get(0).originalUrl());
        String firstToken = matches.get(0).originalUrl();

        if (firstStart < 0) {
            firstStart = 0;
            firstToken = "";
        }
        Font font = Minecraft.getInstance().font;
        pendingMatches = matches;
        pendingPrefix = full.substring(0, firstStart);
        pendingUrlWidth = font == null ? 0 : font.width(Component.literal(firstToken));

        ranges.sort(Comparator.comparingInt(range -> range[0]));
        StringBuilder kept = new StringBuilder(full.length());
        int cursor = 0;
        for (int[] range : ranges) {
            if (range[0] < cursor) continue;
            kept.append(full, cursor, range[0]);
            cursor = range[1];
        }
        kept.append(full, cursor, full.length());
        // ponytail: flattening drops per-sibling styles; only the removed URL spans matter here.
        return Component.literal(kept.toString()).withStyle(message.getStyle());
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
        List<ResourceMatch> matches = pendingMatches;
        String prefix = pendingPrefix;
        int urlWidth = pendingUrlWidth;
        pendingMatches = null;
        pendingPrefix = null;
        pendingUrlWidth = 0;
        if (matches == null || this.allMessages.isEmpty()) return;
        GuiMessage newest = this.allMessages.get(0);
        EmbedManager.INSTANCE.onMessage(matches, newest.addedTime(), prefix == null ? "" : prefix, urlWidth);
    }

    // ponytail: vanilla's FIRST forEachLine pass draws one one-row highlight via this lambda.
    // Shift it to follow the text AND stretch its lower edge over the rows the GIF reserved.
    @Inject(method = "method_75802", at = @At("HEAD"), cancellable = true)
    private static void drawChatBackground(int chatBottom, int entryHeight,
                                           ChatComponent.ChatGraphicsAccess access, int width, float opacity,
                                           GuiMessage.Line line, int slot, float textBackgroundOpacity,
                                           CallbackInfo ci) {
        int shiftedSlot = slot + EmbedManager.INSTANCE.shiftRowsForSlot(slot);
        int y2 = chatBottom - shiftedSlot * entryHeight;
        int y1 = y2 - entryHeight;
        int reserve = EmbedManager.INSTANCE.backgroundSpanRows(slot);
        if (reserve > 0) y2 += reserve * entryHeight;
        access.fill(-4, y1, width + 8, y2, ARGB.black(textBackgroundOpacity * opacity));
        ci.cancel();
    }

    @Inject(method = "render(Lnet/minecraft/client/gui/GuiGraphics;Lnet/minecraft/client/gui/Font;IIIZZ)V", at = @At("HEAD"))
    private void onRenderPrepare(GuiGraphics graphics, Font font, int currentTick, int mouseX, int mouseY,
                                 boolean focused, boolean changeCursorOnInsertions, CallbackInfo ci) {
        EmbedManager.INSTANCE.prepare(font, this.trimmedMessages, this.allMessages,
                currentTick, this.getLineHeight(), this.getScale(), this.getWidth(), this.chatScrollbarPos,
                this.getLinesPerPage(), this.isChatHidden() || !focused);
    }

    @Inject(method = "render(Lnet/minecraft/client/gui/GuiGraphics;Lnet/minecraft/client/gui/Font;IIIZZ)V", at = @At("TAIL"))
    private void onRenderDraw(GuiGraphics graphics, Font font, int currentTick, int mouseX, int mouseY,
                              boolean focused, boolean changeCursorOnInsertions, CallbackInfo ci) {
        EmbedManager.INSTANCE.draw(graphics);
    }
}
