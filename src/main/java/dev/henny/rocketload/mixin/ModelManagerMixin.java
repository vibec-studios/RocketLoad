package dev.henny.rocketload.mixin;

import dev.henny.rocketload.client.ReloadSkips;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import net.minecraft.client.resources.model.ModelManager;
import net.minecraft.server.packs.resources.PreparableReloadListener;
import net.minecraft.util.Unit;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ModelManager.class)
public abstract class ModelManagerMixin {

    @Inject(method = "reload", at = @At("HEAD"), cancellable = true)
    private void rocketload$skipUnchanged(
            PreparableReloadListener.SharedState state,
            Executor taskExecutor,
            PreparableReloadListener.PreparationBarrier barrier,
            Executor reloadExecutor,
            CallbackInfoReturnable<CompletableFuture<Void>> cir) {
        if (ReloadSkips.shouldSkipVisuals(state.resourceManager())) {
            cir.setReturnValue(CompletableFuture.completedFuture(Unit.INSTANCE).thenCompose(barrier::wait).thenAcceptAsync(unit -> {}, reloadExecutor));
        }
    }
}
