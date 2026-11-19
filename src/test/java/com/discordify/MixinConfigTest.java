package com.discordify;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.spongepowered.asm.mixin.Mixin;

class MixinConfigTest {
    private static final String MIXIN_DESC = "L" + Mixin.class.getName().replace('.', '/') + ";";
    private static final Map<String, String> TARGETS = Map.of(
            "ChatComponentMixin", "net.minecraft.client.gui.components.ChatComponent",
            "ChatLineMixin", "net.minecraft.client.gui.components.ChatComponent$1");

    @Test
    void everyDeclaredMixinLoadsWithMixinAnnotationAndOnClasspathTarget() throws Exception {
        String config = TestSupport.resource("/discordify.mixins.json");
        String pkg = TestSupport.string(config, "package");
        assertEquals("com.discordify.mixin", pkg);

        List<String> names = new ArrayList<>();
        names.addAll(TestSupport.strings(config, "common"));
        names.addAll(TestSupport.strings(config, "client"));
        names.addAll(TestSupport.strings(config, "server"));
        assertTrue(names.size() >= 2, "expected at least two declared mixins, got " + names);

        ClassLoader loader = getClass().getClassLoader();
        for (String name : names) {
            String fqcn = pkg + "." + name;
            assertNotNull(Class.forName(fqcn, false, loader), "mixin class did not load: " + fqcn);

            String raw = new String(classBytes(fqcn, loader), StandardCharsets.ISO_8859_1);
            // ponytail: @Mixin is CLASS-retained, so reflection cannot read it; scan the class bytes instead.
            assertTrue(raw.contains(MIXIN_DESC), fqcn + " is missing @Mixin");

            String target = TARGETS.get(name);
            assertNotNull(target, "no expected target registered for " + name);
            assertTrue(raw.contains(target) || raw.contains(target.replace('.', '/')),
                    fqcn + " does not declare target " + target);
            assertNotNull(Class.forName(target, false, loader), "target missing from classpath: " + target);
        }
    }

    private static byte[] classBytes(String fqcn, ClassLoader loader) throws IOException {
        String path = "/" + fqcn.replace('.', '/') + ".class";
        try (InputStream in = loader.getResourceAsStream(path.substring(1))) {
            assertNotNull(in, "missing class bytes: " + path);
            return in.readAllBytes();
        }
    }
}
