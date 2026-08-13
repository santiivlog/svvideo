package santiivlog.dev.svvideo.client;

import net.minecraft.client.MinecraftClient;
import net.minecraft.resource.Resource;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.util.Identifier;
import santiivlog.dev.svvideo.util.SVVideoFiles;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.metadata.IIOMetadataNode;
import javax.imageio.stream.ImageInputStream;
import java.awt.AlphaComposite;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

public class SVVideoGifPlayer {

    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private static final AtomicInteger INSTANCE_COUNTER = new AtomicInteger(0);

    private final List<NativeImage> frames = new ArrayList<>();
    private final List<Integer> delays = new ArrayList<>();
    private final List<Identifier> textureIds = new ArrayList<>();

    private int currentFrame = 0;
    private long lastAdvanceTime = 0;
    private boolean initialized = false;
    private boolean released = false;
    private int gifWidth;
    private int gifHeight;

    private SVVideoGifPlayer() {}

    public static SVVideoGifPlayer load(String source) throws Exception {
        byte[] gifBytes = fetchBytes(source);
        if (gifBytes == null || gifBytes.length == 0) {
            throw new IOException("GIF vacío: " + source);
        }
        return decodeGif(gifBytes);
    }

    private static byte[] fetchBytes(String source) throws Exception {
        String lower = source.toLowerCase();
        if (lower.startsWith("file://")) {
            return Files.readAllBytes(Path.of(URI.create(source)));
        }
        if (SVVideoFiles.isAssetMediaSource(source)) {
            return fetchAssetBytes(source);
        }
        if (lower.startsWith("http://") || lower.startsWith("https://")) {
            HttpRequest req = HttpRequest.newBuilder(URI.create(source))
                    .timeout(Duration.ofSeconds(20))
                    .GET()
                    .header("User-Agent", "Mozilla/5.0")
                    .build();
            HttpResponse<byte[]> resp = HTTP_CLIENT.send(req, HttpResponse.BodyHandlers.ofByteArray());
            if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
                throw new IOException("HTTP " + resp.statusCode() + " al cargar GIF: " + source);
            }
            return resp.body();
        }
        throw new IOException("Esquema no soportado para GIF: " + source);
    }

    private static byte[] fetchAssetBytes(String source) throws IOException {
        SVVideoFiles.AssetMediaSource asset = SVVideoFiles.parseAssetMediaSource(source);
        if (!asset.success()) {
            throw new IOException(asset.failureReason());
        }

        MinecraftClient client = MinecraftClient.getInstance();
        if (client != null) {
            Identifier id = Identifier.of(asset.namespace(), asset.resourcePath());
            Optional<Resource> resource = client.getResourceManager().getResource(id);
            if (resource.isPresent()) {
                try (InputStream input = resource.get().getInputStream()) {
                    return input.readAllBytes();
                }
            }
        }

        Path externalAsset = SVVideoFiles.findExternalAssetFile(asset.namespace(), asset.resourcePath());
        if (externalAsset != null && Files.isRegularFile(externalAsset)) {
            return Files.readAllBytes(externalAsset);
        }

        throw new IOException("Asset GIF no encontrado: " + asset.namespace() + ":" + asset.resourcePath());
    }

    private static SVVideoGifPlayer decodeGif(byte[] gifBytes) throws IOException {
        SVVideoGifPlayer gif = new SVVideoGifPlayer();

        Iterator<ImageReader> readers = ImageIO.getImageReadersByFormatName("gif");
        if (!readers.hasNext()) {
            throw new IOException("No hay ImageReader para GIF");
        }
        ImageReader reader = readers.next();

        try (ImageInputStream stream = ImageIO.createImageInputStream(new ByteArrayInputStream(gifBytes))) {
            reader.setInput(stream);
            int numFrames = reader.getNumImages(true);
            if (numFrames == 0) throw new IOException("GIF sin frames");

            gif.gifWidth = reader.getWidth(0);
            gif.gifHeight = reader.getHeight(0);

            BufferedImage canvas = new BufferedImage(gif.gifWidth, gif.gifHeight, BufferedImage.TYPE_INT_ARGB);
            BufferedImage preFrameCanvas = null;

            for (int i = 0; i < numFrames; i++) {
                BufferedImage rawFrame = reader.read(i);

                int delay = 100;
                String disposal = "doNotDispose";
                int fx = 0, fy = 0, fw = rawFrame.getWidth(), fh = rawFrame.getHeight();

                try {
                    IIOMetadataNode root = (IIOMetadataNode) reader.getImageMetadata(i)
                            .getAsTree(reader.getImageMetadata(i).getNativeMetadataFormatName());

                    var gcList = root.getElementsByTagName("GraphicControlExtension");
                    if (gcList.getLength() > 0) {
                        IIOMetadataNode gce = (IIOMetadataNode) gcList.item(0);
                        String ds = gce.getAttribute("delayTime");
                        if (ds != null && !ds.isBlank()) delay = Math.max(20, Integer.parseInt(ds.trim()) * 10);
                        String dm = gce.getAttribute("disposalMethod");
                        if (dm != null && !dm.isBlank()) disposal = dm.trim();
                    }

                    var idList = root.getElementsByTagName("ImageDescriptor");
                    if (idList.getLength() > 0) {
                        IIOMetadataNode desc = (IIOMetadataNode) idList.item(0);
                        String lx = desc.getAttribute("imageLeftPosition");
                        String ty = desc.getAttribute("imageTopPosition");
                        if (lx != null && !lx.isBlank()) fx = Integer.parseInt(lx.trim());
                        if (ty != null && !ty.isBlank()) fy = Integer.parseInt(ty.trim());
                    }
                } catch (Exception ignored) {}

                if ("restoreToPrevious".equals(disposal)) {
                    preFrameCanvas = deepCopy(canvas);
                }

                Graphics2D g = canvas.createGraphics();
                g.setComposite(AlphaComposite.SrcOver);
                g.drawImage(rawFrame, fx, fy, null);
                g.dispose();

                gif.frames.add(toNativeImage(canvas));
                gif.delays.add(delay);

                switch (disposal) {
                    case "restoreToBackgroundColor" -> {
                        Graphics2D gc = canvas.createGraphics();
                        gc.setComposite(AlphaComposite.Clear);
                        gc.fillRect(fx, fy, fw, fh);
                        gc.dispose();
                    }
                    case "restoreToPrevious" -> {
                        if (preFrameCanvas != null) canvas = deepCopy(preFrameCanvas);
                    }
                    default -> {}
                }
            }
        } finally {
            reader.dispose();
        }

        return gif;
    }

    private static NativeImage toNativeImage(BufferedImage src) throws IOException {
        BufferedImage argb = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = argb.createGraphics();
        g.setComposite(AlphaComposite.Src);
        g.drawImage(src, 0, 0, null);
        g.dispose();
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(argb, "png", baos);
        return NativeImage.read(new ByteArrayInputStream(baos.toByteArray()));
    }

    private static BufferedImage deepCopy(BufferedImage src) {
        BufferedImage copy = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = copy.createGraphics();
        g.drawImage(src, 0, 0, null);
        g.dispose();
        return copy;
    }

    public void initTextures(MinecraftClient client) {
        if (initialized || released || frames.isEmpty()) return;
        int instance = INSTANCE_COUNTER.getAndIncrement();
        for (int i = 0; i < frames.size(); i++) {
            NativeImageBackedTexture tex = new NativeImageBackedTexture(frames.get(i));
            Identifier id = client.getTextureManager().registerDynamicTexture(
                    "svvideo_gif_" + instance + "_f" + i, tex);
            textureIds.add(id);
        }
        frames.clear();
        initialized = true;
        lastAdvanceTime = System.currentTimeMillis();
    }

    public void tick() {
        if (!initialized || released || textureIds.isEmpty()) return;
        long now = System.currentTimeMillis();
        if (now - lastAdvanceTime >= delays.get(currentFrame)) {
            currentFrame = (currentFrame + 1) % textureIds.size();
            lastAdvanceTime = now;
        }
    }

    public boolean isEmpty() {
        return frames.isEmpty() && textureIds.isEmpty();
    }

    public boolean isReady() {
        return initialized && !textureIds.isEmpty();
    }

    public Identifier currentIdentifier() {
        if (!isReady()) return null;
        return textureIds.get(currentFrame);
    }

    public int width() {
        return gifWidth;
    }

    public int height() {
        return gifHeight;
    }

    public void release(MinecraftClient client) {
        if (released) return;
        released = true;
        if (client != null) {
            for (Identifier id : textureIds) {
                try { client.getTextureManager().destroyTexture(id); } catch (Exception ignored) {}
            }
        }
        textureIds.clear();
        for (NativeImage img : frames) {
            try { img.close(); } catch (Exception ignored) {}
        }
        frames.clear();
    }
}

