package santiivlog.dev.svvideo.util;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.concurrent.Executors;

public class SVVideoFiles {
    private static final String REMOTE_SCHEME = "svvideo-server";
    private static final String TRANSFER_SCHEME = "svvideo-transfer";
    private static final String ASSET_SCHEME = "svvideo-asset";
    public static final String FOLDER_NAME = "svvideo";
    private static final String MOD_ASSET_FOLDER_NAME = "svvideo";
    private static final String LEGACY_ASSET_FOLDER_NAME = "svvideoforge";
    private static final String GIF_FOLDER_NAME = "gif";
    private static final String LOADING_ASSET_NAME = "loading.gif";
    private static final String LOADING_CACHE_RELATIVE_PATH = "cache/assets/" + LOADING_ASSET_NAME;
    private static final String TRANSFER_CACHE_FOLDER = "cache/server";
    private static final HttpClient REMOTE_MEDIA_CLIENT = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    private static final int DEFAULT_FILE_SERVER_PORT = 19400;
    private static volatile HttpServer fileServer;
    private static volatile int fileServerPort = -1;

    public record ClientMediaResolution(String inputSource, String resolvedSource, String failureReason) {
        public boolean success() {
            return resolvedSource != null && !resolvedSource.isBlank();
        }
    }

    public record AssetMediaSource(String namespace, String resourcePath, String failureReason) {
        public boolean success() {
            return namespace != null && !namespace.isBlank()
                    && resourcePath != null && !resourcePath.isBlank();
        }
    }

    public record TransferMediaSource(String hash, String relativePath, String failureReason) {
        public boolean success() {
            return isValidTransferHash(hash) && relativePath != null && !relativePath.isBlank();
        }
    }

    public static Path getFolder() {
        return FabricLoader.getInstance().getGameDir().resolve(FOLDER_NAME);
    }

    public static Path getLoadingAssetFolder() {
        return FabricLoader.getInstance().getGameDir()
                .resolve("assets")
                .resolve(MOD_ASSET_FOLDER_NAME)
                .resolve(GIF_FOLDER_NAME);
    }

    private static Path getLegacyLoadingAssetFolder() {
        return FabricLoader.getInstance().getGameDir()
                .resolve("assets")
                .resolve(LEGACY_ASSET_FOLDER_NAME)
                .resolve(GIF_FOLDER_NAME);
    }

    public static Path getLoadingAssetPath() {
        return getLoadingAssetFolder().resolve(LOADING_ASSET_NAME);
    }

    public static void createFolder() {
        try {
            Files.createDirectories(getFolder());
            Files.createDirectories(getLoadingAssetFolder());
        } catch (IOException e) {
            System.err.println("[SVVideo] No se pudo crear la carpeta svvideo");
            e.printStackTrace();
        }
    }

    public static Path getFile(String fileName) {
        return getFolder().resolve(fileName).normalize();
    }

    public static Path findLoadingAsset() {
        Path asset = getLoadingAssetPath().toAbsolutePath().normalize();
        if (Files.isRegularFile(asset)) {
            return asset;
        }

        Path legacyAsset = getLegacyLoadingAssetFolder().resolve(LOADING_ASSET_NAME).toAbsolutePath().normalize();
        if (Files.isRegularFile(legacyAsset)) {
            return legacyAsset;
        }

        return null;
    }

    public static String resolveLoadingAssetMediaSource(Path assetPath) {
        if (assetPath == null) {
            return "";
        }

        try {
            createFolder();
            Path normalizedSource = assetPath.toAbsolutePath().normalize();
            Path cachedAsset = getFolder().resolve(LOADING_CACHE_RELATIVE_PATH).toAbsolutePath().normalize();
            Files.createDirectories(cachedAsset.getParent());
            if (!normalizedSource.equals(cachedAsset) || !Files.isRegularFile(cachedAsset)) {
                Files.copy(normalizedSource, cachedAsset, StandardCopyOption.REPLACE_EXISTING);
            }

            return cachedAsset.toUri().toString();
        } catch (IOException e) {
            return "";
        }
    }

    public static boolean isPathInsideFolder(Path path) {
        Path folder = getFolder().toAbsolutePath().normalize();
        Path normalized = path.toAbsolutePath().normalize();
        return normalized.startsWith(folder);
    }

    public static String createServerMediaSource(String fileName) {
        Path resolved = getFile(fileName).toAbsolutePath().normalize();
        if (!isPathInsideFolder(resolved) || !Files.isRegularFile(resolved)) {
            return "";
        }

        try {
            String relative = getFolder().toAbsolutePath().normalize().relativize(resolved)
                    .toString()
                    .replace('\\', '/');
            return TRANSFER_SCHEME + "://" + sha256(resolved) + "/" + encodePath(relative);
        } catch (IOException e) {
            return "";
        }
    }

    public static String createGifAssetMediaSource(String fileName) {
        String resourcePath = normalizeGifAssetPath(fileName);
        if (resourcePath.isBlank()) {
            return "";
        }
        return ASSET_SCHEME + "://" + MOD_ASSET_FOLDER_NAME + "/" + encodePath(resourcePath);
    }

    public static boolean isAssetMediaSource(String source) {
        return source != null && source.trim().startsWith(ASSET_SCHEME + "://");
    }

    public static boolean isTransferMediaSource(String source) {
        return source != null && source.trim().startsWith(TRANSFER_SCHEME + "://");
    }

    public static AssetMediaSource parseAssetMediaSource(String source) {
        String cleaned = source == null ? "" : source.trim();
        if (!isAssetMediaSource(cleaned)) {
            return new AssetMediaSource("", "", "La fuente no es un asset de SVVideo.");
        }

        try {
            URI uri = URI.create(cleaned);
            String namespace = uri.getHost();
            String rawPath = uri.getRawPath();
            if (namespace == null || namespace.isBlank() || rawPath == null || rawPath.isBlank()) {
                return new AssetMediaSource("", "", "Asset SVVideo invalido.");
            }

            String resourcePath = rawPath.startsWith("/") ? rawPath.substring(1) : rawPath;
            resourcePath = normalizeAssetPath(URLDecoder.decode(resourcePath, StandardCharsets.UTF_8));
            if (resourcePath.isBlank()) {
                return new AssetMediaSource("", "", "Ruta de asset SVVideo invalida.");
            }

            return new AssetMediaSource(namespace, resourcePath, "");
        } catch (Exception e) {
            return new AssetMediaSource("", "", "URL de asset SVVideo invalida.");
        }
    }

    public static TransferMediaSource parseTransferMediaSource(String source) {
        String cleaned = source == null ? "" : source.trim();
        if (!isTransferMediaSource(cleaned)) {
            return new TransferMediaSource("", "", "La fuente no es una transferencia de SVVideo.");
        }

        try {
            URI uri = URI.create(cleaned);
            String hash = uri.getHost();
            String rawPath = uri.getRawPath();
            if (!isValidTransferHash(hash) || rawPath == null || rawPath.isBlank()) {
                return new TransferMediaSource("", "", "Transferencia SVVideo invalida.");
            }

            String relativePath = rawPath.startsWith("/") ? rawPath.substring(1) : rawPath;
            relativePath = normalizeAssetPath(URLDecoder.decode(relativePath, StandardCharsets.UTF_8));
            if (relativePath.isBlank()) {
                return new TransferMediaSource("", "", "Ruta de transferencia SVVideo invalida.");
            }

            return new TransferMediaSource(hash.toLowerCase(), relativePath, "");
        } catch (Exception e) {
            return new TransferMediaSource("", "", "URL de transferencia SVVideo invalida.");
        }
    }

    public static Path resolveServerTransferFile(String source) {
        TransferMediaSource transfer = parseTransferMediaSource(source);
        if (!transfer.success()) {
            return null;
        }

        Path file = getFile(transfer.relativePath()).toAbsolutePath().normalize();
        if (!isPathInsideFolder(file) || !Files.isRegularFile(file)) {
            return null;
        }

        try {
            return transfer.hash().equals(sha256(file)) ? file : null;
        } catch (IOException e) {
            return null;
        }
    }

    public static Path getTransferCacheFile(String source) {
        TransferMediaSource transfer = parseTransferMediaSource(source);
        return transfer.success() ? getTransferCacheFile(transfer) : null;
    }

    public static Path getTransferCacheFile(TransferMediaSource transfer) {
        Path cacheRoot = getFolder().resolve(TRANSFER_CACHE_FOLDER).toAbsolutePath().normalize();
        Path file = cacheRoot
                .resolve(transfer.hash().toLowerCase())
                .resolve(cacheFileName(transfer.relativePath()))
                .toAbsolutePath()
                .normalize();
        return file.startsWith(cacheRoot) ? file : null;
    }

    public static Path getTransferTempFile(TransferMediaSource transfer) {
        Path cacheFile = getTransferCacheFile(transfer);
        if (cacheFile == null) {
            return null;
        }
        return cacheFile.resolveSibling(cacheFile.getFileName() + ".part");
    }

    public static Path findExternalAssetFile(String namespace, String resourcePath) {
        String normalizedPath = normalizeAssetPath(resourcePath);
        String normalizedNamespace = namespace == null ? "" : namespace.trim();
        if (normalizedNamespace.isBlank() || normalizedPath.isBlank()) {
            return null;
        }

        Path base = FabricLoader.getInstance().getGameDir()
                .resolve("assets")
                .resolve(normalizedNamespace)
                .toAbsolutePath()
                .normalize();
        Path file = base.resolve(normalizedPath).toAbsolutePath().normalize();
        return file.startsWith(base) ? file : null;
    }

    public static String displayNameForSource(String source) {
        String cleaned = source == null ? "" : source.trim();
        if (isAssetMediaSource(cleaned)) {
            AssetMediaSource asset = parseAssetMediaSource(cleaned);
            return asset.success() ? asset.resourcePath() : cleaned;
        }

        if (isTransferMediaSource(cleaned)) {
            TransferMediaSource transfer = parseTransferMediaSource(cleaned);
            return transfer.success() ? transfer.relativePath() : cleaned;
        }

        if (!cleaned.startsWith(REMOTE_SCHEME + "://")) {
            return cleaned;
        }

        try {
            URI uri = URI.create(cleaned);
            String path = uri.getRawPath();
            if (path == null || path.isBlank()) {
                return "";
            }

            String relative = path.startsWith("/") ? path.substring(1) : path;
            return URLDecoder.decode(relative, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return cleaned;
        }
    }

    public static String resolveClientMediaSource(String source) {
        return resolveClientMediaSourceDetailed(source).resolvedSource();
    }

    public static ClientMediaResolution resolveClientMediaSourceDetailed(String source) {
        String cleaned = source == null ? "" : source.trim();
        if (cleaned.isBlank()) {
            return new ClientMediaResolution(source, "", "La fuente esta vacia.");
        }

        if (isHttpSource(cleaned) || cleaned.startsWith("file://")) {
            return new ClientMediaResolution(source, cleaned, "");
        }

        if (isTransferMediaSource(cleaned)) {
            TransferMediaSource transfer = parseTransferMediaSource(cleaned);
            if (!transfer.success()) {
                return new ClientMediaResolution(source, "", transfer.failureReason());
            }

            Path cachedFile = getTransferCacheFile(transfer);
            if (cachedFile != null && Files.isRegularFile(cachedFile) && Files.isReadable(cachedFile)) {
                return new ClientMediaResolution(source, cachedFile.toUri().toString(), "");
            }

            return new ClientMediaResolution(
                    source,
                    "",
                    "El archivo del servidor todavia no se recibio en este cliente: " + transfer.relativePath()
            );
        }

        if (isAssetMediaSource(cleaned)) {
            AssetMediaSource asset = parseAssetMediaSource(cleaned);
            if (!asset.success()) {
                return new ClientMediaResolution(source, "", asset.failureReason());
            }
            return new ClientMediaResolution(source, cleaned, "");
        }

        if (cleaned.startsWith(REMOTE_SCHEME + "://")) {
            String resolvedRemote = resolveRemoteServerSource(cleaned);
            if (resolvedRemote.isBlank()) {
                return new ClientMediaResolution(
                        source,
                        "",
                        "No se pudo resolver la fuente remota " + cleaned
                                + ". Revisa el host del servidor, el puerto del file server y el firewall."
                );
            }
            return new ClientMediaResolution(source, resolvedRemote, "");
        }

        Path localFile = getFile(cleaned).toAbsolutePath().normalize();
        if (!isPathInsideFolder(localFile)) {
            return new ClientMediaResolution(
                    source,
                    "",
                    "La ruta queda fuera de /svvideo: " + localFile
            );
        }

        if (!Files.exists(localFile)) {
            return new ClientMediaResolution(
                    source,
                    "",
                    "El archivo no existe en el cliente: " + localFile
            );
        }

        if (!Files.isRegularFile(localFile)) {
            return new ClientMediaResolution(
                    source,
                    "",
                    "La ruta no es un archivo valido: " + localFile
            );
        }

        if (!Files.isReadable(localFile)) {
            return new ClientMediaResolution(
                    source,
                    "",
                    "El cliente no tiene permiso de lectura sobre: " + localFile
            );
        }

        return new ClientMediaResolution(source, localFile.toUri().toString(), "");
    }

    public static String cacheClientRemoteMedia(String source) {
        String cleaned = source == null ? "" : source.trim();
        if (!isHttpSource(cleaned)) {
            return cleaned;
        }

        try {
            createFolder();
            Path cacheFolder = getFolder().resolve("cache");
            Files.createDirectories(cacheFolder);

            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(cleaned))
                    .timeout(Duration.ofSeconds(20))
                    .GET()
                    .header("User-Agent", "Mozilla/5.0")
                    .header("Accept", "image/avif,image/webp,image/apng,image/svg+xml,image/*,video/*,*/*;q=0.8");

            String referer = refererFor(cleaned);
            if (!referer.isBlank()) {
                builder.header("Referer", referer);
            }

            HttpResponse<byte[]> response = REMOTE_MEDIA_CLIENT.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                return cleaned;
            }

            String contentType = response.headers()
                    .firstValue("Content-Type")
                    .orElse("application/octet-stream");
            String extension = extensionForContentType(contentType, cleaned);
            if (extension.isBlank()) {
                return cleaned;
            }

            Path cachedFile = cacheFolder.resolve(hash(cleaned) + extension);
            Files.write(cachedFile, response.body(),
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
            return cachedFile.toUri().toString();
        } catch (Exception e) {
            return cleaned;
        }
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_FILE = FabricLoader.getInstance().getConfigDir().resolve("svvideo.json");

    private static int loadFileServerPort() {
        if (!Files.isRegularFile(CONFIG_FILE)) {
            writeDefaultConfig();
            return DEFAULT_FILE_SERVER_PORT;
        }
        try {
            String content = Files.readString(CONFIG_FILE, StandardCharsets.UTF_8);
            JsonObject json = JsonParser.parseString(content).getAsJsonObject();
            if (!json.has("fileServerPort")) {
                mergeDefaultConfig(json);
                return DEFAULT_FILE_SERVER_PORT;
            }
            int port = json.get("fileServerPort").getAsInt();
            return port > 0 && port <= 65535 ? port : DEFAULT_FILE_SERVER_PORT;
        } catch (Exception ignored) {
            return DEFAULT_FILE_SERVER_PORT;
        }
    }

    private static void ensureConfigFile() {
        if (!Files.isRegularFile(CONFIG_FILE)) {
            writeDefaultConfig();
            return;
        }

        try {
            String content = Files.readString(CONFIG_FILE, StandardCharsets.UTF_8);
            JsonObject json = JsonParser.parseString(content).getAsJsonObject();
            if (!json.has("fileServerPort")) {
                mergeDefaultConfig(json);
            }
        } catch (Exception ignored) {
        }
    }

    private static void writeDefaultConfig() {
        try {
            Files.createDirectories(CONFIG_FILE.getParent());
            JsonObject json = new JsonObject();
            json.addProperty("fileServerPort", DEFAULT_FILE_SERVER_PORT);
            Files.writeString(CONFIG_FILE, GSON.toJson(json),
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException ignored) {
        }
    }

    private static void mergeDefaultConfig(JsonObject existing) {
        try {
            Files.createDirectories(CONFIG_FILE.getParent());
            existing.addProperty("fileServerPort", DEFAULT_FILE_SERVER_PORT);
            Files.writeString(CONFIG_FILE, GSON.toJson(existing),
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException ignored) {
        }
    }

    private static synchronized void ensureFileServer() {
        if (fileServer != null) {
            return;
        }

        int port = loadFileServerPort();
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
            server.createContext("/svvideo/", SVVideoFiles::handleFileRequest);
            server.setExecutor(Executors.newCachedThreadPool());
            server.start();
            fileServer = server;
            fileServerPort = server.getAddress().getPort();
            System.out.println("[SVVideo] Servidor de archivos iniciado en puerto " + fileServerPort
                    + ". Asegurate de que el puerto " + fileServerPort + " este abierto en el firewall del servidor.");
        } catch (IOException e) {
            System.err.println("[SVVideo] No se pudo iniciar el servidor de archivos en puerto " + port
                    + ". Verifica que el puerto no este en uso y este permitido por el firewall.");
            e.printStackTrace();
        }
    }

    private static void handleFileRequest(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(405, -1);
            exchange.close();
            return;
        }

        String requestPath = exchange.getRequestURI().getPath();
        String relative = requestPath.substring("/svvideo/".length());
        Path file = getFolder()
                .resolve(URLDecoder.decode(relative, StandardCharsets.UTF_8))
                .normalize()
                .toAbsolutePath();

        if (!isPathInsideFolder(file) || !Files.isRegularFile(file)) {
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
            return;
        }

        exchange.getResponseHeaders().add("Content-Type", Files.probeContentType(file) != null
                ? Files.probeContentType(file)
                : "application/octet-stream");
        exchange.sendResponseHeaders(200, Files.size(file));

        try (InputStream input = Files.newInputStream(file);
             OutputStream output = exchange.getResponseBody()) {
            input.transferTo(output);
        } finally {
            exchange.close();
        }
    }

    private static String resolveRemoteServerSource(String source) {
        try {
            URI uri = URI.create(source);
            String host = currentServerHost();
            if (host.isBlank()) {
                return "";
            }
            String path = uri.getRawPath();
            if (path == null || path.isBlank()) {
                return "";
            }
            return "http://" + host + ":" + uri.getHost() + "/svvideo" + path;
        } catch (Exception e) {
            return "";
        }
    }

    private static String normalizeGifAssetPath(String fileName) {
        String path = fileName == null ? "" : fileName.trim().replace('\\', '/');
        if (path.isBlank()) {
            return "";
        }

        String assetRoot = "assets/" + MOD_ASSET_FOLDER_NAME + "/";
        if (path.startsWith(assetRoot)) {
            path = path.substring(assetRoot.length());
        }
        if (!path.startsWith(GIF_FOLDER_NAME + "/")) {
            path = GIF_FOLDER_NAME + "/" + path;
        }
        return normalizeAssetPath(path);
    }

    private static String normalizeAssetPath(String path) {
        String normalized = path == null ? "" : path.trim().replace('\\', '/');
        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        if (normalized.isBlank() || normalized.endsWith("/")) {
            return "";
        }

        String[] segments = normalized.split("/");
        StringBuilder safePath = new StringBuilder();
        for (String segment : segments) {
            if (segment.isBlank() || ".".equals(segment) || "..".equals(segment)) {
                return "";
            }
            if (safePath.length() > 0) {
                safePath.append('/');
            }
            safePath.append(segment);
        }
        return safePath.toString();
    }

    private static String cacheFileName(String relativePath) {
        String normalized = normalizeAssetPath(relativePath);
        if (normalized.isBlank()) {
            return "media.bin";
        }

        String name = Path.of(normalized).getFileName().toString();
        name = name.replaceAll("[^A-Za-z0-9._-]", "_");
        return name.isBlank() ? "media.bin" : name;
    }

    private static boolean isValidTransferHash(String hash) {
        return hash != null && hash.matches("[A-Fa-f0-9]{64}");
    }

    public static String sha256(Path file) throws IOException {
        try (InputStream input = Files.newInputStream(file)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read > 0) {
                    digest.update(buffer, 0, read);
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("No se pudo calcular SHA-256 para " + file, e);
        }
    }

    private static String currentServerHost() {
        if (!FabricLoader.getInstance().getEnvironmentType().name().equals("CLIENT")) {
            return "";
        }
        try {
            Class<?> helperClass = Class.forName("santiivlog.dev.svvideo.client.SVVideoClientHelper");
            Object result = helperClass.getMethod("getServerHost").invoke(null);
            return result instanceof String s ? s : "";
        } catch (Exception e) {
            return "";
        }
    }

    private static String encodePath(String path) {
        String normalized = path == null ? "" : path.replace('\\', '/');
        if (normalized.isBlank()) {
            return "";
        }

        String[] segments = normalized.split("/");
        StringBuilder encoded = new StringBuilder();
        for (int i = 0; i < segments.length; i++) {
            if (i > 0) {
                encoded.append('/');
            }
            encoded.append(URLEncoder.encode(segments[i], StandardCharsets.UTF_8).replace("+", "%20"));
        }
        return encoded.toString();
    }

    private static boolean isHttpSource(String source) {
        String lower = source.toLowerCase();
        return lower.startsWith("http://") || lower.startsWith("https://");
    }

    private static String refererFor(String source) {
        try {
            URI uri = URI.create(source);
            String scheme = uri.getScheme();
            String host = uri.getHost();
            if (scheme == null || host == null || host.isBlank()) {
                return "";
            }
            return scheme + "://" + host + "/";
        } catch (Exception e) {
            return "";
        }
    }

    private static String extensionForContentType(String contentType, String source) {
        String lower = contentType == null ? "" : contentType.toLowerCase();
        int separator = lower.indexOf(';');
        if (separator >= 0) {
            lower = lower.substring(0, separator).trim();
        }

        return switch (lower) {
            case "image/gif" -> ".gif";
            case "image/webp" -> ".webp";
            case "image/png" -> ".png";
            case "image/jpeg" -> ".jpg";
            case "video/mp4" -> ".mp4";
            case "video/webm" -> ".webm";
            case "application/octet-stream" -> extensionFromSource(source);
            default -> "";
        };
    }

    private static String extensionFromSource(String source) {
        String lower = source == null ? "" : source.toLowerCase();
        for (String extension : new String[]{".gif", ".webp", ".png", ".jpg", ".jpeg", ".mp4", ".webm"}) {
            if (lower.contains(extension + "?") || lower.endsWith(extension)) {
                return ".jpeg".equals(extension) ? ".jpg" : extension;
            }
        }
        return "";
    }

    private static String hash(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (Exception e) {
            return Integer.toHexString(value.hashCode());
        }
    }
}
