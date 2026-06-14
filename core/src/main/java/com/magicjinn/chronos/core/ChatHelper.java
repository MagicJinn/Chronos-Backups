package com.magicjinn.chronos.core;

/** Shared helper for building tellraw JSON components. */
public final class ChatHelper {
    private ChatHelper() {
    }

    /**
     * Escapes {@code message} and wraps it in {@code {"text":"..."}} for use
     * with {@code /tellraw @a ...}.
     */
    public static String jsonTellraw(String message) {
        if (message == null || message.isEmpty()) {
            return "{\"text\":\"\"}";
        }
        StringBuilder sb = new StringBuilder(message.length() + 16);
        sb.append("{\"text\":\"");
        for (int i = 0; i < message.length(); i++) {
            char c = message.charAt(i);
            switch (c) {
                case '"':
                    sb.append("\\\"");
                    break;
                case '\\':
                    sb.append("\\\\");
                    break;
                case '\n':
                    sb.append("\\n");
                    break;
                case '\r':
                    sb.append("\\r");
                    break;
                case '\t':
                    sb.append("\\t");
                    break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        sb.append("\"}");
        return sb.toString();
    }

    /**
     * Broadcast command for Minecraft 1.13+. Skips {@code tellraw} when no
     * players are online, avoiding console errors on empty dedicated servers.
     */
    public static String makeModernTellraw(String message) {
        return "execute if entity @a run tellraw @a " + jsonTellraw(message);
    }

    /**
     * Broadcast command for Minecraft 1.12.2 and below.
     */
    public static String makeLegacySay(String message) {
        if (message == null || message.isEmpty()) {
            return "say";
        }
        return "say " + message;
    }

    public static String makeTellraw(String message) {
        return makeModernTellraw(message);
    }
}
