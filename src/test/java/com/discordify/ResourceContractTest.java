package com.discordify;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class ResourceContractTest {
    private static final List<String> FORBIDDEN = List.of(
            "HudRenderCallback", "GuiGraphicsExtractor", "ResourceLocation");

    @Test
    void fabricModDeclaresClientModWithMixinConfig() throws IOException {
        String json = TestSupport.resource("/fabric.mod.json");
        assertEquals("discordify", TestSupport.string(json, "id"));
        assertEquals("client", TestSupport.string(json, "environment"));
        assertTrue(TestSupport.strings(json, "client").contains("com.discordify.DiscordifyMod"),
                "missing client entrypoint");
        assertEquals("discordify.mixins.json", TestSupport.string(json, "config"),
                "missing mixin config entry");
    }

    @Test
    void mixinConfigDeclaresRequiredJava21ChatMixins() throws IOException {
        String json = TestSupport.resource("/discordify.mixins.json");
        assertTrue(TestSupport.bool(json, "required"));
        assertEquals("JAVA_21", TestSupport.string(json, "compatibilityLevel"));
        List<String> client = TestSupport.strings(json, "client");
        assertTrue(client.contains("ChatComponentMixin"), "missing ChatComponentMixin");
        assertTrue(client.contains("ChatLineMixin"), "missing ChatLineMixin");
    }

    @Test
    void sourceDoesNotReferenceForbiddenRenderingHooks() throws IOException {
        Path root = Path.of("src", "main", "java");
        assertTrue(Files.isDirectory(root), "missing source root: " + root.toAbsolutePath());
        List<String> violations = new ArrayList<>();
        try (Stream<Path> paths = Files.walk(root)) {
            for (Path file : paths.filter(p -> p.toString().endsWith(".java")).toList()) {
                String text = Files.readString(file, StandardCharsets.UTF_8);
                for (String token : FORBIDDEN) {
                    if (text.contains(token)) violations.add(file + " contains " + token);
                }
            }
        }
        assertTrue(violations.isEmpty(), String.join("\n", violations));
    }
}
