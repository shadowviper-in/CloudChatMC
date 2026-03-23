package com.cloudchatmc;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.MinecraftClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class CloudChatMC implements ClientModInitializer {

    public static final Logger LOGGER = LoggerFactory.getLogger("CloudChatMC");

    private static ConfigManager configManager;
    private static WebSocketManager webSocketManager;
    private static CommandHandler commandHandler;

    @Override
    public void onInitializeClient() {
        LOGGER.info("CloudChatMC initializing...");

        configManager = new ConfigManager();
        configManager.load();

        webSocketManager = new WebSocketManager(configManager);
        commandHandler = new CommandHandler(webSocketManager);
        commandHandler.register();

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client.player != null) {
                webSocketManager.drainMessageQueue(client);
            }
        });

        webSocketManager.connect();

        LOGGER.info("CloudChatMC initialized");
    }

    public static ConfigManager getConfigManager() { return configManager; }
    public static WebSocketManager getWebSocketManager() { return webSocketManager; }
    public static CommandHandler getCommandHandler() { return commandHandler; }
}
