package com.cloudchatmc;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

public class EncryptionUtil {

    private static final String KEY = "oggang";

    public static String encode(String message) {
        String combined = KEY + message;
        return Base64.getEncoder().encodeToString(combined.getBytes(StandardCharsets.UTF_8));
    }

    public static String decode(String encoded) {
        if (encoded == null || encoded.isEmpty()) {
            return "";
        }
        try {
            byte[] decodedBytes = Base64.getDecoder().decode(encoded);
            String decoded = new String(decodedBytes, StandardCharsets.UTF_8);
            if (decoded.startsWith(KEY)) {
                return decoded.substring(KEY.length());
            }
            return decoded;
        } catch (IllegalArgumentException e) {
            CloudChatMC.LOGGER.warn("Base64 decode failed, showing raw: {}", encoded);
            return encoded;
        } catch (Exception e) {
            CloudChatMC.LOGGER.error("Unexpected decode error", e);
            return encoded;
        }
    }
}
