package santiivlog.dev.svvideo.command;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.command.argument.EntityArgumentType;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import santiivlog.dev.svvideo.block.entity.SVVideoSpeakerBlockEntity;
import santiivlog.dev.svvideo.network.SVvideonetwork;
import santiivlog.dev.svvideo.util.SVVideoFiles;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.concurrent.CompletableFuture;

public class svvideocommands {

    public static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                dispatcher.register(buildRootCommand()));
    }

    private static LiteralArgumentBuilder<ServerCommandSource> buildRootCommand() {
        return CommandManager.literal("svvideo")
                .requires(source -> source.hasPermissionLevel(2))

                .then(CommandManager.literal("stop")
                        .then(CommandManager.argument("targets", EntityArgumentType.players())
                                .executes(ctx -> {
                                    Collection<ServerPlayerEntity> players = EntityArgumentType.getPlayers(ctx, "targets");
                                    for (ServerPlayerEntity player : players) {
                                        SVvideonetwork.sendStop(player);
                                    }

                                    ctx.getSource().sendFeedback(() -> Text.literal("Video detenido"), false);
                                    return players.size();
                                })))

                .then(CommandManager.literal("volume")
                        .then(CommandManager.argument("targets", EntityArgumentType.players())
                                .then(CommandManager.argument("volume", IntegerArgumentType.integer(0, 100))
                                        .executes(ctx -> {
                                            Collection<ServerPlayerEntity> players = EntityArgumentType.getPlayers(ctx, "targets");
                                            int volume = IntegerArgumentType.getInteger(ctx, "volume");
                                            for (ServerPlayerEntity player : players) {
                                                SVvideonetwork.sendVolume(player, volume);
                                            }

                                            ctx.getSource().sendFeedback(() ->
                                                    Text.literal("Volumen SVVideo cambiado a " + volume), false);
                                            return players.size();
                                        }))))

                .then(CommandManager.literal("video")
                        .then(CommandManager.argument("targets", EntityArgumentType.players())
                                .then(CommandManager.argument("volume", IntegerArgumentType.integer(0, 100))
                                        .then(CommandManager.argument("url", StringArgumentType.greedyString())
                                                .executes(ctx -> {
                                                    Collection<ServerPlayerEntity> players = EntityArgumentType.getPlayers(ctx, "targets");
                                                    int volume = IntegerArgumentType.getInteger(ctx, "volume");
                                                    String url = StringArgumentType.getString(ctx, "url");

                                                    for (ServerPlayerEntity player : players) {
                                                        SVvideonetwork.sendVideo(player, url, volume);
                                                    }

                                                    ctx.getSource().sendFeedback(() ->
                                                            Text.literal("Video enviado: " + url + " volumen: " + volume), false);
                                                    return players.size();
                                                })))))

                .then(CommandManager.literal("music")
                        .then(CommandManager.literal("hidehud")
                                .then(CommandManager.argument("targets", EntityArgumentType.players())
                                        .executes(ctx -> toggleMusicHud(ctx.getSource(),
                                                EntityArgumentType.getPlayers(ctx, "targets"))))
                                .executes(ctx -> toggleMusicHudSelf(ctx.getSource())))
                        .then(CommandManager.literal("file")
                                .then(CommandManager.argument("targets", EntityArgumentType.players())
                                        .then(CommandManager.argument("volume", IntegerArgumentType.integer(0, 100))
                                                .then(CommandManager.argument("file", StringArgumentType.greedyString())
                                                        .suggests((ctx, builder) -> suggestMediaFiles(builder))
                                                        .executes(ctx -> {
                                                            Collection<ServerPlayerEntity> players = EntityArgumentType.getPlayers(ctx, "targets");
                                                            int volume = IntegerArgumentType.getInteger(ctx, "volume");
                                                            String file = StringArgumentType.getString(ctx, "file");
                                                            String mediaSource = publishServerFile(ctx.getSource(), file);
                                                            if (mediaSource.isBlank()) {
                                                                return 0;
                                                            }

                                                            for (ServerPlayerEntity player : players) {
                                                                SVvideonetwork.sendMusicFile(player, mediaSource, volume);
                                                            }

                                                            ctx.getSource().sendFeedback(() ->
                                                                    Text.literal("Musica local enviada: " + file + " volumen: " + volume), false);
                                                            return players.size();
                                                        })))))
                        .then(CommandManager.argument("targets", EntityArgumentType.players())
                                .then(CommandManager.argument("volume", IntegerArgumentType.integer(0, 100))
                                        .then(CommandManager.argument("url", StringArgumentType.greedyString())
                                                .executes(ctx -> {
                                                    Collection<ServerPlayerEntity> players = EntityArgumentType.getPlayers(ctx, "targets");
                                                    int volume = IntegerArgumentType.getInteger(ctx, "volume");
                                                    String url = StringArgumentType.getString(ctx, "url");

                                                    for (ServerPlayerEntity player : players) {
                                                        SVvideonetwork.sendMusic(player, url, volume);
                                                    }

                                                    ctx.getSource().sendFeedback(() ->
                                                            Text.literal("Musica enviada: " + url + " volumen: " + volume), false);
                                                    return players.size();
                                                })))))

                .then(CommandManager.literal("file")
                        .then(CommandManager.argument("targets", EntityArgumentType.players())
                                .then(CommandManager.argument("volume", IntegerArgumentType.integer(0, 100))
                                        .then(CommandManager.argument("file", StringArgumentType.greedyString())
                                                .suggests((ctx, builder) -> suggestMediaFiles(builder))
                                                .executes(ctx -> {
                                                    Collection<ServerPlayerEntity> players = EntityArgumentType.getPlayers(ctx, "targets");
                                                    int volume = IntegerArgumentType.getInteger(ctx, "volume");
                                                    String file = StringArgumentType.getString(ctx, "file");
                                                    String mediaSource = publishServerFile(ctx.getSource(), file);
                                                    if (mediaSource.isBlank()) {
                                                        return 0;
                                                    }

                                                    for (ServerPlayerEntity player : players) {
                                                        SVvideonetwork.sendFile(player, mediaSource, volume);
                                                    }

                                                    ctx.getSource().sendFeedback(() ->
                                                            Text.literal("Video local enviado: " + file + " volumen: " + volume), false);
                                                    return players.size();
                                                })))))

                .then(CommandManager.literal("gif")
                        .then(buildGifUrlCommand())
                        .then(buildGifFileCommand()))

                .then(buildVanillaSoundsLiteral("vanillasounds"))
                .then(CommandManager.literal("vanilla").then(buildVanillaSoundsLiteral("sounds")))

                .then(buildPlayblockCommand());
    }

    private static LiteralArgumentBuilder<ServerCommandSource> buildVanillaSoundsLiteral(String literal) {
        return CommandManager.literal(literal)
                .then(CommandManager.literal("on")
                        .executes(ctx -> setVanillaSoundsForSource(ctx.getSource(), true)))
                .then(CommandManager.literal("off")
                        .executes(ctx -> setVanillaSoundsForSource(ctx.getSource(), false)))
                .then(CommandManager.argument("targets", EntityArgumentType.players())
                        .then(CommandManager.literal("on")
                                .executes(ctx -> setVanillaSounds(
                                        ctx.getSource(),
                                        EntityArgumentType.getPlayers(ctx, "targets"),
                                        true)))
                        .then(CommandManager.literal("off")
                                .executes(ctx -> setVanillaSounds(
                                        ctx.getSource(),
                                        EntityArgumentType.getPlayers(ctx, "targets"),
                                        false))));
    }

    private static LiteralArgumentBuilder<ServerCommandSource> buildGifUrlCommand() {
        return CommandManager.literal("url")
                .then(CommandManager.argument("targets", EntityArgumentType.players())
                        .then(CommandManager.argument("volume", IntegerArgumentType.integer(0, 100))
                                .then(CommandManager.argument("position", StringArgumentType.word())
                                        .suggests((ctx, builder) -> suggestPositions(builder))
                                        .then(CommandManager.argument("url", StringArgumentType.string())
                                                .executes(ctx -> executeGifUrl(ctx, 100, 0))
                                                .then(CommandManager.argument("scale", IntegerArgumentType.integer(1, 500))
                                                        .executes(ctx -> executeGifUrl(
                                                                ctx,
                                                                IntegerArgumentType.getInteger(ctx, "scale"),
                                                                0))
                                                        .then(CommandManager.argument("duration", IntegerArgumentType.integer(1, 3600))
                                                                .executes(ctx -> executeGifUrl(
                                                                        ctx,
                                                                        IntegerArgumentType.getInteger(ctx, "scale"),
                                                                        IntegerArgumentType.getInteger(ctx, "duration")))))))));
    }

    private static LiteralArgumentBuilder<ServerCommandSource> buildGifFileCommand() {
        return CommandManager.literal("file")
                .then(CommandManager.argument("targets", EntityArgumentType.players())
                        .then(CommandManager.argument("volume", IntegerArgumentType.integer(0, 100))
                                .then(CommandManager.argument("position", StringArgumentType.word())
                                        .suggests((ctx, builder) -> suggestPositions(builder))
                                        .then(CommandManager.argument("file", StringArgumentType.string())
                                                .suggests((ctx, builder) -> suggestMediaFiles(builder))
                                                .executes(ctx -> executeGifFile(ctx, 100, 0))
                                                .then(CommandManager.argument("scale", IntegerArgumentType.integer(1, 500))
                                                        .executes(ctx -> executeGifFile(
                                                                ctx,
                                                                IntegerArgumentType.getInteger(ctx, "scale"),
                                                                0))
                                                        .then(CommandManager.argument("duration", IntegerArgumentType.integer(1, 3600))
                                                                .executes(ctx -> executeGifFile(
                                                                        ctx,
                                                                        IntegerArgumentType.getInteger(ctx, "scale"),
                                                                        IntegerArgumentType.getInteger(ctx, "duration")))))))));
    }

    private static LiteralArgumentBuilder<ServerCommandSource> buildPlayblockCommand() {
        return CommandManager.literal("playblock")
                .then(CommandManager.argument("x", IntegerArgumentType.integer())
                        .then(CommandManager.argument("y", IntegerArgumentType.integer())
                                .then(CommandManager.argument("z", IntegerArgumentType.integer())
                                        .then(CommandManager.argument("volume", IntegerArgumentType.integer(0, 100))
                                                .then(CommandManager.argument("file", StringArgumentType.string())
                                                        .suggests((ctx, builder) -> suggestMediaFiles(builder))
                                                        .executes(ctx -> playBlock(
                                                                ctx.getSource(),
                                                                new BlockPos(
                                                                        IntegerArgumentType.getInteger(ctx, "x"),
                                                                        IntegerArgumentType.getInteger(ctx, "y"),
                                                                        IntegerArgumentType.getInteger(ctx, "z")),
                                                                IntegerArgumentType.getInteger(ctx, "volume"),
                                                                StringArgumentType.getString(ctx, "file"),
                                                                null,
                                                                null))
                                                        .then(CommandManager.argument("width", IntegerArgumentType.integer(1, 50))
                                                                .then(CommandManager.argument("height", IntegerArgumentType.integer(1, 50))
                                                                        .executes(ctx -> playBlock(
                                                                                ctx.getSource(),
                                                                                new BlockPos(
                                                                                        IntegerArgumentType.getInteger(ctx, "x"),
                                                                                        IntegerArgumentType.getInteger(ctx, "y"),
                                                                                        IntegerArgumentType.getInteger(ctx, "z")),
                                                                                IntegerArgumentType.getInteger(ctx, "volume"),
                                                                                StringArgumentType.getString(ctx, "file"),
                                                                                IntegerArgumentType.getInteger(ctx, "width"),
                                                                                IntegerArgumentType.getInteger(ctx, "height")))))))
                                        .then(CommandManager.argument("targets", EntityArgumentType.players())
                                                .then(CommandManager.argument("volume", IntegerArgumentType.integer(0, 100))
                                                        .then(CommandManager.argument("file", StringArgumentType.string())
                                                                .suggests((ctx, builder) -> suggestMediaFiles(builder))
                                                                .executes(ctx -> playBlock(
                                                                        ctx.getSource(),
                                                                        new BlockPos(
                                                                                IntegerArgumentType.getInteger(ctx, "x"),
                                                                                IntegerArgumentType.getInteger(ctx, "y"),
                                                                                IntegerArgumentType.getInteger(ctx, "z")),
                                                                        IntegerArgumentType.getInteger(ctx, "volume"),
                                                                        StringArgumentType.getString(ctx, "file"),
                                                                        null,
                                                                        null))
                                                                .then(CommandManager.argument("width", IntegerArgumentType.integer(1, 50))
                                                                        .then(CommandManager.argument("height", IntegerArgumentType.integer(1, 50))
                                                                                .executes(ctx -> playBlock(
                                                                                        ctx.getSource(),
                                                                                        new BlockPos(
                                                                                                IntegerArgumentType.getInteger(ctx, "x"),
                                                                                                IntegerArgumentType.getInteger(ctx, "y"),
                                                                                                IntegerArgumentType.getInteger(ctx, "z")),
                                                                                        IntegerArgumentType.getInteger(ctx, "volume"),
                                                                                        StringArgumentType.getString(ctx, "file"),
                                                                                        IntegerArgumentType.getInteger(ctx, "width"),
                                                                                        IntegerArgumentType.getInteger(ctx, "height")))))))))));
    }

    private static int playBlock(ServerCommandSource source, BlockPos pos, int volume, String inputFile,
                                 Integer width, Integer height) {
        ServerWorld world = source.getWorld();
        if (!(world.getBlockEntity(pos) instanceof SVVideoSpeakerBlockEntity speaker)) {
            source.sendError(Text.literal("No hay un bloque SVVideo en esas coordenadas."));
            return 0;
        }

        String file = inputFile == null ? "" : inputFile.trim();
        String mediaSource = publishServerFile(source, file);
        if (mediaSource.isBlank()) {
            return 0;
        }

        speaker.setSourceMode("file");
        speaker.setMediaSource(mediaSource);
        speaker.setVolume(volume);
        if (width != null) {
            speaker.setDisplayWidth(width);
        }
        if (height != null) {
            speaker.setDisplayHeight(height);
        }
        speaker.setPaused(false);
        speaker.requestReload();
        speaker.markDirty();
        world.getChunkManager().markForUpdate(pos);

        int finalWidth = width != null ? width : speaker.getDisplayWidth();
        int finalHeight = height != null ? height : speaker.getDisplayHeight();
        source.sendFeedback(() -> Text.literal(
                "SVVideo block actualizado en " + pos.toShortString()
                        + " file=" + file
                        + " volume=" + volume
                        + " size=" + finalWidth + "x" + finalHeight
        ), false);
        return 1;
    }

    private static int executeGifUrl(CommandContext<ServerCommandSource> ctx, int scale, int duration)
            throws CommandSyntaxException {
        Collection<ServerPlayerEntity> players = EntityArgumentType.getPlayers(ctx, "targets");
        int volume = IntegerArgumentType.getInteger(ctx, "volume");
        String position = StringArgumentType.getString(ctx, "position");
        String url = StringArgumentType.getString(ctx, "url");

        for (ServerPlayerEntity player : players) {
            SVvideonetwork.sendGifUrl(player, url, volume, position, scale, duration);
        }

        ctx.getSource().sendFeedback(() -> Text.literal(
                formatGifFeedback("GIF/video URL enviado: ", url, position, scale, duration)
        ), false);
        return players.size();
    }

    private static int executeGifFile(CommandContext<ServerCommandSource> ctx, int scale, int duration)
            throws CommandSyntaxException {
        Collection<ServerPlayerEntity> players = EntityArgumentType.getPlayers(ctx, "targets");
        int volume = IntegerArgumentType.getInteger(ctx, "volume");
        String position = StringArgumentType.getString(ctx, "position");
        String file = StringArgumentType.getString(ctx, "file");
        String mediaSource = publishGifFile(ctx.getSource(), file);
        if (mediaSource.isBlank()) {
            return 0;
        }

        for (ServerPlayerEntity player : players) {
            SVvideonetwork.sendGifFile(player, mediaSource, volume, position, scale, duration);
        }

        ctx.getSource().sendFeedback(() -> Text.literal(
                formatGifFeedback("GIF/video local enviado: ", file, position, scale, duration)
        ), false);
        return players.size();
    }

    private static String formatGifFeedback(String prefix, String source, String position, int scale, int duration) {
        String message = prefix + source + " posicion: " + position;
        if (scale != 100) {
            message += " escala: " + scale + "%";
        }
        if (duration > 0) {
            message += " duracion: " + duration + "s";
        }
        return message;
    }

    private static int setVanillaSounds(ServerCommandSource source, Collection<ServerPlayerEntity> players,
                                        boolean enabled) {
        for (ServerPlayerEntity player : players) {
            SVvideonetwork.sendVanillaSoundsEnabled(player, enabled);
        }

        source.sendFeedback(() -> Text.literal(
                "Sonidos vanilla " + (enabled ? "activados" : "desactivados")
                        + " para " + players.size() + " jugador(es)."
        ), false);
        return players.size();
    }

    private static int setVanillaSoundsForSource(ServerCommandSource source, boolean enabled) {
        if (!(source.getEntity() instanceof ServerPlayerEntity player)) {
            source.sendError(Text.literal("Este uso del comando requiere un jugador."));
            return 0;
        }

        SVvideonetwork.sendVanillaSoundsEnabled(player, enabled);
        source.sendFeedback(() -> Text.literal(
                "Sonidos vanilla " + (enabled ? "activados" : "desactivados") + " para vos."
        ), false);
        return 1;
    }

    private static String publishGifFile(ServerCommandSource source, String inputFile) {
        String file = inputFile == null ? "" : inputFile.trim();
        String mediaSource = publishServerFile(source, file, false);
        if (!mediaSource.isBlank()) {
            return mediaSource;
        }

        String assetSource = SVVideoFiles.createGifAssetMediaSource(file);
        if (assetSource.isBlank()) {
            source.sendError(Text.literal("Archivo no encontrado en /svvideo ni en assets/svvideo/gif: " + file));
            return "";
        }

        return assetSource;
    }

    private static String publishServerFile(ServerCommandSource source, String inputFile) {
        return publishServerFile(source, inputFile, true);
    }

    private static String publishServerFile(ServerCommandSource source, String inputFile, boolean reportMissing) {
        String file = inputFile == null ? "" : inputFile.trim();
        Path resolvedFile = SVVideoFiles.getFile(file).toAbsolutePath().normalize();
        if (!SVVideoFiles.isPathInsideFolder(resolvedFile) || !Files.isRegularFile(resolvedFile)) {
            if (reportMissing) {
                source.sendError(Text.literal("Archivo no encontrado en /svvideo: " + file));
            }
            return "";
        }

        String mediaSource = SVVideoFiles.createServerMediaSource(file);
        if (mediaSource.isBlank()) {
            source.sendError(Text.literal("No se pudo publicar el archivo: " + file));
            return "";
        }
        return mediaSource;
    }

    private static CompletableFuture<Suggestions> suggestMediaFiles(SuggestionsBuilder builder) {
        try {
            SVVideoFiles.createFolder();
            if (Files.exists(SVVideoFiles.getFolder())) {
                Files.list(SVVideoFiles.getFolder())
                        .filter(Files::isRegularFile)
                        .filter(path -> {
                            String name = path.getFileName().toString().toLowerCase();
                            return name.endsWith(".mp4")
                                    || name.endsWith(".webm")
                                    || name.endsWith(".mov")
                                    || name.endsWith(".mkv")
                                    || name.endsWith(".gif")
                                    || name.endsWith(".webp")
                                    || name.endsWith(".ogg")
                                    || name.endsWith(".mp3")
                                    || name.endsWith(".wav")
                                    || name.endsWith(".aac")
                                    || name.endsWith(".m4a")
                                    || name.endsWith(".flac")
                                    || name.endsWith(".opus");
                        })
                        .forEach(path -> builder.suggest(path.getFileName().toString()));
            }
        } catch (Exception ignored) {
        }

        return builder.buildFuture();
    }

    private static int toggleMusicHud(ServerCommandSource source, Collection<ServerPlayerEntity> players) {
        for (ServerPlayerEntity player : players) {
            SVvideonetwork.sendHideMusicHud(player, true);
        }
        source.sendFeedback(() -> Text.literal("HUD de música ocultado para " + players.size() + " jugador(es)."), false);
        return players.size();
    }

    private static int toggleMusicHudSelf(ServerCommandSource source) {
        if (!(source.getEntity() instanceof ServerPlayerEntity player)) {
            source.sendError(Text.literal("Este uso del comando requiere un jugador."));
            return 0;
        }
        SVvideonetwork.sendHideMusicHud(player, true);
        source.sendFeedback(() -> Text.literal("HUD de música ocultado."), false);
        return 1;
    }

    private static CompletableFuture<Suggestions> suggestPositions(SuggestionsBuilder builder) {
        builder.suggest("fullscreen");
        builder.suggest("center");
        builder.suggest("top-center");
        builder.suggest("actionbar");
        builder.suggest("bottom-left");
        builder.suggest("bottom-right");
        builder.suggest("top-right");
        builder.suggest("top-left");
        return builder.buildFuture();
    }
}
