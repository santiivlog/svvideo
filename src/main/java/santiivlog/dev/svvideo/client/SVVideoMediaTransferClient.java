package santiivlog.dev.svvideo.client;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import santiivlog.dev.svvideo.network.SVVideoMediaChunkPayload;
import santiivlog.dev.svvideo.network.SVVideoMediaRequestPayload;
import santiivlog.dev.svvideo.util.SVVideoFiles;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class SVVideoMediaTransferClient {
    private static final Logger LOGGER = LoggerFactory.getLogger(SVVideoMediaTransferClient.class);
    private static final ExecutorService IO_EXECUTOR = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "SVVideo Media Transfer");
        thread.setDaemon(true);
        return thread;
    });
    private static final long REQUEST_INTERVAL_MS = 5_000L;
    private static final Map<String, IncomingTransfer> INCOMING = new HashMap<>();
    private static final Map<String, Long> REQUESTED_AT = new ConcurrentHashMap<>();
    private static boolean registered;

    private SVVideoMediaTransferClient() {
    }

    public static void registerReceiver() {
        if (registered) {
            return;
        }
        registered = true;
        ClientPlayNetworking.registerGlobalReceiver(SVVideoMediaChunkPayload.ID,
                (payload, context) -> IO_EXECUTOR.execute(() -> receive(payload)));
    }

    public static SVVideoFiles.ClientMediaResolution resolveOrRequest(String source) {
        SVVideoFiles.ClientMediaResolution resolution = SVVideoFiles.resolveClientMediaSourceDetailed(source);
        if (!resolution.success() && SVVideoFiles.isTransferMediaSource(source)) {
            request(source);
        }
        return resolution;
    }

    public static void request(String source) {
        if (!SVVideoFiles.isTransferMediaSource(source)) {
            return;
        }

        long now = System.currentTimeMillis();
        long lastRequest = REQUESTED_AT.getOrDefault(source, 0L);
        if (now - lastRequest < REQUEST_INTERVAL_MS) {
            return;
        }

        if (!ClientPlayNetworking.canSend(SVVideoMediaRequestPayload.ID)) {
            LOGGER.warn("[SVVideo] No se puede pedir media al servidor: canal {} no registrado.",
                    SVVideoMediaRequestPayload.ID.id());
            return;
        }

        REQUESTED_AT.put(source, now);
        ClientPlayNetworking.send(new SVVideoMediaRequestPayload(source));
    }

    private static void receive(SVVideoMediaChunkPayload payload) {
        SVVideoFiles.TransferMediaSource transfer = SVVideoFiles.parseTransferMediaSource(payload.source());
        if (!transfer.success()
                || !transfer.hash().equalsIgnoreCase(payload.hash())
                || !transfer.relativePath().equals(payload.relativePath())) {
            LOGGER.warn("[SVVideo] Chunk de media invalido: {}", payload.source());
            return;
        }

        IncomingTransfer incoming = INCOMING.computeIfAbsent(payload.source(), ignored -> {
            try {
                return new IncomingTransfer(payload.source(), transfer);
            } catch (IOException e) {
                LOGGER.error("[SVVideo] No se pudo crear cache de transferencia: {}", payload.source(), e);
                return null;
            }
        });

        if (incoming == null) {
            INCOMING.remove(payload.source());
            return;
        }

        try {
            if (incoming.accept(payload)) {
                INCOMING.remove(payload.source());
                REQUESTED_AT.remove(payload.source());
            }
        } catch (Exception e) {
            INCOMING.remove(payload.source());
            incoming.abort();
            LOGGER.error("[SVVideo] Transferencia de media corrupta o incompleta: {}", payload.source(), e);
        }
    }

    private static final class IncomingTransfer {
        private final String source;
        private final SVVideoFiles.TransferMediaSource transfer;
        private final Path cacheFile;
        private final Path tempFile;
        private OutputStream output;
        private int expectedIndex;
        private long receivedBytes;

        private IncomingTransfer(String source, SVVideoFiles.TransferMediaSource transfer) throws IOException {
            this.source = source;
            this.transfer = transfer;
            this.cacheFile = SVVideoFiles.getTransferCacheFile(transfer);
            this.tempFile = SVVideoFiles.getTransferTempFile(transfer);
            if (cacheFile == null || tempFile == null) {
                throw new IOException("Ruta de cache invalida para " + source);
            }
            Files.createDirectories(tempFile.getParent());
        }

        private boolean accept(SVVideoMediaChunkPayload payload) throws IOException {
            if (payload.chunkCount() <= 0 || payload.totalBytes() <= 0) {
                throw new IOException("Metadata de transferencia invalida.");
            }
            if (payload.chunkIndex() != expectedIndex) {
                throw new IOException("Chunk fuera de orden. Esperado "
                        + expectedIndex + ", recibido " + payload.chunkIndex());
            }

            if (output == null) {
                output = Files.newOutputStream(tempFile,
                        StandardOpenOption.CREATE,
                        StandardOpenOption.TRUNCATE_EXISTING,
                        StandardOpenOption.WRITE);
            }

            output.write(payload.data());
            receivedBytes += payload.data().length;
            expectedIndex++;

            if (expectedIndex < payload.chunkCount()) {
                return false;
            }

            closeOutput();
            if (receivedBytes != payload.totalBytes()) {
                throw new IOException("Tamano recibido invalido para " + transfer.relativePath());
            }

            String actualHash = SVVideoFiles.sha256(tempFile);
            if (!transfer.hash().equals(actualHash)) {
                throw new IOException("Hash recibido invalido para " + transfer.relativePath());
            }

            try {
                Files.move(tempFile, cacheFile,
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicMoveFailed) {
                Files.move(tempFile, cacheFile, StandardCopyOption.REPLACE_EXISTING);
            }

            LOGGER.info("[SVVideo] Media recibida desde servidor: {} -> {}", transfer.relativePath(), cacheFile);
            return true;
        }

        private void abort() {
            closeOutput();
            try {
                Files.deleteIfExists(tempFile);
            } catch (IOException ignored) {
            }
        }

        private void closeOutput() {
            if (output != null) {
                try {
                    output.close();
                } catch (IOException ignored) {
                }
                output = null;
            }
        }
    }
}

