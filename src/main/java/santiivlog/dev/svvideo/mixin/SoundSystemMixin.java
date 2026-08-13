package santiivlog.dev.svvideo.mixin;

import net.minecraft.client.sound.SoundInstance;
import net.minecraft.client.sound.SoundSystem;
import net.minecraft.sound.SoundCategory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(SoundSystem.class)
public abstract class SoundSystemMixin {

    @Inject(method = "play(Lnet/minecraft/client/sound/SoundInstance;)V", at = @At("HEAD"), cancellable = true)
    private void svvideo$play(SoundInstance sound, CallbackInfo ci) {
        if (shouldCancel(sound)) {
            ci.cancel();
        }
    }

    @Inject(method = "play(Lnet/minecraft/client/sound/SoundInstance;I)V", at = @At("HEAD"), cancellable = true)
    private void svvideo$playDelayed(SoundInstance sound, int delay, CallbackInfo ci) {
        if (shouldCancel(sound)) {
            ci.cancel();
        }
    }

    private static boolean shouldCancel(SoundInstance sound) {
        if (areVanillaSoundsEnabled() || sound == null) {
            return false;
        }

        return sound.getCategory() == SoundCategory.MUSIC;
    }

    private static boolean areVanillaSoundsEnabled() {
        try {
            Class<?> settingsClass = Class.forName("santiivlog.dev.svvideo.client.SVVideoSettings");
            Object result = settingsClass.getMethod("areVanillaSoundsEnabled").invoke(null);
            return !(result instanceof Boolean enabled) || enabled;
        } catch (Throwable ignored) {
            return true;
        }
    }
}

