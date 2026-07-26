package santiivlog.dev.svvideo.client;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.sound.SoundCategory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

public final class SVVideoSettings {

    private static final int DEFAULT_LOCAL_VOLUME = 100;
    private static final String KEY_LOCAL_VOLUME = "localVolume";
    private static final String KEY_VANILLA_SOUNDS_ENABLED = "vanillaSoundsEnabled";
    private static final String KEY_DEBUG_ENABLED = "debug";
    private static final Path SETTINGS_FILE =
            FabricLoader.getInstance().getConfigDir().resolve("svvideo-client.properties");

    private static int localVolume = loadLocalVolume();
    private static boolean vanillaSoundsEnabled = loadVanillaSoundsEnabled();
    private static boolean debugEnabled = loadDebugEnabled();

    private SVVideoSettings() {
    }

    public static int getLocalVolume() {
        return localVolume;
    }

    public static void setLocalVolume(int volume) {
        localVolume = clamp(volume);
        save();
        VideoRenderer.applyVolume();
    }

    public static boolean areVanillaSoundsEnabled() {
        return vanillaSoundsEnabled;
    }

    public static boolean isDebugEnabled() {
        return debugEnabled;
    }

    public static void setDebugEnabled(boolean enabled) {
        debugEnabled = enabled;
        save();
    }

    public static void setVanillaMinecraftSoundsEnabled(boolean enabled) {
        vanillaSoundsEnabled = enabled;
        save();

        
}

    public static int mixVolume(int baseVolume, SoundCategory soundCategory) {
        float factor = clamp(baseVolume) / 100.0f;

        MinecraftClient client = MinecraftClient.getInstance();
        if (client != null && client.options != null) {
            factor *= client.options.getSoundVolume(SoundCategory.MASTER);
            factor *= client.options.getSoundVolume(SoundCategory.MUSIC);
        }

        return clamp(Math.round(factor * 100.0f));
    }

    private static int loadLocalVolume() {
        if (!Files.isRegularFile(SETTINGS_FILE)) {
            return DEFAULT_LOCAL_VOLUME;
        }

        Properties properties = new Properties();
        try (InputStream input = Files.newInputStream(SETTINGS_FILE)) {
            properties.load(input);
            return clamp(Integer.parseInt(properties.getProperty(
                    KEY_LOCAL_VOLUME,
                    String.valueOf(DEFAULT_LOCAL_VOLUME)
            )));
        } catch (Exception ignored) {
            return DEFAULT_LOCAL_VOLUME;
        }
    }

    private static boolean loadVanillaSoundsEnabled() {
        if (!Files.isRegularFile(SETTINGS_FILE)) {
            return true;
        }

        Properties properties = new Properties();
        try (InputStream input = Files.newInputStream(SETTINGS_FILE)) {
            properties.load(input);
            return Boolean.parseBoolean(properties.getProperty(KEY_VANILLA_SOUNDS_ENABLED, "true"));
        } catch (Exception ignored) {
            return true;
        }
    }

    private static boolean loadDebugEnabled() {
        if (!Files.isRegularFile(SETTINGS_FILE)) {
            return false;
        }

        Properties properties = new Properties();
        try (InputStream input = Files.newInputStream(SETTINGS_FILE)) {
            properties.load(input);
            return Boolean.parseBoolean(properties.getProperty(KEY_DEBUG_ENABLED, "false"));
        } catch (Exception ignored) {
            return false;
        }
    }

    private static void save() {
        try {
            Files.createDirectories(SETTINGS_FILE.getParent());
            Properties properties = new Properties();
            properties.setProperty(KEY_LOCAL_VOLUME, String.valueOf(localVolume));
            properties.setProperty(KEY_VANILLA_SOUNDS_ENABLED, String.valueOf(vanillaSoundsEnabled));
            properties.setProperty(KEY_DEBUG_ENABLED, String.valueOf(debugEnabled));
            try (OutputStream output = Files.newOutputStream(SETTINGS_FILE)) {
                properties.store(output, "SVVideo client settings");
            }
        } catch (IOException ignored) {
        }
    }

    private static int clamp(int volume) {
        return Math.max(0, Math.min(100, volume));
    }
}
