package santiivlog.dev.svvideo.network;

import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import santiivlog.dev.svvideo.block.entity.SVVideoSpeakerBlockEntity;

public record SVVideoSpeakerStatePayload(
        long blockPos,
        String mode,
        String source,
        int volume,
        int minDistance,
        int maxDistance,
        int displayWidth,
        int displayHeight,
        int renderQuality,
        boolean paused
) implements CustomPayload {
    private static final Logger LOGGER = LoggerFactory.getLogger(SVVideoSpeakerStatePayload.class);

    public static final Id<SVVideoSpeakerStatePayload> ID =
            new Id<>(Identifier.of("svvideo", "speaker_state"));

    public static final PacketCodec<RegistryByteBuf, SVVideoSpeakerStatePayload> CODEC =
            PacketCodec.of(
                    (value, buf) -> {
                        buf.writeLong(value.blockPos());
                        buf.writeString(value.mode());
                        buf.writeString(value.source());
                        buf.writeInt(value.volume());
                        buf.writeInt(value.minDistance());
                        buf.writeInt(value.maxDistance());
                        buf.writeInt(value.displayWidth());
                        buf.writeInt(value.displayHeight());
                        buf.writeInt(value.renderQuality());
                        buf.writeBoolean(value.paused());
                    },
                    buf -> new SVVideoSpeakerStatePayload(
                            buf.readLong(),
                            buf.readString(),
                            buf.readString(),
                            buf.readInt(),
                            buf.readInt(),
                            buf.readInt(),
                            buf.readInt(),
                            buf.readInt(),
                            buf.readInt(),
                            buf.readBoolean()
                    )
            );

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }

    public static void send(ServerPlayerEntity player, SVVideoSpeakerBlockEntity blockEntity) {
        if (!ServerPlayNetworking.canSend(player, ID)) {
            LOGGER.warn("[SVVideo] No se envio estado de bloque a {}: el cliente no registro el canal {}.",
                    player.getGameProfile().getName(), ID.id());
            return;
        }

        ServerPlayNetworking.send(player, new SVVideoSpeakerStatePayload(
                blockEntity.getPos().asLong(),
                blockEntity.getSourceMode(),
                blockEntity.getMediaSource(),
                blockEntity.getVolume(),
                blockEntity.getMinDistance(),
                blockEntity.getMaxDistance(),
                blockEntity.getDisplayWidth(),
                blockEntity.getDisplayHeight(),
                blockEntity.getRenderQuality(),
                blockEntity.isPaused()
        ));
    }
}

