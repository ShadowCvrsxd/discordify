package com.discordify.chat;

import java.util.Optional;
import net.minecraft.network.chat.Component;

public final class GifUrlDetector {
    private GifUrlDetector() {
    }

    public static Optional<String> findGifUrl(Component message) {
        return ResourceDetector.findGifUrl(message);
    }
}
