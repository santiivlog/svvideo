package santiivlog.dev.svvideo.network;

import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import santiivlog.dev.svvideo.block.entity.SVVideoSpeakerBlockEntity;
import santiivlog.dev.svvideo.util.SVVideoFiles;

public record SVVideoSpeakerActionPayload(
        long blockPos,
        String action,
        String mode,
        String source,
        int volume,
        int minDistance,
        int maxDistance,
        int displayWidth,
        int displayHeight,
        int renderQuality
) implements CustomPayload {
    private static final Logger LOGGER = LoggerFactory.getLogger(SVVideoSpeakerActionPayload.class);

    public static final Id<SVVideoSpeakerActionPayload> ID =
            new Id<>(Identifier.of("svvideo", "speaker_action"));

    public static final PacketCodec<RegistryByteBuf, SVVideoSpeakerActionPayload> CODEC =
            PacketCodec.of(
                    (value, buf) -> {
                        buf.writeLong(value.blockPos());
                        buf.writeString(value.action());
                        buf.writeString(value.mode());
                        buf.writeString(value.source());
                        buf.writeInt(value.volume());
                        buf.writeInt(value.minDistance());
                        buf.writeInt(value.maxDistance());
                        buf.writeInt(value.displayWidth());
                        buf.writeInt(value.displayHeight());
                        buf.writeInt(value.renderQuality());
                    },
                    buf -> new SVVideoSpeakerActionPayload(
                            buf.readLong(),
                            buf.readString(),
                            buf.readString(),
                            buf.readString(),
                            buf.readInt(),
                            buf.readInt(),
                            buf.readInt(),
                            buf.readInt(),
                            buf.readInt(),
                            buf.readInt()
                    )
            );

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }

    public static void registerReceiver() {
        ServerPlayNetworking.registerGlobalReceiver(ID, (payload, context) ->
                context.server().execute(() -> {
                    ServerPlayerEntity player = context.player();
                    if (!player.hasPermissionLevel(2)) {
                        LOGGER.warn("[SVVideo] {} intento modificar un bloque sin permisos.",
                                player.getGameProfile().getName());
                        return;
                    }

                    BlockPos pos = BlockPos.fromLong(payload.blockPos());
                    BlockEntity blockEntity = player.getWorld().getBlockEntity(pos);
                    if (!(blockEntity instanceof SVVideoSpeakerBlockEntity speaker)) {
                        player.sendMessage(Text.literal("No hay un bloque SVVideo en esas coordenadas."), false);
                        LOGGER.warn("[SVVideo] {} envio accion {} para bloque invalido en {}.",
                                player.getGameProfile().getName(), payload.action(), pos.toShortString());
                        return;
                    }

                    boolean fileMode = "file".equals(payload.mode());
                    String source = payload.source().trim();
                    if (fileMode) {
                        source = SVVideoFiles.createServerMediaSource(source);
                        if (source.isBlank()) {
                            player.sendMessage(Text.literal("Archivo no encontrado en /svvideo del servidor."), false);
                            LOGGER.warn("[SVVideo] {} pidio archivo inexistente para bloque {}: {}",
                                    player.getGameProfile().getName(), pos.toShortString(), payload.source());
                            return;
                        }
                    } else if (source.isBlank()) {
                        player.sendMessage(Text.literal("La fuente de SVVideo no puede estar vacia."), false);
                        return;
                    }

                    LOGGER.info("[SVVideo] Recibida accion de bloque desde {}: action={} mode={} source={} pos={}",
                            player.getGameProfile().getName(), payload.action(), payload.mode(), source, pos.toShortString());

                    if (fileMode) {
                        SVVideoMediaTransfer.enqueue(player, source, null);
                    }

                    speaker.setSourceMode(fileMode ? "file" : "url");
                    speaker.setMediaSource(source);
                    speaker.setVolume(payload.volume());
                    speaker.setMinDistance(payload.minDistance());
                    speaker.setMaxDistance(Math.max(payload.minDistance() + 1, payload.maxDistance()));
                    speaker.setDisplayWidth(payload.displayWidth());
                    speaker.setDisplayHeight(payload.displayHeight());
                    speaker.setRenderQuality(payload.renderQuality());
                    speaker.markDirty();
                    player.getServerWorld().getChunkManager().markForUpdate(pos);

                    switch (payload.action()) {
                        case "reload" -> speaker.requestReload();
                        case "reload_all" -> speaker.requestReloadAll(player.getServerWorld());
                        case "pause" -> speaker.togglePaused(player.getServerWorld());
                        default -> {
                        }
                    }
                })
        );
    }
}

