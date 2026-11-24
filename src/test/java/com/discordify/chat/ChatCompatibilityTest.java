package com.discordify.chat;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class ChatCompatibilityTest {
    private static final String MIXIN_SOURCE =
            "src/main/java/com/discordify/mixin/ChatComponentMixin.java";
    private static final String MIXIN_CONFIG = "src/main/resources/discordify.mixins.json";

    // ponytail: pure string guard so in-process fixtures prove rejection without mutating product code.
    static boolean sourceGuard(String source) {
        int drain = source.indexOf("drainHideRequests()");
        int prepare = source.indexOf("EmbedManager.INSTANCE.prepare(");
        return source.contains("@ModifyArgs")
                && source.contains("method_75802")
                && source.contains("priority = 1001")
                && !source.contains("cancellable = true")
                && !source.contains("ci.cancel()")
                && !source.contains("args.set(2")
                && source.contains("drainHides()")
                && source.contains("ResourceDetector.hideUrl")
                && source.contains("@Shadow private void refreshTrimmedMessages()")
                && drain >= 0 && prepare >= 0 && drain < prepare;
    }

    @Test
    void backgroundFillYNoShiftNoReserve() {
        assertArrayEquals(new int[] {90, 100}, EmbedManager.backgroundFillY(100, 10, 0, 0, 0));
    }

    @Test
    void backgroundFillYWithShiftAndReserve() {
        assertArrayEquals(new int[] {70, 110}, EmbedManager.backgroundFillY(100, 10, 0, 2, 3));
    }

    @Test
    void backgroundFillYNoAdditiveReserveWhenReserveZero() {
        // y2 = chatBottom - (slot+shift)*entryHeight, no additive reserve.
        assertArrayEquals(new int[] {60, 70}, EmbedManager.backgroundFillY(100, 10, 1, 2, 0));
    }

    @Test
    void sourceGuardAcceptsCurrentMixin() throws IOException {
        assertTrue(sourceGuard(Files.readString(Path.of(MIXIN_SOURCE))));
    }

    @Test
    void sourceGuardRejectsCancellable() {
        assertTrue(sourceGuard(validSource()));
        assertFalse(sourceGuard(validSource().replace("priority = 1001", "cancellable = true, priority = 1001")));
    }

    @Test
    void sourceGuardRejectsMissingModifyArgs() {
        assertTrue(sourceGuard(validSource()));
        assertFalse(sourceGuard(validSource().replace("@ModifyArgs", "@ModifyArg")));
    }

    @Test
    void sourceGuardRejectsWrongPriority() {
        assertTrue(sourceGuard(validSource()));
        assertFalse(sourceGuard(validSource().replace("priority = 1001", "priority = 1000")));
    }

    @Test
    void sourceGuardRejectsMissingDrain() {
        assertTrue(sourceGuard(validSource()));
        assertFalse(sourceGuard(validSource().replace("drainHides()", "")));
    }

    @Test
    void mixinConfigHasNoMixinPriority() throws IOException {
        assertFalse(Files.readString(Path.of(MIXIN_CONFIG)).contains("mixinPriority"));
    }

    private static String validSource() {
        return "@ModifyArgs(method = \"method_75802\")\n"
                + "@Mixin(value = ChatComponent.class, priority = 1001)\n"
                + "@Shadow private void refreshTrimmedMessages() { }\n"
                + "args.set(1, y[0]);\nargs.set(3, y[1]);\n"
                + "private void drainHideRequests() {\n"
                + "    EmbedManager.INSTANCE.drainHides();\n"
                + "    ResourceDetector.hideUrl(content, url);\n"
                + "}\n"
                + "this.drainHideRequests();\n"
                + "EmbedManager.INSTANCE.prepare(font, trimmed, all, tick);\n";
    }
}
