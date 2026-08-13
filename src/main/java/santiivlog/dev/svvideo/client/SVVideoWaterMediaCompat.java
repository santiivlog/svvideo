package santiivlog.dev.svvideo.client;

import org.watermedia.api.media.MRL;
import org.watermedia.api.media.MediaAPI;
import org.watermedia.api.media.engines.GFXEngine;
import org.watermedia.api.media.engines.SFXEngine;
import org.watermedia.api.media.players.MediaPlayer;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.function.Supplier;

final class SVVideoWaterMediaCompat {
    private static final Method MRL_READY = findMethod(MRL.class, "ready");
    private static final Method MRL_ERROR = findMethod(MRL.class, "error");
    private static final Method MRL_STATUS = findMethod(MRL.class, "status");
    private static final Method MRL_EXCEPTION = findMethod(MRL.class, "exception");
    private static final Method MRL_CREATE_PLAYER =
            findMethod(MRL.class, "createPlayer", GFXEngine.class, SFXEngine.class);
    private static final Method MEDIA_API_CREATE_PLAYER =
            findMethod(MediaAPI.class, "createPlayer", MRL.class, Supplier.class, Supplier.class);

    private SVVideoWaterMediaCompat() {
    }

    static boolean isMrlReady(MRL mrl) {
        if (mrl == null) {
            return false;
        }
        if (MRL_READY != null) {
            return invokeBoolean(MRL_READY, mrl, false);
        }

        Object status = status(mrl);
        if (status == null) {
            return false;
        }
        if (invokeBoolean(findMethod(status.getClass(), "loaded"), status, false)) {
            return true;
        }
        return "LOADED".equals(String.valueOf(status));
    }

    static boolean isMrlFailed(MRL mrl) {
        if (mrl == null) {
            return false;
        }
        if (MRL_ERROR != null) {
            return invokeBoolean(MRL_ERROR, mrl, false);
        }

        Object status = status(mrl);
        if (status == null) {
            return false;
        }
        if (invokeBoolean(findMethod(status.getClass(), "failed"), status, false)) {
            return true;
        }

        String name = String.valueOf(status);
        return "ERROR".equals(name) || "BLOCKED".equals(name)
                || "EXPIRED".equals(name) || "FORGOTTEN".equals(name);
    }

    static MediaPlayer createPlayer(MRL mrl, Supplier<GFXEngine> videoSupplier, Supplier<SFXEngine> audioSupplier) {
        try {
            if (MEDIA_API_CREATE_PLAYER != null) {
                return (MediaPlayer) MEDIA_API_CREATE_PLAYER.invoke(null, mrl, videoSupplier, audioSupplier);
            }
            if (MRL_CREATE_PLAYER != null) {
                GFXEngine videoEngine = videoSupplier == null ? null : videoSupplier.get();
                SFXEngine audioEngine = audioSupplier == null ? null : audioSupplier.get();
                return (MediaPlayer) MRL_CREATE_PLAYER.invoke(mrl, videoEngine, audioEngine);
            }
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("No se pudo acceder a la API de WaterMedia.", e);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            throw new IllegalStateException("WaterMedia fallo al crear el player.", cause);
        }

        throw new IllegalStateException("Version de WaterMedia incompatible: no hay metodo createPlayer usable.");
    }

    static boolean isNativeBackendLoaded() {
        try {
            return MediaAPI.ffmpegLoaded();
        } catch (Throwable ignored) {
        }
        try {
            Class<?> ffMediaPlayer = Class.forName("org.watermedia.api.media.players.FFMediaPlayer");
            Method loaded = findMethod(ffMediaPlayer, "loaded");
            return invokeBoolean(loaded, null, false);
        } catch (Exception e) {
            return false;
        }
    }

    static String failureMessage(MRL mrl, String fallback) {
        String safeFallback = fallback == null || fallback.isBlank()
                ? "WaterMedia no pudo resolver la fuente."
                : fallback;
        if (mrl == null || MRL_EXCEPTION == null) {
            return safeFallback;
        }

        try {
            Object value = MRL_EXCEPTION.invoke(mrl);
            if (value instanceof Throwable throwable) {
                String message = throwable.getMessage();
                if (message != null && !message.isBlank()) {
                    return safeFallback + " " + message;
                }
                return safeFallback + " " + throwable.getClass().getSimpleName();
            }
        } catch (IllegalAccessException | InvocationTargetException ignored) {
        }

        return safeFallback;
    }

    private static Object status(MRL mrl) {
        if (MRL_STATUS == null) {
            return null;
        }
        try {
            return MRL_STATUS.invoke(mrl);
        } catch (IllegalAccessException | InvocationTargetException e) {
            return null;
        }
    }

    private static boolean invokeBoolean(Method method, Object target, boolean fallback) {
        if (method == null) {
            return fallback;
        }
        try {
            Object value = method.invoke(target);
            return value instanceof Boolean bool ? bool : fallback;
        } catch (IllegalAccessException | InvocationTargetException e) {
            return fallback;
        }
    }

    private static Method findMethod(Class<?> owner, String name, Class<?>... parameterTypes) {
        try {
            return owner.getMethod(name, parameterTypes);
        } catch (NoSuchMethodException e) {
            return null;
        }
    }
}

