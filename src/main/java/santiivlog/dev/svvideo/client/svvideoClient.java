package santiivlog.dev.svvideo.client;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientSendMessageEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.BlockEntityRendererRegistry;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import santiivlog.dev.svvideo.block.SVVideoBlocks;
import santiivlog.dev.svvideo.network.SVVideoSpeakerStatePayload;
import santiivlog.dev.svvideo.network.SVvideonetwork;

public class svvideoClient implements ClientModInitializer {
    private static final Logger LOGGER = LoggerFactory.getLogger(svvideoClient.class);

    @Override
    public void onInitializeClient() {
        SVVideoMediaTransferClient.registerReceiver();

        if (!FabricLoader.getInstance().isModLoaded("watermedia")) {
            LOGGER.warn("[SVVideo] WaterMedia no esta instalado en el cliente. Los paquetes de media se ignoraran.");
            registerReceiversWithoutWaterMedia();
            return;
        }

        BlockEntityRendererRegistry.register(SVVideoBlocks.SPEAKER_BLOCK_ENTITY, SVVideoSpeakerBlockEntityRenderer::new);
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            SVVideoWorldMediaManager.tick(client);
            VideoRenderer.tickClient();
        });

        ClientPlayNetworking.registerGlobalReceiver(SVvideonetwork.ID, (payload, context) -> {
            context.client().execute(() -> {
                if (!"VOLUME".equals(payload.action())) {
                    LOGGER.info("[SVVideo] Recibido paquete de reproduccion en cliente: action={} source={} position={}",
                            payload.action(), payload.url(), payload.position());
                } else if (SVVideoSettings.isDebugEnabled()) {
                    LOGGER.info("[SVVideo] Recibido paquete de volumen en cliente: {}", payload.volume());
                }
                VideoRenderer.setCanChat(payload.canChat());

                switch (payload.action()) {
                    case "PLAY" -> VideoRenderer.play(payload.url(), payload.volume());
                    case "MUSIC" -> VideoRenderer.playMusic(payload.url(), payload.volume());
                    case "STOP" -> VideoRenderer.stop();
                    case "VOLUME" -> VideoRenderer.setVolume(payload.volume());
                    case "FILE" -> VideoRenderer.playFile(payload.url(), payload.volume());
                    case "GIF_URL" -> VideoRenderer.playOverlay(payload.url(), payload.volume(), payload.position());
                    case "GIF_FILE" -> VideoRenderer.playOverlayFile(payload.url(), payload.volume(), payload.position());
                    case "MUSIC_FILE" -> VideoRenderer.playMusicFile(payload.url(), payload.volume());
                    case "VANILLA_SOUNDS" -> SVVideoSettings.setVanillaMinecraftSoundsEnabled(payload.volume() > 0);
                    case "HIDE_MUSIC_HUD" -> VideoRenderer.setMusicHudVisible(payload.volume() == 0);
                }
            });
        });

        ClientPlayNetworking.registerGlobalReceiver(SVVideoSpeakerStatePayload.ID, (payload, context) ->
                context.client().execute(() -> SVVideoSpeakerScreen.open(payload)));

        ClientSendMessageEvents.ALLOW_CHAT.register(message -> !VideoRenderer.shouldBlockChat());
        ClientSendMessageEvents.ALLOW_COMMAND.register(command -> !VideoRenderer.shouldBlockChat());
    }

    private void registerReceiversWithoutWaterMedia() {
        ClientPlayNetworking.registerGlobalReceiver(SVvideonetwork.ID, (payload, context) ->
                context.client().execute(() -> {
                    switch (payload.action()) {
                        case "VANILLA_SOUNDS" -> SVVideoSettings.setVanillaMinecraftSoundsEnabled(payload.volume() > 0);
                        case "HIDE_MUSIC_HUD" -> {
                        }
                        default -> notifyMissingWaterMedia();
                    }
                }));

        ClientPlayNetworking.registerGlobalReceiver(SVVideoSpeakerStatePayload.ID, (payload, context) ->
                context.client().execute(() -> {
                    notifyMissingWaterMedia();
                    SVVideoSpeakerScreen.open(payload);
                }));
    }

    private static void notifyMissingWaterMedia() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client != null && client.player != null) {
            client.player.sendMessage(Text.literal(
                    "SVVideo requiere WaterMedia (y WaterMedia Binaries) instalados en el cliente para "
                            + "reproducir videos, musica y GIFs. WaterMedia 3 separa los binarios de FFmpeg "
                            + "en el mod 'WaterMedia Binaries'; instala ambos."
            ), false);
        }
    }
}

