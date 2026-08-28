package santiivlog.dev.svvideo.client;

import com.mojang.blaze3d.systems.RenderSystem;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.render.BufferRenderer;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.client.render.Tessellator;
import net.minecraft.client.render.VertexFormat;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.sound.SoundCategory;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import org.joml.Matrix4f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.watermedia.api.media.MRL;
import org.watermedia.api.media.engines.ALEngine;
import org.watermedia.api.media.engines.GLEngine;
import org.watermedia.api.media.players.MediaPlayer;
import santiivlog.dev.svvideo.util.SVVideoFiles;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class VideoRenderer {
    private static final Logger LOGGER = LoggerFactory.getLogger(VideoRenderer.class);
    private static final long LOAD_TIMEOUT_MS = 15_000L;
    private static final long STATUS_NOTICE_MS = 8_000L;
    private static final String GENERIC_LOAD_FAILURE =
            "No se pudo cargar el video. Revisa WaterMedia, el archivo o tu cliente.";
    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(8))
            .build();
    private static final Pattern META_MEDIA_PATTERN = Pattern.compile(
            "<meta[^>]+property=[\"'](?:og:video|og:video:url|og:image|twitter:image|twitter:player:stream)[\"'][^>]+content=[\"']([^\"']+)[\"']",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern META_TITLE_PATTERN = Pattern.compile(
            "<meta[^>]+property=[\"'](?:og:title|twitter:title)[\"'][^>]+content=[\"']([^\"']+)[\"']",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern HTML_TITLE_PATTERN = Pattern.compile(
            "<title>(.*?)</title>",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL
    );
    private static final Pattern OEMBED_AUTHOR_URL_PATTERN = Pattern.compile(
            "\"author_url\"\\s*:\\s*\"([^\"]+)\"",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern YOUTUBE_ID_PATTERN = Pattern.compile("(?:[?&]v=|youtu\\.be/)([A-Za-z0-9_-]{11})");

    private static MediaPlayer player;
    private static MRL mrl;
    private static GLEngine videoEngine;
    private static ALEngine audioEngine;

    private static final List<GifOverlay> gifOverlays = new ArrayList<>();

    private static boolean registered;
    private static boolean canChat = true;
    private static boolean forceHidden = true;
    private static boolean pendingGlCleanup;
    private static boolean audioOnly;
    private static boolean previousHudHidden;
    private static boolean hudStateCaptured;
    private static String renderPosition = "fullscreen";
    private static SoundCategory activeSoundCategory = SoundCategory.MUSIC;
    private static boolean musicHudVisible = true;

    private static int serverVolume = 100;
    private static long startTime;
    private static boolean playing;
    private static String currentMediaLabel = "";
    private static String currentMediaTitle = "";
    private static String currentThumbnailSource = "";
    private static Identifier currentThumbnailTexture;
    private static NativeImageBackedTexture currentThumbnailImage;
    private static CompletableFuture<Void> thumbnailTask;
    private static CompletableFuture<PreparedPlayback> prepareTask;
    private static boolean loading;
    private static long sessionId;
    private static long loadingStartedAt;
    private static long playerStartedAt;
    private static String requestedSource = "";
    private static String resolvedSource = "";
    private static String sourceType = "";
    private static String playbackDetail = "";
    private static SVVideoPlaybackState playbackState = SVVideoPlaybackState.STOPPED;
    private static boolean audioReadyLogged;
    private static boolean videoReadyLogged;
    private static boolean renderReadyLogged;
    private static String statusNotice = "";
    private static long statusNoticeUntil;
    private static int statusNoticeColor = 0xFFFF8080;

    private VideoRenderer() {
    }

    public static void init() {
        if (registered) {
            return;
        }
        registered = true;
        HudRenderCallback.EVENT.register((context, tickDelta) -> render(context));
    }

    public static void play(String url, int volume) {
        startPlayback(url, volume, false, "fullscreen", false, "PLAY");
    }

    public static void playMusic(String url, int volume) {
        startPlayback(url, volume, true, "fullscreen", false, "MUSIC");
    }

    public static void playMusicFile(String path, int volume) {
        SVVideoFiles.ClientMediaResolution resolution = SVVideoMediaTransferClient.resolveOrRequest(cleanUrl(path));
        if (!resolution.success()) {
            handleSourceResolutionFailure("MUSIC_FILE", path, resolution.failureReason());
            return;
        }
        startPlayback(resolution.resolvedSource(), volume, true, "fullscreen", false, "MUSIC_FILE");
    }

    public static void playOverlay(String url, int volume, String position) {
        addGifOverlay(cleanUrl(url), position, false);
        init();
    }

    public static void playFile(String path, int volume) {
        SVVideoFiles.ClientMediaResolution resolution = SVVideoMediaTransferClient.resolveOrRequest(cleanUrl(path));
        if (!resolution.success()) {
            handleSourceResolutionFailure("FILE", path, resolution.failureReason());
            return;
        }
        startPlayback(resolution.resolvedSource(), volume, false, "fullscreen", false, "FILE");
    }

    public static void playOverlayFile(String path, int volume, String position) {
        SVVideoFiles.ClientMediaResolution resolution = SVVideoMediaTransferClient.resolveOrRequest(cleanUrl(path));
        if (!resolution.success()) {
            handleSourceResolutionFailure("GIF_FILE", path, resolution.failureReason());
            return;
        }

        addGifOverlay(resolution.resolvedSource(), position, true);
        init();
    }

    private static void addGifOverlay(String url, String position, boolean isDirectSource) {
        int scale = extractScale(position);
        long durationMs = extractDurationMs(position);
        String normalizedPos = normalizePosition(position);

        logInfo("Iniciando GIF overlay: url={} pos={} scale={} durationMs={}", url, normalizedPos, scale, durationMs);

        CompletableFuture<SVVideoGifPlayer> loadTask = CompletableFuture.supplyAsync(() -> {
            try {
                String src = isDirectSource ? url : resolvePlayableUrl(url);
                if (src == null || src.isBlank()) {
                    logWarn("GIF overlay: URL resuelta vacía para: {}", url);
                    return null;
                }
                return SVVideoGifPlayer.load(src);
            } catch (Exception e) {
                logError("Error cargando GIF overlay: " + url, e);
                Throwable cause = e.getCause() != null ? e.getCause() : e;
                String notice = (cause instanceof ConnectException || e instanceof ConnectException
                        || cause instanceof HttpTimeoutException || e instanceof HttpTimeoutException)
                        ? fileServerConnectionFailure(url)
                        : "No se pudo cargar el GIF. Revisa la URL o el archivo.";
                MinecraftClient mc = MinecraftClient.getInstance();
                if (mc != null) mc.execute(() -> showStatusNotice(notice, 0xFFFF8080));
                return null;
            }
        });

        gifOverlays.add(new GifOverlay(loadTask, normalizedPos, scale, durationMs));
    }

    private static void stopAllGifOverlays() {
        MinecraftClient client = MinecraftClient.getInstance();
        for (GifOverlay overlay : gifOverlays) {
            overlay.release(client);
        }
        gifOverlays.clear();
    }

    public static void setMusicHudVisible(boolean visible) {
        musicHudVisible = visible;
    }

    public static boolean isMusicHudVisible() {
        return musicHudVisible;
    }

    private static void startPlayback(String url, int volume, boolean musicMode, String position,
                                      boolean cacheRemoteMedia, String newSourceType) {
        stopInternal(SVVideoPlaybackState.STOPPED, "Reiniciando reproduccion", false);
        forceHidden = false;
        audioOnly = musicMode;
        renderPosition = normalizePosition(position);
        activeSoundCategory = SoundCategory.MUSIC;
        serverVolume = clamp(volume);
        requestedSource = cleanUrl(url);
        resolvedSource = requestedSource;
        sourceType = newSourceType;
        currentMediaLabel = mediaLabel(requestedSource);
        currentMediaTitle = currentMediaLabel;
        long currentSession = ++sessionId;
        loading = true;
        loadingStartedAt = System.currentTimeMillis();
        playerStartedAt = 0L;
        audioReadyLogged = false;
        videoReadyLogged = false;
        renderReadyLogged = false;
        setPlaybackState(SVVideoPlaybackState.LOADING, "Esperando a WaterMedia");
        clearStatusNotice();
        logInfo("Iniciando carga con WaterMedia: action={} source={} position={} volume={}",
                sourceType, requestedSource, renderPosition, serverVolume);

        try {
            MinecraftClient client = MinecraftClient.getInstance();
            if (!musicMode && isFullscreen()) {
                captureAndHideHud(client);
                client.mouse.unlockCursor();
            }

            String originalSource = requestedSource;
            prepareTask = CompletableFuture.supplyAsync(() ->
                    preparePlayback(originalSource, currentSession, musicMode, cacheRemoteMedia));
            init();
        } catch (Exception e) {
            failPlayback("Error al iniciar la carga del cliente.", e, false);
        }
    }

    public static void stop() {
        stopInternal(SVVideoPlaybackState.STOPPED, "Playback detenido", false);
    }

    private static void stopInternal(SVVideoPlaybackState finalState, String detail, boolean keepStatusNotice) {
        boolean hadLoading = loading;
        sessionId++;
        forceHidden = true;
        loading = false;
        playing = false;
        canChat = true;
        startTime = 0;
        pendingGlCleanup = true;
        audioOnly = false;
        renderPosition = "fullscreen";
        activeSoundCategory = SoundCategory.MUSIC;
        currentMediaLabel = "";
        currentMediaTitle = "";
        currentThumbnailSource = "";
        requestedSource = "";
        resolvedSource = "";
        sourceType = "";
        prepareTask = null;
        thumbnailTask = null;
        audioReadyLogged = false;
        videoReadyLogged = false;
        renderReadyLogged = false;
        loadingStartedAt = 0L;
        playerStartedAt = 0L;

        stopAllGifOverlays();
        releaseThumbnail();

        if (player != null) {
            try {
                player.stop();
            } catch (Exception | LinkageError ignored) {
            }
            try {
                player.release();
            } catch (Exception | LinkageError ignored) {
            }
            player = null;
            videoEngine = null;
            audioEngine = null;
        } else {
            releaseDetachedEngines();
        }

        restoreHud();
        mrl = null;
        if (hadLoading) {
            logInfo("Cerrando pantalla de loading");
        }
        if (!keepStatusNotice) {
            clearStatusNotice();
        }
        setPlaybackState(finalState, detail);
    }

    private static void render(DrawContext context) {
        try {
            if (pendingGlCleanup) {
                pendingGlCleanup = false;
                RenderSystem.setShaderTexture(0, 0);
                RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
                RenderSystem.disableBlend();
                RenderSystem.enableDepthTest();
            }

            if (loading) {
                renderLoadingState(context);
            } else if (playing && player != null) {
                if (!handleTerminalPlaybackState()) {
                    if (audioOnly || !player.withVideo()) {
                        renderMusicHud(context);
                    } else if (!forceHidden) {
                        int texture = (int) player.texture();
                        if (texture > 0) {
                            int width = context.getScaledWindowWidth();
                            int height = context.getScaledWindowHeight();
                            RenderBox box = new RenderBox(0, 0, width, height);
                            if (!isFullscreen()) {
                                box = getOverlayRenderBox(width, height, player.width(), player.height(),
                                        renderPosition, 100);
                            }
                            drawTexturedQuad(context, texture, box);
                        } else {
                            renderLoadingState(context);
                        }
                    }
                }
            }

            renderGifOverlays(context);

        } catch (Exception | LinkageError e) {
            failPlayback("Excepcion silenciosa en el render del cliente.", e, false);
        } finally {
            renderStatusNotice(context);
        }
    }

    private static void renderGifOverlays(DrawContext context) {
        if (gifOverlays.isEmpty()) return;
        int width = context.getScaledWindowWidth();
        int height = context.getScaledWindowHeight();
        for (GifOverlay overlay : gifOverlays) {
            if (!overlay.isReady()) continue;
            Identifier tex = overlay.currentIdentifier();
            if (tex == null) continue;
            RenderBox box = getOverlayRenderBox(width, height, overlay.gifWidth(), overlay.gifHeight(),
                    overlay.position, overlay.scale);
            drawTexturedQuad(context, tex, box, overlay.calculateAlpha());
        }
    }

    public static void tickClient() {
        try {
            MinecraftClient client = MinecraftClient.getInstance();
            if (client.world == null) {
                if (playing || loading) {
                    stopInternal(SVVideoPlaybackState.STOPPED, "Mundo cliente cerrado", false);
                } else {
                    stopAllGifOverlays();
                }
                return;
            }

            SVVideoLoadingIndicator.tick();

            if (loading) {
                advanceLoading();
                enforceLoadingTimeout();
            }

            tickGifOverlays();

            if (!playing || player == null) {
                return;
            }

            applyVolume();
            if (!loading) {
                handleTerminalPlaybackState();
            }
        } catch (Exception | LinkageError e) {
            failPlayback("Excepcion silenciosa en el tick del cliente.", e, false);
        }
    }

    private static void tickGifOverlays() {
        if (gifOverlays.isEmpty()) return;
        MinecraftClient client = MinecraftClient.getInstance();
        List<GifOverlay> toRemove = null;
        for (GifOverlay overlay : gifOverlays) {
            overlay.advance(client);
            if (overlay.shouldExpire()) {
                if (toRemove == null) toRemove = new ArrayList<>();
                toRemove.add(overlay);
            }
        }
        if (toRemove != null) {
            for (GifOverlay expired : toRemove) {
                expired.release(client);
            }
            gifOverlays.removeAll(toRemove);
        }
    }

    private static void advanceLoading() {
        if (prepareTask != null) {
            if (!prepareTask.isDone()) {
                return;
            }

            PreparedPlayback prepared;
            try {
                prepared = prepareTask.join();
            } catch (Exception e) {
                failPlayback("Error preparando la fuente para WaterMedia.", e, false);
                return;
            }
            prepareTask = null;

            if (prepared.sessionId() != sessionId) {
                return;
            }
            if (prepared.failed()) {
                failPlayback(prepared.failureReason(), null, false);
                return;
            }

            resolvedSource = prepared.playableSource();
            currentMediaTitle = prepared.title().isBlank() ? currentMediaLabel : prepared.title();
            loadThumbnailAsync(prepared.originalSource(), prepared.sessionId());

            mrl = SVVideoWaterMediaCompat.createMrl(prepared.playableSource());
            if (mrl != null) {
                setPlaybackState(SVVideoPlaybackState.LOADING, "MRL creada, esperando ready()");
            }
        }

        if (mrl == null) {
            updateLoadingFromPlayer();
            return;
        }
        if (SVVideoWaterMediaCompat.isMrlFailed(mrl)) {
            failPlayback(SVVideoWaterMediaCompat.failureMessage(
                    mrl,
                    "WaterMedia marco error al cargar la MRL."
            ), null, false);
            return;
        }
        if (!SVVideoWaterMediaCompat.isMrlReady(mrl)) {
            return;
        }

        if (player == null) {
            try {
                logInfo("MRL lista. Creando player WaterMedia para {}", resolvedSource);
                Executor renderExecutor = task -> RenderSystem.recordRenderCall(task::run);
                player = SVVideoWaterMediaCompat.createPlayer(
                        mrl,
                        () -> videoEngine = SVVideoWaterMediaCompat.createGlEngine(Thread.currentThread(), renderExecutor),
                        () -> audioEngine = SVVideoWaterMediaCompat.createAlEngine()
                );
                if (player == null) {
                    releaseDetachedEngines();
                    failPlayback(createPlayerFailureMessage(), null, false);
                    return;
                }

                applyVolume();
                player.repeat(false);
                player.start();
                startTime = System.currentTimeMillis();
                playerStartedAt = startTime;
                playing = true;
                setPlaybackState(SVVideoPlaybackState.LOADING, "Player creado, esperando audio/video");
            } catch (Exception e) {
                failPlayback("Error creando o iniciando el player de WaterMedia.", e, false);
                return;
            }
        }

        updateLoadingFromPlayer();
    }

    private static void releaseDetachedEngines() {
        if (videoEngine != null) {
            try {
                videoEngine.release();
            } catch (Exception | LinkageError ignored) {
            }
            videoEngine = null;
        }

        if (audioEngine != null) {
            try {
                audioEngine.release();
            } catch (Exception | LinkageError ignored) {
            }
            audioEngine = null;
        }
    }

    private static String createPlayerFailureMessage() {
        if (!SVVideoWaterMediaCompat.isNativeBackendLoaded()) {
            return "WaterMedia Binaries no esta instalado o FFmpeg no cargo en el cliente.";
        }
        return "WaterMedia no pudo crear el player para esa fuente.";
    }

    private static void updateLoadingFromPlayer() {
        if (player == null) {
            return;
        }
        if (player.error()) {
            failPlayback("WaterMedia dejo el player en estado ERROR.", null, false);
            return;
        }
        if (player.stopped() && loading) {
            failPlayback("WaterMedia detuvo la reproduccion antes de que el video estuviera listo.", null, false);
            return;
        }

        if (!audioReadyLogged && player.withAudio() && player.audioSource() > 0) {
            markAudioReady("Audio cargado");
        }
        if (!videoReadyLogged && player.width() > 0 && player.height() > 0) {
            markVideoReady("Video cargado " + player.width() + "x" + player.height());
        }
        if (audioOnly && player.canPlay() && (player.playing() || player.paused() || player.buffering())) {
            markRenderReady("Audio listo");
            return;
        }
        if (!renderReadyLogged && player.texture() > 0 && player.width() > 0 && player.height() > 0) {
            markRenderReady("Render listo con textura " + player.texture());
        }
    }

    private static void enforceLoadingTimeout() {
        if (!loading || loadingStartedAt <= 0L) {
            return;
        }
        long elapsed = System.currentTimeMillis() - loadingStartedAt;
        if (elapsed < LOAD_TIMEOUT_MS) {
            return;
        }
        String reason = "Timeout de carga tras " + elapsed + "ms. state=" + playbackState + " detail=" + playbackDetail;
        if (isSvvideoServerHttp(resolvedSource)) {
            reason = fileServerConnectionFailure(resolvedSource);
        }
        failPlayback(reason, null, true);
    }

    private static void markAudioReady(String detail) {
        audioReadyLogged = true;
        setPlaybackState(SVVideoPlaybackState.AUDIO_READY, detail);
        logInfo("Audio cargado: {}", detail);
    }

    private static void markVideoReady(String detail) {
        videoReadyLogged = true;
        setPlaybackState(SVVideoPlaybackState.VIDEO_READY, detail);
        logInfo("Video cargado: {}", detail);
    }

    private static void markRenderReady(String detail) {
        renderReadyLogged = true;
        loading = false;
        setPlaybackState(SVVideoPlaybackState.RENDER_READY, detail);
        logInfo("Render listo: {}", detail);
    }

    private static void handleSourceResolutionFailure(String action, String source, String reason) {
        requestedSource = cleanUrl(source);
        resolvedSource = "";
        sourceType = action;
        logWarn("No se pudo resolver la fuente {}: {} ({})", action, requestedSource, reason);
        showStatusNotice(GENERIC_LOAD_FAILURE, 0xFFFF8080);
        notifyPlayer(GENERIC_LOAD_FAILURE);
        init();
        stopInternal(SVVideoPlaybackState.FAILED, reason, true);
    }

    private static void failPlayback(String reason, Throwable error, boolean timeout) {
        String failure = reason == null || reason.isBlank() ? GENERIC_LOAD_FAILURE : reason;
        if (timeout) {
            logWarn("Timeout de carga: {} | action={} requested={} resolved={}",
                    failure, sourceType, requestedSource, resolvedSource);
        } else if (error != null) {
            logError("Error al cargar: " + failure + " | action=" + sourceType
                    + " requested=" + requestedSource + " resolved=" + resolvedSource, error);
        } else {
            logWarn("Error al cargar: {} | action={} requested={} resolved={}",
                    failure, sourceType, requestedSource, resolvedSource);
        }
        String playerMessage = playerFailureMessage(failure);
        showStatusNotice(playerMessage, 0xFFFF8080);
        notifyPlayer(playerMessage);
        stopInternal(timeout ? SVVideoPlaybackState.TIMEOUT : SVVideoPlaybackState.FAILED, failure, true);
    }

    private static PreparedPlayback preparePlayback(String originalSource, long currentSession,
                                                    boolean musicMode, boolean cacheRemoteMedia) {
        try {
            String playableSource = resolvePlayableUrl(originalSource);
            SVVideoYoutubeAccess.AccessDecision accessDecision =
                    SVVideoYoutubeAccess.checkAnonymousPlayback(originalSource);
            if (!accessDecision.allowed()) {
                logWarn("{}: {}", accessDecision.message(), originalSource);
                return new PreparedPlayback(currentSession, originalSource, "", "", accessDecision.message());
            }
            if (!musicMode && shouldCacheRemoteMedia(originalSource, playableSource, cacheRemoteMedia)) {
                String cached = SVVideoFiles.cacheClientRemoteMedia(playableSource);
                if (cached != null && !cached.isBlank()) {
                    playableSource = cached;
                }
            }
            if (looksLikeBrokenYoutubeUrl(playableSource)) {
                logWarn("URL de YouTube invalida: {}", playableSource);
                return new PreparedPlayback(currentSession, originalSource, "", "",
                        "La URL de YouTube no tiene un video id valido.");
            }
            String serverFailure = validateSvvideoServerSource(playableSource);
            if (!serverFailure.isBlank()) {
                return new PreparedPlayback(currentSession, originalSource, "", "", serverFailure);
            }
            return new PreparedPlayback(
                    currentSession,
                    originalSource,
                    playableSource,
                    mediaTitle(originalSource, playableSource),
                    ""
            );
        } catch (Exception e) {
            logError("Error preparando source para WaterMedia: " + originalSource, e);
            return new PreparedPlayback(currentSession, originalSource, "", "",
                    "Fallo la preparacion de la fuente antes de llegar a WaterMedia.");
        }
    }

    private static boolean shouldCacheRemoteMedia(String originalSource, String playableSource, boolean overlayMode) {
        if (!isRemoteHttp(playableSource)) {
            return false;
        }
        return overlayMode
                || looksLikeRemoteImage(playableSource)
                || looksLikeGifProvider(originalSource)
                || looksLikeGifProvider(playableSource);
    }

    private static boolean handleTerminalPlaybackState() {
        if (player == null) {
            return true;
        }
        if (player.error()) {
            failPlayback("WaterMedia reporto ERROR durante la reproduccion.", null, false);
            return true;
        }
        if (player.ended() || player.stopped()) {
            stopInternal(SVVideoPlaybackState.STOPPED, "Playback finalizado", false);
            return true;
        }
        return false;
    }

    public static void setVolume(int volume) {
        serverVolume = clamp(volume);
        applyVolume();
    }

    public static void applyVolume() {
        if (player == null) {
            return;
        }
        player.volume(SVVideoSettings.mixVolume(serverVolume, activeSoundCategory));
    }

    public static void setCanChat(boolean value) {
        canChat = value;
    }

    public static boolean shouldBlockChat() {
        return (loading || (playing && player != null)) && !canChat;
    }

    private static void renderLoadingState(DrawContext context) {
        if (forceHidden) {
            return;
        }

        int width = context.getScaledWindowWidth();
        int height = context.getScaledWindowHeight();
        if (!audioOnly && isFullscreen()) {
            context.fill(0, 0, width, height, SVVideoLoadingIndicator.backgroundColor());
        }

        drawLoadingIndicator(context, getLoadingBox(width, height, "bottom-right"));
    }

    private static void renderStatusNotice(DrawContext context) {
        if (statusNotice.isBlank() || System.currentTimeMillis() > statusNoticeUntil) {
            return;
        }

        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null) {
            return;
        }

        int textWidth = client.textRenderer.getWidth(statusNotice);
        int boxWidth = Math.min(context.getScaledWindowWidth() - 20, textWidth + 16);
        int boxHeight = client.textRenderer.fontHeight + 10;
        int boxX = Math.max(10, (context.getScaledWindowWidth() - boxWidth) / 2);
        int boxY = Math.max(10, context.getScaledWindowHeight() - boxHeight - 12);

        context.fill(boxX, boxY, boxX + boxWidth, boxY + boxHeight, 0xD0101018);
        context.fill(boxX, boxY, boxX + boxWidth, boxY + 1, statusNoticeColor);
        context.fill(boxX, boxY, boxX + 1, boxY + boxHeight, statusNoticeColor);
        context.drawTextWithShadow(client.textRenderer, statusNotice, boxX + 8, boxY + 3, 0xFFF5F5F5);
    }

    private static void drawLoadingIndicator(DrawContext context, RenderBox box) {
        if (SVVideoLoadingIndicator.hasTexture()) {
            drawTexturedQuad(context, (int) SVVideoLoadingIndicator.texture(), box);
            return;
        }
        drawLoadingFallback(context, box);
    }

    private static void drawTexturedQuad(DrawContext context, int texture, RenderBox box) {
        Matrix4f matrix = context.getMatrices().peek().getPositionMatrix();

        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableDepthTest();
        RenderSystem.setShader(GameRenderer::getPositionTexProgram);
        RenderSystem.setShaderTexture(0, texture);

        BufferBuilder buffer = Tessellator.getInstance().begin(
                VertexFormat.DrawMode.QUADS,
                VertexFormats.POSITION_TEXTURE
        );

        buffer.vertex(matrix, box.x(), box.y() + box.height(), 0).texture(0, 1);
        buffer.vertex(matrix, box.x() + box.width(), box.y() + box.height(), 0).texture(1, 1);
        buffer.vertex(matrix, box.x() + box.width(), box.y(), 0).texture(1, 0);
        buffer.vertex(matrix, box.x(), box.y(), 0).texture(0, 0);

        BufferRenderer.drawWithGlobalProgram(buffer.end());

        RenderSystem.enableDepthTest();
        RenderSystem.disableBlend();
    }

    private static void drawTexturedQuad(DrawContext context, Identifier texture, RenderBox box, float alpha) {
        Matrix4f matrix = context.getMatrices().peek().getPositionMatrix();

        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableDepthTest();
        RenderSystem.setShader(GameRenderer::getPositionTexProgram);
        RenderSystem.setShaderTexture(0, texture);
        RenderSystem.setShaderColor(1f, 1f, 1f, alpha);

        BufferBuilder buffer = Tessellator.getInstance().begin(
                VertexFormat.DrawMode.QUADS,
                VertexFormats.POSITION_TEXTURE
        );

        buffer.vertex(matrix, box.x(), box.y() + box.height(), 0).texture(0, 1);
        buffer.vertex(matrix, box.x() + box.width(), box.y() + box.height(), 0).texture(1, 1);
        buffer.vertex(matrix, box.x() + box.width(), box.y(), 0).texture(1, 0);
        buffer.vertex(matrix, box.x(), box.y(), 0).texture(0, 0);

        BufferRenderer.drawWithGlobalProgram(buffer.end());

        RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
        RenderSystem.enableDepthTest();
        RenderSystem.disableBlend();
    }

    private static void drawLoadingFallback(DrawContext context, RenderBox box) {
        MinecraftClient client = MinecraftClient.getInstance();
        int padding = 6;
        int left = box.x() - padding;
        int top = box.y() - padding;
        int right = box.x() + box.width() + padding;
        int bottom = box.y() + box.height() + padding;

        context.fill(left, top, right, bottom, 0xB0000000);
        context.fill(left, top, right, top + 1, 0xFF7B5FFF);
        context.fill(left, top, left + 1, bottom, 0xFF7B5FFF);
        context.fill(box.x(), box.y(), box.x() + box.width(), box.y() + box.height(), 0xFF101018);

        String dots = ".".repeat((int) ((System.currentTimeMillis() / 350L) % 4));
        String label = "Cargando" + dots;
        int textX = left + 8;
        int textY = Math.max(top + 8, bottom - client.textRenderer.fontHeight - 6);
        context.drawTextWithShadow(client.textRenderer, label, textX, textY, 0xFFD7CCFF);
    }

    private static RenderBox getLoadingBox(int screenWidth, int screenHeight, String position) {
        String normalized = normalizePosition(position);
        float aspectRatio = SVVideoLoadingIndicator.hasTexture() ? SVVideoLoadingIndicator.aspectRatio() : 1.0f;
        int marginX = Math.max(18, Math.round(screenWidth * 0.02f));
        int marginY = Math.max(18, Math.round(screenHeight * 0.03f));
        int baseSize = Math.max(54, Math.min(96, Math.round(Math.min(screenWidth, screenHeight) * 0.10f)));
        int maxWidth = baseSize;
        int maxHeight = baseSize;

        int boxWidth = maxWidth;
        int boxHeight = Math.max(1, Math.round(boxWidth / aspectRatio));
        if (boxHeight > maxHeight) {
            boxHeight = maxHeight;
            boxWidth = Math.max(1, Math.round(boxHeight * aspectRatio));
        }

        int x = marginX;
        int y = screenHeight - boxHeight - marginY;
        switch (normalized) {
            case "top-center" -> {
                x = (screenWidth - boxWidth) / 2;
                y = marginY;
            }
            case "top-left" -> y = marginY;
            case "top-right" -> {
                x = screenWidth - boxWidth - marginX;
                y = marginY;
            }
            case "bottom-right" -> x = screenWidth - boxWidth - marginX;
            case "center" -> {
                x = (screenWidth - boxWidth) / 2;
                y = (screenHeight - boxHeight) / 2;
            }
            case "actionbar" -> {
                x = (screenWidth - boxWidth) / 2;
                y = screenHeight - boxHeight - 40;
            }
            default -> {
            }
        }
        return new RenderBox(x, y, boxWidth, boxHeight);
    }

    private static void renderMusicHud(DrawContext context) {
        if (!audioOnly || player == null || !musicHudVisible) {
            return;
        }

        MinecraftClient client = MinecraftClient.getInstance();
        final int cardWidth = 270;
        final int cardHeight = 50;
        final int cardX = 10;
        final int cardY = context.getScaledWindowHeight() - cardHeight - 10;
        final int thumbSize = 20;

        long current = Math.max(0, player.time());
        long duration = Math.max(0, player.duration());

        context.fill(cardX, cardY, cardX + cardWidth, cardY + cardHeight, 0xEA07101E);
        context.fill(cardX, cardY, cardX + cardWidth, cardY + 1, 0xFF7B5FFF);
        context.fill(cardX, cardY + 1, cardX + 3, cardY + cardHeight, 0xFF7B5FFF);
        context.fill(cardX, cardY + cardHeight - 1, cardX + cardWidth, cardY + cardHeight, 0xFF1A1A2E);
        context.fill(cardX + cardWidth - 1, cardY + 1, cardX + cardWidth, cardY + cardHeight, 0xFF1A1A2E);

        int iconBoxX = cardX + 8;
        int iconBoxY = cardY + 8;
        context.fill(iconBoxX, iconBoxY, iconBoxX + 20, iconBoxY + 20, 0xFF101828);
        context.fill(iconBoxX, iconBoxY, iconBoxX + 20, iconBoxY + 1, 0xFF2A2A44);
        context.fill(iconBoxX, iconBoxY, iconBoxX + 1, iconBoxY + 20, 0xFF2A2A44);
        if (currentThumbnailTexture != null) {
            context.drawTexture(currentThumbnailTexture, iconBoxX, iconBoxY, 0.0f, 0.0f, thumbSize, thumbSize, thumbSize, thumbSize);
        } else {
            context.drawTextWithShadow(client.textRenderer, "\u266B", iconBoxX + 5, iconBoxY + 6, 0xFFBB99FF);
        }

        int textX = cardX + 34;
        String displayTitle = currentMediaTitle.isBlank() ? currentMediaLabel : currentMediaTitle;
        int titleMaxWidth = cardWidth - (textX - cardX) - 42;
        drawScrollingLabel(context, client, displayTitle, textX, cardY + 7, titleMaxWidth, 0xFFEEEEFF, current);

        String timeStr = formatTime(current) + " / " + formatTime(duration);
        context.drawTextWithShadow(client.textRenderer, timeStr, textX, cardY + 19, 0xFF8888BB);
        int percent = duration > 0 ? (int) Math.round((current * 100.0) / duration) : 0;
        String percentStr = Math.max(0, Math.min(100, percent)) + "%";
        context.drawTextWithShadow(
                client.textRenderer,
                percentStr,
                cardX + cardWidth - 10 - client.textRenderer.getWidth(percentStr),
                cardY + 19,
                0xFFBB99FF
        );

        int barX = textX;
        int barY = cardY + 34;
        int barW = cardWidth - (textX - cardX) - 10;
        context.fill(barX, barY, barX + barW, barY + 4, 0xFF181828);
        context.fill(barX, barY + 1, barX + barW, barY + 3, 0xFF222236);

        if (duration > 0) {
            int filled = (int) Math.round((current / (double) duration) * barW);
            filled = Math.max(0, Math.min(barW, filled));
            context.fill(barX, barY + 1, barX + filled, barY + 3, 0xFF7B5FFF);
            if (filled > 1) {
                context.fill(barX + filled - 1, barY, barX + filled + 1, barY + 4, 0xFFBB99FF);
            }
        }
    }

    private static String resolvePlayableUrl(String url) {
        if (isYoutubeUrl(url)) {
            return normalizeYoutubeUrl(url);
        }
        if (!looksLikeHtmlPage(url)) {
            return url;
        }

        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(8))
                    .header("User-Agent", "SVVideo/1.0")
                    .header("Accept", "text/html,application/xhtml+xml")
                    .GET()
                    .build();

            HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                return url;
            }

            Matcher matcher = META_MEDIA_PATTERN.matcher(response.body());
            while (matcher.find()) {
                String candidate = htmlDecode(matcher.group(1).trim());
                if (looksLikeDirectMedia(candidate)) {
                    return candidate;
                }
            }
        } catch (Exception e) {
            logWarn("No se pudo resolver URL compartida: {}", url);
        }

        return url;
    }

    private static String resolveMediaTitle(String url) {
        if (!looksLikeHtmlPage(url)) {
            return "";
        }

        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(8))
                    .header("User-Agent", "SVVideo/1.0")
                    .header("Accept", "text/html,application/xhtml+xml")
                    .GET()
                    .build();

            HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                return "";
            }

            Matcher metaMatcher = META_TITLE_PATTERN.matcher(response.body());
            if (metaMatcher.find()) {
                return humanizeLabel(htmlDecode(metaMatcher.group(1).trim()));
            }

            Matcher titleMatcher = HTML_TITLE_PATTERN.matcher(response.body());
            if (titleMatcher.find()) {
                return humanizeLabel(htmlDecode(titleMatcher.group(1).trim()));
            }
        } catch (Exception ignored) {
        }

        return "";
    }

    private static String resolveThumbnailUrl(String url) {
        if (isYoutubeUrl(url)) {
            String channelAvatar = resolveYoutubeChannelAvatar(url);
            if (!channelAvatar.isBlank()) {
                return channelAvatar;
            }
        }

        if (!looksLikeHtmlPage(url)) {
            return "";
        }

        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(8))
                    .header("User-Agent", "SVVideo/1.0")
                    .header("Accept", "text/html,application/xhtml+xml")
                    .GET()
                    .build();

            HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                return "";
            }

            Matcher matcher = Pattern.compile(
                    "<meta[^>]+property=[\"'](?:og:image|twitter:image)[\"'][^>]+content=[\"']([^\"']+)[\"']",
                    Pattern.CASE_INSENSITIVE
            ).matcher(response.body());
            if (matcher.find()) {
                return htmlDecode(matcher.group(1).trim());
            }
        } catch (Exception ignored) {
        }

        return "";
    }

    private static String resolveYoutubeChannelAvatar(String url) {
        try {
            String oembedUrl = "https://www.youtube.com/oembed?format=json&url=" + URI.create(url).toASCIIString();
            HttpRequest request = HttpRequest.newBuilder(URI.create(oembedUrl))
                    .timeout(Duration.ofSeconds(8))
                    .header("User-Agent", "SVVideo/1.0")
                    .header("Accept", "application/json")
                    .GET()
                    .build();

            HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                return "";
            }

            Matcher authorMatcher = OEMBED_AUTHOR_URL_PATTERN.matcher(response.body());
            if (!authorMatcher.find()) {
                return "";
            }

            String authorUrl = htmlDecode(authorMatcher.group(1));
            return resolveSocialImage(authorUrl);
        } catch (Exception ignored) {
            return "";
        }
    }

    private static String resolveSocialImage(String url) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(8))
                    .header("User-Agent", "SVVideo/1.0")
                    .header("Accept", "text/html,application/xhtml+xml")
                    .GET()
                    .build();

            HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                return "";
            }

            Matcher matcher = Pattern.compile(
                    "<meta[^>]+property=[\"'](?:og:image|twitter:image)[\"'][^>]+content=[\"']([^\"']+)[\"']",
                    Pattern.CASE_INSENSITIVE
            ).matcher(response.body());
            if (matcher.find()) {
                return htmlDecode(matcher.group(1).trim());
            }
        } catch (Exception ignored) {
        }

        return "";
    }

    private static void loadThumbnailAsync(String originalUrl, long currentSession) {
        releaseThumbnail();
        currentThumbnailSource = "";
        thumbnailTask = CompletableFuture.runAsync(() -> {
            try {
                String thumbnailUrl = resolveThumbnailUrl(originalUrl);
                if (thumbnailUrl.isBlank()) {
                    return;
                }

                HttpRequest request = HttpRequest.newBuilder(URI.create(thumbnailUrl))
                        .timeout(Duration.ofSeconds(8))
                        .header("User-Agent", "SVVideo/1.0")
                        .GET()
                        .build();
                HttpResponse<byte[]> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofByteArray());
                if (response.statusCode() < 200 || response.statusCode() >= 300) {
                    return;
                }

                NativeImage image = NativeImage.read(new ByteArrayInputStream(response.body()));
                NativeImageBackedTexture texture = new NativeImageBackedTexture(image);
                MinecraftClient.getInstance().execute(() -> {
                    if (currentSession != sessionId) {
                        texture.close();
                        return;
                    }

                    currentThumbnailSource = thumbnailUrl;
                    releaseThumbnail();
                    currentThumbnailImage = texture;
                    currentThumbnailTexture = MinecraftClient.getInstance().getTextureManager()
                            .registerDynamicTexture("svvideo_music_thumb", texture);
                });
            } catch (Exception ignored) {
            }
        });
    }

    private static void releaseThumbnail() {
        if (currentThumbnailImage != null) {
            currentThumbnailImage.close();
            currentThumbnailImage = null;
        }
        currentThumbnailTexture = null;
    }

    private static void setPlaybackState(SVVideoPlaybackState newState, String detail) {
        playbackState = newState;
        playbackDetail = detail == null ? "" : detail;
        if (SVVideoSettings.isDebugEnabled() || newState == SVVideoPlaybackState.FAILED
                || newState == SVVideoPlaybackState.TIMEOUT || newState == SVVideoPlaybackState.RENDER_READY) {
            logInfo("Estado cliente: {} ({})", newState, playbackDetail);
        }
    }

    private static void showStatusNotice(String message, int color) {
        statusNotice = message == null ? "" : message;
        statusNoticeColor = color;
        statusNoticeUntil = System.currentTimeMillis() + STATUS_NOTICE_MS;
    }

    private static void clearStatusNotice() {
        statusNotice = "";
        statusNoticeUntil = 0L;
        statusNoticeColor = 0xFFFF8080;
    }

    private static void notifyPlayer(String message) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client != null && client.player != null && message != null && !message.isBlank()) {
            client.player.sendMessage(Text.literal(message), false);
        }
    }

    private static void logInfo(String message, Object... args) {
        LOGGER.info("[SVVideo] " + message, args);
    }

    private static void logWarn(String message, Object... args) {
        LOGGER.warn("[SVVideo] " + message, args);
    }

    private static void logError(String message, Throwable error) {
        LOGGER.error("[SVVideo] " + message, error);
    }

    private static void captureAndHideHud(MinecraftClient client) {
        if (!hudStateCaptured) {
            previousHudHidden = client.options.hudHidden;
            hudStateCaptured = true;
        }
        client.options.hudHidden = true;
    }

    private static void restoreHud() {
        if (!hudStateCaptured) {
            return;
        }

        MinecraftClient client = MinecraftClient.getInstance();
        client.options.hudHidden = previousHudHidden;
        hudStateCaptured = false;
    }

    private static boolean looksLikeHtmlPage(String url) {
        String lower = url.toLowerCase();
        return lower.contains("tenor.com/view/")
                || lower.contains("giphy.com/gifs/")
                || lower.contains("giphy.com/clips/")
                || lower.contains("giphy.com/embed/")
                || (!looksLikeDirectMedia(lower) && (lower.startsWith("http://") || lower.startsWith("https://")));
    }

    private static boolean looksLikeDirectMedia(String url) {
        String lower = url.toLowerCase();
        return lower.endsWith(".gif")
                || lower.endsWith(".mp4")
                || lower.endsWith(".mp3")
                || lower.endsWith(".wav")
                || lower.endsWith(".ogg")
                || lower.endsWith(".m4a")
                || lower.endsWith(".flac")
                || lower.endsWith(".aac")
                || lower.endsWith(".webm")
                || lower.endsWith(".webp")
                || lower.contains(".gif?")
                || lower.contains(".mp4?")
                || lower.contains(".mp3?")
                || lower.contains(".wav?")
                || lower.contains(".ogg?")
                || lower.contains(".m4a?")
                || lower.contains(".flac?")
                || lower.contains(".aac?")
                || lower.contains(".webm?")
                || lower.contains(".webp?");
    }

    private static String validateSvvideoServerSource(String source) {
        if (!isSvvideoServerHttp(source)) {
            return "";
        }

        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(source))
                    .timeout(Duration.ofSeconds(4))
                    .header("User-Agent", "SVVideo/1.0")
                    .method("HEAD", HttpRequest.BodyPublishers.noBody())
                    .build();
            HttpResponse<Void> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.discarding());
            int status = response.statusCode();
            if ((status >= 200 && status < 400) || status == 405) {
                return "";
            }
            if (status == 404) {
                return "No se encontro el archivo en el servidor de SVVideo: " + mediaLabel(source);
            }
            return "El servidor de archivos de SVVideo respondio HTTP " + status + ".";
        } catch (HttpTimeoutException | ConnectException e) {
            return fileServerConnectionFailure(source);
        } catch (IOException e) {
            return fileServerConnectionFailure(source);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "Se interrumpio la conexion al servidor de archivos de SVVideo.";
        } catch (IllegalArgumentException e) {
            return "URL invalida para el servidor de archivos de SVVideo.";
        }
    }

    private static boolean isSvvideoServerHttp(String source) {
        if (!isRemoteHttp(source)) {
            return false;
        }
        try {
            URI uri = URI.create(source);
            String path = uri.getPath();
            return path != null && path.startsWith("/svvideo/");
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static String fileServerConnectionFailure(String source) {
        int port = serverPort(source);
        String portText = port > 0 ? " en el puerto " + port : "";
        return "No se pudo conectar al servidor de archivos" + portText
                + ". Abre ese puerto en el firewall del servidor o revisa que SVVideo este sirviendo el archivo.";
    }

    private static int serverPort(String source) {
        try {
            URI uri = URI.create(source);
            int port = uri.getPort();
            if (port > 0) {
                return port;
            }
            return "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
        } catch (IllegalArgumentException e) {
            return -1;
        }
    }

    private static String playerFailureMessage(String failure) {
        if (failure.startsWith("No se pudo conectar al servidor de archivos")
                || failure.startsWith("No se encontro el archivo")
                || failure.startsWith("El servidor de archivos")
                || failure.startsWith("URL invalida")
                || failure.startsWith("Se interrumpio la conexion")) {
            return failure;
        }
        return GENERIC_LOAD_FAILURE;
    }

    private static String htmlDecode(String value) {
        return value.replace("&amp;", "&")
                .replace("&#x2F;", "/")
                .replace("&#47;", "/");
    }

    private static String mediaLabel(String url) {
        String clean = url;
        int slash = clean.lastIndexOf('/');
        if (slash >= 0 && slash < clean.length() - 1) {
            clean = clean.substring(slash + 1);
        }
        int query = clean.indexOf('?');
        if (query >= 0) {
            clean = clean.substring(0, query);
        }
        return humanizeLabel(clean.isBlank() ? "Media" : clean);
    }

    private static String mediaTitle(String originalUrl, String playableUrl) {
        String title = resolveMediaTitle(originalUrl);
        if (!title.isBlank()) {
            return title;
        }

        String lowerOriginal = originalUrl.toLowerCase();
        if (lowerOriginal.contains("youtube.com/watch") || lowerOriginal.contains("youtu.be/")) {
            Matcher matcher = YOUTUBE_ID_PATTERN.matcher(originalUrl);
            if (matcher.find()) {
                return "YouTube - " + matcher.group(1);
            }
            return "YouTube";
        }

        return mediaLabel(playableUrl);
    }

    private static String formatTime(long millis) {
        if (millis <= 0) {
            return "00:00";
        }
        long totalSeconds = millis / 1000;
        long minutes = totalSeconds / 60;
        long seconds = totalSeconds % 60;
        return String.format("%02d:%02d", minutes, seconds);
    }

    private static boolean looksLikeBrokenYoutubeUrl(String source) {
        String lower = source.toLowerCase();
        if (!lower.contains("youtube.com/watch") && !lower.contains("youtu.be/")) {
            return false;
        }
        return !YOUTUBE_ID_PATTERN.matcher(source).find();
    }

    private static boolean isYoutubeUrl(String source) {
        return SVVideoYoutubeAccess.isYoutubeUrl(source);
    }

    private static boolean isRemoteHttp(String source) {
        String lower = source.toLowerCase();
        return lower.startsWith("http://") || lower.startsWith("https://");
    }

    private static boolean looksLikeRemoteImage(String source) {
        String lower = source.toLowerCase();
        return lower.endsWith(".gif") || lower.endsWith(".webp") || lower.endsWith(".png")
                || lower.endsWith(".jpg") || lower.endsWith(".jpeg")
                || lower.contains(".gif?") || lower.contains(".webp?")
                || lower.contains(".png?") || lower.contains(".jpg?") || lower.contains(".jpeg?");
    }

    private static boolean looksLikeGifProvider(String source) {
        String lower = source.toLowerCase();
        return lower.contains("tenor.com") || lower.contains("giphy.com");
    }

    private static String humanizeLabel(String text) {
        if (text == null || text.isBlank()) {
            return "Media";
        }

        String clean = URLDecoder.decode(text, StandardCharsets.UTF_8);
        clean = clean.replace('+', ' ');
        clean = clean.replace('_', ' ');
        clean = clean.replace('-', ' ');
        clean = clean.replaceAll("\\.(mp3|mp4|webm|wav|ogg|m4a|flac|mov|mkv|webp|gif)$", "");
        clean = clean.replaceAll("\\s+", " ").trim();
        return clean.isBlank() ? "Media" : clean;
    }

    private static void drawScrollingLabel(DrawContext context, MinecraftClient client, String text,
                                           int x, int y, int maxWidth, int color, long currentMillis) {
        if (text == null || text.isBlank()) {
            return;
        }

        int textWidth = client.textRenderer.getWidth(text);
        if (textWidth <= maxWidth) {
            context.drawTextWithShadow(client.textRenderer, text, x, y, color);
            return;
        }

        String marquee = text + "   \u2022   " + text;
        int marqueeWidth = client.textRenderer.getWidth(marquee);
        int loopWidth = Math.max(1, marqueeWidth / 2);
        int offset = (int) ((currentMillis / 60L) % loopWidth);

        context.enableScissor(x, y, x + maxWidth, y + client.textRenderer.fontHeight + 2);
        context.drawTextWithShadow(client.textRenderer, marquee, x - offset, y, color);
        if (marqueeWidth - offset < maxWidth) {
            context.drawTextWithShadow(client.textRenderer, marquee, x - offset + marqueeWidth, y, color);
        }
        context.disableScissor();
    }

    private static boolean isFullscreen() {
        return "fullscreen".equals(renderPosition);
    }

    private static String normalizePosition(String position) {
        if (position == null) return "fullscreen";
        String pos = position.split(":")[0].trim().toLowerCase();
        return switch (pos) {
            case "fullscreen", "center", "top-center", "top_left", "top-left", "top_right", "top-right",
                 "bottom-left", "bottom_left", "bottom-right", "bottom_right", "actionbar" ->
                    pos.replace('_', '-');
            default -> "fullscreen";
        };
    }

    private static int extractScale(String position) {
        if (position == null) return 100;
        String[] parts = position.split(":");
        if (parts.length < 2) return 100;
        try {
            int scale = Integer.parseInt(parts[1]);
            return Math.max(1, Math.min(500, scale));
        } catch (NumberFormatException e) {
            return 100;
        }
    }

    private static long extractDurationMs(String position) {
        if (position == null) return 0;
        String[] parts = position.split(":");
        if (parts.length < 3) return 0;
        try {
            int seconds = Integer.parseInt(parts[2]);
            return Math.max(0, seconds) * 1000L;
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static RenderBox getOverlayRenderBox(int screenWidth, int screenHeight, int mediaWidth, int mediaHeight,
                                                  String pos, int scale) {
        int margin = 20;
        float aspectRatio = (mediaWidth > 0 && mediaHeight > 0)
                ? (float) mediaWidth / (float) mediaHeight
                : (16.0f / 9.0f);

        float scaleFactor = scale / 100.0f;
        int maxWidth = Math.max(1, Math.round(Math.max(160, screenWidth * 0.35f) * scaleFactor));
        int maxHeight = Math.max(1, Math.round(Math.max(90, screenHeight * 0.28f) * scaleFactor));

        int boxWidth = maxWidth;
        int boxHeight = Math.max(1, Math.round(boxWidth / aspectRatio));

        if (boxHeight > maxHeight) {
            boxHeight = maxHeight;
            boxWidth = Math.max(1, Math.round(boxHeight * aspectRatio));
        }

        int x = (screenWidth - boxWidth) / 2;
        int y = (screenHeight - boxHeight) / 2;

        switch (pos) {
            case "top-center" -> y = margin;
            case "top-left" -> {
                x = margin;
                y = margin;
            }
            case "top-right" -> {
                x = screenWidth - boxWidth - margin;
                y = margin;
            }
            case "bottom-left" -> {
                x = margin;
                y = screenHeight - boxHeight - margin;
            }
            case "bottom-right" -> {
                x = screenWidth - boxWidth - margin;
                y = screenHeight - boxHeight - margin;
            }
            case "actionbar" -> y = screenHeight - boxHeight - 40;
            case "center" -> {
            }
            default -> {
                x = 0;
                y = 0;
                boxWidth = screenWidth;
                boxHeight = screenHeight;
            }
        }

        return new RenderBox(x, y, boxWidth, boxHeight);
    }

    private static String normalizeYoutubeUrl(String url) {
        Matcher matcher = YOUTUBE_ID_PATTERN.matcher(url);
        if (matcher.find()) {
            return "https://www.youtube.com/watch?v=" + matcher.group(1);
        }
        return url;
    }

    private static String cleanUrl(String url) {
        String clean = url.trim();

        if ((clean.startsWith("\"") && clean.endsWith("\""))
                || (clean.startsWith("'") && clean.endsWith("'"))) {
            clean = clean.substring(1, clean.length() - 1);
        }

        return clean;
    }

    private static int clamp(int value) {
        return Math.max(0, Math.min(100, value));
    }

    private record RenderBox(int x, int y, int width, int height) {
    }

    private record PreparedPlayback(long sessionId, String originalSource, String playableSource,
                                    String title, String failureReason) {
        private boolean failed() {
            return failureReason != null && !failureReason.isBlank();
        }
    }

    private static class GifOverlay {
        private SVVideoGifPlayer gifPlayer;
        private CompletableFuture<SVVideoGifPlayer> loadTask;
        final String position;
        final int scale;
        final long durationMs;
        private long startTime = 0;
        private boolean timerStarted = false;

        GifOverlay(CompletableFuture<SVVideoGifPlayer> task, String pos, int sc, long dur) {
            this.loadTask = task;
            this.position = pos;
            this.scale = sc;
            this.durationMs = dur;
        }

        void advance(MinecraftClient client) {
            if (loadTask != null && loadTask.isDone()) {
                try {
                    SVVideoGifPlayer loaded = loadTask.join();
                    loadTask = null;
                    if (loaded != null && !loaded.isEmpty()) {
                        gifPlayer = loaded;
                        gifPlayer.initTextures(client);
                    }
                } catch (Exception e) {
                    loadTask = null;
                }
            }
            if (gifPlayer != null) {
                gifPlayer.tick();
                if (!timerStarted) {
                    startTime = System.currentTimeMillis();
                    timerStarted = true;
                }
            }
        }

        boolean shouldExpire() {

            if (gifPlayer == null && loadTask == null) return true;
            if (durationMs > 0 && timerStarted) {
                return System.currentTimeMillis() - startTime >= durationMs;
            }
            return false;
        }

        boolean isReady() {
            return gifPlayer != null && gifPlayer.isReady();
        }

        Identifier currentIdentifier() {
            return gifPlayer != null ? gifPlayer.currentIdentifier() : null;
        }

        int gifWidth() {
            return gifPlayer != null ? gifPlayer.width() : 0;
        }

        int gifHeight() {
            return gifPlayer != null ? gifPlayer.height() : 0;
        }

        float calculateAlpha() {
            if (durationMs <= 0 || !timerStarted) return 1f;
            long elapsed = System.currentTimeMillis() - startTime;
            long fadeInMs = Math.min(500L, durationMs / 4);
            long fadeOutMs = Math.min(1000L, durationMs / 4);
            long fadeOutStart = durationMs - fadeOutMs;
            if (elapsed < fadeInMs) {
                return (float) elapsed / fadeInMs;
            } else if (elapsed >= fadeOutStart) {
                return Math.max(0f, 1f - (float) (elapsed - fadeOutStart) / fadeOutMs);
            }
            return 1f;
        }

        void release(MinecraftClient client) {
            loadTask = null;
            if (gifPlayer != null) {
                gifPlayer.release(client);
                gifPlayer = null;
            }
        }
    }
}

