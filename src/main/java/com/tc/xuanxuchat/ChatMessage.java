package com.tc.xuanxuchat;

import net.minecraft.network.chat.Component;

import java.util.UUID;

/**
 * 一条聊天消息（客户端展示与持久化用）。
 *
 * @param senderId   发送者 UUID；系统消息或无法识别时为 null
 * @param senderName 发送者显示名；系统消息为空串
 * @param content    原始消息组件（保留颜色 / 点击事件）
 * @param plainText  纯文本内容（用于搜索与写入历史）
 * @param timestamp  接收时间（毫秒）
 * @param self       是否为本地玩家自己发送的消息
 */
public record ChatMessage(
        UUID senderId,
        String senderName,
        Component content,
        String plainText,
        long timestamp,
        boolean self
) {
    /**
     * 该消息所属会话的键：公共频道或某个玩家的私聊。
     */
    public String conversationKey() {
        return self ? ChatHub.PUBLIC : (senderName.isEmpty() ? ChatHub.PUBLIC : senderName);
    }
}