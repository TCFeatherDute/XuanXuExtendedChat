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
    /** 最左侧竖排功能导航栏。 */
    private static final int NAV_WIDTH = 52;
    /** 导航项行高 / 左右内缩。 */
    private static final int NAV_ITEM_HEIGHT = 34;
    private static final int NAV_ITEM_INSET = 6;
    /** 中间会话列表栏。 */
    private static final int LIST_WIDTH = 210;
    private static final int CONVERSATION_ITEM_HEIGHT = 44;
    private static final int MARGIN = 8;
    /** 输入框 / 发送按钮 / 工具栏行高。 */
    private static final int INPUT_BAR_HEIGHT = 20;
    private static final int TOOL_BAR_HEIGHT = 18;
    private static final int TOOL_GAP = 6;
    private static final int SEND_BUTTON_WIDTH = 56;
    /**
     * 单行文字高度。
     * <p>
     * 注意：{@code EditBox} 在 {@code bordered=false} 时把文字画在控件原点
     * （{@code bordered ? getY() + (height-8)/2 : getY()}），
     * 所以输入框控件必须自己下移 {@code (框高 - 8) / 2} 才能落在底板中间。
     */
    private static final int TEXT_LINE_HEIGHT = 8;
    /** QQ 蓝发送按钮。 */
    private static final int SEND_BUTTON_COLOR = 0xFF2F7CF6;
    private static final int SEND_BUTTON_HOVER = 0xFF4A90FF;
    /** 会话列表搜索框高度，以及它相对「消息」标题的下移量。 */
    private static final int SEARCH_BOX_HEIGHT = 18;
    private static final int SEARCH_BOX_TOP_OFFSET = 15;
    private static final int AVATAR = 16;
    private static final int LIST_AVATAR = 20;
    private static final int LINE = 9;
    private static final int BUBBLE_PAD = 6;
    /** 消息与消息之间的固定空隙。 */
    private static final int MESSAGE_GAP = 10;
    /** 名称行到气泡之间的空隙。 */
    private static final int NAME_GAP = 2;
    /** 气泡到消息图片之间的空隙。 */
    private static final int IMAGE_GAP = 4;
    /** 时间分隔：上留白 + 文字行 + 下留白，合计为 TIME_SEPARATOR_HEIGHT。 */
    private static final int TIME_PAD_ABOVE = 8;
    private static final int TIME_TEXT_HEIGHT = 9;
    private static final int TIME_PAD_BELOW = 12;
    private static final int TIME_SEPARATOR_HEIGHT = TIME_PAD_ABOVE + TIME_TEXT_HEIGHT + TIME_PAD_BELOW;
    /** 导航栏底色（绘制图标镂空时也要用到）。 */
    private static final int NAV_BG = 0xFF101018;
    private static final int EMOJI_CELL = 34;
    /** 表情面板每行列数、单格图片绘制尺寸。 */
    private static final int EMOJI_COLUMNS = 4;
    private static final int EMOJI_PANEL_SIZE = 24;
    /** 分组标签行高、网格最多显示的行数。 */
    private static final int EMOJI_TAB_HEIGHT = 13;
    private static final int EMOJI_MAX_ROWS = 8;
    /** 消息气泡内表情的绘制尺寸。 */
    private static final int EMOJI_SIZE = 18;
    /** 窗口顶部标题栏高度。 */
    private static final int TITLE_BAR_HEIGHT = 22;

    /** 世界聊天（公共频道）在会话列表里的显示名。 */
    private static final String WORLD_CHAT_NAME = "世界聊天";

    /** 消息中的图片 / GIF 链接。 */
    private static final Pattern IMAGE_URL =
            Pattern.compile("(?i)(https?://\\S+?\\.(?:png|jpe?g|gif|webp)(?:\\?\\S*)?)");
    private static final int IMAGE_DISPLAY_MAX_W = 160;

    /** 左侧功能导航栏的栏目。 */
    private enum NavTab {
        MESSAGES("消息"),
        CONTACTS("联系人"),
        FAVORITES("收藏"),
        CHANNELS("频道"),
        MOMENTS("动态"),
        APPS("应用"),
        MORE("更多"),
        MAIL("邮件"),
        DEVICES("设备"),
        MAIN_MENU("主菜单");

        private final String label;

        NavTab(String label) {
            this.label = label;
        }
    }

    /** 输入工具栏的图标。目前只有表情和图片是真功能，其余先占位。 */
    private enum ToolIcon {
        EMOJI("表情"),
        SCREENSHOT("截图"),
        FILE("传文件"),
        IMAGE("图片"),
        RED_PACKET("红包"),
        VOICE("语音"),
        SHAKE("震动");

        private final String label;

        ToolIcon(String label) {
            this.label = label;
        }
    }

    /** QQ 风格黄豆表情（绘制 + 插入对应的 unicode emoji）。 */
    private enum Face {
        SMILE("😊"), LAUGH("😂"), WINK("😉"), CRY("😭"),
        ANGRY("😠"), SURPRISED("😮"), SHY("😳"), LOVE("😍");

        private final String emoji;

        Face(String emoji) {
            this.emoji = emoji;
        }
    }

    /**
     * 颜文字（附在表情面板后面）。
     * <p>
     * 只用短的：表情格子只有 {@link #EMOJI_CELL} 宽，长颜文字会溢出到相邻格子。
     * 另外绘制时还有 {@link #drawScaledString} 兜底，超宽一律等比缩小。
     */
    private static final List<String> KAOMOJI = List.of(
            "(^^)", "(^_^)", "(-_-)", "(>_<)", "(T_T)", "(*_*)"
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
    /** 表情面板当前分组下标与竖向滚动偏移（像素）。 */
    private int emojiGroupIndex;
    private int emojiScroll;
    private String atQuery;

    /** 当前选中的左侧导航栏目。 */
    private NavTab activeTab = NavTab.MESSAGES;

    private String headMenuPlayer;
    private double headMenuX;
    private double headMenuY;

    private ChatMessage messageMenuTarget;
    private double messageMenuX;
    private double messageMenuY;

    private List<PlayerInfo> cachedPlayers = List.of();

    private final List<FlatButton> flatButtons = new ArrayList<>();
    /** 主按钮（蓝色发送按钮这类）。 */
    private final List<PrimaryButton> primaryButtons = new ArrayList<>();
    /** 工具栏图标按钮。 */
    private final List<IconButton> iconButtons = new ArrayList<>();

    /** 固定面板区域（屏幕左侧）。 */
    private int panelX;
    private int panelY;
    private int panelW;
    private int panelH;

    private String hintText;
    private long hintUntil;

    private record FlatButton(int x, int y, int w, int h, Component label, Runnable action) {
    }

    /** 蓝色主按钮。 */
    private record PrimaryButton(int x, int y, int w, int h, Component label, Runnable action) {
    }

    /** 工具栏图标按钮。 */
    private record IconButton(int x, int y, int w, int h, ToolIcon icon, String label, Runnable action) {
    }

    /**
     * 会话列表里的一行。
     *
     * @param key       会话键（{@link ChatHub#PUBLIC} 或玩家名）
     * @param name      显示名
     * @param player    头像对应的玩家名；世界聊天为 null（画专用图标）
     * @param preview   最新消息预览
     * @param timestamp 最新消息时间；没有消息为 0
     * @param unread    未读数
     */
    private record ConversationItem(String key, String name, String player, String preview,
                                   long timestamp, int unread) {
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
        this.primaryButtons.clear();
        this.iconButtons.clear();
        computePanel();

        // 搜索框（会话列表栏顶部）：收起状态下也保留
        this.searchBox = new EditBox(this.font, listX() + MARGIN + 6, searchBoxTextY(),
                listWidth() - MARGIN * 2 - 12, SEARCH_BOX_HEIGHT, Component.literal("搜索消息..."));
        this.searchBox.setMaxLength(32);
        this.searchBox.setBordered(false);
        this.searchBox.setHint(Component.literal("搜索消息"));
        this.searchBox.setValue(this.searchDraft);
        addRenderableWidget(this.searchBox);

        // 标题栏右侧：收起整个聊天区
        addFlatButton(this.panelX + this.panelW - MARGIN - 18, this.panelY + 3, 18, 16,
                Component.literal("×"), this::collapseRightPanel);

        // 聊天区收起时，输入区和工具栏整个不存在
        if (this.collapsed) {
            this.input = null;
            return;
        }

        // 工具栏：图标按钮（表情 / 截图 / 传文件 / 图片 / 红包 / 语音 / 震动）
        int toolY = toolBarY();
        int toolX = areaX();
        addIconButton(toolX, toolY, ToolIcon.EMOJI, () -> this.emojiOpen = !this.emojiOpen);
        addIconButton(toolX + 24, toolY, ToolIcon.SCREENSHOT, () -> showHint("截图功能开发中"));
        addIconButton(toolX + 48, toolY, ToolIcon.FILE, () -> showHint("传文件功能开发中"));
        addIconButton(toolX + 72, toolY, ToolIcon.IMAGE, this::insertClipboardImage);
        addIconButton(toolX + 96, toolY, ToolIcon.RED_PACKET, () -> showHint("红包功能开发中"));
        addIconButton(toolX + 120, toolY, ToolIcon.VOICE, () -> showHint("语音功能开发中"));
        addIconButton(toolX + 144, toolY, ToolIcon.SHAKE, () -> showHint("震动功能开发中"));

        // 输入框
        this.input = new EditBox(this.font, inputBoxX() + 6, inputTextY(), inputBoxW() - 12,
                INPUT_BAR_HEIGHT, Component.literal("输入消息..."));
        this.input.setValue(this.draft);
        this.input.setMaxLength(256);
        this.input.setFocused(true);
        this.input.setBordered(false);
        this.input.setHint(Component.literal("输入消息..."));
        this.input.setEditable(this.conversationOpen);
        addRenderableWidget(this.input);
        setInitialFocus(this.input);

        // 右下角蓝色发送按钮
        addPrimaryButton(areaX() + areaW() - SEND_BUTTON_WIDTH, inputBoxTop(),
                SEND_BUTTON_WIDTH, INPUT_BAR_HEIGHT, Component.literal("发送"),
                () -> sendMessage(this.input.getValue()));
    }

    /** 记住输入框 / 搜索框内容，供重建控件后恢复。 */
    private void rememberInputState() {
        if (this.input != null) {
            this.draft = this.input.getValue();
        }
        if (this.searchBox != null) {
            this.searchDraft = this.searchBox.getValue();
        }
    }

    /** 收起聊天区：面板只剩导航栏 + 会话列表，点任意会话重新展开。 */
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
    }

    /** 计算固定面板区域：固定在屏幕左侧，三栏（导航 / 会话列表 / 聊天）。 */
    private void computePanel() {
        this.panelH = (int) Math.min(this.height - 16, Math.max(320, this.height * 0.9));
        this.panelY = Math.max(4, (this.height - this.panelH) / 2);
        this.panelX = 8;
        int maxW = Math.max(360, this.width - this.panelX - 8);
        if (this.collapsed) {
            this.panelW = Math.min(maxW, NAV_WIDTH + LIST_WIDTH);
        } else {
            // 聊天区至少留 260px，屏幕小的时候整体收窄
            int minW = NAV_WIDTH + 120 + 260;
            int want = Math.max(880, (int) (this.width * 0.68));
            this.panelW = Math.min(maxW, Math.max(minW, want));
        }
    }

    // ==================== 区域 ====================

    /** 标题栏下方的内容起始 y。 */
    private int contentY() {
        return this.panelY + TITLE_BAR_HEIGHT;
    }

    /** 会话列表栏左边界（导航栏右侧）。 */
    private int listX() {
        return this.panelX + NAV_WIDTH;
    }

    /** 会话列表栏宽度：屏幕太窄时让出空间给聊天区。 */
    private int listWidth() {
        if (this.collapsed) {
            return Math.max(120, this.panelW - NAV_WIDTH);
        }
        int maxList = Math.max(120, this.panelW - NAV_WIDTH - 260);
        return Math.min(LIST_WIDTH, maxList);
    }

    /** 聊天栏左边界（会话列表右侧）。 */
    private int chatX() {
        return this.panelX + NAV_WIDTH + listWidth();
    }

    private int chatW() {
        return this.panelX + this.panelW - chatX();
    }

    /** 输入框 / 发送按钮所在行的视觉底板顶部。 */
    private int inputBoxTop() {
        return this.panelY + this.panelH - MARGIN - INPUT_BAR_HEIGHT;
    }

    /** 输入框控件 y：控件原点即文字顶部，下移让文字在底板里垂直居中。 */
    private int inputTextY() {
        return inputBoxTop() + (INPUT_BAR_HEIGHT - TEXT_LINE_HEIGHT) / 2;
    }

    /** 输入框底板左边界 / 宽度（右侧给发送按钮留位置）。 */
    private int inputBoxX() {
        return areaX();
    }

    private int inputBoxW() {
        return Math.max(80, areaW() - SEND_BUTTON_WIDTH - 14);
    }

    /** 表情 / 截图 / 传文件 / 发图片 工具栏所在行。 */
    private int toolBarY() {
        return inputBoxTop() - TOOL_GAP - TOOL_BAR_HEIGHT;
    }

    /** 消息区（聊天记录）。 */
    private int areaX() {
        return chatX() + MARGIN;
    }

    private int areaY() {
        return contentY() + MARGIN;
    }

    private int areaW() {
        return Math.max(60, chatW() - MARGIN * 2);
    }

    private int areaH() {
        return Math.max(40, toolBarY() - TOOL_GAP - areaY());
    }

    /** 会话列表搜索框的视觉底板顶部。 */
    private int searchBoxTop() {
        return contentY() + MARGIN + SEARCH_BOX_TOP_OFFSET;
    }

    /** 搜索框控件 y：控件原点即文字顶部，下移让文字在底板里垂直居中。 */
    private int searchBoxTextY() {
        return searchBoxTop() + (SEARCH_BOX_HEIGHT - TEXT_LINE_HEIGHT) / 2;
    }

    /** 会话条目起始 y：渲染与点击判定共用同一个值，避免 UI 与热区错位。 */
    private int conversationItemsTop() {
        return searchBoxTop() + SEARCH_BOX_HEIGHT + MARGIN;
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
        ChatHub.setOpenConversation(this.conversationOpen && !this.collapsed ? this.conversation : null);
        // scroll 是「距底部的高度」：新消息只会在底部追加，
        // 不重置它，正在翻看历史的人就不会被拽回底部。
        updateAtQuery();

        // 非「消息」栏目下，会话列表和输入框都不显示
        boolean messagesTab = this.activeTab == NavTab.MESSAGES;
        if (this.searchBox != null) {
            this.searchBox.setVisible(messagesTab);
        }
        if (this.input != null) {
            this.input.setVisible(messagesTab);
        }

        // 屏幕暗化 + 面板背景
        guiGraphics.fill(0, 0, this.width, this.height, 0x80000000);
        guiGraphics.fill(this.panelX, this.panelY,
                this.panelX + this.panelW, this.panelY + this.panelH, this.theme.background());

        // 三栏：导航 / 会话列表 / 聊天
        renderNavBar(guiGraphics, mouseX, mouseY);
        renderTitleBar(guiGraphics);
        if (!messagesTab) {
            renderPlaceholderPage(guiGraphics);
        } else {
            renderConversationList(guiGraphics, mouseX, mouseY);
            if (!this.collapsed) {
                renderMessages(guiGraphics, mouseX, mouseY);
                renderInputBar(guiGraphics);
            }
        }

        // 聊天区专属控件（发送、表情/图片等工具、收起按钮）只在「消息」栏目下出现
        if (messagesTab) {
            renderFlatButtons(guiGraphics, mouseX, mouseY);
            renderPrimaryButtons(guiGraphics, mouseX, mouseY);
            renderIconButtons(guiGraphics, mouseX, mouseY);
        }

        for (var renderable : this.renderables) {
            renderable.render(guiGraphics, mouseX, mouseY, partialTick);
        }

        if (!this.collapsed && messagesTab) {
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
        renderNavTooltip(guiGraphics, mouseX, mouseY);
        if (messagesTab) {
            renderIconTooltips(guiGraphics, mouseX, mouseY);
        }
        renderHint(guiGraphics);
    }

    // ==================== 三栏：导航 / 列表 / 聊天 ====================

    /** 导航项行高：面板矮时自动压缩，保证 10 个栏目都放得下。 */
    private int navRowHeight() {
        int available = this.panelH - MARGIN * 2;
        int fit = available / NavTab.values().length;
        return Math.max(18, Math.min(NAV_ITEM_HEIGHT, fit));
    }

    /**
     * 第 index 个导航项的 y；返回 -1 表示放不下、不显示。
     * <p>
     * 主菜单固定贴在导航栏左下角，其余栏目从顶部依次排列。
     * 渲染和点击判定共用这个方法，保证热区和图形一致。
     */
    private int navItemTop(int index) {
        NavTab tab = NavTab.values()[index];
        int rowH = navRowHeight();
        int itemH = rowH - 6;
        int mainMenuTop = this.panelY + this.panelH - MARGIN - itemH;
        if (tab == NavTab.MAIN_MENU) {
            return mainMenuTop;
        }
        int y = this.panelY + MARGIN + index * rowH;
        if (y + itemH + 4 > mainMenuTop) {
            return -1;
        }
        return y;
    }

    /** 最左侧竖排功能导航栏。 */
    private void renderNavBar(GuiGraphics guiGraphics, int mouseX, int mouseY) {
        int left = this.panelX;
        guiGraphics.fill(left, this.panelY, left + NAV_WIDTH, this.panelY + this.panelH, NAV_BG);

        NavTab[] tabs = NavTab.values();
        for (int i = 0; i < tabs.length; i++) {
            int y = navItemTop(i);
            if (y < 0) {
                continue;
            }
            NavTab tab = tabs[i];
            boolean active = tab == this.activeTab;
            int itemX = left + NAV_ITEM_INSET;
            int itemW = NAV_WIDTH - NAV_ITEM_INSET * 2;
            int itemH = navRowHeight() - 6;
            boolean hover = isInside(mouseX, mouseY, itemX, y, itemW, itemH);
            int backdrop = NAV_BG;
            if (active) {
                backdrop = this.theme.panelSelected();
                fillRoundedRect(guiGraphics, itemX, y, itemW, itemH, 4, backdrop);
                // 选中项左侧的高亮竖条
                guiGraphics.fill(itemX - 2, y + 5, itemX, y + itemH - 5, this.theme.accent());
            } else if (hover) {
                backdrop = this.theme.buttonBg();
                fillRoundedRect(guiGraphics, itemX, y, itemW, itemH, 4, backdrop);
            }
            int color = active ? this.theme.accent() : (hover ? this.theme.textPrimary() : this.theme.textSecondary());
            drawNavIcon(guiGraphics, tab, itemX + itemW / 2, y + itemH / 2, color, backdrop);
        }
    }

    /**
     * 代码绘制的导航图标（16×16 局部坐标系，origin 为图标左上角）。
     *
     * @param backdrop 图标所在背景色，镂空处用它填
     */
    private void drawNavIcon(GuiGraphics guiGraphics, NavTab tab, int cx, int cy, int color, int backdrop) {
        int x = cx - 8;
        int y = cy - 8;
        switch (tab) {
            case MESSAGES -> {
                // 对话气泡 + 左下小尾巴
                fillRoundedRect(guiGraphics, x + 1, y + 3, 14, 9, 3, color);
                guiGraphics.fill(x + 4, y + 11, x + 7, y + 13, color);
                guiGraphics.fill(x + 3, y + 12, x + 6, y + 14, color);
            }
            case CONTACTS -> {
                // 头 + 肩
                fillCircle(guiGraphics, cx, y + 5, 3, color);
                fillRoundedRect(guiGraphics, x + 2, y + 10, 12, 6, 3, color);
            }
            case FAVORITES -> {
                // 书签（底部带 V 缺口）
                guiGraphics.fill(x + 4, y + 2, x + 6, y + 13, color);
                guiGraphics.fill(x + 10, y + 2, x + 12, y + 13, color);
                guiGraphics.fill(x + 4, y + 2, x + 12, y + 4, color);
                guiGraphics.fill(x + 6, y + 11, x + 8, y + 13, color);
                guiGraphics.fill(x + 8, y + 11, x + 10, y + 13, color);
            }
            case CHANNELS -> {
                // 井号 #
                guiGraphics.fill(x + 3, y + 5, x + 13, y + 7, color);
                guiGraphics.fill(x + 3, y + 10, x + 13, y + 12, color);
                guiGraphics.fill(x + 5, y + 2, x + 7, y + 14, color);
                guiGraphics.fill(x + 9, y + 2, x + 11, y + 14, color);
            }
            case MOMENTS -> {
                // 相机：机身 + 顶部突起 + 镜头
                fillRoundedRect(guiGraphics, x + 1, y + 4, 14, 9, 2, color);
                guiGraphics.fill(x + 5, y + 2, x + 11, y + 4, color);
                fillCircle(guiGraphics, cx, y + 9, 3, backdrop);
                fillCircle(guiGraphics, cx, y + 9, 1, color);
            }
            case APPS -> {
                // 2×2 宫格
                fillRoundedRect(guiGraphics, x + 2, y + 2, 6, 6, 2, color);
                fillRoundedRect(guiGraphics, x + 9, y + 2, 6, 6, 2, color);
                fillRoundedRect(guiGraphics, x + 2, y + 9, 6, 6, 2, color);
                fillRoundedRect(guiGraphics, x + 9, y + 9, 6, 6, 2, color);
            }
            case MORE -> {
                // 三个点
                fillCircle(guiGraphics, cx - 5, cy, 2, color);
                fillCircle(guiGraphics, cx, cy, 2, color);
                fillCircle(guiGraphics, cx + 5, cy, 2, color);
            }
            case MAIL -> {
                // 信封：边框 + 内部折线
                guiGraphics.fill(x + 1, y + 4, x + 15, y + 6, color);
                guiGraphics.fill(x + 1, y + 12, x + 15, y + 14, color);
                guiGraphics.fill(x + 1, y + 4, x + 3, y + 14, color);
                guiGraphics.fill(x + 13, y + 4, x + 15, y + 14, color);
                for (int i = 0; i < 5; i++) {
                    guiGraphics.fill(x + 3 + i, y + 6 + i, x + 5 + i, y + 7 + i, color);
                    guiGraphics.fill(x + 11 - i, y + 6 + i, x + 13 - i, y + 7 + i, color);
                }
            }
            case DEVICES -> {
                // 显示器：屏幕 + 支架 + 底座
                fillRoundedRect(guiGraphics, x + 1, y + 2, 14, 10, 2, color);
                fillRoundedRect(guiGraphics, x + 2, y + 3, 12, 8, 1, backdrop);
                guiGraphics.fill(x + 7, y + 12, x + 9, y + 14, color);
                guiGraphics.fill(x + 4, y + 14, x + 12, y + 15, color);
            }
            case MAIN_MENU -> {
                // 三条横线
                guiGraphics.fill(x + 2, y + 4, x + 14, y + 6, color);
                guiGraphics.fill(x + 2, y + 7, x + 14, y + 9, color);
                guiGraphics.fill(x + 2, y + 10, x + 14, y + 12, color);
            }
        }
    }

    /** 悬停时在导航栏右侧显示栏目全名。 */
    private void renderNavTooltip(GuiGraphics guiGraphics, int mouseX, int mouseY) {
        int index = navItemAt(mouseX, mouseY);
        if (index < 0) {
            return;
        }
        String label = NavTab.values()[index].label;
        int y = Math.max(this.panelY + 4,
                Math.min(mouseY - 4, this.panelY + this.panelH - 16));
        drawTooltipLabel(guiGraphics, label, this.panelX + NAV_WIDTH + 4, y);
    }

    /** 按最大宽度绘制文字：超出可用宽度就等比缩小，绝对不会越界。 */
    private void drawScaledString(GuiGraphics guiGraphics, String text, int x, int y, int maxWidth, int color) {
        if (text == null || text.isEmpty() || maxWidth <= 0) {
            return;
        }
        int width = this.font.width(text);
        if (width <= maxWidth) {
            guiGraphics.drawString(this.font, text, x, y, color);
            return;
        }
        float scale = (float) maxWidth / (float) width;
        guiGraphics.pose().pushPose();
        guiGraphics.pose().translate(x, y, 0.0F);
        guiGraphics.pose().scale(scale, scale, 1.0F);
        guiGraphics.drawString(this.font, text, 0, 0, color);
        guiGraphics.pose().popPose();
    }

    /** 深色圆角小标签（导航栏 / 工具栏的悬停提示共用）。 */
    private void drawTooltipLabel(GuiGraphics guiGraphics, String label, int x, int y) {
        int width = this.font.width(label) + 10;
        fillRoundedRect(guiGraphics, x, y, width, 15, 4, 0xF0202030);
        guiGraphics.drawString(this.font, label, x + 5, y + 4, 0xFFFFFFFF);
    }

    /** 代码绘制的工具栏图标（16×16 局部坐标系，origin 为左上角）。 */
    private void drawToolIcon(GuiGraphics guiGraphics, ToolIcon icon, int cx, int cy, int color, int backdrop) {
        int x = cx - 8;
        int y = cy - 8;
        switch (icon) {
            case EMOJI -> drawFace(guiGraphics, x + 1, y + 1, 14, Face.SMILE);
            case SCREENSHOT -> {
                // 四个取景角
                guiGraphics.fill(x + 2, y + 2, x + 6, y + 4, color);
                guiGraphics.fill(x + 2, y + 2, x + 4, y + 6, color);
                guiGraphics.fill(x + 10, y + 2, x + 14, y + 4, color);
                guiGraphics.fill(x + 12, y + 2, x + 14, y + 6, color);
                guiGraphics.fill(x + 2, y + 10, x + 6, y + 12, color);
                guiGraphics.fill(x + 2, y + 8, x + 4, y + 12, color);
                guiGraphics.fill(x + 10, y + 10, x + 14, y + 12, color);
                guiGraphics.fill(x + 12, y + 8, x + 14, y + 12, color);
            }
            case FILE -> {
                // 文件夹
                guiGraphics.fill(x + 2, y + 3, x + 8, y + 5, color);
                fillRoundedRect(guiGraphics, x + 1, y + 5, 14, 9, 2, color);
            }
            case IMAGE -> {
                // 相框 + 远山 + 太阳
                guiGraphics.fill(x + 1, y + 2, x + 15, y + 4, color);
                guiGraphics.fill(x + 1, y + 12, x + 15, y + 14, color);
                guiGraphics.fill(x + 1, y + 2, x + 3, y + 14, color);
                guiGraphics.fill(x + 13, y + 2, x + 15, y + 14, color);
                guiGraphics.fill(x + 4, y + 9, x + 12, y + 12, color);
                guiGraphics.fill(x + 6, y + 6, x + 9, y + 9, color);
                fillCircle(guiGraphics, x + 11, y + 6, 1, color);
            }
            case RED_PACKET -> {
                // 红包：圆角块 + 中间金色横带
                fillRoundedRect(guiGraphics, x + 2, y + 2, 12, 12, 2, color);
                guiGraphics.fill(x + 2, y + 7, x + 14, y + 9, backdrop);
                guiGraphics.fill(x + 7, y + 6, x + 9, y + 10, color);
            }
            case VOICE -> {
                // 麦克风
                fillRoundedRect(guiGraphics, x + 5, y + 1, 6, 9, 3, color);
                guiGraphics.fill(x + 5, y + 9, x + 11, y + 11, color);
                guiGraphics.fill(x + 7, y + 11, x + 9, y + 14, color);
                guiGraphics.fill(x + 4, y + 14, x + 12, y + 15, color);
            }
            case SHAKE -> {
                // 手机 + 两侧震动线
                fillRoundedRect(guiGraphics, x + 5, y + 1, 6, 14, 2, color);
                fillRoundedRect(guiGraphics, x + 6, y + 3, 4, 10, 1, backdrop);
                guiGraphics.fill(x + 1, y + 4, x + 3, y + 5, color);
                guiGraphics.fill(x + 1, y + 7, x + 3, y + 8, color);
                guiGraphics.fill(x + 1, y + 10, x + 3, y + 11, color);
                guiGraphics.fill(x + 13, y + 4, x + 15, y + 5, color);
                guiGraphics.fill(x + 13, y + 7, x + 15, y + 8, color);
                guiGraphics.fill(x + 13, y + 10, x + 15, y + 11, color);
            }
        }
    }

    /** 点击到第几个导航项，没有则 -1。 */
    private int navItemAt(double mouseX, double mouseY) {
        if (mouseX < this.panelX || mouseX > this.panelX + NAV_WIDTH) {
            return -1;
        }
        NavTab[] tabs = NavTab.values();
        for (int i = 0; i < tabs.length; i++) {
            int y = navItemTop(i);
            if (y < 0) {
                continue;
            }
            if (isInside(mouseX, mouseY, this.panelX + NAV_ITEM_INSET, y,
                    NAV_WIDTH - NAV_ITEM_INSET * 2, navRowHeight() - 6)) {
                return i;
            }
        }
        return -1;
    }

    /** 顶部标题栏（覆盖会话列表 + 聊天两栏）。 */
    private void renderTitleBar(GuiGraphics guiGraphics) {
        guiGraphics.fill(listX(), this.panelY,
                this.panelX + this.panelW, this.panelY + TITLE_BAR_HEIGHT, this.theme.panelSelected());
        String title = this.activeTab == NavTab.MESSAGES ? "玄墟聊天" : this.activeTab.label;
        guiGraphics.drawString(this.font, title, listX() + MARGIN, this.panelY + 7, this.theme.accent());
    }

    /** 非「消息」栏目的占位页（功能待做）。 */
    private void renderPlaceholderPage(GuiGraphics guiGraphics) {
        int left = listX();
        int right = this.panelX + this.panelW;
        int bottom = this.panelY + this.panelH;
        guiGraphics.fill(left, contentY(), right, bottom, this.theme.panel());

        String title = this.activeTab.label;
        String sub = "「" + title + "」页面开发中";
        int cx = left + (right - left) / 2;
        int cy = contentY() + (bottom - contentY()) / 2;
        int tw = this.font.width(title);
        guiGraphics.drawString(this.font, title, cx - tw / 2, cy - 14, this.theme.accent());
        int sw = this.font.width(sub);
        guiGraphics.drawString(this.font, sub, cx - sw / 2, cy, this.theme.textSecondary());
    }

    /** 中间会话列表栏：头像 + 名字 + 最新消息预览 + 时间 + 未读。 */
    private void renderConversationList(GuiGraphics guiGraphics, int mouseX, int mouseY) {
        int left = listX();
        int right = chatX();
        int bottom = this.panelY + this.panelH;
        guiGraphics.fill(left, contentY(), right, bottom, this.theme.panel());

        // 栏目标题
        guiGraphics.drawString(this.font, "消息", left + MARGIN, contentY() + MARGIN, this.theme.accent());

        // 搜索框底板
        fillRoundedRect(guiGraphics, left + MARGIN, searchBoxTop(),
                listWidth() - MARGIN * 2, SEARCH_BOX_HEIGHT, 5, this.theme.inputBg());

        int y = conversationItemsTop();
        List<ConversationItem> items = conversationItems();
        for (ConversationItem item : items) {
            if (y + CONVERSATION_ITEM_HEIGHT > bottom) {
                break;
            }
            boolean selected = item.key().equals(this.conversation) && this.conversationOpen && !this.collapsed;
            boolean hover = isInside(mouseX, mouseY, left, y, right - left, CONVERSATION_ITEM_HEIGHT);
            if (selected) {
                guiGraphics.fill(left, y, right, y + CONVERSATION_ITEM_HEIGHT - 2, this.theme.panelSelected());
            } else if (hover) {
                guiGraphics.fill(left, y, right, y + CONVERSATION_ITEM_HEIGHT - 2, 0x22FFFFFF);
            }

            // 头像
            if (item.player() != null) {
                drawPlayerHead(guiGraphics, item.player(), left + MARGIN, y + 6, LIST_AVATAR);
            } else {
                drawWorldAvatar(guiGraphics, left + MARGIN, y + 6, LIST_AVATAR);
            }

            int textX = left + MARGIN + LIST_AVATAR + 8;
            int textRight = right - MARGIN;

            // 时间（右上角）
            String time = formatListTime(item.timestamp());
            if (time != null && !time.isEmpty()) {
                guiGraphics.drawString(this.font, time, textRight - this.font.width(time), y + 7,
                        this.theme.textSecondary());
                textRight -= this.font.width(time) + 6;
            }
            // 名字
            guiGraphics.drawString(this.font, ellipsize(item.name(), textRight - textX + 4),
                    textX, y + 7, selected ? this.theme.accent() : this.theme.textPrimary());

            // 最新消息预览（给未读红点留位置）
            int badgeWidth = 0;
            if (item.unread() > 0) {
                String badgeText = item.unread() > 99 ? "99+" : String.valueOf(item.unread());
                badgeWidth = Math.max(10, this.font.width(badgeText) + 4) + 6;
            }
            String preview = item.preview();
            if (preview != null && !preview.isEmpty()) {
                guiGraphics.drawString(this.font, ellipsize(preview, right - MARGIN - badgeWidth - textX),
                        textX, y + 23, this.theme.textSecondary());
            } else {
                guiGraphics.drawString(this.font, "暂无消息", textX, y + 23, this.theme.textSecondary());
            }

            // 未读红点
            if (item.unread() > 0) {
                drawUnreadBadge(guiGraphics, item.unread(), right - MARGIN, y + 25);
            }
            y += CONVERSATION_ITEM_HEIGHT;
        }
    }

    /** 世界聊天没有玩家头像，画一个深色圆角方块 + 「世」。 */
    private void drawWorldAvatar(GuiGraphics guiGraphics, int x, int y, int size) {
        fillRoundedRect(guiGraphics, x, y, size, size, 4, 0xFF2F5FC4);
        String glyph = "世";
        guiGraphics.drawString(this.font, glyph,
                x + (size - this.font.width(glyph)) / 2, y + (size - LINE) / 2 + 1, 0xFFFFFFFF);
    }

    /** 会话列表项集合：世界聊天 + 在线玩家 + 有历史记录的会话，按最新消息时间倒序。 */
    private List<ConversationItem> conversationItems() {
        java.util.LinkedHashSet<String> keys = new java.util.LinkedHashSet<>();
        keys.add(ChatHub.PUBLIC);
        for (PlayerInfo info : this.cachedPlayers) {
            keys.add(info.getProfile().getName());
        }
        keys.addAll(ChatHub.conversationKeys());

        List<ConversationItem> items = new ArrayList<>();
        for (String key : keys) {
            ChatMessage last = ChatHub.lastMessage(key);
            String preview = "";
            long timestamp = 0L;
            if (last != null) {
                preview = last.self() ? "我：" + last.plainText() : last.plainText();
                timestamp = last.timestamp();
            }
            boolean isPublic = ChatHub.PUBLIC.equals(key);
            items.add(new ConversationItem(key,
                    isPublic ? WORLD_CHAT_NAME : key,
                    isPublic ? null : key,
                    preview, timestamp, ChatHub.unreadOf(key)));
        }
        items.sort((a, b) -> {
            if (a.timestamp() != b.timestamp()) {
                return Long.compare(b.timestamp(), a.timestamp());
            }
            if (ChatHub.PUBLIC.equals(a.key())) {
                return -1;
            }
            if (ChatHub.PUBLIC.equals(b.key())) {
                return 1;
            }
            return a.name().compareTo(b.name());
        });
        return items;
    }

    /** 点击到第几个会话，没有则 -1。 */
    private int conversationItemAt(double mouseX, double mouseY) {
        int left = listX();
        int right = chatX();
        if (mouseX < left || mouseX > right) {
            return -1;
        }
        int y = conversationItemsTop();
        List<ConversationItem> items = conversationItems();
        for (int i = 0; i < items.size(); i++) {
            if (isInside(mouseX, mouseY, left, y, right - left, CONVERSATION_ITEM_HEIGHT)) {
                return i;
            }
            y += CONVERSATION_ITEM_HEIGHT;
        }
        return -1;
    }

    /** 选中第 index 个会话，并切回「消息」栏目。 */
    private void selectConversation(int index) {
        List<ConversationItem> items = conversationItems();
        if (index < 0 || index >= items.size()) {
            return;
        }
        this.activeTab = NavTab.MESSAGES;
        this.conversation = items.get(index).key();
        this.conversationOpen = true;
        this.scroll = 0;
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

    /** 界面内提示（替代动作栏，避免文字过长显示不全）。 */
    private void showHint(String text) {
        this.hintText = text;
        this.hintUntil = System.currentTimeMillis() + 3000L;
    }

    private void renderHint(GuiGraphics guiGraphics) {
        if (this.hintText == null || System.currentTimeMillis() > this.hintUntil) {
            return;
        }
        int centerX = this.collapsed ? this.listX() : this.chatX();
        int centerW = this.collapsed ? this.panelX + this.panelW - this.listX() : this.chatW();
        int textWidth = this.font.width(this.hintText);
        int x = centerX + (centerW - textWidth) / 2;
        x = Math.max(this.panelX + 4, Math.min(x, this.panelX + this.panelW - textWidth - 4));
        int y = this.toolBarY() - 22;
        guiGraphics.fill(x - 6, y - 3, x + textWidth + 6, y + 11, 0xD0202030);
        guiGraphics.drawString(this.font, this.hintText, x, y, 0xFFFFDD55);
    }

    private void drawUnreadBadge(GuiGraphics guiGraphics, int unread, int right, int y) {
        String text = unread > 99 ? "99+" : String.valueOf(unread);
        int w = Math.max(10, this.font.width(text) + 4);
        int x = right - w;
        fillRoundedRect(guiGraphics, x, y, w, 11, 5, 0xFFE04040);
        guiGraphics.drawString(this.font, text, x + 2, y + 1, 0xFFFFFFFF, false);
    }

    /** 超过宽度就用省略号截断。 */
    private String ellipsize(String text, int maxWidth) {
        if (text == null) {
            return "";
        }
        if (maxWidth <= 0) {
            return "";
        }
        if (this.font.width(text) <= maxWidth) {
            return text;
        }
        String ellipsis = "…";
        int ellipsisWidth = this.font.width(ellipsis);
        StringBuilder buffer = new StringBuilder();
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            String next = new String(Character.toChars(cp));
            if (this.font.width(buffer + next) + ellipsisWidth > maxWidth) {
                break;
            }
            buffer.append(next);
            i += Character.charCount(cp);
        }
        return buffer + ellipsis;
    }

    /** 会话列表里的时间：当天只显示时分，跨天显示月-日。 */
    private String formatListTime(long timestamp) {
        if (timestamp <= 0L) {
            return "";
        }
        Date date = new Date(timestamp);
        Date now = new Date();
        SimpleDateFormat f = new SimpleDateFormat(sameDay(date, now) ? "HH:mm" : "MM-dd");
        return f.format(date);
    }

    // ==================== 消息区 ====================

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
            int cursor = areaY + areaH;
            for (int i = messages.size() - 1; i >= 0; i--) {
                ChatMessage message = messages.get(i);
                int height = messageHeight(message, areaW);
                boolean showTime = i == 0 || message.timestamp() - messages.get(i - 1).timestamp() > 5 * 60_000L;
                // 整条消息占「时间分隔（可选）+ 气泡」的高度
                int slotHeight = height + (showTime ? TIME_SEPARATOR_HEIGHT : 0);
                cursor -= slotHeight;
                int drawY = cursor + (int) this.scroll;
                if (drawY + slotHeight < areaY) {
                    break;
                }
                int messageY = drawY;
                if (showTime) {
                    drawTimeSeparator(guiGraphics, message.timestamp(), areaX, drawY, areaW);
                    messageY = drawY + TIME_SEPARATOR_HEIGHT;
                }
                drawMessage(guiGraphics, message, areaX, messageY, areaW);
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
        int textY = y + TIME_PAD_ABOVE;
        int lineY = textY + TIME_TEXT_HEIGHT / 2;
        guiGraphics.fill(x + 10, lineY, centerX - textW / 2 - 8, lineY + 1, 0x66FFFFFF);
        guiGraphics.fill(centerX + textW / 2 + 8, lineY, x + width - 10, lineY + 1, 0x66FFFFFF);
        guiGraphics.drawString(this.font, text, centerX - textW / 2, textY, this.theme.textSecondary());
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
            return lines * LINE + 4 + MESSAGE_GAP;
        }
        // 纯表情消息：按表情尺寸计算（避免溢出气泡）
        if (isEmojiMessage(message)) {
            return LINE + NAME_GAP + (EMOJI_SIZE + BUBBLE_PAD * 2) + MESSAGE_GAP;
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
        int height = LINE + NAME_GAP + bubbleHeight + MESSAGE_GAP;
        if (findImageUrl(message.plainText()) != null) {
            height += IMAGE_GAP + imageDisplayHeight(message.plainText());
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
            int bubbleY = nameY + LINE + NAME_GAP;
            guiGraphics.fill(bubbleX, bubbleY, bubbleX + bubbleWidth, bubbleY + bubbleHeight, this.theme.bubbleSelf());
            drawBubbleContent(guiGraphics, message, lines, mixed, bubbleX + BUBBLE_PAD, bubbleY + BUBBLE_PAD,
                    this.theme.bubbleSelfText());
            if (message.repeatCount() > 1) {
                String badge = "x" + message.repeatCount();
                guiGraphics.drawString(this.font, badge,
                        bubbleX + bubbleWidth - this.font.width(badge) - 4, bubbleY + bubbleHeight + 1, 0xFFAAAAAA);
            }
            drawPlayerHead(guiGraphics, message.senderName(), areaX + areaW - AVATAR - 2, bubbleY, AVATAR);
            drawMessageImage(guiGraphics, message, bubbleX, bubbleY + bubbleHeight + IMAGE_GAP);
        } else {
            drawPlayerHead(guiGraphics, message.senderName(), areaX + 2, areaY + 2, AVATAR);
            int bubbleX = areaX + AVATAR + 8;
            if (!message.senderName().isEmpty()) {
                guiGraphics.drawString(this.font, message.senderName(), bubbleX, nameY, this.theme.accent());
            }
            int bubbleY = nameY + LINE + NAME_GAP;
            guiGraphics.fill(bubbleX, bubbleY, bubbleX + bubbleWidth, bubbleY + bubbleHeight, this.theme.bubbleOthers());
            drawBubbleContent(guiGraphics, message, lines, mixed, bubbleX + BUBBLE_PAD, bubbleY + BUBBLE_PAD,
                    this.theme.textPrimary());
            if (message.repeatCount() > 1) {
                String badge = "x" + message.repeatCount();
                guiGraphics.drawString(this.font, badge,
                        bubbleX + bubbleWidth - this.font.width(badge) - 4, bubbleY + bubbleHeight + 1, 0xFFAAAAAA);
            }
            drawMessageImage(guiGraphics, message, bubbleX, bubbleY + bubbleHeight + IMAGE_GAP);
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
        // 聊天栏底部整块底色（工具栏 + 输入行）
        guiGraphics.fill(chatX(), toolBarY() - TOOL_GAP,
                this.panelX + this.panelW, this.panelY + this.panelH, this.theme.panel());
        // 输入框底板
        fillRoundedRect(guiGraphics, inputBoxX(), inputBoxTop(), inputBoxW(), INPUT_BAR_HEIGHT, 5,
                this.theme.inputBg());
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

    /** 表情面板的一个分组（黄豆 / 颜文字 / 某个前缀的自定义表情）。 */
    private record EmojiGroup(String name, List<EmojiSlot> slots) {
    }

    /** 自定义表情的文件名前缀 → 分组显示名。 */
    private static String groupLabel(String prefix) {
        return switch (prefix) {
            case "tieba" -> "贴吧";
            case "weibo" -> "微博";
            case "bb", "bilibili" -> "B站";
            case "alus" -> "阿鲁";
            case "bmoji" -> "BMO";
            case "coolapk" -> "酷安";
            case "soul", "soul-emoji" -> "灵魂";
            case "tw", "tw-emoji" -> "Twemoji";
            default -> prefix;
        };
    }

    /**
     * 自定义表情按文件名前缀分组：{@code tieba_agree.png} → 「贴吧」组；
     * 文件名里没有下划线的统一归到「自定义」。
     */
    private static String customGroupPrefix(String emojiName) {
        int idx = emojiName.indexOf('_');
        return idx > 0 ? emojiName.substring(0, idx) : "自定义";
    }

    /** 表情面板全部分组：黄豆、颜文字，然后是按前缀排序的自定义表情组。 */
    private List<EmojiGroup> emojiGroups() {
        List<EmojiGroup> groups = new ArrayList<>();

        List<EmojiSlot> faces = new ArrayList<>();
        for (Face face : Face.values()) {
            faces.add(new EmojiSlot(face, null, null));
        }
        groups.add(new EmojiGroup("黄豆", faces));

        List<EmojiSlot> kaomoji = new ArrayList<>();
        for (String text : KAOMOJI) {
            kaomoji.add(new EmojiSlot(null, null, text));
        }
        groups.add(new EmojiGroup("颜文字", kaomoji));

        java.util.TreeMap<String, List<EmojiSlot>> byPrefix = new java.util.TreeMap<>();
        for (ChatEmojiLoader.EmojiEntry entry : ChatEmojiLoader.emojis()) {
            String prefix = customGroupPrefix(entry.name());
            byPrefix.computeIfAbsent(prefix, k -> new ArrayList<>()).add(new EmojiSlot(null, entry, null));
        }
        for (java.util.Map.Entry<String, List<EmojiSlot>> entry : byPrefix.entrySet()) {
            groups.add(new EmojiGroup(groupLabel(entry.getKey()), entry.getValue()));
        }
        return groups;
    }

    /** 当前选中的分组（越界时回落到黄豆组）。 */
    private EmojiGroup currentEmojiGroup() {
        List<EmojiGroup> groups = emojiGroups();
        if (this.emojiGroupIndex < 0 || this.emojiGroupIndex >= groups.size()) {
            this.emojiGroupIndex = 0;
        }
        return groups.get(this.emojiGroupIndex);
    }

    private int emojiPanelWidth() {
        return EMOJI_COLUMNS * EMOJI_CELL + 8;
    }

    /** 表情面板 x：默认贴聊天区左边，聊天区太窄时整体左移，保证不超出面板。 */
    private int emojiPanelX(int panelWidth) {
        int x = areaX();
        int minX = this.panelX + 4;
        int maxX = this.panelX + this.panelW - panelWidth - 4;
        return (int) Math.max(minX, Math.min(x, maxX));
    }

    /** 分组标签行的总高度（标签放不下会换行）。 */
    private int emojiTabAreaHeight(int panelWidth) {
        List<EmojiGroup> groups = emojiGroups();
        if (groups.size() <= 1) {
            return 0;
        }
        int perRow = Math.max(1, panelWidth - 8);
        int used = 0;
        int rows = 1;
        for (EmojiGroup group : groups) {
            int tabWidth = this.font.width(group.name()) + 10;
            if (used > 0 && used + tabWidth > perRow) {
                rows++;
                used = 0;
            }
            used += tabWidth + 2;
        }
        return rows * (EMOJI_TAB_HEIGHT + 2) + 2;
    }

    /** 网格可见行数：受聊天区可用高度限制。 */
    private int emojiVisibleRows(int panelWidth) {
        int available = toolBarY() - TOOL_GAP - areaY() - 8 - emojiTabAreaHeight(panelWidth);
        int fit = Math.max(1, available / EMOJI_CELL);
        int totalRows = emojiRowCount();
        return Math.min(Math.min(fit, EMOJI_MAX_ROWS), totalRows);
    }

    private int emojiRowCount() {
        int slots = currentEmojiGroup().slots().size();
        return Math.max(1, (slots + EMOJI_COLUMNS - 1) / EMOJI_COLUMNS);
    }

    /** 当前分组最大可滚动像素（0 表示不需要滚动）。 */
    private int emojiMaxScroll(int panelWidth) {
        return Math.max(0, (emojiRowCount() - emojiVisibleRows(panelWidth)) * EMOJI_CELL);
    }

    private int emojiScrollPx(int panelWidth) {
        this.emojiScroll = (int) Math.max(0, Math.min(emojiMaxScroll(panelWidth), this.emojiScroll));
        return this.emojiScroll;
    }

    private int emojiPanelHeight() {
        int panelWidth = emojiPanelWidth();
        return emojiTabAreaHeight(panelWidth) + emojiVisibleRows(panelWidth) * EMOJI_CELL + 8;
    }

    /** 表情面板 y：贴着工具栏往上摆，但不会超出聊天区顶部。 */
    private int emojiPanelY(int panelHeight) {
        int y = toolBarY() - TOOL_GAP - panelHeight;
        return Math.max(areaY(), y);
    }

    /** 网格区域顶部（分组标签下方）。 */
    private int emojiGridTop(int panelY, int panelWidth) {
        return panelY + emojiTabAreaHeight(panelWidth);
    }

    private void renderEmojiPanel(GuiGraphics guiGraphics, int mouseX, int mouseY) {
        List<EmojiGroup> groups = emojiGroups();
        EmojiGroup current = currentEmojiGroup();
        int panelWidth = emojiPanelWidth();
        int panelHeight = emojiPanelHeight();
        int x = emojiPanelX(panelWidth);
        int y = emojiPanelY(panelHeight);
        int tabArea = emojiTabAreaHeight(panelWidth);
        guiGraphics.fill(x, y, x + panelWidth, y + panelHeight, 0xF0202028);

        // 分组标签
        if (tabArea > 0) {
            int tx = x + 4;
            int ty = y + 3;
            for (int i = 0; i < groups.size(); i++) {
                EmojiGroup group = groups.get(i);
                int tabWidth = this.font.width(group.name()) + 10;
                if (tx > x + 4 && tx + tabWidth > x + panelWidth - 4) {
                    tx = x + 4;
                    ty += EMOJI_TAB_HEIGHT + 2;
                }
                boolean active = group == current;
                boolean hover = isInside(mouseX, mouseY, tx, ty, tabWidth, EMOJI_TAB_HEIGHT);
                int background = active ? this.theme.accent() : (hover ? this.theme.buttonHover() : this.theme.buttonBg());
                fillRoundedRect(guiGraphics, tx, ty, tabWidth, EMOJI_TAB_HEIGHT, 3, background);
                guiGraphics.drawString(this.font, group.name(), tx + 5, ty + 2,
                        active ? 0xFFFFFFFF : this.theme.textPrimary());
                tx += tabWidth + 2;
            }
        }

        // 网格（裁剪 + 滚动）
        int gridTop = y + tabArea;
        int visibleRows = emojiVisibleRows(panelWidth);
        int gridHeight = visibleRows * EMOJI_CELL;
        int scroll = emojiScrollPx(panelWidth);
        guiGraphics.enableScissor(x, gridTop, x + panelWidth, gridTop + gridHeight);
        List<EmojiSlot> slots = current.slots();
        for (int i = 0; i < slots.size(); i++) {
            int cx = x + 4 + (i % EMOJI_COLUMNS) * EMOJI_CELL;
            int cy = gridTop + (i / EMOJI_COLUMNS) * EMOJI_CELL - scroll;
            if (cy + EMOJI_CELL <= gridTop || cy >= gridTop + gridHeight) {
                continue;   // 完全在可视区外，跳过
            }
            EmojiSlot slot = slots.get(i);
            if (isInside(mouseX, mouseY, cx, cy, EMOJI_CELL - 4, EMOJI_CELL - 4)) {
                guiGraphics.fill(cx, cy, cx + EMOJI_CELL - 4, cy + EMOJI_CELL - 4, 0x40FFFFFF);
            }
            if (slot.emoji() != null) {
                ChatEmojiLoader.EmojiEntry entry = slot.emoji();
                guiGraphics.blit(entry.location(), cx + 2, cy + 2, EMOJI_PANEL_SIZE, EMOJI_PANEL_SIZE, 0, 0,
                        entry.width(), entry.height(), entry.width(), entry.height());
            } else if (slot.face() != null) {
                drawFace(guiGraphics, cx + 3, cy + 3, EMOJI_CELL - 10, slot.face());
            } else {
                // 颜文字：严格限制在格子里，超出就等比缩小
                drawScaledString(guiGraphics, slot.kaomoji(), cx + 3, cy + 11, EMOJI_CELL - 8, 0xFFFFFFFF);
            }
        }
        guiGraphics.disableScissor();

        // 滚动条
        int maxScroll = emojiMaxScroll(panelWidth);
        if (maxScroll > 0) {
            int trackTop = gridTop;
            int trackHeight = gridHeight;
            int totalPx = emojiRowCount() * EMOJI_CELL;
            int barHeight = Math.max(10, trackHeight * trackHeight / totalPx);
            int barTop = trackTop + (trackHeight - barHeight) * scroll / maxScroll;
            int barX = x + panelWidth - 3;
            guiGraphics.fill(barX, trackTop, barX + 2, trackTop + trackHeight, 0x30FFFFFF);
            guiGraphics.fill(barX, barTop, barX + 2, barTop + barHeight, 0xA0FFFFFF);
        }
    }

    /** 点击到的分组标签下标，没有则 -1。 */
    private int emojiGroupAt(double mouseX, double mouseY) {
        int panelWidth = emojiPanelWidth();
        int tabArea = emojiTabAreaHeight(panelWidth);
        if (tabArea <= 0) {
            return -1;
        }
        int x = emojiPanelX(panelWidth);
        int y = emojiPanelY(emojiPanelHeight());
        if (!isInside(mouseX, mouseY, x, y, panelWidth, tabArea)) {
            return -1;
        }
        List<EmojiGroup> groups = emojiGroups();
        int tx = x + 4;
        int ty = y + 3;
        for (int i = 0; i < groups.size(); i++) {
            EmojiGroup group = groups.get(i);
            int tabWidth = this.font.width(group.name()) + 10;
            if (tx > x + 4 && tx + tabWidth > x + panelWidth - 4) {
                tx = x + 4;
                ty += EMOJI_TAB_HEIGHT + 2;
            }
            if (isInside(mouseX, mouseY, tx, ty, tabWidth, EMOJI_TAB_HEIGHT)) {
                return i;
            }
            tx += tabWidth + 2;
        }
        return -1;
    }

    /** 返回点击到的表情内容，没有则 null。 */
    private String emojiAt(double mouseX, double mouseY) {
        List<EmojiSlot> slots = currentEmojiGroup().slots();
        int panelWidth = emojiPanelWidth();
        int x = emojiPanelX(panelWidth);
        int y = emojiPanelY(emojiPanelHeight());
        int gridTop = emojiGridTop(y, panelWidth);
        int gridHeight = emojiVisibleRows(panelWidth) * EMOJI_CELL;
        if (!isInside(mouseX, mouseY, x, gridTop, panelWidth, gridHeight)) {
            return null;
        }
        int scroll = emojiScrollPx(panelWidth);
        for (int i = 0; i < slots.size(); i++) {
            int cx = x + 4 + (i % EMOJI_COLUMNS) * EMOJI_CELL;
            int cy = gridTop + (i / EMOJI_COLUMNS) * EMOJI_CELL - scroll;
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
        int x = areaX() + 4;
        int y = Math.max(areaY(), toolBarY() - TOOL_GAP - height - 4);

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
        int x = areaX() + 4;
        int y = Math.max(areaY(), toolBarY() - TOOL_GAP - height - 4);
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

    private void addPrimaryButton(int x, int y, int w, int h, Component label, Runnable action) {
        this.primaryButtons.add(new PrimaryButton(x, y, w, h, label, action));
    }

    private void addIconButton(int x, int y, ToolIcon icon, Runnable action) {
        this.iconButtons.add(new IconButton(x, y, 20, TOOL_BAR_HEIGHT, icon, icon.label, action));
    }

    /** 工具栏图标按钮。 */
    private void renderIconButtons(GuiGraphics guiGraphics, int mouseX, int mouseY) {
        for (IconButton button : this.iconButtons) {
            boolean hover = isInside(mouseX, mouseY, button.x(), button.y(), button.w(), button.h());
            if (hover) {
                fillRoundedRect(guiGraphics, button.x(), button.y(), button.w(), button.h(), 4, this.theme.buttonHover());
            }
            int color = hover ? this.theme.accent() : this.theme.textSecondary();
            drawToolIcon(guiGraphics, button.icon(), button.x() + button.w() / 2, button.y() + button.h() / 2,
                    color, hover ? this.theme.buttonHover() : this.theme.panel());
        }
    }

    /** 悬停时在按钮上方显示名称。 */
    private void renderIconTooltips(GuiGraphics guiGraphics, int mouseX, int mouseY) {
        for (IconButton button : this.iconButtons) {
            if (isInside(mouseX, mouseY, button.x(), button.y(), button.w(), button.h())) {
                String label = button.label();
                int width = this.font.width(label) + 10;
                int x = button.x() + button.w() / 2 - width / 2;
                x = Math.max(this.panelX + 4, Math.min(x, this.panelX + this.panelW - width - 4));
                drawTooltipLabel(guiGraphics, label, x, button.y() - 17);
                return;
            }
        }
    }

    /** 蓝色主按钮（发送）。 */
    private void renderPrimaryButtons(GuiGraphics guiGraphics, int mouseX, int mouseY) {
        for (PrimaryButton button : this.primaryButtons) {
            boolean hover = isInside(mouseX, mouseY, button.x(), button.y(), button.w(), button.h());
            fillRoundedRect(guiGraphics, button.x(), button.y(), button.w(), button.h(), 5,
                    hover ? SEND_BUTTON_HOVER : SEND_BUTTON_COLOR);
            int textWidth = this.font.width(button.label());
            guiGraphics.drawString(this.font, button.label(),
                    button.x() + (button.w() - textWidth) / 2,
                    button.y() + (button.h() - 8) / 2, 0xFFFFFFFF);
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
            // 先判断分组标签，再判断表情格子
            int group = emojiGroupAt(vx, vy);
            if (group >= 0) {
                if (group != this.emojiGroupIndex) {
                    this.emojiGroupIndex = group;
                    this.emojiScroll = 0;
                }
                return true;
            }
            String emoji = emojiAt(vx, vy);
            if (emoji != null && this.input != null) {
                this.input.insertText(emoji);
                return true;
            }
            this.emojiOpen = false;
            return true;
        }

        // 聊天区专属按钮只在「消息」栏目下可点
        if (this.activeTab == NavTab.MESSAGES) {
            for (FlatButton flatButton : this.flatButtons) {
                if (isInside(vx, vy, flatButton.x(), flatButton.y(), flatButton.w(), flatButton.h())) {
                    flatButton.action().run();
                    return true;
                }
            }

            for (PrimaryButton primaryButton : this.primaryButtons) {
                if (isInside(vx, vy, primaryButton.x(), primaryButton.y(), primaryButton.w(), primaryButton.h())) {
                    primaryButton.action().run();
                    return true;
                }
            }

            for (IconButton iconButton : this.iconButtons) {
                if (isInside(vx, vy, iconButton.x(), iconButton.y(), iconButton.w(), iconButton.h())) {
                    iconButton.action().run();
                    return true;
                }
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

        // 左侧功能导航栏
        int navItem = navItemAt(vx, vy);
        if (navItem >= 0) {
            NavTab tab = NavTab.values()[navItem];
            if (tab != this.activeTab) {
                this.activeTab = tab;
                this.emojiOpen = false;
                this.atQuery = null;
                this.messageMenuTarget = null;
                this.headMenuPlayer = null;
                if (this.input != null) {
                    if (tab == NavTab.MESSAGES) {
                        this.input.setFocused(true);
                    } else {
                        this.input.setFocused(false);
                        this.setFocused(null);
                    }
                }
            }
            return true;
        }

        // 中间会话列表（只在「消息」栏目下生效）
        if (this.activeTab == NavTab.MESSAGES) {
            int conversationItem = conversationItemAt(vx, vy);
            if (conversationItem >= 0) {
                selectConversation(conversationItem);
                return true;
            }
        }

        ChatMessage messageAt = this.activeTab == NavTab.MESSAGES ? messageAt(vx, vy) : null;
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

        if (clickIntoTextBoxes(vx, vy, button)) {
            return true;
        }

        return super.mouseClicked(vx, vy, button);
    }

    /** 点到底板区域（比 EditBox 控件本身大）时也把焦点交给对应输入框。 */
    private boolean clickIntoTextBoxes(double mouseX, double mouseY, int button) {
        if (this.searchBox != null
                && isInside(mouseX, mouseY, listX() + MARGIN, searchBoxTop(),
                        listWidth() - MARGIN * 2, SEARCH_BOX_HEIGHT)) {
            this.setFocused(this.searchBox);
            if (this.input != null) {
                this.input.setFocused(false);
            }
            super.mouseClicked(mouseX, mouseY, button);   // 让控件自己定位光标
            return true;
        }
        if (this.input != null && !this.collapsed && this.activeTab == NavTab.MESSAGES
                && isInside(mouseX, mouseY, inputBoxX(), inputBoxTop(), inputBoxW(), INPUT_BAR_HEIGHT)) {
            this.setFocused(this.input);
            if (this.searchBox != null) {
                this.searchBox.setFocused(false);
            }
            super.mouseClicked(mouseX, mouseY, button);
            return true;
        }
        return false;
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
        int cursor = areaY + areaH;
        for (int i = messages.size() - 1; i >= 0; i--) {
            ChatMessage message = messages.get(i);
            int height = messageHeight(message, areaW);
            boolean showTime = i == 0 || message.timestamp() - messages.get(i - 1).timestamp() > 5 * 60_000L;
            int slotHeight = height + (showTime ? TIME_SEPARATOR_HEIGHT : 0);
            cursor -= slotHeight;
            int drawY = cursor + (int) this.scroll;
            int messageY = drawY + (showTime ? TIME_SEPARATOR_HEIGHT : 0);
            if (mouseY >= messageY && mouseY < messageY + height) {
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
        int cursor = areaY + areaH;
        for (int i = messages.size() - 1; i >= 0; i--) {
            ChatMessage m = messages.get(i);
            int height = messageHeight(m, areaW);
            boolean showTime = i == 0 || m.timestamp() - messages.get(i - 1).timestamp() > 5 * 60_000L;
            int slotHeight = height + (showTime ? TIME_SEPARATOR_HEIGHT : 0);
            cursor -= slotHeight;
            int drawY = cursor + (int) this.scroll;
            int messageY = drawY + (showTime ? TIME_SEPARATOR_HEIGHT : 0);
            if (m == message) {
                int headX = m.self() ? areaX + areaW - AVATAR - 2 : areaX + 2;
                int headY = messageY + LINE + NAME_GAP;
                return isInside(mouseX, mouseY, headX, headY, AVATAR, AVATAR);
            }
            if (drawY + slotHeight < areaY) {
                break;
            }
        }
        return false;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        // 表情面板打开且鼠标在面板上：滚轮滚表情，不要滚聊天记录
        if (this.emojiOpen && !this.collapsed && this.activeTab == NavTab.MESSAGES
                && isInside(mouseX, mouseY, this.panelX, this.panelY, this.panelW, this.panelH)) {
            int panelWidth = emojiPanelWidth();
            int maxScroll = emojiMaxScroll(panelWidth);
            this.emojiScroll = (int) Math.max(0, Math.min(maxScroll, this.emojiScroll - scrollY * EMOJI_CELL));
            return true;
        }
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
        // 非「消息」栏目 / 聊天区已收起：没有输入框
        if (this.input == null || this.activeTab != NavTab.MESSAGES) {
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