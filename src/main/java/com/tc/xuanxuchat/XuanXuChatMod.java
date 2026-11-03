package com.tc.xuanxuchat;

import com.mojang.logging.LogUtils;
import com.tc.xuanxuchat.client.ChatEvents;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.fml.loading.FMLLoader;
import net.neoforged.neoforge.common.NeoForge;
import org.slf4j.Logger;

/**
 * 玄墟聊天（XuanXu Chat）主类。
 * <p>
 * 以聊天 App 风格替代原版聊天界面，并提供右键头像扩展接口。
 */
@Mod(XuanXuChatMod.MOD_ID)
public final class XuanXuChatMod {
    public static final String MOD_ID = "xuanxuchat";
    public static final String MOD_NAME = "XuanXu Chat";

    private static final Logger LOGGER = LogUtils.getLogger();

    public XuanXuChatMod(IEventBus modEventBus) {
        modEventBus.addListener(this::commonSetup);

        // 聊天功能全部在客户端生效
        if (FMLLoader.getDist().isClient()) {
            NeoForge.EVENT_BUS.register(ChatEvents.class);
            ChatEvents.registerDefaultActions();
        }
    }

    private void commonSetup(final FMLCommonSetupEvent event) {
        LOGGER.info("{} 初始化完成！", MOD_NAME);
    }
}