package com.tc.xuanxuchat.client;

import com.tc.xuanxuchat.ChatEmojiLoader;
import com.tc.xuanxuchat.ChatHub;
import com.tc.xuanxuchat.ChatImageCache;
import com.tc.xuanxuchat.ChatMessage;
import com.tc.xuanxuchat.ChatTheme;
import com.tc.xuanxuchat.PlayerHeadAction;
import com.tc.xuanxuchat.PlayerHeadActions;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
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
 * 窗口化布局（可切换大/中/小三档大小），左侧会话列表（搜索 + 公共频道 + 在线玩家），
 * 右侧消息气泡流（时间分隔、系统头像、图片），底部 QQ 风格工具栏（表情 / 图片 / 发送）。
 */
public class XuanXuChatScreen extends Screen {
    private static final int SIDEBAR_WIDTH = 140;
    private static final int MARGIN = 8;
    private static final int BAR_HEIGHT = 18;
    /** 侧边栏搜索框高度，以及它相对「会话」标题的下移量。 */
    private static final int SEARCH_BOX_HEIGHT = 16;
    private static final int SEARCH_BOX_TOP_OFFSET = 13;
    private static final int AVATAR = 16;
    private static final int LINE = 9;
    private static final int BUBBLE_PAD = 6;
    private static final int TIME_SEPARATOR_HEIGHT = 14;
    private static final int EMOJI_CELL = 34;
    /** 表情面板每行列数、单格图片绘制尺寸。 */
    private static final int EMOJI_COLUMNS = 4;
    private static final int EMOJI_PANEL_SIZE = 24;
    /** 消息气泡内表情的绘制尺寸。 */
    private static final int EMOJI_SIZE = 18;
    /** 窗口顶部标题栏高度。 */
    private static final int TITLE_BAR_HEIGHT = 22;

    /** 消息中的图片 / GIF 链接。 */
    private static final Pattern IMAGE_URL =
            Pattern.compile("(?i)(https?://\\S+?\\.(?:png|jpe?g|gif|webp)(?:\\?\\S*)?)");
    private static final int IMAGE_DISPLAY_MAX_W = 160;

    /** QQ 风格黄豆表情（绘制 + 插入对应的 unicode emoji）。 */
    private enum Face {
        SMILE("😊"), LAUGH("😂"), WINK("😉"), CRY("😭"),
        ANGRY("😠"), SURPRISED("😮"), SHY("😳"), LOVE("😍");

        private final String emoji;

        Face(String emoji) {
            this.emoji = emoji;
        }
    }

    /** 颜文字（附在表情面板后面）。 */
    private static final List<String> KAOMOJI = List.of(
            "(＾▽＾)", "(￣▽￣)", "(≧∇≦)", "(´・ω・`)", "(¬‿¬)", "(๑•̀ㅂ•́)و✧"
    );

    private static final int FACE_YELLOW = 0xFFFFD93D;
    private static final int FACE_DARK = 0xFF5C4A28;
    private static final int FACE_BLUSH = 0xFFFF9E9E;

    private final String initialText;
    /** 主题固定深色（浅色主题先下线，配色问题太多）。 */
    private final ChatTheme theme = ChatTheme.DARK;

    private EditBox input;
    private EditBox searchBox;

    /** 是否已打开某个会话（false 时右侧只显示提示，点击左侧会话才展开）。 */
    private boolean conversationOpen;

    /** 右侧面板是否已收起（收起到只剩会话列表）。 */
    private boolean collapsed;

    /** 输入框草稿：重建控件（切主题 / 收起展开）时保留，否则打了一半的字会丢。 */
    private String draft;
    /** 搜索框草稿。 */
    private String searchDraft = "";

    private String conversation = ChatHub.PUBLIC;
    private double scroll;
    private boolean emojiOpen;
    private String atQuery;

    private String headMenuPlayer;
    private double headMenuX;
    private double headMenuY;

    private ChatMessage messageMenuTarget;
    private double messageMenuX;
    private double messageMenuY;

    private List<PlayerInfo> cachedPlayers = List.of();

    private final List<FlatButton> flatButtons = new ArrayList<>();

    /** 固定面板区域（屏幕左侧）。 */
    private int panelX;
    private int panelY;
    private int panelW;
    private int panelH;

    private String hintText;
    private long hintUntil;

    private record FlatButton(int x, int y, int w, int h, Component label, Runnable action) {
    }

    /**
     * 气泡里的一个渲染段：普通文字、内置黄豆表情、或自定义表情图片。
     *
     * @param text  文字内容；表情段为 null
     * @param face  内置黄豆表情；否则 null
     * @param emoji 自定义表情图片；否则 null
     * @param x     在本行内的横坐标偏移
     */
    private record Segment(String text, Face face, ChatEmojiLoader.EmojiEntry emoji, int x) {
    }

    /** 混排后的一行：若干段 + 行宽 + 行高（含表情的行更高）。 */
    private record MessageLine(List<Segment> segments, int width, int height) {
    }

    public XuanXuChatScreen(String initialText) {
        super(Component.literal("玄墟聊天"));
        this.initialText = initialText == null ? "" : initialText;
        this.draft = this.initialText;
    }

    // ==================== 初始化 ====================

    @Override
    protected void init() {
        ChatHub.ensureSessionLoaded();
        ChatHub.setOpenConversation(this.conversationOpen && !this.collapsed ? this.conversation : null);
        this.cachedPlayers = collectOnlinePlayers();
        this.flatButtons.clear();
        computePanel();

        int barY = this.panelY + this.panelH - MARGIN - BAR_HEIGHT;
        int barX = this.panelX + SIDEBAR_WIDTH + MARGIN;

        // 搜索框（侧边栏顶部，标题栏下方）：收起状态下也保留
        this.searchBox = new EditBox(this.font, this.panelX + MARGIN + 2,
                this.searchBoxY(),
                SIDEBAR_WIDTH - MARGIN * 2 - 4, SEARCH_BOX_HEIGHT, Component.literal("搜索消息..."));
        this.searchBox.setMaxLength(32);
        this.searchBox.setBordered(false);
        this.searchBox.setHint(Component.literal("搜索消息"));
        this.searchBox.setValue(this.searchDraft);
        addRenderableWidget(this.searchBox);

        // 右侧面板收起时，输入区和工具栏整个不存在
        if (this.collapsed) {
            this.input = null;
            return;
        }

        // 工具栏：表情
        addFlatButton(barX, barY, 22, BAR_HEIGHT, Component.literal("☺"),
                () -> this.emojiOpen = !this.emojiOpen);

        // 工具栏：图片
        addFlatButton(barX + 26, barY, 22, BAR_HEIGHT, Component.literal("图"),
                this::insertClipboardImage);

        // 聊天输入框
        int inputX = barX + 58;
        int inputWidth = Math.max(80, this.panelX + this.panelW - MARGIN - 52 - inputX);
        this.input = new EditBox(this.font, inputX, barY, inputWidth, BAR_HEIGHT,
                Component.literal("输入消息..."));
        this.input.setValue(this.draft);
        this.input.setMaxLength(256);
        this.input.setFocused(true);
        this.input.setBordered(false);
        this.input.setHint(Component.literal("输入消息..."));
        this.input.setEditable(this.conversationOpen);
        addRenderableWidget(this.input);
        setInitialFocus(this.input);

        // 标题栏右侧：收起整个右侧面板（不只是清空当前会话）
        addFlatButton(this.panelX + this.panelW - MARGIN - 18, this.panelY + 3, 18, 16,
                Component.literal("×"),
                this::collapseRightPanel);

        // 发送按钮
        addFlatButton(this.panelX + this.panelW - MARGIN - 48, barY, 48, BAR_HEIGHT,
                Component.literal("发送"),
                () -> sendMessage(this.input.getValue()));
    }

    /** 记住输入框 / 搜索框内容，供重建控件后恢复。 */
    private void rememberInputState() {        if (this.input != null) {
            this.draft = this.input.getValue();
        }
        if (this.searchBox != null) {
            this.searchDraft = this.searchBox.getValue();
        }
    }

    /** 收起右侧面板：只剩会话列表，点任意会话重新展开。 */
    private void collapseRightPanel() {
        if (this.collapsed) {
            return;
        }
        rememberInputState();
        this.collapsed = true;
        this.conversationOpen = false;
        this.emojiOpen = false;
        this.atQuery = null;
        this.messageMenuTarget = null;
        this.headMenuPlayer = null;
        this.rebuildWidgets();
        showHint("点击会话展开");
    }

    /** 计算固定面板区域：固定在屏幕左侧，大小适中。 */
    private void computePanel() {
        // 收起状态：面板只剩会话列表那么宽
        this.panelW = this.collapsed
                ? SIDEBAR_WIDTH + MARGIN
                : (int) Math.max(480, Math.min(760, this.width * 0.46));
        this.panelH = (int) Math.min(this.height - 20, Math.max(380, this.height * 0.88));
        this.panelX = 8;
        this.panelY = (this.height - this.panelH) / 2;
    }

    /** 侧边栏搜索框的 y 坐标。 */
    private int searchBoxY() {
        return this.panelY + TITLE_BAR_HEIGHT + MARGIN + SEARCH_BOX_TOP_OFFSET;
    }

    /** 会话条目的起始 y：渲染与点击判定共用同一个值，避免 UI 与热区错位。 */
    private int sidebarItemsTop() {
        return searchBoxY() + SEARCH_BOX_HEIGHT + MARGIN;
    }

    private List<PlayerInfo> collectOnlinePlayers() {        Minecraft mc = Minecraft.getInstance();
        if (mc.getConnection() == null) {
            return List.of();
        }
        return new ArrayList<>(mc.getConnection().getOnlinePlayers());
    }

    // ==================== 渲染 ====================

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        ChatHub.setOpenConversation(this.conversationOpen && !this.collapsed ? this.conversation : null);
        // scroll 是「距底部的高度」：新消息只会在底部追加，
        // 不重置它，正在翻看历史的人就不会被拽回底部。
        updateAtQuery();

        // 屏幕暗化 + 面板背景
        guiGraphics.fill(0, 0, this.width, this.height, 0x80000000);
        guiGraphics.fill(this.panelX, this.panelY,
                this.panelX + this.panelW, this.panelY + this.panelH, this.theme.background());

        renderTitleBar(guiGraphics);
        renderSidebar(guiGraphics, mouseX, mouseY);
        if (!this.collapsed) {
            renderMessages(guiGraphics, mouseX, mouseY);
            renderInputBar(guiGraphics);
        }
        renderFlatButtons(guiGraphics, mouseX, mouseY);

        for (var renderable : this.renderables) {
            renderable.render(guiGraphics, mouseX, mouseY, partialTick);
        }

        if (!this.collapsed) {
            if (this.emojiOpen) {
                renderEmojiPanel(guiGraphics, mouseX, mouseY);
            }
            if (this.atQuery != null && !this.atQuery.isEmpty()) {
                renderAtPopup(guiGraphics, mouseX, mouseY);
            }
        }
        if (this.headMenuPlayer != null) {
            renderHeadMenu(guiGraphics, mouseX, mouseY);
        }
        if (this.messageMenuTarget != null) {
            renderMessageMenu(guiGraphics, mouseX, mouseY);
        }
        renderHint(guiGraphics);
    }

    /** 顶部标题栏。 */
    private void renderTitleBar(GuiGraphics guiGraphics) {
        guiGraphics.fill(this.panelX, this.panelY,
                this.panelX + this.panelW, this.panelY + TITLE_BAR_HEIGHT, this.theme.panelSelected());
        guiGraphics.drawString(this.font, "玄墟聊天", this.panelX + MARGIN, this.panelY + 7, this.theme.accent());
    }

    /** 界面内提示（替代动作栏，避免文字过长显示不全）。 */
    private void showHint(String text) {
        this.hintText = text;
        this.hintUntil = System.currentTimeMillis() + 3000L;
    }

    private void renderHint(GuiGraphics guiGraphics) {
        if (this.hintText == null || System.currentTimeMillis() > this.hintUntil) {
            return;
        }
        int textWidth = this.font.width(this.hintText);
        int x = this.panelX + (this.panelW - textWidth) / 2;
        int y = this.panelY + this.panelH - MARGIN - BAR_HEIGHT - 20;
        guiGraphics.fill(x - 6, y - 3, x + textWidth + 6, y + 11, 0xD0202030);
        guiGraphics.drawString(this.font, this.hintText, x, y, 0xFFFFDD55);
    }

    private void renderSidebar(GuiGraphics guiGraphics, int mouseX, int mouseY) {
        int left = this.panelX;
        int top = this.panelY + TITLE_BAR_HEIGHT;
        guiGraphics.fill(left, top, left + SIDEBAR_WIDTH, this.panelY + this.panelH, this.theme.panel());

        // 标题
        guiGraphics.drawString(this.font, "会话", left + MARGIN, top + MARGIN, this.theme.accent());

        // 搜索框底板（位于「会话」标题和列表之间，收起状态下同样显示）
        if (this.searchBox != null) {
            int bx = left + MARGIN;
            int by = searchBoxY();
            guiGraphics.fill(bx, by, bx + SIDEBAR_WIDTH - MARGIN * 2, by + SEARCH_BOX_HEIGHT,
                    this.theme.inputBg());
        }

        int y = sidebarItemsTop();

        boolean publicSelected = ChatHub.PUBLIC.equals(this.conversation);
        if (publicSelected) {
            guiGraphics.fill(left + 3, y, left + SIDEBAR_WIDTH - 3, y + 18, this.theme.panelSelected());
        }
        guiGraphics.drawString(this.font, "公共频道", left + MARGIN + 2, y + 5,
                publicSelected ? this.theme.accent() : this.theme.textPrimary());
        drawUnreadBadge(guiGraphics, ChatHub.PUBLIC, left, y);
        y += 22;

        for (PlayerInfo info : this.cachedPlayers) {
            String name = info.getProfile().getName();
            boolean selected = name.equals(this.conversation);
            if (selected) {
                guiGraphics.fill(left + 3, y, left + SIDEBAR_WIDTH - 3, y + 18, this.theme.panelSelected());
            }
            drawPlayerHead(guiGraphics, name, left + MARGIN + 2, y + 1, 14);
            guiGraphics.drawString(this.font, name, left + MARGIN + 20, y + 5,
                    selected ? this.theme.accent() : this.theme.textPrimary());
            drawUnreadBadge(guiGraphics, name, left, y);
            y += 20;
        }

        for (String key : ChatHub.conversationKeys()) {
            if (ChatHub.PUBLIC.equals(key) || findPlayer(key) != null) {
                continue;
            }
            boolean selected = key.equals(this.conversation);
            if (selected) {
                guiGraphics.fill(left + 3, y, left + SIDEBAR_WIDTH - 3, y + 18, this.theme.panelSelected());
            }
            drawPlayerHead(guiGraphics, key, left + MARGIN + 2, y + 1, 14);
            guiGraphics.drawString(this.font, key, left + MARGIN + 20, y + 5,
                    selected ? this.theme.accent() : this.theme.textSecondary());
            drawUnreadBadge(guiGraphics, key, left, y);
            y += 20;
        }
    }

    private void drawUnreadBadge(GuiGraphics guiGraphics, String conversationKey, int left, int y) {
        int unread = ChatHub.unreadOf(conversationKey);
        if (unread <= 0) {
            return;
        }
        String text = unread > 99 ? "99+" : String.valueOf(unread);
        int w = Math.max(10, this.font.width(text) + 4);
        int x = left + SIDEBAR_WIDTH - MARGIN - w;
        guiGraphics.fill(x, y + 4, x + w, y + 15, 0xFFE04040);
        guiGraphics.drawString(this.font, text, x + 2, y + 6, 0xFFFFFFFF, false);
    }

    // ==================== 消息区 ====================

    private int areaX() {
        return this.panelX + SIDEBAR_WIDTH + MARGIN;
    }

    private int areaY() {
        return this.panelY + TITLE_BAR_HEIGHT + MARGIN;
    }

    private int areaW() {
        return this.panelW - SIDEBAR_WIDTH - MARGIN * 2;
    }

    private int areaH() {
        return this.panelH - TITLE_BAR_HEIGHT - MARGIN * 2 - BAR_HEIGHT - 8;
    }

    private void renderMessages(GuiGraphics guiGraphics, int mouseX, int mouseY) {
        int areaX = areaX();
        int areaY = areaY();
        int areaW = areaW();
        int areaH = areaH();

        guiGraphics.fill(areaX, areaY, areaX + areaW, areaY + areaH, 0x30000000);

        // 未打开会话：只显示提示
        if (!this.conversationOpen) {
            String hint = "选择左侧会话开始聊天";
            guiGraphics.drawString(this.font, hint,
                    areaX + (areaW - this.font.width(hint)) / 2, areaY + areaH / 2,
                    this.theme.textSecondary());
            return;
        }

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
        return sameDay(now, date) ? hm.format(date) : full.format(date);
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
        // 纯表情消息：按表情尺寸计算（避免溢出气泡）
        if (isEmojiMessage(message)) {
            return LINE + 2 + (EMOJI_SIZE + BUBBLE_PAD * 2) + 8;
        }
        int maxWidth = Math.max(40, areaW - AVATAR - 40);
        int bubbleHeight;
        List<MessageLine> mixed = hasInlineEmoji(message) ? layoutMixed(message.plainText(), maxWidth) : null;
        if (mixed != null) {
            // 混排：每行高度可能不同（含表情的行更高）
            bubbleHeight = 0;
            for (MessageLine line : mixed) {
                bubbleHeight += line.height();
            }
            bubbleHeight += BUBBLE_PAD * 2;
        } else {
            bubbleHeight = this.font.split(message.content(), maxWidth).size() * LINE + BUBBLE_PAD * 2;
        }
        int height = LINE + 2 + bubbleHeight + 8;
        if (findImageUrl(message.plainText()) != null) {
            height += imageDisplayHeight(message.plainText()) + 6;
        }
        return height;
    }

    /** 是否为可绘制的表情消息（自定义表情或内置黄豆表情）。 */
    private boolean isEmojiMessage(ChatMessage message) {
        String text = message.plainText();
        String name = customEmojiName(text);
        if (name != null) {
            return ChatEmojiLoader.find(name) != null;
        }
        return isPureEmoji(text) && faceOf(text) != null;
    }

    private void drawMessage(GuiGraphics guiGraphics, ChatMessage message, int areaX, int areaY, int areaW) {
        if (message.system()) {
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

        // 纯表情消息：气泡按表情尺寸绘制（避免表情超出气泡）
        boolean emojiMessage = isEmojiMessage(message);
        // 文本 + 表情混排（如「你好:smile:」）：走自己算的行布局
        List<MessageLine> mixed = !emojiMessage && hasInlineEmoji(message)
                ? layoutMixed(message.plainText(), maxWidth)
                : null;
        int bubbleWidth;
        int bubbleHeight;
        if (emojiMessage) {
            bubbleWidth = EMOJI_SIZE + BUBBLE_PAD * 2;
            bubbleHeight = EMOJI_SIZE + BUBBLE_PAD * 2;
        } else if (mixed != null) {
            bubbleWidth = 0;
            bubbleHeight = 0;
            for (MessageLine line : mixed) {
                bubbleWidth = Math.max(bubbleWidth, line.width());
                bubbleHeight += line.height();
            }
            bubbleWidth += BUBBLE_PAD * 2;
            bubbleHeight += BUBBLE_PAD * 2;
        } else {
            bubbleWidth = 0;
            for (FormattedCharSequence line : lines) {
                bubbleWidth = Math.max(bubbleWidth, this.font.width(line));
            }
            bubbleWidth += BUBBLE_PAD * 2;
            bubbleHeight = lines.size() * LINE + BUBBLE_PAD * 2;
        }
        int nameY = areaY;

        if (message.self()) {
            int bubbleX = areaX + areaW - AVATAR - MARGIN - bubbleWidth;
            guiGraphics.drawString(this.font, message.senderName(), bubbleX, nameY, this.theme.accent());
            int bubbleY = nameY + LINE + 2;
            guiGraphics.fill(bubbleX, bubbleY, bubbleX + bubbleWidth, bubbleY + bubbleHeight, this.theme.bubbleSelf());
            drawBubbleContent(guiGraphics, message, lines, mixed, bubbleX + BUBBLE_PAD, bubbleY + BUBBLE_PAD,
                    this.theme.bubbleSelfText());
            if (message.repeatCount() > 1) {
                String badge = "x" + message.repeatCount();
                guiGraphics.drawString(this.font, badge,
                        bubbleX + bubbleWidth - this.font.width(badge) - 4, bubbleY + bubbleHeight + 1, 0xFFAAAAAA);
            }
            drawPlayerHead(guiGraphics, message.senderName(), areaX + areaW - AVATAR - 2, bubbleY, AVATAR);
            drawMessageImage(guiGraphics, message, bubbleX, bubbleY + bubbleHeight + 2);
        } else {
            drawPlayerHead(guiGraphics, message.senderName(), areaX + 2, areaY + 2, AVATAR);
            int bubbleX = areaX + AVATAR + 8;
            if (!message.senderName().isEmpty()) {
                guiGraphics.drawString(this.font, message.senderName(), bubbleX, nameY, this.theme.accent());
            }
            int bubbleY = nameY + LINE + 2;
            guiGraphics.fill(bubbleX, bubbleY, bubbleX + bubbleWidth, bubbleY + bubbleHeight, this.theme.bubbleOthers());
            drawBubbleContent(guiGraphics, message, lines, mixed, bubbleX + BUBBLE_PAD, bubbleY + BUBBLE_PAD,
                    this.theme.textPrimary());
            if (message.repeatCount() > 1) {
                String badge = "x" + message.repeatCount();
                guiGraphics.drawString(this.font, badge,
                        bubbleX + bubbleWidth - this.font.width(badge) - 4, bubbleY + bubbleHeight + 1, 0xFFAAAAAA);
            }
            drawMessageImage(guiGraphics, message, bubbleX, bubbleY + bubbleHeight + 2);
        }
    }

    /** 绘制气泡内容：整条表情 / 文本表情混排 / 普通文字。 */
    private void drawBubbleContent(GuiGraphics guiGraphics, ChatMessage message,
                                   List<FormattedCharSequence> lines, List<MessageLine> mixed,
                                   int x, int y, int color) {
        String emojiName = customEmojiName(message.plainText());
        if (emojiName != null) {
            ChatEmojiLoader.EmojiEntry entry = ChatEmojiLoader.find(emojiName);
            if (entry != null) {
                guiGraphics.blit(entry.location(), x, y, EMOJI_SIZE, EMOJI_SIZE, 0, 0,
                        entry.width(), entry.height(), entry.width(), entry.height());
                return;
            }
        }
        if (isPureEmoji(message.plainText())) {
            Face face = faceOf(message.plainText());
            if (face != null) {
                drawFace(guiGraphics, x, y, EMOJI_SIZE, face);
                return;
            }
        }
        if (mixed != null) {
            int lineY = y;
            for (MessageLine line : mixed) {
                for (Segment segment : line.segments()) {
                    if (segment.emoji() != null) {
                        ChatEmojiLoader.EmojiEntry entry = segment.emoji();
                        guiGraphics.blit(entry.location(), x + segment.x(), lineY, EMOJI_SIZE, EMOJI_SIZE,
                                0, 0, entry.width(), entry.height(), entry.width(), entry.height());
                    } else if (segment.face() != null) {
                        drawFace(guiGraphics, x + segment.x(), lineY, EMOJI_SIZE, segment.face());
                    } else {
                        // 文字在含表情的行里垂直居中
                        int textY = lineY + (line.height() - LINE) / 2;
                        guiGraphics.drawString(this.font, segment.text(), x + segment.x(), textY, color);
                    }
                }
                lineY += line.height();
            }
            return;
        }
        int ty = y;
        for (FormattedCharSequence line : lines) {
            guiGraphics.drawString(this.font, line, x, ty, color);
            ty += LINE;
        }
    }

    /** 检测整条消息是否为自定义表情代码（:name:）。 */
    private String customEmojiName(String text) {
        if (text == null || text.length() < 3) {
            return null;
        }
        if (text.charAt(0) == ':' && text.charAt(text.length() - 1) == ':') {
            String name = text.substring(1, text.length() - 1);
            if (name.matches("[\\w-]+")) {
                return name;
            }
        }
        return null;
    }

    // ==================== 文本 + 表情混排 ====================

    /** 从 i 开始匹配自定义表情代码 {@code :name:}，返回结束下标（不含）；不匹配返回 -1。 */
    private int customEmojiEnd(String text, int i) {
        if (text == null || i >= text.length() || text.charAt(i) != ':') {
            return -1;
        }
        int end = text.indexOf(':', i + 1);
        if (end < 0 || end - i < 3 || end - i > 34) {
            return -1;
        }
        String name = text.substring(i + 1, end);
        if (!name.matches("[\\w-]+") || ChatEmojiLoader.find(name) == null) {
            return -1;
        }
        return end + 1;
    }

    /** 从 i 开始匹配内置黄豆表情，返回对应的 Face；不匹配返回 null。 */
    private Face faceStartingAt(String text, int i) {
        for (Face face : Face.values()) {
            if (text.startsWith(face.emoji, i)) {
                return face;
            }
        }
        return null;
    }

    /** 把消息正文切成「文字 / 内置表情 / 自定义表情」三种 token。 */
    private List<Segment> tokenize(String text) {
        List<Segment> tokens = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            return tokens;
        }
        StringBuilder buffer = new StringBuilder();
        int i = 0;
        while (i < text.length()) {
            int customEnd = customEmojiEnd(text, i);
            if (customEnd > 0) {
                addTextToken(tokens, buffer);
                tokens.add(new Segment(null, null, ChatEmojiLoader.find(text.substring(i + 1, customEnd - 1)), 0));
                i = customEnd;
                continue;
            }
            Face face = faceStartingAt(text, i);
            if (face != null) {
                addTextToken(tokens, buffer);
                tokens.add(new Segment(null, face, null, 0));
                i += face.emoji.length();
                continue;
            }
            buffer.append(text.charAt(i));
            i++;
        }
        addTextToken(tokens, buffer);
        return tokens;
    }

    private void addTextToken(List<Segment> tokens, StringBuilder buffer) {
        if (buffer.length() > 0) {
            tokens.add(new Segment(buffer.toString(), null, null, 0));
            buffer.setLength(0);
        }
    }

    /** 消息里是否含有可绘制的行内表情（整条纯表情另由 {@link #isEmojiMessage} 处理）。 */
    private boolean hasInlineEmoji(ChatMessage message) {
        String text = message.plainText();
        if (text == null || text.isEmpty()) {
            return false;
        }
        // 快速预判：没有冒号也没有代理对（emoji 都落在代理区）时不可能有表情
        boolean possible = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == ':' || c >= 0xD800) {
                possible = true;
                break;
            }
        }
        if (!possible) {
            return false;
        }
        for (Segment token : tokenize(text)) {
            if (token.face() != null || token.emoji() != null) {
                return true;
            }
        }
        return false;
    }

    /** 把文本切成最小断行单元：拉丁单词（尾随空格一起）与单个 CJK 字符。 */
    private List<String> breakUnits(String text) {
        List<String> units = new ArrayList<>();
        StringBuilder word = new StringBuilder();
        int i = 0;
        while (i < text.length()) {
            int cp = text.codePointAt(i);
            int len = Character.charCount(cp);
            if (cp == ' ' || cp == '\t' || cp == '\u3000') {
                word.appendCodePoint(cp);
                units.add(word.toString());
                word.setLength(0);
                i += len;
                continue;
            }
            if (word.length() == 0) {
                word.appendCodePoint(cp);
            } else {
                int last = word.codePointBefore(word.length());
                // 拉丁 / ASCII 连成一个单词，CJK 每字都可断行
                if (cp < 0x2E80 && last < 0x2E80) {
                    word.appendCodePoint(cp);
                } else {
                    units.add(word.toString());
                    word.setLength(0);
                    word.appendCodePoint(cp);
                }
            }
            i += len;
        }
        if (word.length() > 0) {
            units.add(word.toString());
        }
        return units;
    }

    /**
     * 把消息正文摊平成排布单元：表情各占一个单元，文本按可断行单元切开，
     * 比一整行还宽的不可断文本（长链接等）按字符硬断，避免撑破气泡。
     */
    private List<Segment> flattenUnits(String text, int maxWidth) {
        List<Segment> units = new ArrayList<>();
        for (Segment token : tokenize(text)) {
            if (token.face() != null || token.emoji() != null) {
                units.add(token);
                continue;
            }
            for (String unit : breakUnits(token.text())) {
                if (this.font.width(unit) <= maxWidth) {
                    units.add(new Segment(unit, null, null, 0));
                    continue;
                }
                for (int k = 0; k < unit.length(); ) {
                    int cp = unit.codePointAt(k);
                    units.add(new Segment(new String(Character.toChars(cp)), null, null, 0));
                    k += Character.charCount(cp);
                }
            }
        }
        return units;
    }

    /**
     * 文本 + 表情混排成行。表情按 {@link #EMOJI_SIZE} 占宽，含表情的行更高，
     * 该行里的文字垂直居中。
     */
    private List<MessageLine> layoutMixed(String text, int maxWidth) {
        List<MessageLine> lines = new ArrayList<>();
        List<Segment> current = new ArrayList<>();
        StringBuilder buffer = new StringBuilder();
        int runX = 0;
        int cursorX = 0;
        int lineHeight = LINE;

        for (Segment unit : flattenUnits(text, maxWidth)) {
            if (unit.face() != null || unit.emoji() != null) {
                if (buffer.length() > 0) {
                    current.add(new Segment(buffer.toString(), null, null, runX));
                    buffer.setLength(0);
                    runX = cursorX;
                }
                if (cursorX > 0 && cursorX + EMOJI_SIZE > maxWidth) {
                    lines.add(new MessageLine(current, cursorX, lineHeight));
                    current = new ArrayList<>();
                    cursorX = 0;
                    runX = 0;
                    lineHeight = LINE;
                }
                current.add(new Segment(null, unit.face(), unit.emoji(), cursorX));
                cursorX += EMOJI_SIZE;
                lineHeight = Math.max(lineHeight, EMOJI_SIZE);
                runX = cursorX;
                continue;
            }
            String piece = unit.text();
            int width = this.font.width(piece);
            if (cursorX > 0 && cursorX + width > maxWidth) {
                String trimmed = piece.stripLeading();
                if (trimmed.isEmpty()) {
                    continue;   // 行尾空白不单独占一行
                }
                piece = trimmed;
                width = this.font.width(piece);
                if (buffer.length() > 0) {
                    current.add(new Segment(buffer.toString(), null, null, runX));
                    buffer.setLength(0);
                }
                lines.add(new MessageLine(current, cursorX, lineHeight));
                current = new ArrayList<>();
                cursorX = 0;
                runX = 0;
                lineHeight = LINE;
            }
            if (buffer.length() == 0) {
                runX = cursorX;
            }
            buffer.append(piece);
            cursorX += width;
        }
        if (buffer.length() > 0) {
            current.add(new Segment(buffer.toString(), null, null, runX));
        }
        if (!current.isEmpty()) {
            lines.add(new MessageLine(current, cursorX, lineHeight));
        }
        return lines;
    }

    /** 系统消息头像：灰色方块 + 感叹号。 */
    private void drawSystemAvatar(GuiGraphics guiGraphics, int x, int y) {
        guiGraphics.fill(x, y, x + AVATAR, y + AVATAR, 0xFF4A4A5A);
        guiGraphics.drawString(this.font, "!", x + 6, y + 4, 0xFFFFFFFF);
    }

    // ==================== 输入区 ====================

    private void renderInputBar(GuiGraphics guiGraphics) {
        int y = this.panelY + this.panelH - MARGIN - BAR_HEIGHT;
        guiGraphics.fill(this.panelX + SIDEBAR_WIDTH, y - 4,
                this.panelX + this.panelW, this.panelY + this.panelH, this.theme.panel());
        if (this.input != null) {
            guiGraphics.fill(this.input.getX() - 4, this.input.getY() - 1,
                    this.input.getX() + this.input.getWidth() + 4,
                    this.input.getY() + this.input.getHeight() + 1, this.theme.inputBg());
        }
    }

    private void insertClipboardImage() {
        if (this.input == null) {
            return;
        }
        String clip = Minecraft.getInstance().keyboardHandler.getClipboard();
        if (clip != null && clip.matches("(?i)^https?://\\S+$")) {
            this.input.insertText(clip);
            this.input.setFocused(true);
        } else {
            showHint("先复制图片链接，再点「图」");
        }
    }

    // ==================== 表情面板 ====================

    /** 表情面板的一格：内置黄豆表情 / 自定义表情图片 / 颜文字，三选一。 */
    private record EmojiSlot(Face face, ChatEmojiLoader.EmojiEntry emoji, String kaomoji) {
        /** 点这一格要插入输入框的文本。 */
        private String insertText() {
            if (face != null) {
                return face.emoji;
            }
            if (emoji != null) {
                return ":" + emoji.name() + ":";
            }
            return kaomoji;
        }
    }

    /** 面板格子：内置黄豆表情 + 自定义表情图片 + 颜文字，全部平铺在一起。 */
    private List<EmojiSlot> emojiSlots() {
        List<EmojiSlot> slots = new ArrayList<>();
        for (Face face : Face.values()) {
            slots.add(new EmojiSlot(face, null, null));
        }
        for (ChatEmojiLoader.EmojiEntry entry : ChatEmojiLoader.emojis()) {
            slots.add(new EmojiSlot(null, entry, null));
        }
        for (String kaomoji : KAOMOJI) {
            slots.add(new EmojiSlot(null, null, kaomoji));
        }
        return slots;
    }

    private int emojiPanelX() {
        return this.panelX + SIDEBAR_WIDTH + MARGIN;
    }

    /** 表情面板高度（表情变多时自动长高）。 */
    private int emojiPanelHeight() {
        int slots = emojiSlots().size();
        int rows = Math.max(1, (slots + EMOJI_COLUMNS - 1) / EMOJI_COLUMNS);
        return rows * EMOJI_CELL + 8;
    }

    /** 表情面板 y：贴着工具栏往上摆，但不会超出面板顶部。 */
    private int emojiPanelY(int panelHeight) {
        int y = this.panelY + this.panelH - MARGIN - BAR_HEIGHT - panelHeight - 4;
        return Math.max(this.panelY + TITLE_BAR_HEIGHT + 2, y);
    }

    private void renderEmojiPanel(GuiGraphics guiGraphics, int mouseX, int mouseY) {
        List<EmojiSlot> slots = emojiSlots();
        int panelWidth = EMOJI_COLUMNS * EMOJI_CELL + 8;
        int panelHeight = emojiPanelHeight();
        int x = emojiPanelX();
        int y = emojiPanelY(panelHeight);
        guiGraphics.fill(x, y, x + panelWidth, y + panelHeight, 0xF0202028);

        for (int i = 0; i < slots.size(); i++) {
            int cx = x + 4 + (i % EMOJI_COLUMNS) * EMOJI_CELL;
            int cy = y + 4 + (i / EMOJI_COLUMNS) * EMOJI_CELL;
            if (isInside(mouseX, mouseY, cx, cy, EMOJI_CELL - 4, EMOJI_CELL - 4)) {
                guiGraphics.fill(cx, cy, cx + EMOJI_CELL - 4, cy + EMOJI_CELL - 4, 0x40FFFFFF);
            }
            EmojiSlot slot = slots.get(i);
            if (slot.emoji() != null) {
                ChatEmojiLoader.EmojiEntry entry = slot.emoji();
                guiGraphics.blit(entry.location(), cx + 2, cy + 2, EMOJI_PANEL_SIZE, EMOJI_PANEL_SIZE, 0, 0,
                        entry.width(), entry.height(), entry.width(), entry.height());
            } else if (slot.face() != null) {
                drawFace(guiGraphics, cx + 3, cy + 3, EMOJI_CELL - 10, slot.face());
            } else {
                guiGraphics.drawString(this.font, slot.kaomoji(), cx + 1, cy + 11, 0xFFFFFFFF);
            }
        }
    }

    /** 返回点击到的表情内容，没有则 null。 */
    private String emojiAt(double mouseX, double mouseY) {
        List<EmojiSlot> slots = emojiSlots();
        int x = emojiPanelX();
        int y = emojiPanelY(emojiPanelHeight());
        for (int i = 0; i < slots.size(); i++) {
            int cx = x + 4 + (i % EMOJI_COLUMNS) * EMOJI_CELL;
            int cy = y + 4 + (i / EMOJI_COLUMNS) * EMOJI_CELL;
            if (isInside(mouseX, mouseY, cx, cy, EMOJI_CELL - 4, EMOJI_CELL - 4)) {
                return slots.get(i).insertText();
            }
        }
        return null;
    }

    /** 绘制一个 QQ 风格黄豆表情。 */
    private void drawFace(GuiGraphics guiGraphics, int x, int y, int size, Face face) {
        int cx = x + size / 2;
        int cy = y + size / 2;
        fillCircle(guiGraphics, cx, cy, size / 2, FACE_YELLOW);
        switch (face) {
            case SMILE -> {
                guiGraphics.fill(cx - 5, cy - 4, cx - 3, cy - 2, FACE_DARK);
                guiGraphics.fill(cx + 4, cy - 4, cx + 6, cy - 2, FACE_DARK);
                guiGraphics.fill(cx - 6, cy + 3, cx - 4, cy + 5, FACE_DARK);
                guiGraphics.fill(cx - 4, cy + 5, cx + 4, cy + 6, FACE_DARK);
                guiGraphics.fill(cx + 4, cy + 3, cx + 6, cy + 5, FACE_DARK);
            }
            case LAUGH -> {
                guiGraphics.fill(cx - 6, cy - 5, cx - 3, cy - 3, FACE_DARK);
                guiGraphics.fill(cx + 4, cy - 5, cx + 7, cy - 3, FACE_DARK);
                guiGraphics.fill(cx - 6, cy + 2, cx + 6, cy + 8, FACE_DARK);
                guiGraphics.fill(cx - 4, cy + 4, cx + 4, cy + 7, 0xFFB04A4A);
            }
            case WINK -> {
                guiGraphics.fill(cx - 5, cy - 4, cx - 3, cy - 2, FACE_DARK);
                guiGraphics.fill(cx + 4, cy - 4, cx + 7, cy - 3, FACE_DARK);
                guiGraphics.fill(cx - 5, cy + 3, cx + 5, cy + 5, FACE_DARK);
            }
            case CRY -> {
                guiGraphics.fill(cx - 5, cy - 4, cx - 3, cy - 2, FACE_DARK);
                guiGraphics.fill(cx + 4, cy - 4, cx + 6, cy - 2, FACE_DARK);
                guiGraphics.fill(cx - 7, cy, cx - 5, cy + 4, 0xFF66CCFF);
                guiGraphics.fill(cx + 5, cy, cx + 7, cy + 4, 0xFF66CCFF);
                guiGraphics.fill(cx - 4, cy + 4, cx + 4, cy + 5, FACE_DARK);
                guiGraphics.fill(cx - 5, cy + 3, cx - 4, cy + 4, FACE_DARK);
                guiGraphics.fill(cx + 4, cy + 3, cx + 5, cy + 4, FACE_DARK);
            }
            case ANGRY -> {
                guiGraphics.fill(cx - 7, cy - 5, cx - 3, cy - 4, FACE_DARK);
                guiGraphics.fill(cx + 3, cy - 5, cx + 7, cy - 4, FACE_DARK);
                guiGraphics.fill(cx - 5, cy - 3, cx - 3, cy - 2, FACE_DARK);
                guiGraphics.fill(cx + 4, cy - 3, cx + 6, cy - 2, FACE_DARK);
                guiGraphics.fill(cx - 4, cy + 4, cx + 4, cy + 5, FACE_DARK);
            }
            case SURPRISED -> {
                guiGraphics.fill(cx - 6, cy - 5, cx - 2, cy - 1, FACE_DARK);
                guiGraphics.fill(cx + 2, cy - 5, cx + 6, cy - 1, FACE_DARK);
                guiGraphics.fill(cx - 2, cy + 3, cx + 2, cy + 7, FACE_DARK);
            }
            case SHY -> {
                guiGraphics.fill(cx - 5, cy - 4, cx - 3, cy - 2, FACE_DARK);
                guiGraphics.fill(cx + 4, cy - 4, cx + 6, cy - 2, FACE_DARK);
                guiGraphics.fill(cx - 7, cy + 1, cx - 4, cy + 3, FACE_BLUSH);
                guiGraphics.fill(cx + 5, cy + 1, cx + 8, cy + 3, FACE_BLUSH);
                guiGraphics.fill(cx - 3, cy + 4, cx + 3, cy + 5, FACE_DARK);
            }
            case LOVE -> {
                drawHeart(guiGraphics, cx - 6, cy - 4, 0xFFE04060);
                drawHeart(guiGraphics, cx + 2, cy - 4, 0xFFE04060);
                guiGraphics.fill(cx - 5, cy + 3, cx - 3, cy + 5, FACE_DARK);
                guiGraphics.fill(cx - 3, cy + 5, cx + 3, cy + 6, FACE_DARK);
                guiGraphics.fill(cx + 3, cy + 3, cx + 5, cy + 5, FACE_DARK);
            }
        }
    }

    private void drawHeart(GuiGraphics guiGraphics, int x, int y, int color) {
        guiGraphics.fill(x, y + 1, x + 4, y + 4, color);
        guiGraphics.fill(x + 1, y, x + 3, y + 1, color);
        guiGraphics.fill(x - 1, y + 1, x + 1, y + 4, color);
        guiGraphics.fill(x + 3, y + 1, x + 5, y + 4, color);
    }

    private void fillCircle(GuiGraphics guiGraphics, int cx, int cy, int radius, int color) {
        for (int dy = -radius; dy <= radius; dy++) {
            int dx = (int) Math.sqrt((double) (radius * radius - dy * dy));
            guiGraphics.fill(cx - dx, cy + dy, cx + dx + 1, cy + dy + 1, color);
        }
    }

    private boolean isPureEmoji(String text) {
        if (text == null || text.isEmpty() || text.length() > 16) {
            return false;
        }
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            if (!isEmojiCodePoint(cp)) {
                return false;
            }
            i += Character.charCount(cp);
        }
        return true;
    }

    private Face faceOf(String text) {
        for (Face face : Face.values()) {
            if (face.emoji.equals(text)) {
                return face;
            }
        }
        return null;
    }

    private boolean isEmojiCodePoint(int cp) {
        return (cp >= 0x1F300 && cp <= 0x1FAFF)
                || (cp >= 0x2600 && cp <= 0x27BF)
                || (cp >= 0x1F000 && cp <= 0x1F02F)
                || cp == 0x2764;
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
        int x = this.panelX + SIDEBAR_WIDTH + MARGIN + 4;
        int y = this.panelY + this.panelH - MARGIN - BAR_HEIGHT - height - 4;

        guiGraphics.fill(x, y, x + width, y + height, 0xF0282832);
        for (int i = 0; i < candidates.size(); i++) {
            int itemY = y + 2 + i * itemHeight;
            boolean hover = isInside(mouseX, mouseY, x, itemY, width, itemHeight);
            if (hover) {
                guiGraphics.fill(x + 2, itemY, x + width - 2, itemY + itemHeight - 1, 0xFF3A3A52);
            }
            guiGraphics.drawString(this.font, candidates.get(i), x + 6, itemY + 3,
                    hover ? 0xFFFFFFFF : this.theme.textPrimary());
        }
    }

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
        int x = this.panelX + SIDEBAR_WIDTH + MARGIN + 4;
        int y = this.panelY + this.panelH - MARGIN - BAR_HEIGHT - height - 4;
        for (int i = 0; i < candidates.size(); i++) {
            int itemY = y + 2 + i * itemHeight;
            if (isInside(mouseX, mouseY, x, itemY, width, itemHeight)) {
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

    // ==================== 菜单 ====================

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
            boolean hover = isInside(mouseX, mouseY, x, itemY, width, itemHeight);
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
            if (isInside(mouseX, mouseY, x, itemY, width, itemHeight)) {
                return i;
            }
        }
        return -1;
    }

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
            boolean hover = isInside(mouseX, mouseY, x, itemY, width, itemHeight);
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
            if (isInside(mouseX, mouseY, x, itemY, width, itemHeight)) {
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

    // ==================== 自绘扁平按钮 ====================

    private void addFlatButton(int x, int y, int w, int h, Component label, Runnable action) {
        this.flatButtons.add(new FlatButton(x, y, w, h, label, action));
    }

    private void renderFlatButtons(GuiGraphics guiGraphics, int mouseX, int mouseY) {
        for (FlatButton button : this.flatButtons) {
            boolean hover = isInside(mouseX, mouseY, button.x(), button.y(), button.w(), button.h());
            int background = hover ? this.theme.buttonHover() : this.theme.buttonBg();
            // QQ 风格圆角按钮
            fillRoundedRect(guiGraphics, button.x(), button.y(), button.w(), button.h(), 5, background);
            int textWidth = this.font.width(button.label());
            guiGraphics.drawString(this.font, button.label(),
                    button.x() + (button.w() - textWidth) / 2,
                    button.y() + (button.h() - 8) / 2,
                    hover ? this.theme.accent() : this.theme.textPrimary());
        }
    }

    /** 绘制圆角矩形（逐行内缩近似）。 */
    private void fillRoundedRect(GuiGraphics guiGraphics, int x, int y, int w, int h, int radius, int color) {
        int r = Math.max(0, Math.min(radius, Math.min(w, h) / 2));
        for (int dy = 0; dy < h; dy++) {
            int inset;
            if (dy < r) {
                inset = r - (int) Math.sqrt((double) (r * r - (r - dy) * (r - dy)));
            } else if (dy >= h - r) {
                int d = dy - (h - r - 1);
                inset = r - (int) Math.sqrt((double) (r * r - d * d));
            } else {
                inset = 0;
            }
            guiGraphics.fill(x + inset, y + dy, x + w - inset, y + dy + 1, color);
        }
    }

    private boolean isInside(double mouseX, double mouseY, int x, int y, int w, int h) {
        return mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h;
    }

    // ==================== 交互 ====================

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        double vx = mouseX;
        double vy = mouseY;

        if (this.emojiOpen) {
            String emoji = emojiAt(vx, vy);
            if (emoji != null) {
                this.input.insertText(emoji);
                return true;
            }
            this.emojiOpen = false;
            return true;
        }

        for (FlatButton flatButton : this.flatButtons) {
            if (isInside(vx, vy, flatButton.x(), flatButton.y(), flatButton.w(), flatButton.h())) {
                flatButton.action().run();
                return true;
            }
        }

        if (clickAtCandidate(vx, vy)) {
            return true;
        }

        if (this.messageMenuTarget != null) {
            int index = messageMenuItemAt(vx, vy);
            if (index >= 0) {
                handleMessageMenuAction(index);
                this.messageMenuTarget = null;
                return true;
            }
            this.messageMenuTarget = null;
        }

        if (this.headMenuPlayer != null) {
            int index = headMenuItemAt(vx, vy);
            if (index >= 0) {
                List<PlayerHeadAction> actions = PlayerHeadActions.visibleFor(this.headMenuPlayer);
                actions.get(index).execute(this.headMenuPlayer);
                this.headMenuPlayer = null;
                return true;
            }
            this.headMenuPlayer = null;
        }

        int sidebarItem = sidebarItemAt(vx, vy);
        if (sidebarItem >= 0) {
            selectSidebarItem(sidebarItem);
            return true;
        }

        ChatMessage messageAt = messageAt(vx, vy);
        if (messageAt != null) {
            if (button == 1) {
                if (isOnAvatar(messageAt, vx, vy)) {
                    this.headMenuPlayer = messageAt.senderName();
                    this.headMenuX = mouseX;
                    this.headMenuY = mouseY;
                } else {
                    this.messageMenuTarget = messageAt;
                    this.messageMenuX = mouseX;
                    this.messageMenuY = mouseY;
                }
            } else if (isOnAvatar(messageAt, vx, vy)) {
                if (!messageAt.self() && !messageAt.system() && !ChatHub.PUBLIC.equals(messageAt.senderName())) {
                    this.conversation = messageAt.senderName();
                    this.scroll = 0;
                }
            }
            return true;
        }

        return super.mouseClicked(vx, vy, button);
    }

    private void handleMessageMenuAction(int index) {
        if (this.messageMenuTarget == null) {
            return;
        }
        String text = this.messageMenuTarget.plainText();
        if (index == 0) {
            Minecraft.getInstance().keyboardHandler.setClipboard(text);
        } else if (this.input != null) {
            // 引用回复：单行前缀（聊天不允许换行）
            String quote = "> " + text + " ";
            this.input.setValue(quote + this.input.getValue());
            this.input.setFocused(true);
        }
        this.messageMenuTarget = null;
    }

    private int sidebarItemAt(double mouseX, double mouseY) {
        int left = this.panelX;
        if (mouseX < left || mouseX > left + SIDEBAR_WIDTH) {
            return -1;
        }
        int y = sidebarItemsTop();
        if (isInside(mouseX, mouseY, left, y, SIDEBAR_WIDTH, 18)) {
            return 0;
        }
        y += 22;
        for (int i = 0; i < this.cachedPlayers.size(); i++) {
            if (isInside(mouseX, mouseY, left, y, SIDEBAR_WIDTH, 18)) {
                return i + 1;
            }
            y += 20;
        }
        int offset = this.cachedPlayers.size() + 1;
        for (String key : ChatHub.conversationKeys()) {
            if (ChatHub.PUBLIC.equals(key) || findPlayer(key) != null) {
                continue;
            }
            if (isInside(mouseX, mouseY, left, y, SIDEBAR_WIDTH, 18)) {
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
        this.conversationOpen = true;
        this.scroll = 0;
        // 收起状态下点会话 → 重新展开右侧面板（需要重建控件）
        boolean wasCollapsed = this.collapsed;
        this.collapsed = false;
        ChatHub.setOpenConversation(this.conversation);
        rememberInputState();
        if (wasCollapsed) {
            this.rebuildWidgets();
            if (this.input != null) {
                this.input.setFocused(true);
            }
        } else if (this.input != null) {
            this.input.setEditable(true);
        }
    }

    private ChatMessage messageAt(double mouseX, double mouseY) {
        if (!this.conversationOpen) {
            return null;
        }
        int areaX = areaX();
        int areaY = areaY();
        int areaW = areaW();
        int areaH = areaH();
        if (!isInside(mouseX, mouseY, areaX, areaY, areaW, areaH)) {
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
        int areaX = areaX();
        int areaW = areaW();
        int areaY = areaY();
        int areaH = areaH();
        List<ChatMessage> messages = filterMessages(ChatHub.messages(this.conversation));
        int bottom = areaY + areaH;
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
                return isInside(mouseX, mouseY, headX, headY, AVATAR, AVATAR);
            }
            if (drawY + height < areaY) {
                break;
            }
        }
        return false;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (this.collapsed || !this.conversationOpen) {
            return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
        }
        int areaH = areaH();
        int total = totalMessagesHeight();
        double max = Math.max(0, total - areaH);
        // 向上滚动（scrollY > 0）应看到更早的消息：scroll 增大使内容下移
        this.scroll = Math.max(0, Math.min(max, this.scroll + scrollY * 24));
        return true;
    }

    private int totalMessagesHeight() {
        int areaW = areaW();
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
        if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
            this.onClose();
            return true;
        }
        // 右侧已收起：没有输入框，只保留关闭快捷键
        if (this.input == null) {
            return super.keyPressed(keyCode, scanCode, modifiers);
        }
        // 焦点在搜索框时，回车只用来回到聊天输入框，不要误发消息
        if (this.searchBox != null && this.searchBox.isFocused()) {
            if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
                this.setFocused(this.input);
                this.input.setFocused(true);
                return true;
            }
            return super.keyPressed(keyCode, scanCode, modifiers);
        }
        if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
            if ((modifiers & GLFW.GLFW_MOD_SHIFT) != 0) {
                this.input.insertText(" ");
                return true;
            }
            sendMessage(this.input.getValue());
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    private void sendMessage(String text) {
        if (text == null || text.isBlank()) {
            return;
        }
        // 过滤控制字符：服务端会以「非法聊天字符」拒绝并断开连接
        text = text.replaceAll("[\\p{Cntrl}]", " ").trim();
        if (text.isEmpty()) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.getConnection() == null) {
            return;
        }
        // 以 / 开头的是命令，必须走命令通道（原版 ChatScreen 的行为）。
        // 走 sendChat 的话服务端只会把它当成一句普通聊天广播出去，命令并不会执行。
        if (text.startsWith("/")) {
            String command = text.substring(1).trim();
            if (this.input != null) {
                this.input.setValue("");
            }
            if (!command.isEmpty()) {
                // 公共频道里补一条只自己可见的记录，方便回看发过什么命令
                ChatHub.receiveSelfCommand(text);
                mc.getConnection().sendCommand(command);
            }
            return;
        }
        if (!this.conversationOpen) {
            showHint("先选择左侧会话再发送消息");
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

    // ==================== 图片 ====================

    private String findImageUrl(String text) {
        if (text == null || text.isEmpty()) {
            return null;
        }
        Matcher matcher = IMAGE_URL.matcher(text);
        return matcher.find() ? matcher.group(1) : null;
    }

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
        ChatImageCache.animate(entry, 100);
        var first = entry.frames.get(0);
        int texW = first.getWidth();
        int texH = first.getHeight();
        int drawW = Math.min(IMAGE_DISPLAY_MAX_W, texW);
        int drawH = Math.max(1, (int) ((double) drawW / texW * texH));
        guiGraphics.blit(entry.location, x, y, drawW, drawH, 0, 0, texW, texH, texW, texH);
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