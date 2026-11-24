package com.discordify.mixin;

import com.discordify.chat.EmbedManager;
import com.discordify.chat.ResourceDetector;
import com.discordify.chat.ResourceMatch;
import java.util.List;
import java.util.ListIterator;
import net.minecraft.client.GuiMessage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MessageSignature;
import net.minecraft.client.GuiMessageTag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArgs;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.invoke.arg.Args;

@Mixin(value = ChatComponent.class, priority = 1001)
public abstract class ChatComponentMixin {
    @Shadow private List<GuiMessage> allMessages;
    @Shadow private List<GuiMessage.Line> trimmedMessages;
    @Shadow private int chatScrollbarPos;

    @Shadow private void refreshTrimmedMessages() { }

    @Shadow private int getLineHeight() { return 0; }

    @Shadow private double getScale() { return 0; }

    @Shadow public int getLinesPerPage() { return 0; }

    @Shadow private int getWidth() { return 0; }

    @Shadow private boolean isChatHidden() { return false; }

    private static final Logger LOGGER = LoggerFactory.getLogger("Discordify");

    private List<ResourceMatch> pendingMatches;
    private String pendingPrefix;
    private int pendingUrlWidth;
    // ponytail: focus is true only on the chat screen; HUD gameplay chat is unfocused by design.
    private boolean prevFocused;

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
        // Pass two must not overwrite pass one's richer GIF+PAGE list with a PAGE-only list, but a
        // PAGE-only message still needs its list forwarded so the downloader can resolve share links.
        if (EmbedManager.shouldKeepPending(ranges.isEmpty(), pendingMatches != null)) return message;
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

        if (ranges.isEmpty()) return message;
        return ResourceDetector.stripStyled(message, ranges);
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

    // ponytail: cooperate with chat_heads' @ModifyArg(index=2) on x2 — shift only y1/y2 so both
    // mods' edits to the same fill() call survive. Higher mixin priority runs this handler last.
    @ModifyArgs(method = "method_75802", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/gui/components/ChatComponent$ChatGraphicsAccess;fill(IIIII)V"), expect = 1)
    private static void shiftBackground(Args args, int chatBottom, int entryHeight,
                                        ChatComponent.ChatGraphicsAccess access, int width, float opacity,
                                        GuiMessage.Line line, int slot, float textBackgroundOpacity) {
        int[] y = EmbedManager.backgroundFillY(chatBottom, entryHeight, slot,
                EmbedManager.INSTANCE.shiftRowsForSlot(slot), EmbedManager.INSTANCE.backgroundSpanRows(slot),
                EmbedManager.INSTANCE.backgroundSpanRows(slot - 1) > 0);
        // ponytail: publish the chatpatches-modded chatBottom so prepare() anchors the GIF to the
        // same bottom as the fill; one frame behind on the very first frame, then constant.
        EmbedManager.INSTANCE.setChatBottomLocal(chatBottom);
        args.set(1, y[0]);
        args.set(3, y[1]);
    }

    private void drainHideRequests() {
        boolean replaced = false;
        for (EmbedManager.HideRequest request : EmbedManager.INSTANCE.drainHides()) {
            int matches = 0;
            ListIterator<GuiMessage> it = this.allMessages.listIterator();
            while (it.hasNext()) {
                GuiMessage m = it.next();
                if (m.addedTime() == request.addedTime()
                        && m.content().getString().contains(request.originalUrl())) matches++;
            }
            if (!EmbedManager.shouldReplaceHideMatch(matches)) {
                if (matches > 1) LOGGER.debug("Discordify: ambiguous klipy hide for tick {}", request.addedTime());
                continue;
            }
            it = this.allMessages.listIterator();
            while (it.hasNext()) {
                GuiMessage m = it.next();
                if (m.addedTime() == request.addedTime()
                        && m.content().getString().contains(request.originalUrl())) {
                    Component stripped = ResourceDetector.hideUrl(m.content(), request.originalUrl());
                    it.set(new GuiMessage(m.addedTime(), stripped, m.signature(), m.tag()));
                    replaced = true;
                    break;
                }
            }
        }
        if (replaced) this.refreshTrimmedMessages();
    }

    @Inject(method = "render(Lnet/minecraft/client/gui/GuiGraphics;Lnet/minecraft/client/gui/Font;IIIZZ)V", at = @At("HEAD"))
    private void onRenderPrepare(GuiGraphics graphics, Font font, int currentTick, int mouseX, int mouseY,
                                 boolean focused, boolean changeCursorOnInsertions, CallbackInfo ci) {
        this.drainHideRequests();
        boolean chatJustClosed = EmbedManager.justClosed(this.prevFocused, focused);
        if (EmbedManager.justOpened(this.prevFocused, focused)) {
            EmbedManager.INSTANCE.clearHideLatch();
        }
        this.prevFocused = focused;
        EmbedManager.INSTANCE.prepare(font, this.trimmedMessages, this.allMessages,
                currentTick, this.getLineHeight(), this.getScale(), this.getWidth(), this.chatScrollbarPos,
                this.getLinesPerPage(), this.isChatHidden(), chatJustClosed);
    }

    @Inject(method = "render(Lnet/minecraft/client/gui/GuiGraphics;Lnet/minecraft/client/gui/Font;IIIZZ)V", at = @At("TAIL"))
    private void onRenderDraw(GuiGraphics graphics, Font font, int currentTick, int mouseX, int mouseY,
                              boolean focused, boolean changeCursorOnInsertions, CallbackInfo ci) {
        EmbedManager.INSTANCE.draw(graphics);
    }
}
