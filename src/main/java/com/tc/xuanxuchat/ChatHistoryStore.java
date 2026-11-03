package com.tc.xuanxuchat;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.neoforged.fml.loading.FMLPaths;
import org.slf4j.Logger;

import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 聊天历史本地持久化。
 * <p>
 * 按存档 / 服务器分别保存到 {@code {gameDir}/xuanxu/chat/{session}.json}，
 * 重新进入同一存档时自动恢复。
 */
public final class ChatHistoryStore {
    /** 每个会话最多保留的消息数。 */
    public static final int MAX_MESSAGES = 200;

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private static boolean dirty;

    private ChatHistoryStore() {
    }

    public static Path chatDir() {
        return FMLPaths.GAMEDIR.get().resolve("xuanxu").resolve("chat");
    }

    /**
     * 当前会话标识（单人存档名 / 多人服务器地址）。
     */
    public static String currentSessionId() {
        Minecraft mc = Minecraft.getInstance();
        try {
            if (mc.getSingleplayerServer() != null) {
                return "sp_" + sanitize(mc.getSingleplayerServer().getWorldData().getLevelName());
            }
            if (mc.getCurrentServer() != null) {
                return "mp_" + sanitize(mc.getCurrentServer().ip);
            }
        } catch (Exception e) {
            LOGGER.debug("Failed to determine chat session id", e);
        }
        return "default";
    }

    private static String sanitize(String raw) {
        if (raw == null) {
            return "unknown";
        }
        return raw.replaceAll("[^a-zA-Z0-9_\\-\\u4e00-\\u9fa5]", "_");
    }

    /**
     * 读取指定会话的历史记录。
     */
    public static Map<String, List<ChatMessage>> load(String sessionId) {
        Map<String, List<ChatMessage>> result = new LinkedHashMap<>();
        Path file = chatDir().resolve(sessionId + ".json");
        if (!Files.exists(file)) {
            return result;
        }
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
            for (Map.Entry<String, JsonElement> entry : root.entrySet()) {
                if (!entry.getValue().isJsonArray()) {
                    continue;
                }
                List<ChatMessage> list = new ArrayList<>();
                for (JsonElement element : entry.getValue().getAsJsonArray()) {
                    if (!element.isJsonObject()) {
                        continue;
                    }
                    JsonObject obj = element.getAsJsonObject();
                    String text = obj.has("text") ? obj.get("text").getAsString() : "";
                    String sender = obj.has("sender") ? obj.get("sender").getAsString() : "";
                    long ts = obj.has("ts") ? obj.get("ts").getAsLong() : 0L;
                    boolean self = obj.has("self") && obj.get("self").getAsBoolean();
                    list.add(new ChatMessage(null, sender, Component.literal(text), text, ts, self));
                }
                result.put(entry.getKey(), list);
            }
        } catch (Exception e) {
            LOGGER.error("Failed to load chat history: {}", file, e);
        }
        return result;
    }

    /** 标记有改动，等待批量写盘。 */
    public static void scheduleSave() {
        dirty = true;
    }

    /**
     * 若有改动则写盘（由客户端 tick 调用，避免每条消息都写文件）。
     */
    public static void flushIfDirty() {
        if (!dirty) {
            return;
        }
        dirty = false;
        save(ChatHub.allConversations(), currentSessionId());
    }

    private static void save(Map<String, List<ChatMessage>> conversations, String sessionId) {
        try {
            Files.createDirectories(chatDir());
            JsonObject root = new JsonObject();
            for (Map.Entry<String, List<ChatMessage>> entry : conversations.entrySet()) {
                JsonArray array = new JsonArray();
                for (ChatMessage message : entry.getValue()) {
                    JsonObject obj = new JsonObject();
                    obj.addProperty("sender", message.senderName());
                    obj.addProperty("text", message.plainText());
                    obj.addProperty("ts", message.timestamp());
                    obj.addProperty("self", message.self());
                    array.add(obj);
                }
                root.add(entry.getKey(), array);
            }
            Path file = chatDir().resolve(sessionId + ".json");
            Files.writeString(file, GSON.toJson(root), StandardCharsets.UTF_8);
        } catch (Exception e) {
            LOGGER.error("Failed to save chat history", e);
        }
    }
}