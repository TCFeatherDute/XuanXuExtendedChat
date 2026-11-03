package com.tc.xuanxuchat.client;

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
    private static final int AVATAR = 16;
    private static final int LINE = 9;
    private static final int BUBBLE_PAD = 6;
    private static final int TIME_SEPARATOR_HEIGHT = 14;
    private static final int EMOJI_CELL = 34;

    /** 窗口大小档位（大 / 中 / 小）。 */
    private static final float[] SIZE_SCALE = {1.0f, 0.85f, 0.7f};

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
    private ChatTheme theme = ChatTheme.DARK;

    /** 窗口大小档位：0 大 / 1 中 / 2 小。 */
    private int sizeTier = 1;

    private int panelX;
    private int panelY;
    private int panelW;
    private int panelH;

    private EditBox input;
    private EditBox searchBox;

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
    private int lastSeenMessageCount;

    private final List<FlatButton> flatButtons = new ArrayList<>();

    private String hintText;
    private long hintUntil;

    private record FlatButton(int x, int y, int w, int h, Component label, Runnable action) {
    }

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
        this.flatButtons.clear();
        computePanel();

        int barY = this.panelY + this.panelH - MARGIN - BAR_HEIGHT;
        int barX = this.panelX + SIDEBAR_WIDTH + MARGIN;

        // 搜索框（侧边栏顶部）
        this.searchBox = new EditBox(this.font, this.panelX + MARGIN, this.panelY + MARGIN + 14,
                SIDEBAR_WIDTH - MARGIN * 2, 16, Component.literal("搜索消息..."));
        this.searchBox.setMaxLength(32);
        this.searchBox.setBordered(false);
        addRenderableWidget(this.searchBox);

        // 工具栏：表情
        addFlatButton(barX, barY, 22, BAR_HEIGHT, Component.literal("☺"),
                () -> this.emojiOpen = !this.emojiOpen);

        // 工具栏：图片
        addFlatButton(barX + 26, barY, 22, BAR_HEIGHT, Component.literal("图"),
                this::insertClipboardImage);

        // 工具栏：窗口大小
        addFlatButton(barX + 52, barY, 40, BAR_HEIGHT,
                Component.literal(sizeLabel()),
                this::cycleSize);

        // 聊天输入框
        int inputX = barX + 98;
        int inputWidth = Math.max(80, this.panelX + this.panelW - MARGIN - 52 - inputX);
        this.input = new EditBox(this.font, inputX, barY, inputWidth, BAR_HEIGHT,
                Component.literal("输入消息..."));
        this.input.setValue(this.initialText);
        this.input.setMaxLength(256);
        this.input.setFocused(true);
        this.input.setBordered(false);
        addRenderableWidget(this.input);
        setInitialFocus(this.input);

        // 发送按钮
        addFlatButton(this.panelX + this.panelW - MARGIN - 48, barY, 48, BAR_HEIGHT,
                Component.literal("发送"),
                () -> sendMessage(this.input.getValue()));

        // 主题切换（侧边栏底部）
        addFlatButton(this.panelX + MARGIN, barY, 44, BAR_HEIGHT,
                Component.literal(this.theme.dark() ? "浅色" : "深色"),
                () -> {
                    String draft = this.input != null ? this.input.getValue() : "";
                    this.theme = this.theme.toggle();
                    this.rebuildWidgets();
                    if (this.input != null) {
                        this.input.setValue(draft);
                        this.input.setFocused(true);
                    }
                });
    }

    private String sizeLabel() {
        return switch (this.sizeTier) {
            case 0 -> "大";
            case 1 -> "中";
            default -> "小";
        };
    }

    private void cycleSize() {
        this.sizeTier = (this.sizeTier + 1) % SIZE_SCALE.length;
        this.rebuildWidgets();
    }

    /** 计算窗口面板区域（居中）。 */
    private void computePanel() {
        float scale = SIZE_SCALE[this.sizeTier];
        this.panelW = Math.round(this.width * scale);
        this.panelH = Math.round(this.height * scale);
        this.panelX = (this.width - this.panelW) / 2;
        this.panelY = (this.height - this.panelH) / 2;
    }

    private List<PlayerInfo> collectOnlinePlayers() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.getConnection() == null) {
            return List.of();
        }
        return new ArrayList<>(mc.getConnection().getOnlinePlayers());
    }

    // ==================== 渲染 ====================

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        ChatHub.setOpenConversation(this.conversation);
        int messageCount = ChatHub.messages(this.conversation).size();
        if (messageCount != this.lastSeenMessageCount) {
            this.lastSeenMessageCount = messageCount;
            this.scroll = 0;
        }
        updateAtQuery();

        // 窗口外暗化
        guiGraphics.fill(0, 0, this.width, this.height, 0x80000000);
        // 面板背景
        guiGraphics.fill(this.panelX, this.panelY,
                this.panelX + this.panelW, this.panelY + this.panelH, this.theme.background());

        renderSidebar(guiGraphics, mouseX, mouseY);
        renderMessages(guiGraphics, mouseX, mouseY);
        renderInputBar(guiGraphics);
        renderFlatButtons(guiGraphics, mouseX, mouseY);

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
        renderHint(guiGraphics);
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
        int top = this.panelY;
        guiGraphics.fill(left, top, left + SIDEBAR_WIDTH, top + this.panelH, this.theme.panel());
        guiGraphics.drawString(this.font, "会话", left + MARGIN, top + MARGIN, this.theme.accent());

        int y = top + MARGIN + 34;

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
        return this.panelY + MARGIN;
    }

    private int areaW() {
        return this.panelW - SIDEBAR_WIDTH - MARGIN * 2;
    }

    private int areaH() {
        return this.panelH - MARGIN * 2 - BAR_HEIGHT - 8;
    }

    private void renderMessages(GuiGraphics guiGraphics, int mouseX, int mouseY) {
        int areaX = areaX();
        int areaY = areaY();
        int areaW = areaW();
        int areaH = areaH();

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
        int maxWidth = Math.max(40, areaW - AVATAR - 40);
        int lines = this.font.split(message.content(), maxWidth).size();
        int bubbleHeight = lines * LINE + BUBBLE_PAD * 2;
        int height = LINE + 2 + bubbleHeight + 8;
        if (findImageUrl(message.plainText()) != null) {
            height += imageDisplayHeight(message.plainText()) + 6;
        }
        // 纯 emoji 消息：绘制大号黄豆表情
        if (isPureEmoji(message.plainText())) {
            height = Math.max(height, AVATAR + 20 + 8);
        }
        return height;
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
            drawBubbleContent(guiGraphics, message, lines, bubbleX + BUBBLE_PAD, bubbleY + BUBBLE_PAD, 0xFFFFFFFF);
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
            drawBubbleContent(guiGraphics, message, lines, bubbleX + BUBBLE_PAD, bubbleY + BUBBLE_PAD, this.theme.textPrimary());
            if (message.repeatCount() > 1) {
                String badge = "x" + message.repeatCount();
                guiGraphics.drawString(this.font, badge,
                        bubbleX + bubbleWidth - this.font.width(badge) - 4, bubbleY + bubbleHeight + 1, 0xFFAAAAAA);
            }
            drawMessageImage(guiGraphics, message, bubbleX, bubbleY + bubbleHeight + 2);
        }
    }

    /** 绘制气泡文字：纯 emoji 消息绘制成黄豆表情。 */
    private void drawBubbleContent(GuiGraphics guiGraphics, ChatMessage message,
                                   List<FormattedCharSequence> lines, int x, int y, int color) {
        if (isPureEmoji(message.plainText())) {
            Face face = faceOf(message.plainText());
            if (face != null) {
                drawFace(guiGraphics, x - 2, y - 2, 24, face);
                return;
            }
        }
        int ty = y;
        for (FormattedCharSequence line : lines) {
            guiGraphics.drawString(this.font, line, x, ty, color);
            ty += LINE;
        }
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
        if (this.searchBox != null) {
            guiGraphics.fill(this.searchBox.getX() - 3, this.searchBox.getY() - 1,
                    this.searchBox.getX() + this.searchBox.getWidth() + 3,
                    this.searchBox.getY() + this.searchBox.getHeight() + 1, this.theme.inputBg());
        }
    }

    private void insertClipboardImage() {
        String clip = Minecraft.getInstance().keyboardHandler.getClipboard();
        if (clip != null && clip.matches("(?i)^https?://\\S+$")) {
            this.input.insertText(clip);
            this.input.setFocused(true);
        } else {
            showHint("先复制图片链接，再点「图」");
        }
    }

    // ==================== QQ 黄豆表情 ====================

    /**
     * 表情面板：前 8 格是绘制的黄豆表情，后面是颜文字。
     */
    private void renderEmojiPanel(GuiGraphics guiGraphics, int mouseX, int mouseY) {
        int columns = 4;
        int rows = 4;
        int panelWidth = columns * EMOJI_CELL + 8;
        int panelHeight = rows * EMOJI_CELL + 8;
        int x = this.panelX + this.panelW - panelWidth - MARGIN;
        int y = this.panelY + this.panelH - MARGIN - BAR_HEIGHT - panelHeight - 4;
        guiGraphics.fill(x, y, x + panelWidth, y + panelHeight, 0xF0202028);

        Face[] faces = Face.values();
        for (int i = 0; i < faces.length; i++) {
            int cx = x + 4 + (i % columns) * EMOJI_CELL;
            int cy = y + 4 + (i / columns) * EMOJI_CELL;
            boolean hover = isInside(mouseX, mouseY, cx, cy, EMOJI_CELL - 4, EMOJI_CELL - 4);
            if (hover) {
                guiGraphics.fill(cx, cy, cx + EMOJI_CELL - 4, cy + EMOJI_CELL - 4, 0x40FFFFFF);
            }
            drawFace(guiGraphics, cx + 3, cy + 3, EMOJI_CELL - 10, faces[i]);
        }
        // 颜文字（跟在表情后面）
        for (int k = 0; k < KAOMOJI.size(); k++) {
            int index = faces.length + k;
            int cx = x + 4 + (index % columns) * EMOJI_CELL;
            int cy = y + 4 + (index / columns) * EMOJI_CELL;
            boolean hover = isInside(mouseX, mouseY, cx, cy, EMOJI_CELL - 4, EMOJI_CELL - 4);
            if (hover) {
                guiGraphics.fill(cx, cy, cx + EMOJI_CELL - 4, cy + EMOJI_CELL - 4, 0x40FFFFFF);
            }
            guiGraphics.drawString(this.font, KAOMOJI.get(k), cx + 1, cy + 11, 0xFFFFFFFF);
        }
    }

    /** 返回点击到的表情内容（emoji 字符串或颜文字），没有则 null。 */
    private String emojiAt(double mouseX, double mouseY) {
        int columns = 4;
        int rows = 4;
        int panelWidth = columns * EMOJI_CELL + 8;
        int panelHeight = rows * EMOJI_CELL + 8;
        int x = this.panelX + this.panelW - panelWidth - MARGIN;
        int y = this.panelY + this.panelH - MARGIN - BAR_HEIGHT - panelHeight - 4;

        Face[] faces = Face.values();
        for (int i = 0; i < faces.length; i++) {
            int cx = x + 4 + (i % columns) * EMOJI_CELL;
            int cy = y + 4 + (i / columns) * EMOJI_CELL;
            if (isInside(mouseX, mouseY, cx, cy, EMOJI_CELL - 4, EMOJI_CELL - 4)) {
                return faces[i].emoji;
            }
        }
        for (int k = 0; k < KAOMOJI.size(); k++) {
            int index = faces.length + k;
            int cx = x + 4 + (index % columns) * EMOJI_CELL;
            int cy = y + 4 + (index / columns) * EMOJI_CELL;
            if (isInside(mouseX, mouseY, cx, cy, EMOJI_CELL - 4, EMOJI_CELL - 4)) {
                return KAOMOJI.get(k);
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
            guiGraphics.fill(button.x(), button.y(), button.x() + button.w(), button.y() + button.h(), background);
            int border = this.theme.buttonBorder();
            guiGraphics.fill(button.x(), button.y(), button.x() + button.w(), button.y() + 1, border);
            guiGraphics.fill(button.x(), button.y() + button.h() - 1, button.x() + button.w(), button.y() + button.h(), border);
            guiGraphics.fill(button.x(), button.y(), button.x() + 1, button.y() + button.h(), border);
            guiGraphics.fill(button.x() + button.w() - 1, button.y(), button.x() + button.w(), button.y() + button.h(), border);
            int textWidth = this.font.width(button.label());
            guiGraphics.drawString(this.font, button.label(),
                    button.x() + (button.w() - textWidth) / 2,
                    button.y() + (button.h() - 8) / 2,
                    hover ? this.theme.accent() : this.theme.textPrimary());
        }
    }

    private boolean isInside(double mouseX, double mouseY, int x, int y, int w, int h) {
        return mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h;
    }

    // ==================== 交互 ====================

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (this.emojiOpen) {
            String emoji = emojiAt(mouseX, mouseY);
            if (emoji != null) {
                this.input.insertText(emoji);
                return true;
            }
            this.emojiOpen = false;
            return true;
        }

        for (FlatButton flatButton : this.flatButtons) {
            if (isInside(mouseX, mouseY, flatButton.x(), flatButton.y(), flatButton.w(), flatButton.h())) {
                flatButton.action().run();
                return true;
            }
        }

        if (clickAtCandidate(mouseX, mouseY)) {
            return true;
        }

        if (this.messageMenuTarget != null) {
            int index = messageMenuItemAt(mouseX, mouseY);
            if (index >= 0) {
                handleMessageMenuAction(index);
                this.messageMenuTarget = null;
                return true;
            }
            this.messageMenuTarget = null;
        }

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

        int sidebarItem = sidebarItemAt(mouseX, mouseY);
        if (sidebarItem >= 0) {
            selectSidebarItem(sidebarItem);
            return true;
        }

        ChatMessage messageAt = messageAt(mouseX, mouseY);
        if (messageAt != null) {
            if (button == 1) {
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
            Minecraft.getInstance().keyboardHandler.setClipboard(text);
        } else {
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
        int y = this.panelY + MARGIN + 34;
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
        this.scroll = 0;
        this.lastSeenMessageCount = ChatHub.messages(this.conversation).size();
        ChatHub.setOpenConversation(this.conversation);
    }

    private ChatMessage messageAt(double mouseX, double mouseY) {
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
        if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
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
        // 过滤控制字符：服务端会以「非法聊天字符」拒绝并断开连接
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