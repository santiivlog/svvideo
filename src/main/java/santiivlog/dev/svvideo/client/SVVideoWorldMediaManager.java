package santiivlog.dev.svvideo.client;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.MinecraftClient;
import net.minecraft.sound.SoundCategory;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.watermedia.api.media.MRL;
import org.watermedia.api.media.MediaAPI;
import org.watermedia.api.media.engines.ALEngine;
import org.watermedia.api.media.engines.GLEngine;
import org.watermedia.api.media.players.MediaPlayer;
import santiivlog.dev.svvideo.block.entity.SVVideoSpeakerBlockEntity;
import santiivlog.dev.svvideo.util.SVVideoFiles;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

public final class SVVideoWorldMediaManager {
    private static final Logger LOGGER = LoggerFactory.getLogger(SVVideoWorldMediaManager.class);
    private static final long LOAD_TIMEOUT_MS = 15_000L;
    private static final Map<BlockPos, WorldMediaInstance> INSTANCES = new HashMap<>();

    private SVVideoWorldMediaManager() {
    }

    public static void tick(MinecraftClient client) {
        if (client.world == null) {
            clear();
            return;
        }

        SVVideoLoadingIndicator.tick();

        for (BlockPos pos : new HashSet<>(INSTANCES.keySet())) {
            if (!(client.world.getBlockEntity(pos) instanceof SVVideoSpeakerBlockEntity speaker)) {
                release(pos);
                continue;
            }

            if (speaker.isPaused() || speaker.getMediaSource().isBlank()) {
                release(pos);
                continue;
            }

            WorldMediaInstance instance = getOrCreate(speaker);
            if (instance == null) {
                continue;
            }

            instance.ensurePlayer();
            if (instance.player == null) {
                continue;
            }

            if (instance.handleTerminalState()) {
                continue;
            }

            int blockVolume = 0;
            if (client.player != null) {
                Vec3d center = Vec3d.ofCenter(pos);
                double distance = client.player.getPos().distanceTo(center);
                blockVolume = speaker.computeVolume(distance);
            }

            instance.player.volume(SVVideoSettings.mixVolume(blockVolume, SoundCategory.MUSIC));
        }
    }

    public static WorldMediaInstance getOrCreate(SVVideoSpeakerBlockEntity speaker) {
        BlockPos pos = speaker.getPos().toImmutable();
        if (speaker.isPaused() || speaker.getMediaSource().isBlank()) {
            release(pos);
            return null;
        }

        String source = resolveSource(speaker);
        String key = speaker.getSourceMode() + "|" + source;
        WorldMediaInstance instance = INSTANCES.get(pos);
        if (instance == null || !instance.key.equals(key)) {
            release(pos);
            instance = new WorldMediaInstance(key, source);
            INSTANCES.put(pos, instance);
        }
        instance.ensurePlayer();
        return instance;
    }

    public static void clear() {
        for (BlockPos pos : new HashSet<>(INSTANCES.keySet())) {
            release(pos);
        }
    }

    private static void release(BlockPos pos) {
        WorldMediaInstance instance = INSTANCES.remove(pos);
        if (instance != null) {
            instance.release();
        }
    }

    private static String resolveSource(SVVideoSpeakerBlockEntity speaker) {
        if (!"file".equals(speaker.getSourceMode())) {
            return speaker.getMediaSource();
        }
        SVVideoFiles.ClientMediaResolution resolution =
                SVVideoMediaTransferClient.resolveOrRequest(speaker.getMediaSource());
        if (!resolution.success()) {
            LOGGER.warn("[SVVideo] No se pudo resolver media del bloque {}: {}",
                    speaker.getPos(), resolution.failureReason());
            return "";
        }
        return resolution.resolvedSource();
    }

    public static final class WorldMediaInstance {
        private final String key;
        private final String source;
        private MRL mrl;
        private GLEngine videoEngine;
        private ALEngine audioEngine;
        private MediaPlayer player;
        private CompletableFuture<SVVideoYoutubeAccess.AccessDecision> accessTask;
        private boolean failed;
        private boolean completed;
        private long loadingStartedAt = System.currentTimeMillis();
        private SVVideoPlaybackState playbackState = SVVideoPlaybackState.LOADING;
        private boolean audioReadyLogged;
        private boolean videoReadyLogged;
        private boolean renderReadyLogged;

        private WorldMediaInstance(String key, String source) {
            this.key = key;
            this.source = source;
            if (source == null || source.isBlank()) {
                fail("Fuente vacia para bloque.", null, false);
                return;
            }

            LOGGER.info("[SVVideo] Bloque {} iniciando carga de media: {}", key, source);
            if (SVVideoYoutubeAccess.isYoutubeUrl(source)) {
                this.accessTask = CompletableFuture.supplyAsync(() ->
                        SVVideoYoutubeAccess.checkAnonymousPlayback(source));
            } else {
                this.mrl = MediaAPI.mrl(source);
            }
        }

        public MediaPlayer player() {
            ensurePlayer();
            return player;
        }

        public boolean isLoading() {
            return !failed && !completed && playbackState != SVVideoPlaybackState.RENDER_READY;
        }

        public void fail(String reason, Throwable error, boolean timeout) {
            if (failed || completed) {
                return;
            }
            failed = true;
            playbackState = timeout ? SVVideoPlaybackState.TIMEOUT : SVVideoPlaybackState.FAILED;
            if (error != null) {
                LOGGER.error("[SVVideo] Error en bloque {}: {}", key, reason, error);
            } else if (timeout) {
                LOGGER.warn("[SVVideo] Timeout de carga en bloque {}: {}", key, reason);
            } else {
                LOGGER.warn("[SVVideo] Error en bloque {}: {}", key, reason);
            }
            release();
        }

        private void ensurePlayer() {
            enforceTimeout();
            if (player != null || failed || completed || mrl == null) {
                if (player != null || failed || completed) {
                    return;
                }
            }
            if (!ensureSourceAccess()) {
                return;
            }
            if (mrl == null) {
                mrl = MediaAPI.mrl(source);
            }
            if (SVVideoWaterMediaCompat.isMrlFailed(mrl)) {
                fail(SVVideoWaterMediaCompat.failureMessage(
                        mrl,
                        "WaterMedia marco error al cargar la MRL del bloque."
                ), null, false);
                return;
            }
            if (!SVVideoWaterMediaCompat.isMrlReady(mrl)) {
                return;
            }

            try {
                Executor renderExecutor = task -> RenderSystem.recordRenderCall(task::run);
                player = SVVideoWaterMediaCompat.createPlayer(
                        mrl,
                        () -> videoEngine = MediaAPI.glEngine(Thread.currentThread(), renderExecutor),
                        () -> audioEngine = MediaAPI.alEngine()
                );
                if (player == null) {
                    releaseEngines();
                    fail(createPlayerFailureMessage(), null, false);
                    return;
                }
                player.volume(0);
                player.repeat(false);
                player.start();
                playbackState = SVVideoPlaybackState.LOADING;
                LOGGER.info("[SVVideo] Bloque {} player creado; esperando audio/video/render", key);
            } catch (Exception | LinkageError e) {
                fail("Error creando/iniciando el player del bloque.", e, false);
            }
        }

        private boolean ensureSourceAccess() {
            if (accessTask == null) {
                return true;
            }
            if (!accessTask.isDone()) {
                return false;
            }

            try {
                SVVideoYoutubeAccess.AccessDecision accessDecision = accessTask.join();
                accessTask = null;
                if (!accessDecision.allowed()) {
                    fail(accessDecision.message() + ": " + source, null, false);
                    return false;
                }
                return true;
            } catch (Exception e) {
                accessTask = null;
                fail("Error validando acceso a la fuente del bloque.", e, false);
                return false;
            }
        }

        private boolean handleTerminalState() {
            if (player == null) {
                return failed || completed;
            }
            observeReadiness();
            if (player.error()) {
                fail("WaterMedia reporto ERROR durante la reproduccion del bloque.", null, false);
                return true;
            }
            if (player.ended() || player.stopped()) {
                completed = true;
                playbackState = SVVideoPlaybackState.STOPPED;
                release();
                return true;
            }
            return false;
        }

        private void observeReadiness() {
            if (player == null || failed || completed) {
                return;
            }
            if (!audioReadyLogged && player.withAudio() && player.audioSource() > 0) {
                audioReadyLogged = true;
                playbackState = SVVideoPlaybackState.AUDIO_READY;
                LOGGER.info("[SVVideo] Bloque {} audio cargado", key);
            }
            if (!videoReadyLogged && player.width() > 0 && player.height() > 0) {
                videoReadyLogged = true;
                playbackState = SVVideoPlaybackState.VIDEO_READY;
                LOGGER.info("[SVVideo] Bloque {} video cargado: {}x{}", key, player.width(), player.height());
            }
            if (!renderReadyLogged && player.texture() > 0 && player.width() > 0 && player.height() > 0) {
                renderReadyLogged = true;
                playbackState = SVVideoPlaybackState.RENDER_READY;
                LOGGER.info("[SVVideo] Bloque {} render listo", key);
            }
        }

        private void enforceTimeout() {
            if (failed || completed || playbackState == SVVideoPlaybackState.RENDER_READY) {
                return;
            }
            long elapsed = System.currentTimeMillis() - loadingStartedAt;
            if (elapsed >= LOAD_TIMEOUT_MS) {
                fail("Timeout esperando video/render del bloque tras " + elapsed + "ms.", null, true);
            }
        }

        private void release() {
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
                return;
            }
            releaseEngines();
        }

        private String createPlayerFailureMessage() {
            if (!SVVideoWaterMediaCompat.isNativeBackendLoaded()) {
                return "WaterMedia Binaries no esta instalado o FFmpeg no cargo en el cliente.";
            }
            return "WaterMedia no pudo crear el player del bloque.";
        }

        private void releaseEngines() {
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
    }
}

