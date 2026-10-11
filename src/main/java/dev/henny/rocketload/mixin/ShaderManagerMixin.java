package dev.henny.rocketload.mixin;

import net.minecraft.client.renderer.ShaderManager;
import org.spongepowered.asm.mixin.Mixin;
//? if >=26.3 {
import dev.henny.rocketload.client.ReloadSkips;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import net.minecraft.server.packs.resources.PreparableReloadListener;
import net.minecraft.util.Unit;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
//?}

@Mixin(ShaderManager.class)
public abstract class ShaderManagerMixin {
    //? if >=26.3 {
    @Inject(method = "reload", at = @At("HEAD"), cancellable = true)
    private void rocketload$skipUnchanged(
            PreparableReloadListener.SharedState state,
            Executor taskExecutor,
            PreparableReloadListener.PreparationBarrier barrier,
            Executor reloadExecutor,
            CallbackInfoReturnable<CompletableFuture<Void>> cir) {
        if (ReloadSkips.shouldSkipShaders(state.resourceManager())) {
            cir.setReturnValue(CompletableFuture.completedFuture(Unit.INSTANCE).thenCompose(barrier::wait).thenAcceptAsync(unit -> {}, reloadExecutor));
        }
    }
    //?}
}
