package com.discordify;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class ReleaseConfigTest {
    @Test
    void workflowPublishesVersionedJarToGithubReleases() throws IOException {
        String yaml = Files.readString(Path.of(".github", "workflows", "build.yml"), StandardCharsets.UTF_8);
        String build = Files.readString(Path.of("build.gradle"), StandardCharsets.UTF_8);
        Matcher version = Pattern.compile("version\\s*=\\s*\"([^\"]+)\"").matcher(build);
        assertTrue(version.find(), "missing version in build.gradle");
        String v = version.group(1);

        assertTrue(yaml.contains("./gradlew build"), "build must run before release");
        assertTrue(yaml.contains("actions/upload-artifact@v4"), "artifact upload must be kept");
        assertTrue(yaml.contains("softprops/action-gh-release@v2"));
        assertTrue(yaml.contains("secrets.GITHUB_TOKEN"));
        assertTrue(yaml.contains("generate_release_notes: true"));
        assertTrue(yaml.contains("files: build/libs/discordify-" + v + ".jar"));
        assertTrue(yaml.contains("tag_name: v" + v + "-mc1.21.11"));
        assertTrue(yaml.contains("github.ref == 'refs/heads/master'"), "release must be master-only");
        assertTrue(yaml.contains("contents: write"), "release needs write permission");
    }
}
