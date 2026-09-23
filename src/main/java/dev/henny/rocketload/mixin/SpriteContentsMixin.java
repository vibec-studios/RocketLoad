package dev.henny.rocketload.mixin;

import com.mojang.blaze3d.textures.GpuTexture;
import dev.henny.rocketload.client.BackgroundReloadDriver;
import net.minecraft.client.texture.SpriteContents;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(SpriteContents.class)
public abstract class SpriteContentsMixin {

    @Inject(method = "upload", at = @At("RETURN"))
    private void rocketload$keepWindowAlive(GpuTexture texture, int mipLevel, CallbackInfo ci) {
        if (mipLevel == 0) {
            BackgroundReloadDriver.keepWindowAlive();
        }
    }
}
