package com.tc.xuanxuchat.client;

import com.tc.xuanxuchat.ChatHub;
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

import java.util.ArrayList;
import java.util.List;

/**
 * 玄墟聊天主界面：聊天 App 风格。
 * <p>
 * 左侧会话列表（公共频道 + 在线玩家），右侧消息气泡流，底部输入框。
 * 本界面会替代原版聊天输入界面（由 {@link ChatEvents} 拦截）。
 */
public class XuanXuChatScreen extends Screen {
    private static final int SIDEBAR_WIDTH = 132;
    private static final int MARGIN = 8;
    private static final int BAR_HEIGHT = 20;
    private static final int AVATAR = 16;
    private static final int LINE = 9;
    private static final int BUBBLE_PAD = 6;

    /** 表情 / 颜文字面板内容。 */
    private static final List<String> EMOJIS = List.of(
            "😀", "😂", "😍", "😎", "😭", "😡", "👍", "👎", "❤️", "🔥",
            "✨", "🎉", "😴", "🤔", "😅", "🙏", "💪", "🍀", "🌸", "⭐",
            "(＾▽＾)", "(￣▽￣)", "(≧∇≦)", "(´・ω・`)", "(¬‿¬)", "(๑•̀ㅂ•́)و✧"
    );

    private final String initialText;
    private ChatTheme theme = ChatTheme.DARK;

    private EditBox input;

    /** 当前会话：{@link ChatHub#PUBLIC} 或某个玩家名。 */
    private String conversation = ChatHub.PUBLIC;

    /** 消息区向上滚动的像素。 */
    private double scroll;

    private boolean emojiOpen;
    private String headMenuPlayer;
    private double headMenuX;
    private double headMenuY;

    private List<PlayerInfo> cachedPlayers = List.of();

    public XuanXuChatScreen(String initialText) {
        super(Component.literal("玄墟聊天"));
        this.initialText = initialText == null ? "" : initialText;
    }

    // ==================== 初始化 ====================

    @Override
    protected void init() {
        ChatHub.ensureSessionLoaded();
        this.cachedPlayers = collectOnlinePlayers();

        int inputY = this.height - MARGIN - BAR_HEIGHT;
        int inputX = SIDEBAR_WIDTH + MARGIN;
        int inputWidth = this.width - SIDEBAR_WIDTH - MARGIN * 2 - 40;

        this.input = new EditBox(this.font, inputX, inputY, inputWidth, BAR_HEIGHT, Component.literal("输入消息..."));
        this.input.setValue(this.initialText);
        this.input.setMaxLength(256);
        this.input.setFocused(true);
        addRenderableWidget(this.input);
        setInitialFocus(this.input);

        addRenderableWidget(Button.builder(Component.literal("☺"), b -> this.emojiOpen = !this.emojiOpen)
                .bounds(this.width - MARGIN - 36, inputY, 36, BAR_HEIGHT)
                .build());

        // 主题切换（深色 / 浅色）
        addRenderableWidget(Button.builder(
                        Component.literal(this.theme.dark() ? "浅色主题" : "深色主题"),
                        b -> {
                            // 重建控件前先保存草稿，避免切换主题丢失已输入内容
                            String draft = this.input != null ? this.input.getValue() : "";
                            this.theme = this.theme.toggle();
                            this.rebuildWidgets();
                            if (this.input != null) {
                                this.input.setValue(draft);
                                this.input.setFocused(true);
                            }
                        })
                .bounds(MARGIN, this.height - MARGIN - BAR_HEIGHT, 80, BAR_HEIGHT)
                .build());
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
        // 整体背景
        guiGraphics.fill(0, 0, this.width, this.height, this.theme.background());

        renderSidebar(guiGraphics, mouseX, mouseY);
        renderMessages(guiGraphics, mouseX, mouseY);
        renderInputBar(guiGraphics);

        // 输入框 / 表情按钮等组件
        for (var renderable : this.renderables) {
            renderable.render(guiGraphics, mouseX, mouseY, partialTick);
        }

        if (this.emojiOpen) {
            renderEmojiPanel(guiGraphics, mouseX, mouseY);
        }
        if (this.headMenuPlayer != null) {
            renderHeadMenu(guiGraphics, mouseX, mouseY);
        }
    }

    private void renderSidebar(GuiGraphics guiGraphics, int mouseX, int mouseY) {
        guiGraphics.fill(0, 0, SIDEBAR_WIDTH, this.height, this.theme.panel());
        guiGraphics.drawString(this.font, "会话", MARGIN, MARGIN, this.theme.accent());

        int y = MARGIN + 16;

        // 公共频道
        boolean publicSelected = ChatHub.PUBLIC.equals(this.conversation);
        if (publicSelected) {
            guiGraphics.fill(3, y, SIDEBAR_WIDTH - 3, y + 18, this.theme.panelSelected());
        }
        guiGraphics.drawString(this.font, "公共频道", MARGIN + 2, y + 5,
                publicSelected ? this.theme.accent() : this.theme.textPrimary());
        y += 22;

        // 在线玩家（点击进入私聊）
        for (PlayerInfo info : this.cachedPlayers) {
            String name = info.getProfile().getName();
            boolean selected = name.equals(this.conversation);
            if (selected) {
                guiGraphics.fill(3, y, SIDEBAR_WIDTH - 3, y + 18, this.theme.panelSelected());
            }
            drawPlayerHead(guiGraphics, name, MARGIN + 2, y + 1, 14);
            guiGraphics.drawString(this.font, name, MARGIN + 20, y + 5,
                    selected ? this.theme.accent() : this.theme.textPrimary());
            y += 20;
        }
    }

    private void renderMessages(GuiGraphics guiGraphics, int mouseX, int mouseY) {
        int areaX = SIDEBAR_WIDTH + MARGIN;
        int areaY = MARGIN;
        int areaW = this.width - SIDEBAR_WIDTH - MARGIN * 2;
        int areaH = this.height - MARGIN * 2 - BAR_HEIGHT - 8;

        guiGraphics.fill(areaX, areaY, areaX + areaW, areaY + areaH, 0x30000000);
        guiGraphics.enableScissor(areaX, areaY, areaX + areaW, areaY + areaH);

        List<ChatMessage> messages = ChatHub.messages(this.conversation);
        if (messages.isEmpty()) {
            String hint = ChatHub.PUBLIC.equals(this.conversation)
                    ? "暂无消息，按 Enter 发送第一条消息"
                    : "与 " + this.conversation + " 的私聊尚无记录";
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
                int drawY = cursor + (int) this.scroll;
                if (drawY + height < areaY) {
                    break;
                }
                if (drawY <= areaY + areaH) {
                    drawMessage(guiGraphics, message, areaX, drawY, areaW);
                }
            }
        }

        guiGraphics.disableScissor();
    }

    private int messageHeight(ChatMessage message, int areaW) {
        int maxWidth = Math.max(40, areaW - AVATAR - 40);
        int lines = this.font.split(message.content(), maxWidth).size();
        int bubbleHeight = lines * LINE + BUBBLE_PAD * 2;
        // 名字行 + 气泡
        return LINE + 2 + bubbleHeight + 6;
    }

    private void drawMessage(GuiGraphics guiGraphics, ChatMessage message, int areaX, int areaY, int areaW) {
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
            // 自己的消息：靠右
            int bubbleX = areaX + areaW - AVATAR - MARGIN - bubbleWidth;
            guiGraphics.drawString(this.font, message.senderName(),
                    bubbleX, nameY, this.theme.accent());
            int bubbleY = nameY + LINE + 2;
            guiGraphics.fill(bubbleX, bubbleY, bubbleX + bubbleWidth, bubbleY + bubbleHeight, this.theme.bubbleSelf());
            drawTextLines(guiGraphics, lines, bubbleX + BUBBLE_PAD, bubbleY + BUBBLE_PAD, 0xFFFFFFFF);
            drawPlayerHead(guiGraphics, message.senderName(),
                    areaX + areaW - AVATAR - 2, bubbleY, AVATAR);
        } else {
            // 他人的消息：靠左
            drawPlayerHead(guiGraphics, message.senderName(), areaX + 2, areaY + 2, AVATAR);
            int bubbleX = areaX + AVATAR + 8;
            if (!message.senderName().isEmpty()) {
                guiGraphics.drawString(this.font, message.senderName(), bubbleX, nameY, this.theme.accent());
            }
            int bubbleY = nameY + LINE + 2;
            guiGraphics.fill(bubbleX, bubbleY, bubbleX + bubbleWidth, bubbleY + bubbleHeight, this.theme.bubbleOthers());
            drawTextLines(guiGraphics, lines, bubbleX + BUBBLE_PAD, bubbleY + BUBBLE_PAD, this.theme.textPrimary());
        }
    }

    private void drawTextLines(GuiGraphics guiGraphics, List<FormattedCharSequence> lines, int x, int y, int color) {
        int ty = y;
        for (FormattedCharSequence line : lines) {
            guiGraphics.drawString(this.font, line, x, ty, color);
            ty += LINE;
        }
    }

    private void renderInputBar(GuiGraphics guiGraphics) {
        int y = this.height - MARGIN - BAR_HEIGHT;
        guiGraphics.fill(SIDEBAR_WIDTH, y - 4, this.width, this.height, this.theme.panel());
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

    // ==================== 表情面板 ====================

    private void renderEmojiPanel(GuiGraphics guiGraphics, int mouseX, int mouseY) {
        int panelW = 224;
        int panelH = 96;
        int x = this.width - panelW - MARGIN;
        int y = this.height - MARGIN - BAR_HEIGHT - panelH - 4;
        guiGraphics.fill(x, y, x + panelW, y + panelH, 0xF0202028);

        int columns = 6;
        int cell = 34;
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

    /** 返回鼠标所在的表情，没有则 null。 */
    private String emojiAt(double mouseX, double mouseY) {
        int panelW = 224;
        int panelH = 96;
        int x = this.width - panelW - MARGIN;
        int y = this.height - MARGIN - BAR_HEIGHT - panelH - 4;
        int columns = 6;
        int cell = 34;
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

    /** 返回鼠标所在的菜单项，没有则 -1。 */
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

    // ==================== 交互 ====================

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        // 表情面板优先
        if (this.emojiOpen) {
            String emoji = emojiAt(mouseX, mouseY);
            if (emoji != null) {
                this.input.insertText(emoji);
                return true;
            }
            this.emojiOpen = false;
            return true;
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
            if (sidebarItem == 0) {
                this.conversation = ChatHub.PUBLIC;
            } else if (sidebarItem - 1 < this.cachedPlayers.size()) {
                this.conversation = this.cachedPlayers.get(sidebarItem - 1).getProfile().getName();
            }
            this.scroll = 0;
            return true;
        }

        // 消息区头像点击：左键私聊 / 右键菜单
        String headOwner = messageHeadAt(mouseX, mouseY);
        if (headOwner != null) {
            if (button == 1) {
                this.headMenuPlayer = headOwner;
                this.headMenuX = mouseX;
                this.headMenuY = mouseY;
            } else if (!ChatHub.PUBLIC.equals(headOwner)) {
                this.conversation = headOwner;
                this.scroll = 0;
            }
            return true;
        }

        return super.mouseClicked(mouseX, mouseY, button);
    }

    /** 返回侧边栏第几项（0=公共频道，1..=在线玩家），-1 表示没有。 */
    private int sidebarItemAt(double mouseX, double mouseY) {
        if (mouseX < 0 || mouseX > SIDEBAR_WIDTH) {
            return -1;
        }
        int y = MARGIN + 16;
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
        return -1;
    }

    /**
     * 检测鼠标是否落在某条消息的头像上，返回该消息的发送者名。
     */
    private String messageHeadAt(double mouseX, double mouseY) {
        int areaX = SIDEBAR_WIDTH + MARGIN;
        int areaY = MARGIN;
        int areaW = this.width - SIDEBAR_WIDTH - MARGIN * 2;
        int areaH = this.height - MARGIN * 2 - BAR_HEIGHT - 8;
        if (mouseX < areaX || mouseX > areaX + areaW || mouseY < areaY || mouseY > areaY + areaH) {
            return null;
        }
        if (this.scroll != 0) {
            // 滚动时不处理头像点击，避免坐标计算复杂
            return null;
        }

        List<ChatMessage> messages = ChatHub.messages(this.conversation);
        int bottom = areaY + areaH;
        int cursor = bottom;
        for (int i = messages.size() - 1; i >= 0; i--) {
            ChatMessage message = messages.get(i);
            int height = messageHeight(message, areaW);
            cursor -= height;
            if (cursor + height < areaY) {
                break;
            }
            if (mouseY >= cursor && mouseY < cursor + height) {
                int headX = message.self() ? areaX + areaW - AVATAR - 2 : areaX + 2;
                int headY = message.self() ? cursor + LINE + 2 : cursor + 2;
                if (mouseX >= headX && mouseX < headX + AVATAR && mouseY >= headY && mouseY < headY + AVATAR) {
                    return message.senderName().isEmpty() ? null : message.senderName();
                }
                return null;
            }
        }
        return null;
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
        for (ChatMessage message : ChatHub.messages(this.conversation)) {
            total += messageHeight(message, areaW);
        }
        return total;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
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
        Minecraft mc = Minecraft.getInstance();
        if (mc.getConnection() == null) {
            return;
        }
        ChatHub.receiveSelf(text);
        if (ChatHub.PUBLIC.equals(this.conversation)) {
            mc.getConnection().sendChat(text);
        } else {
            mc.getConnection().sendCommand("msg " + this.conversation + " " + text);
        }
        this.input.setValue("");
        this.scroll = 0;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}