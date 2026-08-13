package santiivlog.dev.svvideo.network;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import santiivlog.dev.svvideo.util.SVVideoFiles;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

public final class SVVideoMediaTransfer {
    private static final Logger LOGGER = LoggerFactory.getLogger(SVVideoMediaTransfer.class);
    private static final int CHUNKS_PER_PLAYER_TICK = 4;
    private static final Map<UUID, ArrayDeque<PendingTransfer>> PENDING = new HashMap<>();
    private static boolean tickRegistered;

    private SVVideoMediaTransfer() {
    }

    public static void registerReceiver() {
        ServerPlayNetworking.registerGlobalReceiver(SVVideoMediaRequestPayload.ID, (payload, context) ->
                context.server().execute(() -> enqueue(context.player(), payload.source(), null)));

        if (!tickRegistered) {
            tickRegistered = true;
            ServerTickEvents.END_SERVER_TICK.register(SVVideoMediaTransfer::tick);
        }
    }

    public static boolean enqueue(ServerPlayerEntity player, String source, Runnable onComplete) {
        if (!SVVideoFiles.isTransferMediaSource(source)) {
            if (onComplete != null) {
                onComplete.run();
            }
            return true;
        }

        if (!ServerPlayNetworking.canSend(player, SVVideoMediaChunkPayload.ID)) {
            LOGGER.warn("[SVVideo] No se puede transferir media a {}: el cliente no registro {}.",
                    player.getGameProfile().getName(), SVVideoMediaChunkPayload.ID.id());
            return false;
        }

        SVVideoFiles.TransferMediaSource transfer = SVVideoFiles.parseTransferMediaSource(source);
        Path file = SVVideoFiles.resolveServerTransferFile(source);
        if (!transfer.success() || file == null) {
            LOGGER.warn("[SVVideo] Transferencia invalida o archivo no encontrado: {}", source);
            return false;
        }

        try {
            long totalBytes = Files.size(file);
            if (totalBytes <= 0) {
                LOGGER.warn("[SVVideo] No se transfiere archivo vacio: {}", transfer.relativePath());
                return false;
            }

            PendingTransfer pending = new PendingTransfer(
                    source,
                    transfer.relativePath(),
                    transfer.hash(),
                    file,
                    totalBytes,
                    Math.toIntExact((totalBytes + SVVideoMediaChunkPayload.MAX_CHUNK_SIZE - 1)
                            / SVVideoMediaChunkPayload.MAX_CHUNK_SIZE),
                    onComplete
            );
            PENDING.computeIfAbsent(player.getUuid(), ignored -> new ArrayDeque<>()).add(pending);
            LOGGER.info("[SVVideo] Transferencia programada para {}: {} ({} bytes)",
                    player.getGameProfile().getName(), transfer.relativePath(), totalBytes);
            return true;
        } catch (Exception e) {
            LOGGER.error("[SVVideo] No se pudo preparar transferencia: {}", source, e);
            return false;
        }
    }

    private static void tick(MinecraftServer server) {
        Iterator<Map.Entry<UUID, ArrayDeque<PendingTransfer>>> iterator = PENDING.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<UUID, ArrayDeque<PendingTransfer>> entry = iterator.next();
            ServerPlayerEntity player = server.getPlayerManager().getPlayer(entry.getKey());
            if (player == null) {
                closeAll(entry.getValue());
                iterator.remove();
                continue;
            }

            ArrayDeque<PendingTransfer> queue = entry.getValue();
            int sentThisTick = 0;
            while (sentThisTick < CHUNKS_PER_PLAYER_TICK && !queue.isEmpty()) {
                PendingTransfer transfer = queue.peek();
                try {
                    boolean done = transfer.sendNext(player);
                    sentThisTick++;
                    if (done) {
                        queue.remove();
                        transfer.close();
                        LOGGER.info("[SVVideo] Transferencia completada para {}: {}",
                                player.getGameProfile().getName(), transfer.relativePath);
                        if (transfer.onComplete != null) {
                            transfer.onComplete.run();
                        }
                    }
                } catch (Exception e) {
                    queue.remove();
                    transfer.close();
                    LOGGER.error("[SVVideo] Transferencia fallida para {}: {}",
                            player.getGameProfile().getName(), transfer.relativePath, e);
                }
            }

            if (queue.isEmpty()) {
                iterator.remove();
            }
        }
    }

    private static void closeAll(ArrayDeque<PendingTransfer> queue) {
        for (PendingTransfer transfer : queue) {
            transfer.close();
        }
    }

    private static final class PendingTransfer {
        private final String source;
        private final String relativePath;
        private final String hash;
        private final Path file;
        private final long totalBytes;
        private final int chunkCount;
        private final Runnable onComplete;
        private InputStream input;
        private int chunkIndex;

        private PendingTransfer(String source, String relativePath, String hash, Path file,
                                long totalBytes, int chunkCount, Runnable onComplete) {
            this.source = source;
            this.relativePath = relativePath;
            this.hash = hash;
            this.file = file;
            this.totalBytes = totalBytes;
            this.chunkCount = chunkCount;
            this.onComplete = onComplete;
        }

        private boolean sendNext(ServerPlayerEntity player) throws IOException {
            if (input == null) {
                input = Files.newInputStream(file);
            }

            byte[] data = input.readNBytes(SVVideoMediaChunkPayload.MAX_CHUNK_SIZE);
            if (data.length == 0) {
                return true;
            }

            ServerPlayNetworking.send(player, new SVVideoMediaChunkPayload(
                    source,
                    relativePath,
                    hash,
                    totalBytes,
                    chunkIndex,
                    chunkCount,
                    data
            ));
            chunkIndex++;
            return chunkIndex >= chunkCount;
        }

        private void close() {
            if (input != null) {
                try {
                    input.close();
                } catch (IOException ignored) {
                }
                input = null;
            }
        }
    }
}

