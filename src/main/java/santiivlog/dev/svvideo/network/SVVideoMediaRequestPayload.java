package santiivlog.dev.svvideo.network;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

public record SVVideoMediaRequestPayload(String source) implements CustomPayload {
    public static final Id<SVVideoMediaRequestPayload> ID =
            new Id<>(Identifier.of("svvideo", "media_request"));

    public static final PacketCodec<RegistryByteBuf, SVVideoMediaRequestPayload> CODEC =
            PacketCodec.of(
                    (value, buf) -> buf.writeString(value.source()),
                    buf -> new SVVideoMediaRequestPayload(buf.readString())
            );

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }
}

