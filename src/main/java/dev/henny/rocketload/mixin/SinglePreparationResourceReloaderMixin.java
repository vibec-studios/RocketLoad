package dev.henny.rocketload.mixin;

import dev.henny.rocketload.client.ReloadSkips;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import net.minecraft.resource.ResourceReloader;
import net.minecraft.resource.SinglePreparationResourceReloader;
import net.minecraft.util.Unit;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(SinglePreparationResourceReloader.class)
public abstract class SinglePreparationResourceReloaderMixin {

    @Inject(method = "reload", at = @At("HEAD"), cancellable = true)
    private void rocketload$skipUnchangedDomain(
            ResourceReloader.Store store,
            Executor prepareExecutor,
            ResourceReloader.Synchronizer synchronizer,
            Executor applyExecutor,
            CallbackInfoReturnable<CompletableFuture<Void>> cir) {
        if (ReloadSkips.shouldSkipSimpleListener(this, store.getResourceManager())) {
            cir.setReturnValue(
                CompletableFuture.completedFuture(Unit.INSTANCE)
                    .thenCompose(synchronizer::whenPrepared)
                    .thenAcceptAsync(unit -> {}, applyExecutor)
            );
        }
    }
}
