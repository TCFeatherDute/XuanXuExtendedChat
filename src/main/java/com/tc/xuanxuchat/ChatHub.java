package com.tc.xuanxuchat;

import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 客户端聊天消息中枢。
 * <p>
 * 负责接收聊天消息、按会话分组、维护内存列表，并交给 {@link ChatHistoryStore} 持久化。
 */
public final class ChatHub {
    /** 公共频道的会话键。 */
    public static final String PUBLIC = "public";

    private static final Logger LOGGER = LogUtils.getLogger();
    /** 原版聊天格式：{@code <PlayerName> message} */
    private static final Pattern PLAYER_CHAT = Pattern.compile("^<([^>]+)> (.+)$");

    private static final Map<String, List<ChatMessage>> CONVERSATIONS = new LinkedHashMap<>();
    private static String sessionId;

    private ChatHub() {
    }

    /**
     * 保证已加载当前存档 / 服务器的聊天历史（切换存档或服务器时重新加载）。
     */
    public static void ensureSessionLoaded() {
        String current = ChatHistoryStore.currentSessionId();
        if (!current.equals(sessionId)) {
            sessionId = current;
            CONVERSATIONS.clear();
            CONVERSATIONS.putAll(ChatHistoryStore.load(current));
        }
    }

    /**
     * 收到一条聊天消息（由 {@code ClientChatReceivedEvent} 调用）。
     */
    public static void receive(Component message) {
        ensureSessionLoaded();
        String plain = message.getString();
        if (plain.isEmpty()) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        String selfName = mc.player != null ? mc.player.getGameProfile().getName() : "";

        Matcher matcher = PLAYER_CHAT.matcher(plain);
        if (matcher.matches()) {
            String sender = matcher.group(1);
            add(new ChatMessage(
                    null,
                    sender,
                    message,
                    matcher.group(2),
                    System.currentTimeMillis(),
                    sender.equals(selfName)
            ));
        } else {
            // 系统消息（含私聊回显、服务器公告等）
            add(new ChatMessage(null, "", message, plain, System.currentTimeMillis(), false));
        }
    }

    /**
     * 本地记录一条自己发送的消息，发送瞬间即可在界面显示（不必等服务器回显）。
     */
    public static void receiveSelf(String text) {
        ensureSessionLoaded();
        Minecraft mc = Minecraft.getInstance();
        String name = mc.player != null ? mc.player.getGameProfile().getName() : "我";
        add(new ChatMessage(
                mc.player != null ? mc.player.getUUID() : null,
                name,
                Component.literal(text),
                text,
                System.currentTimeMillis(),
                true
        ));
    }

    private static void add(ChatMessage message) {
        List<ChatMessage> list = CONVERSATIONS.computeIfAbsent(message.conversationKey(), k -> new ArrayList<>());
        // 去重：服务端会把刚发送的消息广播回来，避免与本地记录重复显示
        if (message.self()) {
            for (int i = list.size() - 1; i >= 0 && i >= list.size() - 5; i--) {
                ChatMessage prev = list.get(i);
                if (prev.self()
                        && prev.plainText().equals(message.plainText())
                        && message.timestamp() - prev.timestamp() < 3000L) {
                    return;
                }
            }
        }
        list.add(message);
        while (list.size() > ChatHistoryStore.MAX_MESSAGES) {
            list.remove(0);
        }
        ChatHistoryStore.scheduleSave();
    }

    /** 某会话的消息列表（只读副本，按时间升序）。 */
    public static List<ChatMessage> messages(String conversationKey) {
        ensureSessionLoaded();
        return List.copyOf(CONVERSATIONS.getOrDefault(conversationKey, List.of()));
    }

    /** 所有会话键（公共频道固定存在）。 */
    public static Set<String> conversationKeys() {
        ensureSessionLoaded();
        return new LinkedHashSet<>(CONVERSATIONS.keySet());
    }

    /** 供持久化模块读取全部会话数据。 */
    public static Map<String, List<ChatMessage>> allConversations() {
        return Collections.unmodifiableMap(CONVERSATIONS);
    }
}