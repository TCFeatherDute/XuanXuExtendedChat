package com.tc.xuanxuchat;

/**
 * 右键玩家头像时弹出的动作菜单项。
 * <p>
 * 这是一个预留扩展接口：其他系统（例如玄墟的修仙属性面板）实现本接口后，
 * 注册到 {@link PlayerHeadActions} 即可出现在右键菜单中。
 */
public interface PlayerHeadAction {

    /** 菜单项显示文字。 */
    String label();

    /** 是否对指定玩家显示该菜单项。 */
    boolean isVisible(String playerName);

    /** 点击菜单项后执行。 */
    void execute(String playerName);
}