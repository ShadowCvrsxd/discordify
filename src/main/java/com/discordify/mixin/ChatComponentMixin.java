package com.discordify.mixin;

import com.discordify.chat.EmbedManager;
import com.discordify.chat.ResourceDetector;
import com.discordify.chat.ResourceKind;
import com.discordify.chat.ResourceMatch;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.minecraft.client.GuiMessage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MessageSignature;
import net.minecraft.client.GuiMessageTag;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ChatComponent.class)
public abstract class ChatComponentMixin {
    private static final Pattern LINK = Pattern.compile("https?://\\S+");
    private static final Pattern TRAILING_PUNCTUATION = Pattern.compile("[.,;:!?)\\]}'\"]+$");

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
        List<int[]> ranges = new ArrayList<>();
        int firstStart = -1;
        String firstToken = null;

        for (ResourceMatch match : matches) {
            if (firstStart < 0) {
                firstStart = full.indexOf(match.originalUrl());
                firstToken = match.originalUrl();
            }
            // ponytail: pages keep their link in chat; if unfurl fails the URL is still visible (fail open).
            if (match.kind() == ResourceKind.PAGE) continue;
            String raw = match.originalUrl();
            int start = full.indexOf(raw);
            if (start < 0) {
                Matcher matcher = LINK.matcher(full);
                while (matcher.find()) {
                    String token = TRAILING_PUNCTUATION.matcher(matcher.group()).replaceAll("");
                    start = full.indexOf(token);
                    if (start >= 0) {
                        raw = token;
                        break;
                    }
                }
            }
            if (start >= 0) ranges.add(new int[]{start, start + raw.length()});
        }

        if (firstStart < 0) {
            firstStart = 0;
            firstToken = "";
        }
        Font font = Minecraft.getInstance().font;
        pendingMatches = matches;
        pendingPrefix = full.substring(0, firstStart);
        pendingUrlWidth = font == null ? 0 : font.width(Component.literal(firstToken));

        if (ranges.isEmpty()) return message;
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

    // ponytail: vanilla's FIRST forEachLine pass draws the per-line highlight via this synthetic lambda with the
    // ORIGINAL row slot (the 7th arg, index 6). Shift it like the text pass or highlights lag the shifted rows.
    @ModifyVariable(method = "method_75802", at = @At("HEAD"), argsOnly = true, index = 6)
    private static int shiftHighlightRow(int row) {
        return row + EmbedManager.INSTANCE.shiftRowsForSlot(row);
    }

    @Inject(method = "render(Lnet/minecraft/client/gui/GuiGraphics;Lnet/minecraft/client/gui/Font;IIIZZ)V", at = @At("HEAD"))
    private void onRenderPrepare(GuiGraphics graphics, Font font, int currentTick, int mouseX, int mouseY,
                                 boolean focused, boolean changeCursorOnInsertions, CallbackInfo ci) {
        EmbedManager.INSTANCE.prepare(font, this.trimmedMessages, this.allMessages, currentTick,
                this.getLineHeight(), this.getScale(), this.getWidth(), this.chatScrollbarPos,
                this.getLinesPerPage(), this.isChatHidden());
    }

    @Inject(method = "render(Lnet/minecraft/client/gui/GuiGraphics;Lnet/minecraft/client/gui/Font;IIIZZ)V", at = @At("TAIL"))
    private void onRenderDraw(GuiGraphics graphics, Font font, int currentTick, int mouseX, int mouseY,
                              boolean focused, boolean changeCursorOnInsertions, CallbackInfo ci) {
        EmbedManager.INSTANCE.draw(graphics);
    }
}
