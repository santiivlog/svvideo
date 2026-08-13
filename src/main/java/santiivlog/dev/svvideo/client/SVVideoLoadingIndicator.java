package santiivlog.dev.svvideo.client;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.resource.Resource;
import net.minecraft.util.Identifier;
import org.w3c.dom.NodeList;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.metadata.IIOMetadataNode;
import javax.imageio.stream.ImageInputStream;
import java.awt.AlphaComposite;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

public final class SVVideoLoadingIndicator {

    private static final Identifier GIF_RESOURCE = Identifier.of("svvideo", "gif/loading.gif");

    private record GifFrame(int glId, NativeImageBackedTexture texture, int delayMs) {}
    private record RawFrame(BufferedImage image, int delayMs) {}

    private static volatile List<GifFrame> frames = null;
    private static volatile boolean loaded = false;
    private static volatile boolean failed = false;
    private static final AtomicBoolean decoding = new AtomicBoolean(false);

    private static volatile int currentFrame = 0;
    private static volatile long lastFrameTime = 0;
    private static int gifWidth = 0;
    private static int gifHeight = 0;
    private static int backgroundColor = 0xFF343434;

    private SVVideoLoadingIndicator() {}

    public static void tick() {
        if (loaded) {
            advanceFrame();
            return;
        }
        if (failed) {
            return;
        }
        if (decoding.compareAndSet(false, true)) {
            startDecode();
        }
    }

    private static void startDecode() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null) {
            failed = true;
            decoding.set(false);
            return;
        }

        net.minecraft.resource.ResourceManager rm = client.getResourceManager();
        CompletableFuture.runAsync(() -> {
            try {
                Optional<Resource> opt = rm.getResource(GIF_RESOURCE);
                if (opt.isEmpty()) {
                    System.err.println("[SVVideo] GIF de carga no encontrado en recursos: " + GIF_RESOURCE);
                    failed = true;
                    decoding.set(false);
                    return;
                }

                List<RawFrame> rawFrames;
                try (InputStream is = opt.get().getInputStream()) {
                    rawFrames = decodeGif(is);
                }

                if (rawFrames.isEmpty()) {
                    System.err.println("[SVVideo] GIF de carga no tiene frames válidos");
                    failed = true;
                    decoding.set(false);
                    return;
                }

                final List<RawFrame> toUpload = rawFrames;
                RenderSystem.recordRenderCall(() -> uploadFrames(toUpload));
            } catch (Exception e) {
                System.err.println("[SVVideo] Error decodificando GIF de carga: " + e.getMessage());
                failed = true;
                decoding.set(false);
            }
        });
    }

    private static void uploadFrames(List<RawFrame> rawFrames) {
        List<GifFrame> newFrames = new ArrayList<>();
        try {
            for (RawFrame raw : rawFrames) {
                NativeImage ni = toNativeImage(raw.image());
                NativeImageBackedTexture tex = new NativeImageBackedTexture(ni);
                tex.upload();
                int glId = tex.getGlId();
                newFrames.add(new GifFrame(glId, tex, raw.delayMs()));
            }

            if (newFrames.isEmpty()) {
                System.err.println("[SVVideo] No se pudo subir ningún frame del GIF");
                failed = true;
                decoding.set(false);
                return;
            }

            gifWidth = rawFrames.get(0).image().getWidth();
            gifHeight = rawFrames.get(0).image().getHeight();
            backgroundColor = computeBgColor(rawFrames.get(0).image());
            frames = Collections.unmodifiableList(newFrames);
            currentFrame = 0;
            lastFrameTime = System.currentTimeMillis();
            loaded = true;
            decoding.set(false);
        } catch (Exception e) {
            System.err.println("[SVVideo] Error subiendo texturas GIF: " + e.getMessage());
            for (GifFrame f : newFrames) {
                try { f.texture().close(); } catch (Exception ignored) {}
            }
            failed = true;
            decoding.set(false);
        }
    }

    private static void advanceFrame() {
        List<GifFrame> f = frames;
        if (f == null || f.isEmpty()) return;
        long now = System.currentTimeMillis();
        int idx = currentFrame;
        if (idx >= f.size()) idx = 0;
        if (now - lastFrameTime >= f.get(idx).delayMs()) {
            lastFrameTime = now;
            currentFrame = (idx + 1) % f.size();
        }
    }

    private static List<RawFrame> decodeGif(InputStream is) throws Exception {
        List<RawFrame> result = new ArrayList<>();
        ImageReader reader = ImageIO.getImageReadersByFormatName("gif").next();
        try (ImageInputStream stream = ImageIO.createImageInputStream(is)) {
            reader.setInput(stream);
            int count = reader.getNumImages(true);
            int fullW = reader.getWidth(0);
            int fullH = reader.getHeight(0);
            BufferedImage canvas = new BufferedImage(fullW, fullH, BufferedImage.TYPE_INT_ARGB);

            for (int i = 0; i < count; i++) {
                BufferedImage frame = reader.read(i);
                int delayMs = 100;
                int fx = 0, fy = 0;
                String disposal = "none";

                try {
                    IIOMetadataNode root = (IIOMetadataNode) reader.getImageMetadata(i)
                            .getAsTree("javax_imageio_gif_image_1.0");

                    NodeList descList = root.getElementsByTagName("ImageDescriptor");
                    if (descList.getLength() > 0) {
                        IIOMetadataNode desc = (IIOMetadataNode) descList.item(0);
                        fx = Integer.parseInt(desc.getAttribute("imageLeftPosition"));
                        fy = Integer.parseInt(desc.getAttribute("imageTopPosition"));
                    }

                    NodeList gceList = root.getElementsByTagName("GraphicControlExtension");
                    if (gceList.getLength() > 0) {
                        IIOMetadataNode gce = (IIOMetadataNode) gceList.item(0);
                        int cs = Integer.parseInt(gce.getAttribute("delayTime"));
                        delayMs = Math.max(20, cs * 10);
                        disposal = gce.getAttribute("disposalMethod");
                    }
                } catch (Exception ignored) {}

                Graphics2D g2 = canvas.createGraphics();
                g2.drawImage(frame, fx, fy, null);
                g2.dispose();

                BufferedImage snap = new BufferedImage(fullW, fullH, BufferedImage.TYPE_INT_ARGB);
                Graphics2D sg = snap.createGraphics();
                sg.drawImage(canvas, 0, 0, null);
                sg.dispose();
                result.add(new RawFrame(snap, delayMs));

                if ("restoreToBackgroundColor".equals(disposal)) {
                    Graphics2D cg = canvas.createGraphics();
                    cg.setComposite(AlphaComposite.Clear);
                    cg.fillRect(fx, fy, frame.getWidth(), frame.getHeight());
                    cg.dispose();
                }
            }
        } finally {
            reader.dispose();
        }
        return result;
    }

    private static NativeImage toNativeImage(BufferedImage img) {
        int w = img.getWidth();
        int h = img.getHeight();
        NativeImage ni = new NativeImage(NativeImage.Format.RGBA, w, h, false);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int argb = img.getRGB(x, y);
                int a = (argb >>> 24) & 0xFF;
                int r = (argb >>> 16) & 0xFF;
                int g = (argb >>> 8) & 0xFF;
                int b = argb & 0xFF;

                ni.setColor(x, y, r | (g << 8) | (b << 16) | (a << 24));
            }
        }
        return ni;
    }

    private static int computeBgColor(BufferedImage img) {
        int w = img.getWidth();
        int h = img.getHeight();
        if (w <= 0 || h <= 0) return 0xFF343434;
        int[] samples = {
            img.getRGB(0, 0),
            img.getRGB(w - 1, 0),
            img.getRGB(0, h - 1),
            img.getRGB(w - 1, h - 1)
        };
        int a = 0, r = 0, g = 0, b = 0;
        for (int c : samples) {
            a += (c >>> 24) & 0xFF;
            r += (c >>> 16) & 0xFF;
            g += (c >>> 8) & 0xFF;
            b += c & 0xFF;
        }
        return ((a / 4) << 24) | ((r / 4) << 16) | ((g / 4) << 8) | (b / 4);
    }

    public static boolean hasTexture() {
        tick();
        return loaded && frames != null && !frames.isEmpty();
    }

    public static long texture() {
        List<GifFrame> f = frames;
        if (f == null || f.isEmpty()) return 0;
        int idx = currentFrame;
        if (idx >= f.size()) idx = 0;
        return f.get(idx).glId();
    }

    public static int width() { return gifWidth > 0 ? gifWidth : 1; }
    public static int height() { return gifHeight > 0 ? gifHeight : 1; }
    public static float aspectRatio() { return width() / (float) height(); }
    public static int backgroundColor() { return backgroundColor; }

    public static void release() {
        List<GifFrame> toRelease = frames;
        frames = null;
        loaded = false;
        failed = false;
        decoding.set(false);
        currentFrame = 0;
        gifWidth = 0;
        gifHeight = 0;
        backgroundColor = 0xFF343434;

        if (toRelease != null) {
            if (RenderSystem.isOnRenderThread()) {
                for (GifFrame f : toRelease) {
                    try { f.texture().close(); } catch (Exception ignored) {}
                }
            } else {
                RenderSystem.recordRenderCall(() -> {
                    for (GifFrame f : toRelease) {
                        try { f.texture().close(); } catch (Exception ignored) {}
                    }
                });
            }
        }
    }
}

