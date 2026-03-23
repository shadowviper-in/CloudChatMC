package com.cloudchatmc;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public class ConfigManager {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String CONFIG_FILE_NAME = "cloudchatmc.json";

    private final Path configPath;
    private Config config;

    public ConfigManager() {
        this.configPath = Path.of("config", CONFIG_FILE_NAME);
    }

    public void load() {
        try {
            Files.createDirectories(configPath.getParent());
            if (Files.exists(configPath)) {
                String content = Files.readString(configPath);
                this.config = GSON.fromJson(content, Config.class);
                if (this.config == null) {
                    this.config = new Config();
                }
            } else {
                this.config = new Config();
                save();
            }
        } catch (IOException | JsonSyntaxException e) {
            CloudChatMC.LOGGER.error("Failed to load config, using defaults", e);
            this.config = new Config();
        }
        validate();
    }

    public void save() {
        try {
            Files.createDirectories(configPath.getParent());
            Files.writeString(configPath, GSON.toJson(this.config));
        } catch (IOException e) {
            CloudChatMC.LOGGER.error("Failed to save config", e);
        }
    }

    public Config getConfig() {
        return config;
    }

    public boolean validate() {
        boolean valid = true;
        if (config.getToken() == null || config.getToken().isEmpty()
                || config.getToken().equals("REPLACE_WITH_TOKEN")) {
            CloudChatMC.LOGGER.warn("Token not configured. Use /ccmc config <token> <url>");
            valid = false;
        }
        if (config.getUrl() == null || config.getUrl().isEmpty()) {
            CloudChatMC.LOGGER.warn("WebSocket URL not configured. Using default.");
            config.setUrl("wss://minesocket.matrixcode.qzz.io");
        }
        return valid;
    }

    public static class Config {
        private String token = "REPLACE_WITH_TOKEN";
        private String url = "wss://minesocket.matrixcode.qzz.io";

        public String getToken() { return token; }
        public void setToken(String token) { this.token = token; }
        public String getUrl() { return url; }
        public void setUrl(String url) { this.url = url; }
    }
}
