package santiivlog.dev.svvideo.client;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ServerInfo;

public final class SVVideoClientHelper {

    private SVVideoClientHelper() {}

    public static String getServerHost() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null) return "";

        if (client.getServer() != null) return "127.0.0.1";

        ServerInfo info = client.getCurrentServerEntry();
        if (info == null || info.address == null || info.address.isBlank()) return "";

        return parseHost(info.address.trim());
    }

    private static String parseHost(String address) {

        if (address.startsWith("[")) {
            int end = address.indexOf(']');
            return end > 0 ? address.substring(1, end) : address;
        }

        int colon = address.lastIndexOf(':');
        return colon > 0 ? address.substring(0, colon) : address;
    }
}

