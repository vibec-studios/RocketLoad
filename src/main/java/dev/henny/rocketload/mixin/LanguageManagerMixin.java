package dev.henny.rocketload.mixin;

import dev.henny.rocketload.client.ReloadSkips;
import net.minecraft.client.resources.language.LanguageManager;
import net.minecraft.server.packs.resources.ResourceManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LanguageManager.class)
public abstract class LanguageManagerMixin {

    @Inject(method = "onResourceManagerReload", at = @At("HEAD"), cancellable = true)
    private void rocketload$skipUnchanged(ResourceManager manager, CallbackInfo ci) {
        if (ReloadSkips.shouldSkipLanguage(manager)) {
            ci.cancel();
        }
    }
}
