package com.tc.xuanxuchat.client;

import com.tc.xuanxuchat.ChatHistoryStore;
import com.tc.xuanxuchat.ChatHub;
import com.tc.xuanxuchat.PlayerHeadAction;
import com.tc.xuanxuchat.PlayerHeadActions;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ChatScreen;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.ClientChatReceivedEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;

/**
 * 聊天系统事件监听。
 * <ul>
 *   <li>把原版聊天输入界面替换为 {@link XuanXuChatScreen}（聊天 App 界面）</li>
 *   <li>收集收到的聊天消息并按会话记录</li>
 *   <li>定期把聊天历史写盘</li>
 * </ul>
 */
public final class ChatEvents {
    private ChatEvents() {
    }

    /**
     * 注册默认的右键头像菜单项。
     * 其他系统可通过 {@link PlayerHeadActions#register} 追加自己的菜单项。
     */
    public static void registerDefaultActions() {
        PlayerHeadActions.register(new PlayerHeadAction() {
            @Override
            public String label() {
                return "复制玩家名";
            }

            @Override
            public boolean isVisible(String playerName) {
                return true;
            }

            @Override
            public void execute(String playerName) {
                Minecraft.getInstance().keyboardHandler.setClipboard(playerName);
            }
        });
    }

    @SubscribeEvent
    public static void onChatReceived(ClientChatReceivedEvent event) {
        ChatHub.receive(event.getMessage());
    }

    /**
     * 拦截原版聊天界面，替换为玄墟聊天界面。
     * <p>
     * 原版按 T 打开时输入框为空，因此无需读取 {@code ChatScreen} 的 protected input 字段。
     */
    @SubscribeEvent
    public static void onScreenOpening(ScreenEvent.Opening event) {
        if (event.getNewScreen() instanceof ChatScreen) {
            event.setNewScreen(new XuanXuChatScreen(""));
        }
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        ChatHub.ensureSessionLoaded();
        ChatHistoryStore.flushIfDirty();
    }
}