package dev.henny.rocketload.mixin;

import dev.henny.rocketload.client.ReloadSkips;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import net.minecraft.client.texture.TextureManager;
import net.minecraft.resource.ResourceReloader;
import net.minecraft.util.Unit;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(TextureManager.class)
public abstract class TextureManagerMixin {

    @Inject(method = "reload", at = @At("HEAD"), cancellable = true)
    private void rocketload$skipUnchangedTextures(
            ResourceReloader.Store store,
            Executor prepareExecutor,
            ResourceReloader.Synchronizer synchronizer,
            Executor applyExecutor,
            CallbackInfoReturnable<CompletableFuture<Void>> cir) {
        if (ReloadSkips.shouldSkipTextureReload(store.getResourceManager())) {
            cir.setReturnValue(
                CompletableFuture.completedFuture(Unit.INSTANCE)
                    .thenCompose(synchronizer::whenPrepared)
                    .thenAcceptAsync(unit -> {}, applyExecutor)
            );
        }
    }
}
