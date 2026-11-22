package dev.henny.rocketload.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import dev.henny.rocketload.RocketLoadConfig;
import dev.henny.rocketload.client.AtlasSpread;
import dev.henny.rocketload.client.BackgroundReloadDriver;
import dev.henny.rocketload.client.ReloadSkips;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import net.minecraft.client.resources.model.sprite.AtlasManager;
import net.minecraft.server.packs.resources.PreparableReloadListener;
import net.minecraft.util.Unit;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(AtlasManager.class)
public abstract class AtlasManagerMixin {

    @Inject(method = "reload", at = @At("HEAD"), cancellable = true)
    private void rocketload$skipUnchanged(
            PreparableReloadListener.SharedState state,
            Executor taskExecutor,
            PreparableReloadListener.PreparationBarrier barrier,
            Executor reloadExecutor,
            CallbackInfoReturnable<CompletableFuture<Void>> cir) {
        if (ReloadSkips.shouldSkipVisuals(state.resourceManager())) {
            cir.setReturnValue(barrier.wait(Unit.INSTANCE).thenAccept(unit -> {}));
        }
    }

    @WrapOperation(
        method = "reload",
        at = @At(value = "INVOKE", target = "Ljava/util/concurrent/CompletableFuture;thenAcceptAsync(Ljava/util/function/Consumer;Ljava/util/concurrent/Executor;)Ljava/util/concurrent/CompletableFuture;")
    )
    @SuppressWarnings("unchecked")
    private CompletableFuture<Void> rocketload$spreadUpload(
            CompletableFuture<?> prepared,
            Consumer<?> apply,
            Executor executor,
            Operation<CompletableFuture<Void>> original,
            @Local(argsOnly = true) PreparableReloadListener.SharedState state) {
        if (!BackgroundReloadDriver.isQueuedApply() || !RocketLoadConfig.getInstance().getSpreadAtlasUpload()) {
            return original.call(prepared, apply, executor);
        }
        AtlasSpread.PendingStitches results = (AtlasSpread.PendingStitches) state.get(AtlasManager.PENDING_STITCH);
        return AtlasSpread.spread(results, prepared, (Consumer<Object>) apply, executor);
    }
}
