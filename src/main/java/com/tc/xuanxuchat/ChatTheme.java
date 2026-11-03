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
        int bubbleSelfText,
        int textPrimary,
        int textSecondary,
        int accent,
        int buttonBg,
        int buttonHover,
        int buttonBorder,
        int inputBg
) {
    public static final ChatTheme DARK = new ChatTheme(
            true,
            0xF0121218,   // 整体背景
            0xFF1A1A24,   // 侧边栏背景
            0xFF2E2E42,   // 选中的会话条目
            0xFF2A2A36,   // 他人消息气泡
            0xFF3A64C8,   // 自己消息气泡
            0xFFFFFFFF,   // 自己消息文字（深色主题白字）
            0xFFFFFFFF,   // 主要文字
            0xFF9A9AB0,   // 次要文字
            0xFF55AAFF,   // 强调色（名字 / 选中高亮）
            0xFF2B2B3A,   // 按钮底色
            0xFF3B3B52,   // 按钮悬停
            0xFF454560,   // 按钮描边
            0xFF22222E    // 输入框底色
    );

    public static final ChatTheme LIGHT = new ChatTheme(
            false,
            0xFFF2F3F7,   // 整体背景
            0xFFFFFFFF,   // 侧边栏背景
            0xFFD8E0F2,   // 选中的会话条目
            0xFFEDEFF5,   // 他人消息气泡
            0xFFC8D8F8,   // 自己消息气泡
            0xFF14203A,   // 自己消息文字（浅色主题深字，保证清晰）
            0xFF1A1A22,   // 主要文字
            0xFF5A5A6E,   // 次要文字
            0xFF2F5FC4,   // 强调色
            0xFFE8EAF2,   // 按钮底色
            0xFFD8DCEA,   // 按钮悬停
            0xFFC8CEDF,   // 按钮描边
            0xFF2B2B3A    // 输入框底色（深色，保证 EditBox 默认白字可见）
    );

    public ChatTheme toggle() {
        return dark ? LIGHT : DARK;
    }
}