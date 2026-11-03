package com.tc.xuanxuchat;

/**
 * 聊天界面主题配色。
 * <p>
 * 所有颜色均为 ARGB，可直接用于 GuiGraphics#fill / drawString。
 */
public record ChatTheme(
        boolean dark,
        int background,
        int panel,
        int panelSelected,
        int bubbleOthers,
        int bubbleSelf,
        int textPrimary,
        int textSecondary,
        int accent
) {
    public static final ChatTheme DARK = new ChatTheme(
            true,
            0xF0121218,   // 整体背景
            0xFF1A1A24,   // 侧边栏背景
            0xFF2E2E42,   // 选中的会话条目
            0xFF2A2A36,   // 他人消息气泡
            0xFF3A64C8,   // 自己消息气泡
            0xFFFFFFFF,   // 主要文字
            0xFF9A9AB0,   // 次要文字
            0xFF55AAFF    // 强调色（名字 / 选中高亮）
    );

    public static final ChatTheme LIGHT = new ChatTheme(
            false,
            0xF0F5F5F8,
            0xFFFFFFFF,
            0xFFDCE4F5,
            0xFFE8E8F0,
            0xFFA8C8FF,
            0xFF1F1F26,
            0xFF6E6E80,
            0xFF3A64C8
    );

    public ChatTheme toggle() {
        return dark ? LIGHT : DARK;
    }
}