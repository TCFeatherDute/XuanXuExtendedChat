package com.tc.xuanxuchat.client;

import com.tc.xuanxuchat.ChatHub;
import com.tc.xuanxuchat.ChatImageCache;
import com.tc.xuanxuchat.ChatMessage;
import com.tc.xuanxuchat.ChatTheme;
import com.tc.xuanxuchat.PlayerHeadAction;
import com.tc.xuanxuchat.PlayerHeadActions;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FormattedCharSequence;
import org.lwjgl.glfw.GLFW;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 玄墟聊天主界面：聊天 App 风格。
 * <p>
 * 左侧会话列表（搜索框 + 公共频道 + 在线玩家，带未读红点），
 * 右侧消息气泡流（含时间分隔、系统头像、图片），底部输入框（含 @ 补全、表情、引用）。
 */
public class XuanXuChatScreen extends Screen {
    private static final int SIDEBAR_WIDTH = 140;
    private static final int MARGIN = 8;
    private static final int BAR_HEIGHT = 18;
    private static final int AVATAR = 16;
    private static final int LINE = 9;
    private static final int BUBBLE_PAD = 6;
    private static final int TIME_SEPARATOR_HEIGHT = 14;

    /** 消息中的图片 / GIF 链接（png / jpg / jpeg / gif / webp）。 */
    private static final Pattern IMAGE_URL =
            Pattern.compile("(?i)(https?://\\S+?\\.(?:png|jpe?g|gif|webp)(?:\\?\\S*)?)");
    /** 聊天内图片的最大显示宽度。 */
    private static final int IMAGE_DISPLAY_MAX_W = 160;

    /** 表情 / 颜文字面板内容。 */
    private static final List<String> EMOJIS = List.of(
            "😀", "😂", "😍", "😎", "😭", "😡", "👍", "👎", "❤️", "🔥",
            "✨", "🎉", "😴", "🤔", "😅", "🙏", "💪", "🍀", "🌸", "⭐",
            "(＾▽＾)", "(￣▽￣)", "(≧∇≦)", "(´・ω・`)", "(¬‿¬)", "(๑•̀ㅂ•́)و✧"
    );

    private final String initialText;
    private ChatTheme theme = ChatTheme.DARK;

    private EditBox input;
    private EditBox searchBox;

    /** 当前会话：{@link ChatHub#PUBLIC} 或某个玩家名。 */
    private String conversation = ChatHub.PUBLIC;

    /** 消息区向上滚动的像素。 */
    private double scroll;

    private boolean emojiOpen;
    private String atQuery;

    /** 右键头像菜单。 */
    private String headMenuPlayer;
    private double headMenuX;
    private double headMenuY;

    /** 右键消息菜单（复制 / 引用）。 */
    private ChatMessage messageMenuTarget;
    private double messageMenuX;
    private double messageMenuY;

    private List<PlayerInfo> cachedPlayers = List.of();
    private int lastSeenMessageCount;

    public XuanXuChatScreen(String initialText) {
        super(Component.literal("玄墟聊天"));
        this.initialText = initialText == null ? "" : initialText;
    }

    // ==================== 初始化 ====================

    @Override
    protected void init() {
        ChatHub.ensureSessionLoaded();
        ChatHub.setOpenConversation(this.conversation);
        this.cachedPlayers = collectOnlinePlayers();
        this.lastSeenMessageCount = ChatHub.messages(this.conversation).size();

        int barY = this.height - MARGIN - BAR_HEIGHT;
        int barX = SIDEBAR_WIDTH + MARGIN;

        // 搜索框（侧边栏顶部）
        this.searchBox = new EditBox(this.font, MARGIN, MARGIN + 14, SIDEBAR_WIDTH - MARGIN * 2, 16,
                Component.literal("搜索消息..."));
        this.searchBox.setMaxLength(32);
        addRenderableWidget(this.searchBox);

        // 工具栏：表情
        addRenderableWidget(Button.builder(Component.literal("☺"), b -> this.emojiOpen = !this.emojiOpen)
                .bounds(barX, barY, 22, BAR_HEIGHT)
                .build());

        // 工具栏：图片（从剪贴板粘贴图片链接发送）
        addRenderableWidget(Button.builder(Component.literal("图"), b -> insertClipboardImage())
                .bounds(barX + 24, barY, 22, BAR_HEIGHT)
                .build());

        // 聊天输入框
        int inputX = barX + 50;
        int inputWidth = Math.max(80, this.width - inputX - MARGIN - 48);
        this.input = new EditBox(this.font, inputX, barY, inputWidth, BAR_HEIGHT,
                Component.literal("输入消息..."));
        this.input.setValue(this.initialText);
        this.input.setMaxLength(256);
        this.input.setFocused(true);
        addRenderableWidget(this.input);
        setInitialFocus(this.input);

        // 发送按钮
        addRenderableWidget(Button.builder(Component.literal("发送"),
                        b -> sendMessage(this.input.getValue()))
                .bounds(this.width - MARGIN - 44, barY, 44, BAR_HEIGHT)
                .build());

        // 主题切换（侧边栏底部）
        addRenderableWidget(Button.builder(
                        Component.literal(this.theme.dark() ? "浅色" : "深色"),
                        b -> {
                            String draft = this.input != null ? this.input.getValue() : "";
                            this.theme = this.theme.toggle();
                            this.rebuildWidgets();
                            if (this.input != null) {
                                this.input.setValue(draft);
                                this.input.setFocused(true);
                            }
                        })
                .bounds(MARGIN, barY, 40, BAR_HEIGHT)
                .build());
    }

    /**
     * 从剪贴板读取图片链接并填入输入框（发送图片的入口）。
     * 用户先在浏览器右键「复制图片地址」，再点「图」按钮即可。
     */
    private void insertClipboardImage() {
        String clip = Minecraft.getInstance().keyboardHandler.getClipboard();
        if (clip != null && clip.matches("(?i)^https?://\\S+$")) {
            this.input.insertText(clip);
            this.input.setFocused(true);
        } else if (this.minecraft != null && this.minecraft.player != null) {
            this.minecraft.player.displayClientMessage(
                    Component.literal("请先在浏览器右键复制图片链接，再点「图」按钮"), true);
        }
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        // 打开界面时把当前会话标记为已读；有新消息时自动滚到底部
        ChatHub.setOpenConversation(this.conversation);
        int messageCount = ChatHub.messages(this.conversation).size();
        if (messageCount != this.lastSeenMessageCount) {
            this.lastSeenMessageCount = messageCount;
            this.scroll = 0;
        }
        updateAtQuery();

        guiGraphics.fill(0, 0, this.width, this.height, this.theme.background());

        renderSidebar(guiGraphics, mouseX, mouseY);
        renderMessages(guiGraphics, mouseX, mouseY);
        renderInputBar(guiGraphics);

        for (var renderable : this.renderables) {
            renderable.render(guiGraphics, mouseX, mouseY, partialTick);
        }

        if (this.emojiOpen) {
            renderEmojiPanel(guiGraphics, mouseX, mouseY);
        }
        if (this.atQuery != null && !this.atQuery.isEmpty()) {
            renderAtPopup(guiGraphics, mouseX, mouseY);
        }
        if (this.headMenuPlayer != null) {
            renderHeadMenu(guiGraphics, mouseX, mouseY);
        }
        if (this.messageMenuTarget != null) {
            renderMessageMenu(guiGraphics, mouseX, mouseY);
        }
    }

    private List<PlayerInfo> collectOnlinePlayers() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.getConnection() == null) {
            return List.of();
        }
        return new ArrayList<>(mc.getConnection().getOnlinePlayers());
    }

    // ==================== 侧边栏 ====================

    private void renderSidebar(GuiGraphics guiGraphics, int mouseX, int mouseY) {
        guiGraphics.fill(0, 0, SIDEBAR_WIDTH, this.height, this.theme.panel());
        guiGraphics.drawString(this.font, "会话", MARGIN, MARGIN, this.theme.accent());

        int y = MARGIN + 34;

        // 公共频道
        boolean publicSelected = ChatHub.PUBLIC.equals(this.conversation);
        if (publicSelected) {
            guiGraphics.fill(3, y, SIDEBAR_WIDTH - 3, y + 18, this.theme.panelSelected());
        }
        guiGraphics.drawString(this.font, "公共频道", MARGIN + 2, y + 5,
                publicSelected ? this.theme.accent() : this.theme.textPrimary());
        drawUnreadBadge(guiGraphics, ChatHub.PUBLIC, y);
        y += 22;

        // 在线玩家（点击私聊）
        for (PlayerInfo info : this.cachedPlayers) {
            String name = info.getProfile().getName();
            boolean selected = name.equals(this.conversation);
            if (selected) {
                guiGraphics.fill(3, y, SIDEBAR_WIDTH - 3, y + 18, this.theme.panelSelected());
            }
            drawPlayerHead(guiGraphics, name, MARGIN + 2, y + 1, 14);
            boolean online = isOnline(name);
            int nameColor = selected ? this.theme.accent() : (online ? this.theme.textPrimary() : this.theme.textSecondary());
            guiGraphics.drawString(this.font, name, MARGIN + 20, y + 5, nameColor);
            drawUnreadBadge(guiGraphics, name, y);
            y += 20;
        }

        // 离线但有历史的私聊会话
        for (String key : ChatHub.conversationKeys()) {
            if (ChatHub.PUBLIC.equals(key) || findPlayer(key) != null) {
                continue;
            }
            boolean selected = key.equals(this.conversation);
            if (selected) {
                guiGraphics.fill(3, y, SIDEBAR_WIDTH - 3, y + 18, this.theme.panelSelected());
            }
            drawPlayerHead(guiGraphics, key, MARGIN + 2, y + 1, 14);
            guiGraphics.drawString(this.font, key, MARGIN + 20, y + 5,
                    selected ? this.theme.accent() : this.theme.textSecondary());
            drawUnreadBadge(guiGraphics, key, y);
            y += 20;
        }
    }

    /** 未读红点 + 数字。 */
    private void drawUnreadBadge(GuiGraphics guiGraphics, String conversationKey, int y) {
        int unread = ChatHub.unreadOf(conversationKey);
        if (unread <= 0) {
            return;
        }
        String text = unread > 99 ? "99+" : String.valueOf(unread);
        int w = Math.max(10, this.font.width(text) + 4);
        int x = SIDEBAR_WIDTH - MARGIN - w;
        guiGraphics.fill(x, y + 4, x + w, y + 15, 0xFFE04040);
        guiGraphics.drawString(this.font, text, x + 2, y + 6, 0xFFFFFFFF, false);
    }

    // ==================== 消息区 ====================

    private void renderMessages(GuiGraphics guiGraphics, int mouseX, int mouseY) {
        int areaX = SIDEBAR_WIDTH + MARGIN;
        int areaY = MARGIN;
        int areaW = this.width - SIDEBAR_WIDTH - MARGIN * 2;
        int areaH = this.height - MARGIN * 2 - BAR_HEIGHT - 8;

        guiGraphics.fill(areaX, areaY, areaX + areaW, areaY + areaH, 0x30000000);
        guiGraphics.enableScissor(areaX, areaY, areaX + areaW, areaY + areaH);

        List<ChatMessage> all = ChatHub.messages(this.conversation);
        List<ChatMessage> messages = filterMessages(all);

        if (messages.isEmpty()) {
            String hint = all.isEmpty()
                    ? (ChatHub.PUBLIC.equals(this.conversation) ? "暂无消息，按 Enter 发送第一条" : "与 " + this.conversation + " 的私聊尚无记录")
                    : "没有匹配「" + searchQuery() + "」的消息";
            guiGraphics.drawString(this.font, hint,
                    areaX + (areaW - this.font.width(hint)) / 2, areaY + areaH / 2,
                    this.theme.textSecondary());
        } else {
            int bottom = areaY + areaH;
            int cursor = bottom;
            for (int i = messages.size() - 1; i >= 0; i--) {
                ChatMessage message = messages.get(i);
                int height = messageHeight(message, areaW);
                cursor -= height;
                // 时间分隔：与上一条间隔超过 5 分钟时插入时间行
                boolean showTime = i == 0 || message.timestamp() - messages.get(i - 1).timestamp() > 5 * 60_000L;
                if (showTime) {
                    cursor -= TIME_SEPARATOR_HEIGHT;
                }
                int drawY = cursor + (int) this.scroll;
                if (drawY + height < areaY) {
                    break;
                }
                if (drawY <= areaY + areaH) {
                    if (showTime) {
                        drawTimeSeparator(guiGraphics, message.timestamp(), areaX, drawY, areaW);
                    }
                    drawMessage(guiGraphics, message, areaX, drawY + TIME_SEPARATOR_HEIGHT, areaW);
                }
            }
        }

        guiGraphics.disableScissor();
    }

    private String searchQuery() {
        return this.searchBox == null ? "" : this.searchBox.getValue().trim().toLowerCase();
    }

    private List<ChatMessage> filterMessages(List<ChatMessage> messages) {
        String query = searchQuery();
        if (query.isEmpty()) {
            return messages;
        }
        List<ChatMessage> result = new ArrayList<>();
        for (ChatMessage message : messages) {
            if (message.plainText().toLowerCase().contains(query)
                    || message.senderName().toLowerCase().contains(query)) {
                result.add(message);
            }
        }
        return result;
    }

    private void drawTimeSeparator(GuiGraphics guiGraphics, long timestamp, int x, int y, int width) {
        String text = formatTime(timestamp);
        int textW = this.font.width(text);
        int centerX = x + width / 2;
        int lineY = y + 5;
        guiGraphics.fill(x + 10, lineY, centerX - textW / 2 - 6, lineY + 1, this.theme.textSecondary());
        guiGraphics.fill(centerX + textW / 2 + 6, lineY, x + width - 10, lineY + 1, this.theme.textSecondary());
        guiGraphics.drawString(this.font, text, centerX - textW / 2, y + 1, this.theme.textSecondary());
    }

    private String formatTime(long timestamp) {
        Date now = new Date();
        Date date = new Date(timestamp);
        SimpleDateFormat hm = new SimpleDateFormat("HH:mm");
        SimpleDateFormat full = new SimpleDateFormat("MM-dd HH:mm");
        boolean sameDay = sameDay(now, date);
        return sameDay ? hm.format(date) : full.format(date);
    }

    private boolean sameDay(Date a, Date b) {
        SimpleDateFormat f = new SimpleDateFormat("yyyyMMdd");
        return f.format(a).equals(f.format(b));
    }

    private int messageHeight(ChatMessage message, int areaW) {
        if (message.system()) {
            int maxWidth = Math.max(60, areaW - 80);
            int lines = this.font.split(message.content(), maxWidth).size();
            return lines * LINE + 12;
        }
        int maxWidth = Math.max(40, areaW - AVATAR - 40);
        int lines = this.font.split(message.content(), maxWidth).size();
        int bubbleHeight = lines * LINE + BUBBLE_PAD * 2;
        int height = LINE + 2 + bubbleHeight + 8;
        // 图片消息额外高度
        if (findImageUrl(message.plainText()) != null) {
            height += imageDisplayHeight(message.plainText()) + 6;
        }
        return height;
    }

    private void drawMessage(GuiGraphics guiGraphics, ChatMessage message, int areaX, int areaY, int areaW) {
        if (message.system()) {
            // 系统消息：居中灰字 + 系统头像
            int maxWidth = Math.max(60, areaW - 80);
            List<FormattedCharSequence> lines = this.font.split(message.content(), maxWidth);
            int textW = 0;
            for (FormattedCharSequence line : lines) {
                textW = Math.max(textW, this.font.width(line));
            }
            int textX = areaX + (areaW - textW) / 2;
            int textY = areaY + 2;
            drawSystemAvatar(guiGraphics, textX - AVATAR - 6, textY);
            int ty = textY + 2;
            for (FormattedCharSequence line : lines) {
                guiGraphics.drawString(this.font, line, textX, ty, this.theme.textSecondary());
                ty += LINE;
            }
            return;
        }

        int maxWidth = Math.max(40, areaW - AVATAR - 40);
        List<FormattedCharSequence> lines = this.font.split(message.content(), maxWidth);

        int bubbleWidth = 0;
        for (FormattedCharSequence line : lines) {
            bubbleWidth = Math.max(bubbleWidth, this.font.width(line));
        }
        bubbleWidth += BUBBLE_PAD * 2;
        int bubbleHeight = lines.size() * LINE + BUBBLE_PAD * 2;
        int nameY = areaY;

        if (message.self()) {
            int bubbleX = areaX + areaW - AVATAR - MARGIN - bubbleWidth;
            guiGraphics.drawString(this.font, message.senderName(), bubbleX, nameY, this.theme.accent());
            int bubbleY = nameY + LINE + 2;
            guiGraphics.fill(bubbleX, bubbleY, bubbleX + bubbleWidth, bubbleY + bubbleHeight, this.theme.bubbleSelf());
            int ty = bubbleY + BUBBLE_PAD;
            for (FormattedCharSequence line : lines) {
                guiGraphics.drawString(this.font, line, bubbleX + BUBBLE_PAD, ty, 0xFFFFFFFF);
                ty += LINE;
            }
            // 重复计数
            if (message.repeatCount() > 1) {
                String badge = "x" + message.repeatCount();
                guiGraphics.drawString(this.font, badge,
                        bubbleX + bubbleWidth - this.font.width(badge) - 4, bubbleY + bubbleHeight + 1,
                        0xFFAAAAAA);
            }
            drawPlayerHead(guiGraphics, message.senderName(),
                    areaX + areaW - AVATAR - 2, bubbleY, AVATAR);
            drawMessageImage(guiGraphics, message, bubbleX, bubbleY + bubbleHeight + 2);
        } else {
            drawPlayerHead(guiGraphics, message.senderName(), areaX + 2, areaY + 2, AVATAR);
            int bubbleX = areaX + AVATAR + 8;
            if (!message.senderName().isEmpty()) {
                guiGraphics.drawString(this.font, message.senderName(), bubbleX, nameY, this.theme.accent());
            }
            int bubbleY = nameY + LINE + 2;
            guiGraphics.fill(bubbleX, bubbleY, bubbleX + bubbleWidth, bubbleY + bubbleHeight, this.theme.bubbleOthers());
            int ty = bubbleY + BUBBLE_PAD;
            for (FormattedCharSequence line : lines) {
                guiGraphics.drawString(this.font, line, bubbleX + BUBBLE_PAD, ty, this.theme.textPrimary());
                ty += LINE;
            }
            if (message.repeatCount() > 1) {
                String badge = "x" + message.repeatCount();
                guiGraphics.drawString(this.font, badge,
                        bubbleX + bubbleWidth - this.font.width(badge) - 4, bubbleY + bubbleHeight + 1,
                        0xFFAAAAAA);
            }
            drawMessageImage(guiGraphics, message, bubbleX, bubbleY + bubbleHeight + 2);
        }
    }

    /** 检测消息中的图片 URL（取第一个）。 */
    private String findImageUrl(String text) {
        if (text == null || text.isEmpty()) {
            return null;
        }
        Matcher matcher = IMAGE_URL.matcher(text);
        return matcher.find() ? matcher.group(1) : null;
    }

    /** 图片显示高度（未加载时为占位行高）。 */
    private int imageDisplayHeight(String text) {
        String url = findImageUrl(text);
        if (url == null) {
            return 0;
        }
        ChatImageCache.Entry entry = ChatImageCache.get(url);
        if (entry.frames.isEmpty()) {
            return LINE + 2;
        }
        var first = entry.frames.get(0);
        int drawW = Math.min(IMAGE_DISPLAY_MAX_W, first.getWidth());
        return Math.max(1, (int) ((double) drawW / first.getWidth() * first.getHeight()));
    }

    /** 在气泡下方渲染图片（支持 GIF 动画）。 */
    private void drawMessageImage(GuiGraphics guiGraphics, ChatMessage message, int x, int y) {
        String url = findImageUrl(message.plainText());
        if (url == null) {
            return;
        }
        ChatImageCache.Entry entry = ChatImageCache.get(url);
        if (entry.state == ChatImageCache.State.FAILED) {
            guiGraphics.drawString(this.font, "[图片加载失败]", x, y, 0xFFFF6666);
            return;
        }
        if (entry.location == null || entry.frames.isEmpty()) {
            guiGraphics.drawString(this.font, "[图片加载中...]", x, y, 0xFFAAAAAA);
            return;
        }
        // GIF 推进
        ChatImageCache.animate(entry, 100);
        var first = entry.frames.get(0);
        int texW = first.getWidth();
        int texH = first.getHeight();
        int drawW = Math.min(IMAGE_DISPLAY_MAX_W, texW);
        int drawH = Math.max(1, (int) ((double) drawW / texW * texH));
        guiGraphics.blit(entry.location, x, y, drawW, drawH, 0, 0, texW, texH, texW, texH);
    }

    /** 系统消息头像：灰色方块 + 感叹号。 */
    private void drawSystemAvatar(GuiGraphics guiGraphics, int x, int y) {
        guiGraphics.fill(x, y, x + AVATAR, y + AVATAR, 0xFF4A4A5A);
        guiGraphics.drawString(this.font, "!", x + 6, y + 4, 0xFFFFFFFF);
    }

    // ==================== 输入区 ====================

    private void renderInputBar(GuiGraphics guiGraphics) {
        int y = this.height - MARGIN - BAR_HEIGHT;
        guiGraphics.fill(SIDEBAR_WIDTH, y - 4, this.width, this.height, this.theme.panel());
    }

    // ==================== @ 补全 ====================

    private void updateAtQuery() {
        if (this.input == null) {
            this.atQuery = null;
            return;
        }
        String text = this.input.getValue();
        int cursor = Math.min(this.input.getCursorPosition(), text.length());
        String before = text.substring(0, cursor);
        int at = before.lastIndexOf('@');
        if (at < 0) {
            this.atQuery = null;
            return;
        }
        String query = before.substring(at + 1);
        this.atQuery = query.matches("[\\w\\u4e00-\\u9fa5]*") ? query : null;
    }

    private List<String> atCandidates() {
        List<String> result = new ArrayList<>();
        for (PlayerInfo info : this.cachedPlayers) {
            String name = info.getProfile().getName();
            if (name.toLowerCase().contains(this.atQuery.toLowerCase())) {
                result.add(name);
            }
        }
        return result;
    }

    private void renderAtPopup(GuiGraphics guiGraphics, int mouseX, int mouseY) {
        List<String> candidates = atCandidates();
        if (candidates.isEmpty()) {
            return;
        }
        int itemHeight = 14;
        int width = 140;
        int height = candidates.size() * itemHeight + 4;
        int x = SIDEBAR_WIDTH + MARGIN + 4;
        int y = this.height - MARGIN - BAR_HEIGHT - height - 4;

        guiGraphics.fill(x, y, x + width, y + height, 0xF0282832);
        for (int i = 0; i < candidates.size(); i++) {
            int itemY = y + 2 + i * itemHeight;
            boolean hover = mouseX >= x && mouseX < x + width && mouseY >= itemY && mouseY < itemY + itemHeight;
            if (hover) {
                guiGraphics.fill(x + 2, itemY, x + width - 2, itemY + itemHeight - 1, 0xFF3A3A52);
            }
            guiGraphics.drawString(this.font, candidates.get(i), x + 6, itemY + 3,
                    hover ? 0xFFFFFFFF : this.theme.textPrimary());
        }
    }

    /** 点击 @ 候选列表，返回是否命中。 */
    private boolean clickAtCandidate(double mouseX, double mouseY) {
        if (this.atQuery == null || this.atQuery.isEmpty()) {
            return false;
        }
        List<String> candidates = atCandidates();
        if (candidates.isEmpty()) {
            return false;
        }
        int itemHeight = 14;
        int width = 140;
        int height = candidates.size() * itemHeight + 4;
        int x = SIDEBAR_WIDTH + MARGIN + 4;
        int y = this.height - MARGIN - BAR_HEIGHT - height - 4;
        for (int i = 0; i < candidates.size(); i++) {
            int itemY = y + 2 + i * itemHeight;
            if (mouseX >= x && mouseX < x + width && mouseY >= itemY && mouseY < itemY + itemHeight) {
                insertAtMention(candidates.get(i));
                return true;
            }
        }
        return false;
    }

    private void insertAtMention(String playerName) {
        String text = this.input.getValue();
        int cursor = Math.min(this.input.getCursorPosition(), text.length());
        String before = text.substring(0, cursor);
        int at = before.lastIndexOf('@');
        if (at < 0) {
            return;
        }
        String newText = text.substring(0, at) + "@" + playerName + " " + text.substring(cursor);
        this.input.setValue(newText);
        this.input.setCursorPosition(newText.length());
        this.atQuery = null;
    }

    // ==================== 表情面板 ====================

    private void renderEmojiPanel(GuiGraphics guiGraphics, int mouseX, int mouseY) {
        int columns = 6;
        int cell = 34;
        int rows = (EMOJIS.size() + columns - 1) / columns;
        int panelW = columns * cell + 8;
        int panelH = rows * cell + 8;
        int x = this.width - panelW - MARGIN;
        int y = this.height - MARGIN - BAR_HEIGHT - panelH - 4;
        guiGraphics.fill(x, y, x + panelW, y + panelH, 0xF0202028);

        for (int i = 0; i < EMOJIS.size(); i++) {
            int cx = x + 4 + (i % columns) * cell;
            int cy = y + 4 + (i / columns) * cell;
            boolean hover = mouseX >= cx && mouseX < cx + cell - 4 && mouseY >= cy && mouseY < cy + cell - 4;
            if (hover) {
                guiGraphics.fill(cx, cy, cx + cell - 4, cy + cell - 4, 0x40FFFFFF);
            }
            guiGraphics.drawString(this.font, EMOJIS.get(i), cx + 2, cy + 2, 0xFFFFFFFF);
        }
    }

    private String emojiAt(double mouseX, double mouseY) {
        int columns = 6;
        int cell = 34;
        int rows = (EMOJIS.size() + columns - 1) / columns;
        int panelW = columns * cell + 8;
        int panelH = rows * cell + 8;
        int x = this.width - panelW - MARGIN;
        int y = this.height - MARGIN - BAR_HEIGHT - panelH - 4;
        for (int i = 0; i < EMOJIS.size(); i++) {
            int cx = x + 4 + (i % columns) * cell;
            int cy = y + 4 + (i / columns) * cell;
            if (mouseX >= cx && mouseX < cx + cell - 4 && mouseY >= cy && mouseY < cy + cell - 4) {
                return EMOJIS.get(i);
            }
        }
        return null;
    }

    // ==================== 右键头像菜单 ====================

    private void renderHeadMenu(GuiGraphics guiGraphics, int mouseX, int mouseY) {
        List<PlayerHeadAction> actions = PlayerHeadActions.visibleFor(this.headMenuPlayer);
        if (actions.isEmpty()) {
            return;
        }
        int width = 132;
        int itemHeight = 16;
        int height = actions.size() * itemHeight + 16;
        int x = (int) Math.max(2, Math.min(this.headMenuX, this.width - width - 2));
        int y = (int) Math.max(2, Math.min(this.headMenuY, this.height - height - 2));

        guiGraphics.fill(x, y, x + width, y + height, 0xF0282832);
        guiGraphics.drawString(this.font, this.headMenuPlayer, x + 5, y + 4, this.theme.accent());
        for (int i = 0; i < actions.size(); i++) {
            int itemY = y + 14 + i * itemHeight;
            boolean hover = mouseX >= x && mouseX < x + width && mouseY >= itemY && mouseY < itemY + itemHeight;
            if (hover) {
                guiGraphics.fill(x + 2, itemY, x + width - 2, itemY + itemHeight - 1, 0xFF3A3A52);
            }
            guiGraphics.drawString(this.font, actions.get(i).label(), x + 6, itemY + 4,
                    hover ? 0xFFFFFFFF : this.theme.textPrimary());
        }
    }

    private int headMenuItemAt(double mouseX, double mouseY) {
        List<PlayerHeadAction> actions = PlayerHeadActions.visibleFor(this.headMenuPlayer);
        if (actions.isEmpty()) {
            return -1;
        }
        int width = 132;
        int itemHeight = 16;
        int height = actions.size() * itemHeight + 16;
        int x = (int) Math.max(2, Math.min(this.headMenuX, this.width - width - 2));
        int y = (int) Math.max(2, Math.min(this.headMenuY, this.height - height - 2));
        for (int i = 0; i < actions.size(); i++) {
            int itemY = y + 14 + i * itemHeight;
            if (mouseX >= x && mouseX < x + width && mouseY >= itemY && mouseY < itemY + itemHeight) {
                return i;
            }
        }
        return -1;
    }

    // ==================== 右键消息菜单（复制 / 引用） ====================

    private void renderMessageMenu(GuiGraphics guiGraphics, int mouseX, int mouseY) {
        if (this.messageMenuTarget == null) {
            return;
        }
        int width = 96;
        int itemHeight = 16;
        String[] labels = {"复制消息", "引用回复"};
        int height = labels.length * itemHeight + 4;
        int x = (int) Math.max(2, Math.min(this.messageMenuX, this.width - width - 2));
        int y = (int) Math.max(2, Math.min(this.messageMenuY, this.height - height - 2));

        guiGraphics.fill(x, y, x + width, y + height, 0xF0282832);
        for (int i = 0; i < labels.length; i++) {
            int itemY = y + 2 + i * itemHeight;
            boolean hover = mouseX >= x && mouseX < x + width && mouseY >= itemY && mouseY < itemY + itemHeight;
            if (hover) {
                guiGraphics.fill(x + 2, itemY, x + width - 2, itemY + itemHeight - 1, 0xFF3A3A52);
            }
            guiGraphics.drawString(this.font, labels[i], x + 6, itemY + 4,
                    hover ? 0xFFFFFFFF : this.theme.textPrimary());
        }
    }

    private int messageMenuItemAt(double mouseX, double mouseY) {
        if (this.messageMenuTarget == null) {
            return -1;
        }
        int width = 96;
        int itemHeight = 16;
        int height = 2 * itemHeight + 4;
        int x = (int) Math.max(2, Math.min(this.messageMenuX, this.width - width - 2));
        int y = (int) Math.max(2, Math.min(this.messageMenuY, this.height - height - 2));
        for (int i = 0; i < 2; i++) {
            int itemY = y + 2 + i * itemHeight;
            if (mouseX >= x && mouseX < x + width && mouseY >= itemY && mouseY < itemY + itemHeight) {
                return i;
            }
        }
        return -1;
    }

    // ==================== 头像 ====================

    private void drawPlayerHead(GuiGraphics guiGraphics, String playerName, int x, int y, int size) {
        PlayerInfo info = findPlayer(playerName);
        if (info != null) {
            ResourceLocation texture = info.getSkin().texture();
            guiGraphics.blit(texture, x, y, size, size, 8, 8, 8, 8, 64, 64);
        } else {
            guiGraphics.fill(x, y, x + size, y + size, 0xFF4A4A5A);
        }
    }

    private PlayerInfo findPlayer(String name) {
        if (name == null || name.isEmpty()) {
            return null;
        }
        for (PlayerInfo info : this.cachedPlayers) {
            if (info.getProfile().getName().equals(name)) {
                return info;
            }
        }
        return null;
    }

    private boolean isOnline(String name) {
        return findPlayer(name) != null;
    }

    // ==================== 交互 ====================

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        // 表情面板
        if (this.emojiOpen) {
            String emoji = emojiAt(mouseX, mouseY);
            if (emoji != null) {
                this.input.insertText(emoji);
                return true;
            }
            this.emojiOpen = false;
            return true;
        }

        // @ 补全
        if (clickAtCandidate(mouseX, mouseY)) {
            return true;
        }

        // 右键消息菜单
        if (this.messageMenuTarget != null) {
            int index = messageMenuItemAt(mouseX, mouseY);
            if (index >= 0) {
                handleMessageMenuAction(index);
                this.messageMenuTarget = null;
                return true;
            }
            this.messageMenuTarget = null;
        }

        // 右键头像菜单
        if (this.headMenuPlayer != null) {
            int index = headMenuItemAt(mouseX, mouseY);
            if (index >= 0) {
                List<PlayerHeadAction> actions = PlayerHeadActions.visibleFor(this.headMenuPlayer);
                actions.get(index).execute(this.headMenuPlayer);
                this.headMenuPlayer = null;
                return true;
            }
            this.headMenuPlayer = null;
        }

        // 侧边栏点击
        int sidebarItem = sidebarItemAt(mouseX, mouseY);
        if (sidebarItem >= 0) {
            selectSidebarItem(sidebarItem);
            return true;
        }

        // 消息区交互：左键头像私聊 / 右键消息菜单
        ChatMessage messageAt = messageAt(mouseX, mouseY);
        if (messageAt != null) {
            if (button == 1) {
                // 右键：在消息上弹菜单；在头像上弹头像菜单
                if (isOnAvatar(messageAt, mouseX, mouseY)) {
                    this.headMenuPlayer = messageAt.senderName();
                    this.headMenuX = mouseX;
                    this.headMenuY = mouseY;
                } else {
                    this.messageMenuTarget = messageAt;
                    this.messageMenuX = mouseX;
                    this.messageMenuY = mouseY;
                }
            } else if (isOnAvatar(messageAt, mouseX, mouseY)) {
                if (!messageAt.self() && !messageAt.system() && !ChatHub.PUBLIC.equals(messageAt.senderName())) {
                    this.conversation = messageAt.senderName();
                    this.scroll = 0;
                }
            }
            return true;
        }

        return super.mouseClicked(mouseX, mouseY, button);
    }

    private void handleMessageMenuAction(int index) {
        if (this.messageMenuTarget == null) {
            return;
        }
        String text = this.messageMenuTarget.plainText();
        if (index == 0) {
            // 复制消息
            Minecraft.getInstance().keyboardHandler.setClipboard(text);
        } else {
            // 引用回复：用单行前缀，注意聊天消息不允许换行/控制字符（会被服务端踢出）
            String quote = "> " + text + " ";
            this.input.setValue(quote + this.input.getValue());
            this.input.setFocused(true);
        }
        this.messageMenuTarget = null;
    }

    /** 返回侧边栏第几项（0=公共频道，1..=在线玩家，之后为离线会话），-1 表示没有。 */
    private int sidebarItemAt(double mouseX, double mouseY) {
        if (mouseX < 0 || mouseX > SIDEBAR_WIDTH) {
            return -1;
        }
        int y = MARGIN + 34;
        if (mouseY >= y && mouseY < y + 18) {
            return 0;
        }
        y += 22;
        for (int i = 0; i < this.cachedPlayers.size(); i++) {
            if (mouseY >= y && mouseY < y + 18) {
                return i + 1;
            }
            y += 20;
        }
        int offset = this.cachedPlayers.size() + 1;
        for (String key : ChatHub.conversationKeys()) {
            if (ChatHub.PUBLIC.equals(key) || findPlayer(key) != null) {
                continue;
            }
            if (mouseY >= y && mouseY < y + 18) {
                return offset + 1;
            }
            y += 20;
            offset++;
        }
        return -1;
    }

    private void selectSidebarItem(int index) {
        if (index == 0) {
            this.conversation = ChatHub.PUBLIC;
        } else if (index - 1 < this.cachedPlayers.size()) {
            this.conversation = this.cachedPlayers.get(index - 1).getProfile().getName();
        } else {
            int offset = index - (this.cachedPlayers.size() + 1);
            List<String> offline = new ArrayList<>();
            for (String key : ChatHub.conversationKeys()) {
                if (!ChatHub.PUBLIC.equals(key) && findPlayer(key) == null) {
                    offline.add(key);
                }
            }
            if (offset >= 0 && offset < offline.size()) {
                this.conversation = offline.get(offset - 1);
            }
        }
        this.scroll = 0;
        this.lastSeenMessageCount = ChatHub.messages(this.conversation).size();
        ChatHub.setOpenConversation(this.conversation);
    }

    /**
     * 找到鼠标所在的消息（用于右键菜单 / 头像点击）。
     */
    private ChatMessage messageAt(double mouseX, double mouseY) {
        int areaX = SIDEBAR_WIDTH + MARGIN;
        int areaY = MARGIN;
        int areaW = this.width - SIDEBAR_WIDTH - MARGIN * 2;
        int areaH = this.height - MARGIN * 2 - BAR_HEIGHT - 8;
        if (mouseX < areaX || mouseX > areaX + areaW || mouseY < areaY || mouseY > areaY + areaH) {
            return null;
        }

        List<ChatMessage> messages = filterMessages(ChatHub.messages(this.conversation));
        int bottom = areaY + areaH;
        int cursor = bottom;
        for (int i = messages.size() - 1; i >= 0; i--) {
            ChatMessage message = messages.get(i);
            int height = messageHeight(message, areaW);
            cursor -= height;
            boolean showTime = i == 0 || message.timestamp() - messages.get(i - 1).timestamp() > 5 * 60_000L;
            if (showTime) {
                cursor -= TIME_SEPARATOR_HEIGHT;
            }
            int drawY = cursor + (int) this.scroll;
            if (mouseY >= drawY && mouseY < drawY + height) {
                return message;
            }
        }
        return null;
    }

    private boolean isOnAvatar(ChatMessage message, double mouseX, double mouseY) {
        int areaX = SIDEBAR_WIDTH + MARGIN;
        int areaW = this.width - SIDEBAR_WIDTH - MARGIN * 2;
        int areaY = MARGIN;
        int areaH = this.height - MARGIN * 2 - BAR_HEIGHT - 8;
        int bottom = areaY + areaH;
        List<ChatMessage> messages = filterMessages(ChatHub.messages(this.conversation));
        int cursor = bottom;
        for (int i = messages.size() - 1; i >= 0; i--) {
            ChatMessage m = messages.get(i);
            int height = messageHeight(m, areaW);
            cursor -= height;
            boolean showTime = i == 0 || m.timestamp() - messages.get(i - 1).timestamp() > 5 * 60_000L;
            if (showTime) {
                cursor -= TIME_SEPARATOR_HEIGHT;
            }
            int drawY = cursor + (int) this.scroll;
            if (m == message) {
                int headX = m.self() ? areaX + areaW - AVATAR - 2 : areaX + 2;
                int headY = drawY + LINE + 2;
                return mouseX >= headX && mouseX < headX + AVATAR && mouseY >= headY && mouseY < headY + AVATAR;
            }
            if (drawY + height < areaY) {
                break;
            }
        }
        return false;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        int areaH = this.height - MARGIN * 2 - BAR_HEIGHT - 8;
        int total = totalMessagesHeight();
        double max = Math.max(0, total - areaH);
        this.scroll = Math.max(0, Math.min(max, this.scroll - scrollY * 24));
        return true;
    }

    private int totalMessagesHeight() {
        int areaW = this.width - SIDEBAR_WIDTH - MARGIN * 2;
        int total = 0;
        List<ChatMessage> messages = filterMessages(ChatHub.messages(this.conversation));
        for (int i = 0; i < messages.size(); i++) {
            ChatMessage message = messages.get(i);
            total += messageHeight(message, areaW);
            boolean showTime = i == 0 || message.timestamp() - messages.get(i - 1).timestamp() > 5 * 60_000L;
            if (showTime) {
                total += TIME_SEPARATOR_HEIGHT;
            }
        }
        return total;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
            // Shift+Enter 插入换行（单行输入框简化为插入空格）
            if ((modifiers & GLFW.GLFW_MOD_SHIFT) != 0) {
                this.input.insertText(" ");
                return true;
            }
            sendMessage(this.input.getValue());
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
            this.onClose();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    private void sendMessage(String text) {
        if (text == null || text.isBlank()) {
            return;
        }
        // 过滤换行等控制字符：服务端会以「非法聊天字符」拒绝并断开连接
        text = text.replaceAll("[\\p{Cntrl}]", " ").trim();
        if (text.isEmpty()) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.getConnection() == null) {
            return;
        }
        boolean publicChannel = ChatHub.PUBLIC.equals(this.conversation);
        ChatHub.receiveSelf(text, publicChannel ? null : this.conversation);
        if (publicChannel) {
            mc.getConnection().sendChat(text);
        } else {
            mc.getConnection().sendCommand("msg " + this.conversation + " " + text);
        }
        this.input.setValue("");
        this.scroll = 0;
    }

    @Override
    public void onClose() {
        ChatHub.setOpenConversation(null);
        super.onClose();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}