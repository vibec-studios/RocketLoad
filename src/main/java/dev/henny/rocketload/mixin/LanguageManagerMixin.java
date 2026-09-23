package dev.henny.rocketload.mixin;

import dev.henny.rocketload.client.ReloadSkips;
import net.minecraft.client.resource.language.LanguageManager;
import net.minecraft.resource.ResourceManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LanguageManager.class)
public abstract class LanguageManagerMixin {

    @Inject(method = "reload", at = @At("HEAD"), cancellable = true)
    private void rocketload$skipUnchangedLanguage(ResourceManager manager, CallbackInfo ci) {
        if (ReloadSkips.shouldSkipLanguageReload(manager)) {
            ci.cancel();
        }
    }
}
