package santiivlog.dev.svvideo.network;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

public record SVVideoMediaChunkPayload(
        String source,
        String relativePath,
        String hash,
        long totalBytes,
        int chunkIndex,
        int chunkCount,
        byte[] data
) implements CustomPayload {
    public static final int MAX_CHUNK_SIZE = 24 * 1024;

    public static final Id<SVVideoMediaChunkPayload> ID =
            new Id<>(Identifier.of("svvideo", "media_chunk"));

    public static final PacketCodec<RegistryByteBuf, SVVideoMediaChunkPayload> CODEC =
            PacketCodec.of(
                    (value, buf) -> {
                        buf.writeString(value.source());
                        buf.writeString(value.relativePath());
                        buf.writeString(value.hash());
                        buf.writeLong(value.totalBytes());
                        buf.writeInt(value.chunkIndex());
                        buf.writeInt(value.chunkCount());
                        buf.writeInt(value.data().length);
                        buf.writeBytes(value.data());
                    },
                    buf -> {
                        String source = buf.readString();
                        String relativePath = buf.readString();
                        String hash = buf.readString();
                        long totalBytes = buf.readLong();
                        int chunkIndex = buf.readInt();
                        int chunkCount = buf.readInt();
                        int length = buf.readInt();
                        if (length < 0 || length > MAX_CHUNK_SIZE) {
                            throw new IllegalArgumentException("SVVideo media chunk invalido: " + length);
                        }
                        byte[] data = new byte[length];
                        buf.readBytes(data);
                        return new SVVideoMediaChunkPayload(
                                source, relativePath, hash, totalBytes, chunkIndex, chunkCount, data
                        );
                    }
            );

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }
}

