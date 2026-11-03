package com.tc.xuanxuchat;

import java.util.ArrayList;
import java.util.List;

/**
 * 右键头像动作的注册表。
 * <p>
 * 其他系统（如修仙属性面板）可通过 {@link #register} 注入自己的菜单项。
 */
public final class PlayerHeadActions {
    private static final List<PlayerHeadAction> REGISTRY = new ArrayList<>();

    private PlayerHeadActions() {
    }

    public static void register(PlayerHeadAction action) {
        REGISTRY.add(action);
    }

    /** 全部已注册动作（只读）。 */
    public static List<PlayerHeadAction> all() {
        return List.copyOf(REGISTRY);
    }

    /** 对指定玩家可见的动作。 */
    public static List<PlayerHeadAction> visibleFor(String playerName) {
        List<PlayerHeadAction> list = new ArrayList<>();
        for (PlayerHeadAction action : REGISTRY) {
            if (action.isVisible(playerName)) {
                list.add(action);
            }
        }
        return list;
    }
}