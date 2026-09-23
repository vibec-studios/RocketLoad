package dev.henny.rocketload.mixin;

import dev.henny.rocketload.client.AtlasGpuUpload;
import dev.henny.rocketload.client.BackgroundReloadDriver;
import net.minecraft.client.texture.SpriteAtlasTexture;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(SpriteAtlasTexture.class)
public abstract class SpriteAtlasTextureMixin {

    @Shadow
    private int width;

    @Shadow
    private int height;

    @Shadow
    private int mipLevel;

    @Inject(method = "createTexture", at = @At("HEAD"), cancellable = true)
    private void rocketload$keepOldAtlas(int w, int h, int mip, CallbackInfo ci) {
        if (this.width > 0 && this.width == w && this.height == h && this.mipLevel == mip) {
            ci.cancel();
        }
    }

    @Inject(method = "upload()V", at = @At("HEAD"), cancellable = true)
    private void rocketload$spreadGpuUpload(CallbackInfo ci) {
        if (!BackgroundReloadDriver.isAtlasSpread()) return;
        ci.cancel();
        AtlasGpuUpload.start((SpriteAtlasTexture) (Object) this);
    }
}
