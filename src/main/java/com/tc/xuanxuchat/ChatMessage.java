package com.tc.xuanxuchat;

import net.minecraft.network.chat.Component;

import java.util.UUID;

/**
 * 一条聊天消息。
 *
 * @param senderId     发送者 UUID；系统消息或无法识别时为 null
 * @param senderName   发送者显示名；系统消息为空串
 * @param conversation 会话键：{@link ChatHub#PUBLIC} 或某个玩家的名字（私聊）
 * @param content      原始消息组件（保留颜色 / 点击事件）
 * @param plainText    纯文本内容（用于搜索、持久化、重复合并比较）
 * @param timestamp    接收时间（毫秒）
 * @param self         是否为本地玩家自己发送的消息
 * @param system       是否为系统消息（无头像 / 无发送者）
 * @param repeatCount  连续重复次数（防刷屏合并，最小 1）
 */
public record ChatMessage(
        UUID senderId,
        String senderName,
        String conversation,
        Component content,
        String plainText,
        long timestamp,
        boolean self,
        boolean system,
        int repeatCount
) {

    public static ChatMessage player(String senderName, String conversation, Component content,
                                     String plainText, boolean self) {
        return new ChatMessage(null, senderName, conversation, content, plainText,
                System.currentTimeMillis(), self, false, 1);
    }

    public static ChatMessage system(String plainText) {
        return new ChatMessage(null, "", ChatHub.PUBLIC, Component.literal(plainText), plainText,
                System.currentTimeMillis(), false, true, 1);
    }

    /** 复制一份并设置重复次数。 */
    public ChatMessage withRepeat(int count) {
        return new ChatMessage(senderId, senderName, conversation, content, plainText,
                timestamp, self, system, count);
    }
}