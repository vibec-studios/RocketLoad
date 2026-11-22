package dev.henny.rocketload.mixin;

import dev.henny.rocketload.fuel.RocketFuelCache;
import java.nio.file.Path;
import net.minecraft.util.HttpUtil;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(HttpUtil.class)
public abstract class HttpUtilMixin {
    @Inject(method = "downloadFile", at = @At("RETURN"))
    private static void rocketload$rememberVerifiedDownload(CallbackInfoReturnable<Path> cir) {
        Path downloaded = cir.getReturnValue();
        if (downloaded != null) {
            RocketFuelCache.INSTANCE.registerVerifiedDownload(downloaded);
        }
    }
}
