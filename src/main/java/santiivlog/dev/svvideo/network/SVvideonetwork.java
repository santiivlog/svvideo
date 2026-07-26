package santiivlog.dev.svvideo.network;

import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import santiivlog.dev.svvideo.util.SVVideoFiles;

public record SVvideonetwork(String action, String url, int volume, boolean canChat, String position) implements CustomPayload {
    private static final Logger LOGGER = LoggerFactory.getLogger(SVvideonetwork.class);

    public static final Id<SVvideonetwork> ID =
            new Id<>(Identifier.of("svvideo", "play_video"));

    public static final PacketCodec<RegistryByteBuf, SVvideonetwork> CODEC =
            PacketCodec.of(
                    (value, buf) -> {
                        buf.writeString(value.action());
                        buf.writeString(value.url());
                        buf.writeInt(value.volume());
                        buf.writeBoolean(value.canChat());
                        buf.writeString(value.position());
                    },
                    buf -> new SVvideonetwork(
                            buf.readString(),
                            buf.readString(),
                            buf.readInt(),
                            buf.readBoolean(),
                            buf.readString()
                    )
            );

    @Override
    public CustomPayload.Id<? extends CustomPayload> getId() {
        return ID;
    }

    public static void sendPlay(ServerPlayerEntity player, String url, int volume) {
        sendPayload(player, new SVvideonetwork(
                "PLAY",
                url,
                volume,
                player.hasPermissionLevel(2),
                "fullscreen"
        ));
    }

    public static void sendVideo(ServerPlayerEntity player, String url, int volume) {
        sendPlay(player, url, volume);
    }

    public static void sendFile(ServerPlayerEntity player, String fileName, int volume) {
        sendMediaPayload(player, new SVvideonetwork(
                "FILE",
                fileName,
                volume,
                player.hasPermissionLevel(2),
                "fullscreen"
        ));
    }

    public static void sendGifUrl(ServerPlayerEntity player, String url, int volume, String position) {
        sendGifUrl(player, url, volume, position, 100, 0);
    }

    public static void sendGifUrl(ServerPlayerEntity player, String url, int volume, String position, int scale) {
        sendGifUrl(player, url, volume, position, scale, 0);
    }

    public static void sendGifUrl(ServerPlayerEntity player, String url, int volume, String position, int scale, int durationSeconds) {
        sendPayload(player, new SVvideonetwork(
                "GIF_URL", url, volume, player.hasPermissionLevel(2), buildPositionEncoded(position, scale, durationSeconds)
        ));
    }

    public static void sendGifFile(ServerPlayerEntity player, String fileName, int volume, String position) {
        sendGifFile(player, fileName, volume, position, 100, 0);
    }

    public static void sendGifFile(ServerPlayerEntity player, String fileName, int volume, String position, int scale) {
        sendGifFile(player, fileName, volume, position, scale, 0);
    }

    public static void sendGifFile(ServerPlayerEntity player, String fileName, int volume, String position, int scale, int durationSeconds) {
        sendMediaPayload(player, new SVvideonetwork(
                "GIF_FILE", fileName, volume, player.hasPermissionLevel(2), buildPositionEncoded(position, scale, durationSeconds)
        ));
    }

    private static String buildPositionEncoded(String position, int scale, int durationSeconds) {
        if (durationSeconds > 0) {
            return position + ":" + scale + ":" + durationSeconds;
        }
        return scale == 100 ? position : position + ":" + scale;
    }

    public static void sendMusic(ServerPlayerEntity player, String url, int volume) {
        sendPayload(player, new SVvideonetwork(
                "MUSIC",
                url,
                volume,
                player.hasPermissionLevel(2),
                "fullscreen"
        ));
    }

    public static void sendMusicFile(ServerPlayerEntity player, String fileName, int volume) {
        sendMediaPayload(player, new SVvideonetwork(
                "MUSIC_FILE",
                fileName,
                volume,
                player.hasPermissionLevel(2),
                "fullscreen"
        ));
    }

    private static void sendMediaPayload(ServerPlayerEntity player, SVvideonetwork payload) {
        if (SVVideoFiles.isTransferMediaSource(payload.url())) {
            boolean queued = SVVideoMediaTransfer.enqueue(player, payload.url(), () -> sendPayload(player, payload));
            if (!queued) {
                LOGGER.warn("[SVVideo] No se envio {} a {} porque fallo la transferencia de media: {}",
                        payload.action(), player.getGameProfile().getName(), payload.url());
            }
            return;
        }

        sendPayload(player, payload);
    }

    public static void sendStop(ServerPlayerEntity player) {
        sendPayload(player, new SVvideonetwork(
                "STOP",
                "",
                0,
                player.hasPermissionLevel(2),
                "fullscreen"
        ));
    }

    public static void sendVolume(ServerPlayerEntity player, int volume) {
        sendPayload(player, new SVvideonetwork(
                "VOLUME",
                "",
                volume,
                player.hasPermissionLevel(2),
                "fullscreen"
        ));
    }

    public static void sendVanillaSoundsEnabled(ServerPlayerEntity player, boolean enabled) {
        sendPayload(player, new SVvideonetwork(
                "VANILLA_SOUNDS",
                "",
                enabled ? 1 : 0,
                player.hasPermissionLevel(2),
                "fullscreen"
        ));
    }

    public static void sendHideMusicHud(ServerPlayerEntity player, boolean hidden) {
        sendPayload(player, new SVvideonetwork(
                "HIDE_MUSIC_HUD",
                "",
                hidden ? 1 : 0,
                player.hasPermissionLevel(2),
                "fullscreen"
        ));
    }

    private static void sendPayload(ServerPlayerEntity player, SVvideonetwork payload) {
        if (!ServerPlayNetworking.canSend(player, ID)) {
            LOGGER.warn("[SVVideo] No se envio {} a {}: el cliente no registro el canal {}.",
                    payload.action(), player.getGameProfile().getName(), ID.id());
            return;
        }

        ServerPlayNetworking.send(player, payload);
    }
}
