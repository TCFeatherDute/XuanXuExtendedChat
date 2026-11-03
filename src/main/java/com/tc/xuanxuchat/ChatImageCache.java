package com.tc.xuanxuchat;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;
import org.slf4j.Logger;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 聊天图片 / GIF 的下载与缓存。
 * <p>
 * 支持 png / jpg / gif / webp（webp 需 JDK 支持）。
 * 图片在后台线程下载并解码，主线程创建纹理；GIF 逐帧切换实现动画。
 * 下载失败不会影响游戏，只会在气泡里显示加载失败。
 */
public final class ChatImageCache {
    /** 单个图片最大字节数。 */
    public static final int MAX_BYTES = 4 * 1024 * 1024;
    /** 缩略图最大宽高（像素）。 */
    public static final int MAX_WIDTH = 220;
    public static final int MAX_HEIGHT = 220;
    /** GIF 最多解析帧数，避免超大动图拖垮内存。 */
    private static final int MAX_FRAMES = 64;

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final ExecutorService EXECUTOR = Executors.newFixedThreadPool(2);
    private static final Map<String, Entry> CACHE = new ConcurrentHashMap<>();

    private ChatImageCache() {
    }

    public enum State {
        LOADING, READY, FAILED
    }

    public static final class Entry {
        public State state = State.LOADING;
        public List<NativeImage> frames = List.of();
        public DynamicTexture texture;
        public ResourceLocation location;
        public boolean animated;
        public long startTime;
        public int currentFrame = -1;
    }

    /**
     * 获取（必要时触发下载）图片条目。
     */
    public static Entry get(String url) {
        Entry entry = CACHE.get(url);
        if (entry == null) {
            entry = new Entry();
            CACHE.put(url, entry);
            request(url, entry);
        }
        return entry;
    }

    private static void request(String url, Entry entry) {
        EXECUTOR.submit(() -> {
            try {
                byte[] data = download(url);
                List<NativeImage> frames = decode(data);
                if (frames.isEmpty()) {
                    entry.state = State.FAILED;
                    return;
                }
                Minecraft.getInstance().execute(() -> {
                    try {
                        DynamicTexture texture = new DynamicTexture(frames.get(0));
                        ResourceLocation location = Minecraft.getInstance().getTextureManager()
                                .register("xuanxuchat_img_" + Math.abs(url.hashCode()), texture);
                        entry.frames = frames;
                        entry.texture = texture;
                        entry.location = location;
                        entry.animated = frames.size() > 1;
                        entry.startTime = System.currentTimeMillis();
                        entry.state = State.READY;
                    } catch (Exception e) {
                        LOGGER.error("Failed to register chat image texture", e);
                        entry.state = State.FAILED;
                    }
                });
            } catch (Exception e) {
                LOGGER.debug("Failed to load chat image: {}", url, e);
                entry.state = State.FAILED;
            }
        });
    }

    private static byte[] download(String urlText) throws Exception {
        URL url = new URL(urlText);
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setConnectTimeout(8000);
        connection.setReadTimeout(8000);
        connection.setRequestProperty("User-Agent", "XuanXuChat/0.1");
        try (InputStream in = connection.getInputStream()) {
            byte[] data = in.readAllBytes();
            if (data.length > MAX_BYTES) {
                throw new IllegalStateException("image too large: " + data.length);
            }
            return data;
        } finally {
            connection.disconnect();
        }
    }

    private static List<NativeImage> decode(byte[] data) throws Exception {
        List<NativeImage> frames = new ArrayList<>();
        try (ImageInputStream inputStream = ImageIO.createImageInputStream(new ByteArrayInputStream(data))) {
            ImageReader reader = ImageIO.getImageReaders(inputStream).next();
            reader.setInput(inputStream);
            int count;
            try {
                count = reader.getNumImages(true);
            } catch (Exception e) {
                count = 1;
            }
            for (int i = 0; i < count && frames.size() < MAX_FRAMES; i++) {
                BufferedImage frame = reader.read(i);
                if (frame != null) {
                    frames.add(toNativeImage(frame));
                }
            }
            reader.dispose();
        }
        return frames;
    }

    /**
     * BufferedImage -> NativeImage（ARGB 与 MC 内部 RGBA 字节序互转）。
     */
    private static NativeImage toNativeImage(BufferedImage image) {
        int width = image.getWidth();
        int height = image.getHeight();

        if (width > MAX_WIDTH || height > MAX_HEIGHT) {
            double scale = Math.min((double) MAX_WIDTH / width, (double) MAX_HEIGHT / height);
            int newW = Math.max(1, (int) (width * scale));
            int newH = Math.max(1, (int) (height * scale));
            BufferedImage scaled = new BufferedImage(newW, newH, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = scaled.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.drawImage(image, 0, 0, newW, newH, null);
            g.dispose();
            image = scaled;
            width = newW;
            height = newH;
        }

        NativeImage nativeImage = new NativeImage(width, height, false);
        int[] pixels = image.getRGB(0, 0, width, height, null, 0, width);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int argb = pixels[y * width + x];
                // BufferedImage 是 ARGB，NativeImage 内部按 RGBA 字节序存放
                int abgr = (argb & 0xFF00FF00)
                        | ((argb & 0x00FF0000) >> 16)
                        | ((argb & 0x000000FF) << 16);
                nativeImage.setPixelRGBA(x, y, abgr);
            }
        }
        return nativeImage;
    }

    /**
     * GIF 动画推进：渲染前调用，返回当前应显示的帧索引。
     */
    public static void animate(Entry entry, long frameMs) {
        if (!entry.animated || entry.texture == null || entry.frames.size() <= 1) {
            return;
        }
        int frame = (int) ((System.currentTimeMillis() - entry.startTime) / frameMs) % entry.frames.size();
        if (frame != entry.currentFrame) {
            entry.currentFrame = frame;
            entry.texture.setPixels(entry.frames.get(frame));
            entry.texture.upload();
        }
    }
}