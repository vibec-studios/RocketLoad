package dev.henny.rocketload.mixin;

import java.util.List;
import net.minecraft.resource.ResourcePack;
import net.minecraft.resource.ResourcePackManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ResourcePackManager.class)
public abstract class ResourcePackManagerMixin {

    @Inject(method = "createResourcePacks", at = @At("HEAD"))
    private void rocketload$beginFullOpen(CallbackInfoReturnable<List<ResourcePack>> cir) {
        dev.henny.rocketload.RocketLoadHooks.beginFullOpen();
    }

    @Inject(method = "createResourcePacks", at = @At("RETURN"))
    private void rocketload$endFullOpen(CallbackInfoReturnable<List<ResourcePack>> cir) {
        dev.henny.rocketload.RocketLoadHooks.endFullOpen();
    }
}
