package com.discordify;

import com.discordify.chat.EmbedManager;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;

public class DiscordifyMod implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> EmbedManager.INSTANCE.closeAll());
    }
}
