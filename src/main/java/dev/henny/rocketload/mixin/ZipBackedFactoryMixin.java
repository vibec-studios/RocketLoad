package dev.henny.rocketload.mixin;

import dev.henny.rocketload.RocketLoadHooks;
import dev.henny.rocketload.pack.PackMetaCache;
import java.io.File;
import net.minecraft.resource.ResourcePack;
import net.minecraft.resource.ResourcePackInfo;
import net.minecraft.resource.ResourcePackProfile;
import net.minecraft.resource.ZipResourcePack;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ZipResourcePack.ZipBackedFactory.class)
public abstract class ZipBackedFactoryMixin {

    @Shadow
    @Final
    private File file;

    @Inject(method = "open", at = @At("HEAD"), cancellable = true)
    private void rocketload$openMetadataOnly(ResourcePackInfo info, CallbackInfoReturnable<ResourcePack> cir) {
        if (RocketLoadHooks.isFullOpen()) return;
        ResourcePack pack = PackMetaCache.INSTANCE.openListPack(this.file, info);
        if (pack != null) {
            cir.setReturnValue(pack);
        }
    }

    @Inject(method = "openWithOverlays", at = @At("HEAD"), cancellable = true)
    private void rocketload$openWithOverlays(ResourcePackInfo info, ResourcePackProfile.Metadata metadata,
            CallbackInfoReturnable<ResourcePack> cir) {
        if (!RocketLoadHooks.isFullOpen()) return;
        ResourcePack pack = RocketLoadHooks.openFull(this.file, info, metadata);
        if (pack != null) {
            cir.setReturnValue(pack);
        }
    }
}
