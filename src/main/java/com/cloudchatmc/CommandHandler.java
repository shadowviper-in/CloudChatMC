package com.cloudchatmc;

import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

public class CommandHandler {

    private final WebSocketManager webSocketManager;

    public CommandHandler(WebSocketManager webSocketManager) {
        this.webSocketManager = webSocketManager;
    }

    public void register() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {

            var ccmc = ClientCommandManager.literal("ccmc");

            // /ccmc msg <message>
            ccmc.then(ClientCommandManager.literal("msg")
                .then(ClientCommandManager.argument("message", StringArgumentType.greedyString())
                    .executes(context -> {
                        if (!webSocketManager.isConnected()) {
                            sendError("Not connected to server");
                            return 0;
                        }
                        String message = StringArgumentType.getString(context, "message");
                        String encoded = EncryptionUtil.encode(message);
                        System.out.println("[CloudChatMC] /ccmc msg: " + message + " -> " + encoded);
                        webSocketManager.sendBroadcast(encoded);
                        return 1;
                    })
                )
            );

            // /ccmc pm <player> <message>
            ccmc.then(ClientCommandManager.literal("pm")
                .then(ClientCommandManager.argument("player", StringArgumentType.word())
                    .then(ClientCommandManager.argument("message", StringArgumentType.greedyString())
                        .executes(context -> {
                            if (!webSocketManager.isConnected()) {
                                sendError("Not connected to server");
                                return 0;
                            }
                            String player = StringArgumentType.getString(context, "player");
                            String message = StringArgumentType.getString(context, "message");
                            if (message == null || message.isEmpty()) {
                                sendError("Message cannot be empty");
                                return 0;
                            }
                            String encoded = EncryptionUtil.encode(message);
                            System.out.println("[CloudChatMC] /ccmc pm " + player + ": " + message + " -> " + encoded);
                            webSocketManager.sendPrivate(player, encoded);
                            webSocketManager.showLocalPrivateEcho(player, message);
                            return 1;
                        })
                    )
                )
            );

            // /ccmc online
            ccmc.then(ClientCommandManager.literal("online")
                .executes(context -> {
                    if (!webSocketManager.isConnected()) {
                        sendError("Not connected to server");
                        return 0;
                    }
                    System.out.println("[CloudChatMC] /ccmc online: requesting user list");
                    webSocketManager.requestList();
                    return 1;
                })
            );

            // /ccmc config <token> <url>
            ccmc.then(ClientCommandManager.literal("config")
                .then(ClientCommandManager.argument("token", StringArgumentType.word())
                    .then(ClientCommandManager.argument("websocket_url", StringArgumentType.greedyString())
                        .executes(context -> {
                            String token = StringArgumentType.getString(context, "token");
                            String url = StringArgumentType.getString(context, "websocket_url");

                            if (token == null || token.isEmpty()) {
                                sendError("Invalid token: token cannot be empty");
                                return 0;
                            }
                            if (url == null || (!url.startsWith("ws://") && !url.startsWith("wss://"))) {
                                sendError("Invalid URL: must start with ws:// or wss://");
                                return 0;
                            }

                            try {
                                ConfigManager configManager = CloudChatMC.getConfigManager();
                                configManager.getConfig().setToken(token);
                                configManager.getConfig().setUrl(url);
                                configManager.save();
                                System.out.println("[CloudChatMC] Config updated: url=" + url);
                                webSocketManager.reconnect();
                                sendSuccess("Config updated and reconnected successfully.");
                            } catch (Exception e) {
                                System.out.println("[CloudChatMC] Failed to update config: " + e.getMessage());
                                sendError("Failed to update config: " + e.getMessage());
                                return 0;
                            }
                            return 1;
                        })
                    )
                )
            );

            // /ccmc help
            ccmc.then(ClientCommandManager.literal("help")
                .executes(context -> {
                    MinecraftClient mc = MinecraftClient.getInstance();
                    if (mc == null || mc.player == null) return 0;
                    mc.execute(() -> {
                        mc.player.sendMessage(
                            Text.literal("[CloudChat] ").formatted(Formatting.YELLOW)
                                .append(Text.literal("Commands:").formatted(Formatting.WHITE)),
                            false);
                        mc.player.sendMessage(
                            Text.literal("/ccmc msg ").formatted(Formatting.GRAY)
                                .append(Text.literal("<message>").formatted(Formatting.WHITE))
                                .append(Text.literal(" - Send global message").formatted(Formatting.GRAY)),
                            false);
                        mc.player.sendMessage(
                            Text.literal("/ccmc pm ").formatted(Formatting.GRAY)
                                .append(Text.literal("<player> <message>").formatted(Formatting.WHITE))
                                .append(Text.literal(" - Send private message").formatted(Formatting.GRAY)),
                            false);
                        mc.player.sendMessage(
                            Text.literal("/ccmc online ").formatted(Formatting.GRAY)
                                .append(Text.literal("- Show online users").formatted(Formatting.WHITE)),
                            false);
                        mc.player.sendMessage(
                            Text.literal("/ccmc config ").formatted(Formatting.GRAY)
                                .append(Text.literal("<token> <url>").formatted(Formatting.WHITE))
                                .append(Text.literal(" - Update config and reconnect").formatted(Formatting.GRAY)),
                            false);
                        mc.player.sendMessage(
                            Text.literal("/ccmc status ").formatted(Formatting.GRAY)
                                .append(Text.literal("- Show connection status").formatted(Formatting.WHITE)),
                            false);
                    });
                    return 1;
                })
            );

            // /ccmc status
            ccmc.then(ClientCommandManager.literal("status")
                .executes(context -> {
                    if (webSocketManager.isConnected()) {
                        String token = CloudChatMC.getConfigManager().getConfig().getToken();
                        String display = token.length() > 8
                            ? token.substring(0, 4) + "***" + token.substring(token.length() - 4)
                            : "***" + token;
                        // §a[CloudChat] §fConnected as §f<token>
                        MinecraftClient mc = MinecraftClient.getInstance();
                        if (mc != null && mc.player != null) {
                            mc.execute(() -> mc.player.sendMessage(
                                Text.literal("[CloudChat] ").formatted(Formatting.GREEN)
                                    .append(Text.literal("Connected as ").formatted(Formatting.WHITE))
                                    .append(Text.literal(display).formatted(Formatting.WHITE)),
                                false));
                        }
                    } else {
                        // §c[CloudChat] §fDisconnected
                        MinecraftClient mc = MinecraftClient.getInstance();
                        if (mc != null && mc.player != null) {
                            mc.execute(() -> mc.player.sendMessage(
                                Text.literal("[CloudChat] ").formatted(Formatting.RED)
                                    .append(Text.literal("Disconnected").formatted(Formatting.WHITE)),
                                false));
                        }
                    }
                    return 1;
                })
            );

            dispatcher.register(ccmc);
        });

        System.out.println("[CloudChatMC] Commands registered: /ccmc msg, /ccmc pm, /ccmc online, /ccmc config, /ccmc help, /ccmc status");
    }

    private void sendSuccess(String message) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc != null && mc.player != null) {
            mc.execute(() -> mc.player.sendMessage(
                Text.literal("[CloudChat] ").formatted(Formatting.GREEN)
                    .append(Text.literal(message).formatted(Formatting.WHITE)),
                false));
        }
    }

    private void sendError(String message) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc != null && mc.player != null) {
            mc.execute(() -> mc.player.sendMessage(
                Text.literal("[!] ").formatted(Formatting.RED)
                    .append(Text.literal(message).formatted(Formatting.WHITE)),
                false));
        }
    }
}
