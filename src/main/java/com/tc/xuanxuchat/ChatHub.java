package com.tc.xuanxuchat;

import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 客户端聊天消息中枢。
 * <p>
 * 负责接收聊天消息、按会话分组、防止刷屏合并、维护未读数，
 * 并交给 {@link ChatHistoryStore} 持久化。
 */
public final class ChatHub {
    /** 公共频道的会话键。 */
    public static final String PUBLIC = "public";

    private static final Logger LOGGER = LogUtils.getLogger();

    /** 原版公共聊天格式：{@code <PlayerName> message} */
    private static final Pattern PLAYER_CHAT = Pattern.compile("^<([^>]+)> (.*)$");
    /** 自己发出的私聊：{@code 你悄悄地对X说：消息} / {@code You whisper to X: message} */
    private static final Pattern WHISPER_TO = Pattern.compile("^(?:你悄悄地对|You whisper to )(.+?)(?:说[：:]|: )(.*)$");
    /** 收到的私聊：{@code X悄悄地对你说：消息} / {@code X whispers to you: message} */
    private static final Pattern WHISPER_FROM = Pattern.compile("^(.+?)(?:悄悄地对你说[：:]| whispers to you: )(.*)$");

    private static final Map<String, List<ChatMessage>> CONVERSATIONS = new java.util.LinkedHashMap<>();
    private static final Map<String, Integer> UNREAD = new HashMap<>();

    /** 已发出但还没收到服务端回显的消息，用于精确去重。 */
    private static final List<PendingEcho> PENDING_ECHO = new ArrayList<>();
    /** 超过这个时间的待回显记录直接丢弃（服务器没回显 / 消息被拦下）。 */
    private static final long ECHO_TIMEOUT_MS = 30_000L;

    private static String sessionId;
    /** 当前打开中的会话（用于未读统计）。 */
    private static String openConversation;

    /** 一条等待服务端回显的自发消息。 */
    private record PendingEcho(String conversation, String text, long timestamp) {
    }

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
            UNREAD.clear();
            PENDING_ECHO.clear();
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

        // 1) 自己发出的私聊 → 归入与目标玩家的会话
        Matcher matcher = WHISPER_TO.matcher(plain);
        if (matcher.matches()) {
            String target = matcher.group(1).trim();
            String body = matcher.group(2);
            if (consumeEcho(target, body)) {
                return;
            }
            add(ChatMessage.player(selfName, target, message, body, true));
            return;
        }
        // 2) 收到的私聊 → 归入与发送者的会话
        matcher = WHISPER_FROM.matcher(plain);
        if (matcher.matches()) {
            add(ChatMessage.player(matcher.group(1).trim(), matcher.group(1).trim(), message, matcher.group(2), false));
            return;
        }
        // 3) 普通聊天
        matcher = PLAYER_CHAT.matcher(plain);
        if (matcher.matches()) {
            String sender = matcher.group(1);
            String body = matcher.group(2);
            boolean self = sender.equals(selfName);
            // 自己发的消息已经在发送瞬间本地显示过，服务端回显直接丢弃
            if (self && consumeEcho(PUBLIC, body)) {
                return;
            }
            add(ChatMessage.player(sender, PUBLIC, message, body, self));
            return;
        }
        // 4) 系统消息
        add(ChatMessage.system(plain));
    }

    /**
     * 本地记录一条自己发送的消息，发送瞬间即可显示（不必等服务器回显）。
     *
     * @param target 私聊目标玩家名；null 表示公共频道
     */
    public static void receiveSelf(String text, String target) {
        ensureSessionLoaded();
        Minecraft mc = Minecraft.getInstance();
        String name = mc.player != null ? mc.player.getGameProfile().getName() : "我";
        ChatMessage message = new ChatMessage(
                mc.player != null ? mc.player.getUUID() : null,
                name,
                target == null ? PUBLIC : target,
                Component.literal(text),
                text,
                System.currentTimeMillis(),
                true,
                false,
                1
        );
        add(message);
        // 记下这次发送，等服务端回显时把它丢掉（避免同一条消息显示两次）
        PENDING_ECHO.add(new PendingEcho(message.conversation(), text, System.currentTimeMillis()));
    }

    /**
     * 本地记录一条自己发出的命令。
     * <p>
     * 命令不会从服务端回显（只有执行结果），所以在公共频道里补一条只自己可见的记录，
     * 让玩家能看到「我刚才发了什么命令」。这段消息不会发到服务器，别人看不到。
     */
    public static void receiveSelfCommand(String text) {
        ensureSessionLoaded();
        Minecraft mc = Minecraft.getInstance();
        String name = mc.player != null ? mc.player.getGameProfile().getName() : "我";
        add(new ChatMessage(
                mc.player != null ? mc.player.getUUID() : null,
                name,
                PUBLIC,
                Component.literal(text),
                text,
                System.currentTimeMillis(),
                true,
                false,
                1
        ));
    }

    /**
     * 检查这条消息是不是自己刚发的那条的服务端回显。
     * <p>
     * 命中则移除待回显记录并返回 true，调用方应丢弃该消息。
     * 相比「与上一条消息比较」，这样即使中间插入了别人的消息也不会重复显示。
     */
    private static boolean consumeEcho(String conversation, String text) {
        if (PENDING_ECHO.isEmpty()) {
            return false;
        }
        long now = System.currentTimeMillis();
        PENDING_ECHO.removeIf(pending -> now - pending.timestamp() > ECHO_TIMEOUT_MS);
        for (int i = PENDING_ECHO.size() - 1; i >= 0; i--) {
            PendingEcho pending = PENDING_ECHO.get(i);
            if (pending.conversation().equals(conversation) && pending.text().equals(text)) {
                PENDING_ECHO.remove(i);
                return true;
            }
        }
        return false;
    }

    private static void add(ChatMessage message) {
        List<ChatMessage> list = CONVERSATIONS.computeIfAbsent(message.conversation(), k -> new ArrayList<>());

        // 防刷屏 + 回显去重：与上一条内容相同的连续消息合并计数
        if (!list.isEmpty()) {
            ChatMessage last = list.get(list.size() - 1);
            if (last.self() == message.self()
                    && last.system() == message.system()
                    && last.senderName().equals(message.senderName())
                    && last.plainText().equals(message.plainText())
                    && message.timestamp() - last.timestamp() < 60_000L) {
                list.set(list.size() - 1, last.withRepeat(last.repeatCount() + 1));
                ChatHistoryStore.scheduleSave();
                return;
            }
        }

        list.add(message);
        while (list.size() > ChatHistoryStore.MAX_MESSAGES) {
            list.remove(0);
        }

        // 未读统计：当前不在看的会话累加
        if (openConversation == null || !openConversation.equals(message.conversation())) {
            UNREAD.merge(message.conversation(), 1, Integer::sum);
        }
        ChatHistoryStore.scheduleSave();
    }

    /** 设置当前正在查看的会话（打开聊天界面 / 切换会话时调用），会清空该会话未读。 */
    public static void setOpenConversation(String conversation) {
        openConversation = conversation;
        if (conversation != null) {
            UNREAD.remove(conversation);
        }
    }

    /** 某会话的未读数。 */
    public static int unreadOf(String conversation) {
        return UNREAD.getOrDefault(conversation, 0);
    }

    /** 全部未读总数（用于 HUD 提示，可选）。 */
    public static int totalUnread() {
        return UNREAD.values().stream().mapToInt(Integer::intValue).sum();
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