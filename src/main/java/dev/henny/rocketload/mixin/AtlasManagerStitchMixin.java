package dev.henny.rocketload.mixin;

import dev.henny.rocketload.client.BackgroundReloadDriver;
import java.util.Map;
import net.minecraft.client.texture.AtlasManager;
import net.minecraft.client.texture.Sprite;
import net.minecraft.client.util.SpriteIdentifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(AtlasManager.Stitch.class)
public abstract class AtlasManagerStitchMixin {

    @Inject(method = "createSpriteMap", at = @At("HEAD"), cancellable = true)
    private void rocketload$usePrefill(CallbackInfoReturnable<Map<SpriteIdentifier, Sprite>> cir) {
        Map<SpriteIdentifier, Sprite> prefilled = BackgroundReloadDriver.peekPrefilledSpriteMap(this);
        if (prefilled != null) {
            cir.setReturnValue(prefilled);
        }
    }
}
