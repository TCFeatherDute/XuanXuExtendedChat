package com.tc.xuanxuchat;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.fml.loading.FMLPaths;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * 自定义聊天表情加载器。
 * <p>
 * 用户可以把 PNG 表情图片放到 {@code 游戏目录/xuanxuchat/emojis/} 下，
 * 每个文件就是一个表情（文件名即表情 ID，如 {@code smile.png} → 发送 {@code :smile:}）。
 * 加载后会在表情面板里显示，发送时以 {@code :name:} 文本形式传递，
 * 聊天界面里会自动渲染成图片。
 */
public final class ChatEmojiLoader {
    private static final Logger LOGGER = LogUtils.getLogger();

    public record EmojiEntry(String name, ResourceLocation location, int width, int height) {
    }

    private static final List<EmojiEntry> EMOJIS = new ArrayList<>();
    private static boolean loaded;

    private ChatEmojiLoader() {
    }

    public static Path emojiDir() {
        return FMLPaths.GAMEDIR.get().resolve("xuanxuchat").resolve("emojis");
    }

    public static List<EmojiEntry> emojis() {
        if (!loaded) {
            load();
        }
        return EMOJIS;
    }

    public static EmojiEntry find(String name) {
        if (name == null) {
            return null;
        }
        for (EmojiEntry entry : emojis()) {
            if (entry.name().equalsIgnoreCase(name)) {
                return entry;
            }
        }
        return null;
    }

    private static void load() {
        loaded = true;
        Path dir = emojiDir();
        if (!Files.isDirectory(dir)) {
            return;
        }
        try (Stream<Path> paths = Files.list(dir)) {
            paths.filter(path -> path.getFileName().toString().toLowerCase().endsWith(".png"))
                    .sorted()
                    .forEach(ChatEmojiLoader::loadFile);
            LOGGER.info("Loaded {} custom chat emojis from {}", EMOJIS.size(), dir);
        } catch (IOException e) {
            LOGGER.error("Failed to list chat emoji dir: {}", dir, e);
        }
    }

    private static void loadFile(Path path) {
        try (InputStream in = Files.newInputStream(path)) {
            NativeImage image = NativeImage.read(in);
            int width = image.getWidth();
            int height = image.getHeight();
            DynamicTexture texture = new DynamicTexture(image);
            String fileName = path.getFileName().toString();
            String name = fileName.substring(0, fileName.length() - 4);
            ResourceLocation location = Minecraft.getInstance().getTextureManager()
                    .register("xuanxuchat_emoji_" + name.toLowerCase(), texture);
            EMOJIS.add(new EmojiEntry(name, location, width, height));
        } catch (Exception e) {
            LOGGER.error("Failed to load chat emoji: {}", path, e);
        }
    }
}