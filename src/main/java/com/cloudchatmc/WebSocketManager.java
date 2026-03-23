package com.cloudchatmc;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

public class WebSocketManager {

    private static final Gson GSON = new Gson();
    private static final int RECONNECT_DELAY_SECONDS = 5;
    private static final int MAX_QUEUE = 50;

    private final ConfigManager configManager;
    private volatile WebSocketClient client;
    private final ScheduledExecutorService scheduler;
    private volatile boolean intentionalClose = false;
    private volatile boolean connecting = false;
    private ScheduledFuture<?> reconnectTask;
    private final LinkedList<Text> messageQueue = new LinkedList<>();

    public WebSocketManager(ConfigManager configManager) {
        this.configManager = configManager;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "CloudChatMC-WebSocket");
            t.setDaemon(true);
            return t;
        });
    }

    public void connect() {
        if (connecting) return;
        if (client != null && client.isOpen()) return;

        connecting = true;
        intentionalClose = false;

        ConfigManager.Config cfg = configManager.getConfig();
        String token = cfg.getToken();
        String url = cfg.getUrl();

        if (token == null || token.equals("REPLACE_WITH_TOKEN")) {
            System.out.println("[CloudChatMC] Token not configured.");
            displayChat(statusText("Token not configured. Use /ccmc config <token> <url>"));
            connecting = false;
            return;
        }

        String encodedToken = URLEncoder.encode(token, StandardCharsets.UTF_8);
        String fullUrl = url + "?token=" + encodedToken;

        System.out.println("[CloudChatMC] Connecting to WebSocket: " + url);
        displayChat(statusText("Connecting..."));

        try {
            client = new WebSocketClient(URI.create(fullUrl)) {
                @Override
                public void onOpen(ServerHandshake handshake) {
                    connecting = false;
                    System.out.println("[CloudChatMC] Connected to WebSocket");
                    displayChat(statusText("Connected"));
                }

                @Override
                public void onMessage(String message) {
                    try {
                        System.out.println("[CloudChatMC] Received raw: " + message);
                        handleMessage(message);
                    } catch (Exception e) {
                        System.out.println("[CloudChatMC] Exception in onMessage: " + e.getMessage());
                        e.printStackTrace();
                    }
                }

                @Override
                public void onClose(int code, String reason, boolean remote) {
                    connecting = false;
                    System.out.println("[CloudChatMC] WebSocket closed: code=" + code + " reason=" + reason);
                    displayChat(statusText("Disconnected"));
                    if (!intentionalClose) {
                        scheduleReconnect();
                    }
                }

                @Override
                public void onError(Exception ex) {
                    connecting = false;
                    System.out.println("[CloudChatMC] WebSocket error: " + ex.getMessage());
                    if (!intentionalClose) {
                        scheduleReconnect();
                    }
                }
            };
            client.connect();
        } catch (Exception e) {
            connecting = false;
            System.out.println("[CloudChatMC] Failed to create WebSocket: " + e.getMessage());
            displayChat(statusText("Connection failed"));
            if (!intentionalClose) {
                scheduleReconnect();
            }
        }
    }

    public void reconnect() {
        disconnect();
        intentionalClose = false;
        connecting = false;
        scheduler.schedule(this::connect, 1, TimeUnit.SECONDS);
    }

    public void disconnect() {
        intentionalClose = true;
        if (reconnectTask != null && !reconnectTask.isDone()) {
            reconnectTask.cancel(false);
        }
        if (client != null) {
            try { client.close(); } catch (Exception ignored) {}
        }
    }

    public void shutdown() {
        disconnect();
        scheduler.shutdown();
    }

    private void scheduleReconnect() {
        if (intentionalClose || scheduler.isShutdown()) return;
        System.out.println("[CloudChatMC] Reconnecting in " + RECONNECT_DELAY_SECONDS + " seconds...");
        reconnectTask = scheduler.schedule(() -> {
            if (!intentionalClose) {
                connect();
            }
        }, RECONNECT_DELAY_SECONDS, TimeUnit.SECONDS);
    }

    private void sendRaw(String json) {
        try {
            WebSocketClient c = this.client;
            if (c != null && c.isOpen()) {
                c.send(json);
                System.out.println("[CloudChatMC] Sent: " + json);
            } else {
                System.out.println("[CloudChatMC] Cannot send, WebSocket not connected");
            }
        } catch (Exception e) {
            System.out.println("[CloudChatMC] Send failed: " + e.getMessage());
        }
    }

    public void sendBroadcast(String encodedMessage) {
        JsonObject json = new JsonObject();
        json.addProperty("type", "broadcast");
        json.addProperty("data", encodedMessage);
        System.out.println("[CloudChatMC] Sending encoded: " + encodedMessage);
        sendRaw(GSON.toJson(json));
    }

    public void sendPrivate(String target, String encodedMessage) {
        JsonObject json = new JsonObject();
        json.addProperty("type", "private");
        json.addProperty("to", target);
        json.addProperty("data", encodedMessage);
        System.out.println("[CloudChatMC] Sending private to " + target + ": " + encodedMessage);
        sendRaw(GSON.toJson(json));
    }

    public void showLocalPrivateEcho(String target, String decodedMessage) {
        // §d[PM] §fYou §7-> §f<to>§7: §f<message>
        MutableText text = Text.literal("[PM] ").formatted(Formatting.LIGHT_PURPLE)
            .append(Text.literal("You ").formatted(Formatting.WHITE))
            .append(Text.literal("-> ").formatted(Formatting.GRAY))
            .append(Text.literal(target).formatted(Formatting.WHITE))
            .append(Text.literal(": ").formatted(Formatting.GRAY))
            .append(Text.literal(decodedMessage).formatted(Formatting.WHITE));
        displayChat(text);
    }

    public void requestList() {
        JsonObject json = new JsonObject();
        json.addProperty("type", "list");
        sendRaw(GSON.toJson(json));
    }

    private void handleMessage(String rawMessage) {
        if (rawMessage == null || rawMessage.isEmpty()) {
            System.out.println("[CloudChatMC] Empty message received, ignoring");
            return;
        }

        JsonObject json;
        try {
            json = JsonParser.parseString(rawMessage).getAsJsonObject();
        } catch (Exception e) {
            System.out.println("[CloudChatMC] Invalid JSON: " + rawMessage);
            return;
        }

        if (!json.has("type")) {
            System.out.println("[CloudChatMC] Message missing 'type' field: " + rawMessage);
            return;
        }

        String type;
        try {
            type = json.get("type").getAsString();
        } catch (Exception e) {
            System.out.println("[CloudChatMC] Could not read 'type': " + rawMessage);
            return;
        }

        System.out.println("[CloudChatMC] Handling message type: " + type);

        switch (type) {
            case "msg": {
                String from = safeGetString(json, "from");
                String data = safeGetString(json, "data");
                String decoded = EncryptionUtil.decode(data);
                // §a[Global] §f<from>§7: §f<message>
                MutableText text = Text.literal("[Global] ").formatted(Formatting.GREEN)
                    .append(Text.literal(from).formatted(Formatting.WHITE))
                    .append(Text.literal(": ").formatted(Formatting.GRAY))
                    .append(Text.literal(decoded).formatted(Formatting.WHITE));
                displayChat(text);
                break;
            }
            case "pm": {
                String from = safeGetString(json, "from");
                String data = safeGetString(json, "data");
                String decoded = EncryptionUtil.decode(data);
                // §d[PM] §f<from> §7-> §fYou§7: §f<message>
                MutableText text = Text.literal("[PM] ").formatted(Formatting.LIGHT_PURPLE)
                    .append(Text.literal(from).formatted(Formatting.WHITE))
                    .append(Text.literal(" -> ").formatted(Formatting.GRAY))
                    .append(Text.literal("You").formatted(Formatting.WHITE))
                    .append(Text.literal(": ").formatted(Formatting.GRAY))
                    .append(Text.literal(decoded).formatted(Formatting.WHITE));
                displayChat(text);
                break;
            }
            case "join": {
                String user = safeGetString(json, "user");
                // §a[+] §f<user> §7joined
                MutableText text = Text.literal("[+] ").formatted(Formatting.GREEN)
                    .append(Text.literal(user).formatted(Formatting.WHITE))
                    .append(Text.literal(" joined").formatted(Formatting.GRAY));
                displayChat(text);
                break;
            }
            case "leave": {
                String user = safeGetString(json, "user");
                // §c[-] §f<user> §7left
                MutableText text = Text.literal("[-] ").formatted(Formatting.RED)
                    .append(Text.literal(user).formatted(Formatting.WHITE))
                    .append(Text.literal(" left").formatted(Formatting.GRAY));
                displayChat(text);
                break;
            }
            case "list": {
                String users = parseUserList(json);
                // §b[Online] §f<users>
                MutableText text = Text.literal("[Online] ").formatted(Formatting.AQUA)
                    .append(Text.literal(users).formatted(Formatting.WHITE));
                displayChat(text);
                break;
            }
            case "error": {
                String errorMsg = safeGetString(json, "message");
                // §c[!] §f<message>
                MutableText text = Text.literal("[!] ").formatted(Formatting.RED)
                    .append(Text.literal(errorMsg).formatted(Formatting.WHITE));
                displayChat(text);
                break;
            }
            default: {
                System.out.println("[CloudChatMC] Unknown message type: " + type);
                break;
            }
        }
    }

    private String parseUserList(JsonObject json) {
        if (json.has("users")) {
            try {
                JsonElement usersEl = json.get("users");
                if (usersEl.isJsonArray()) {
                    JsonArray arr = usersEl.getAsJsonArray();
                    StringBuilder sb = new StringBuilder();
                    for (int i = 0; i < arr.size(); i++) {
                        if (i > 0) sb.append(", ");
                        sb.append(arr.get(i).getAsString());
                    }
                    return sb.toString();
                } else if (usersEl.isJsonPrimitive()) {
                    return usersEl.getAsString();
                }
            } catch (Exception e) {
                System.out.println("[CloudChatMC] Failed to parse user list: " + e.getMessage());
            }
        }
        return "unknown";
    }

    private String safeGetString(JsonObject json, String key) {
        try {
            if (json.has(key) && !json.get(key).isJsonNull()) {
                return json.get(key).getAsString();
            }
        } catch (Exception e) {
            System.out.println("[CloudChatMC] Error reading key '" + key + "': " + e.getMessage());
        }
        return "";
    }

    private MutableText statusText(String message) {
        // §e[CloudChat] §f<message>
        return Text.literal("[CloudChat] ").formatted(Formatting.YELLOW)
            .append(Text.literal(message).formatted(Formatting.WHITE));
    }

    private void displayChat(Text text) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc == null) {
            System.out.println("[CloudChatMC] Cannot display chat: MinecraftClient is null");
            return;
        }
        if (mc.player == null) {
            System.out.println("[CloudChatMC] Player not ready, queuing message");
            synchronized (messageQueue) {
                if (messageQueue.size() >= MAX_QUEUE) {
                    messageQueue.poll();
                }
                messageQueue.add(text);
            }
            return;
        }
        mc.execute(() -> {
            try {
                if (mc.player != null) {
                    mc.player.sendMessage(text, false);
                }
            } catch (Exception e) {
                System.out.println("[CloudChatMC] Error displaying chat: " + e.getMessage());
            }
        });
    }

    public void drainMessageQueue(MinecraftClient client) {
        if (client.player == null) return;
        synchronized (messageQueue) {
            while (!messageQueue.isEmpty() && client.player != null) {
                Text msg = messageQueue.poll();
                if (msg != null) {
                    client.execute(() -> {
                        if (client.player != null) {
                            client.player.sendMessage(msg, false);
                        }
                    });
                }
            }
        }
    }

    public boolean isConnected() {
        WebSocketClient c = this.client;
        return c != null && c.isOpen();
    }
}
