package com.discordify.chat;

public record ResourceMatch(ResourceKind kind, String mediaUrl, String originalUrl,
                            int hintWidth, int hintHeight) {
}
