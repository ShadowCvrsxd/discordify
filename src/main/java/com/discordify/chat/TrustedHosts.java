package com.discordify.chat;

import java.util.Set;

public final class TrustedHosts {
    public static final Set<String> HOSTS = Set.of(
            "i.imgur.com", "i.redd.it", "preview.redd.it", "pbs.twimg.com",
            "cdn.discordapp.com", "raw.githubusercontent.com", "images.githubusercontent.com",
            "giphy.com", "i.giphy.com", "media.giphy.com",
            "media0.giphy.com", "media1.giphy.com", "media2.giphy.com", "media3.giphy.com",
            "tenor.com", "media.tenor.com", "media1.tenor.com", "media2.tenor.com", "media3.tenor.com");

    private TrustedHosts() {
    }
}
