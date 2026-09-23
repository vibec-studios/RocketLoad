package dev.henny.rocketload.mixin;

import dev.henny.rocketload.client.BackgroundReloadDriver;
import dev.henny.rocketload.client.ChunkRebuildGuard;
import dev.henny.rocketload.client.ReloadDebug;
import dev.henny.rocketload.client.ReloadSkips;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Overlay;
import net.minecraft.client.render.WorldRenderer;
import net.minecraft.resource.ReloadableResourceManagerImpl;
import net.minecraft.resource.ResourcePack;
import net.minecraft.resource.ResourceReload;
import net.minecraft.util.Unit;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(MinecraftClient.class)
public abstract class MinecraftClientMixin {

    @Inject(method = "isPaused", at = @At("HEAD"), cancellable = true)
    private void rocketload$pauseIntegratedServerDuringReload(CallbackInfoReturnable<Boolean> cir) {
        if (BackgroundReloadDriver.shouldPauseIntegratedServer()) {
            cir.setReturnValue(true);
        }
    }

    @Inject(method = "render", at = @At("RETURN"))
    private void rocketload$pumpApplyEachFrame(boolean tick, CallbackInfo ci) {
        BackgroundReloadDriver.pumpApply();
    }

    @Inject(
        method = "reloadResources(ZLnet/minecraft/client/MinecraftClient$LoadingContext;)Ljava/util/concurrent/CompletableFuture;",
        at = @At("HEAD"),
        cancellable = true
    )
    private void rocketload$deferAndDedup(boolean force, @Coerce Object loadingContext,
            CallbackInfoReturnable<CompletableFuture<Void>> cir) {
        if (force || BackgroundReloadDriver.shouldStartNow()) {
            ReloadSkips.beginReload();
            return;
        }
        CompletableFuture<Void> active = BackgroundReloadDriver.activeReloadFuture();
        if (active != null) {
            cir.setReturnValue(active);
            return;
        }
        cir.setReturnValue(BackgroundReloadDriver.deferStart((MinecraftClient) (Object) this));
    }

    @Redirect(
        method = "reloadResources(ZLnet/minecraft/client/MinecraftClient$LoadingContext;)Ljava/util/concurrent/CompletableFuture;",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/MinecraftClient;setOverlay(Lnet/minecraft/client/gui/screen/Overlay;)V"
        )
    )
    private void rocketload$suppressSplash(MinecraftClient client, Overlay overlay, boolean force) {
        if (force || !BackgroundReloadDriver.begin(client, overlay)) {
            client.setOverlay(overlay);
        }
    }

    @Redirect(
        method = "reloadResources(ZLnet/minecraft/client/MinecraftClient$LoadingContext;)Ljava/util/concurrent/CompletableFuture;",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/resource/ReloadableResourceManagerImpl;reload(Ljava/util/concurrent/Executor;Ljava/util/concurrent/Executor;Ljava/util/concurrent/CompletableFuture;Ljava/util/List;)Lnet/minecraft/resource/ResourceReload;"
        )
    )
    private ResourceReload rocketload$throttleApply(ReloadableResourceManagerImpl manager, Executor prepareExecutor,
            Executor applyExecutor, CompletableFuture<Unit> initialStage, List<ResourcePack> packs, boolean force) {
        Executor throttled = BackgroundReloadDriver.applyExecutor(applyExecutor, force);
        long t0 = System.nanoTime();
        ReloadDebug.packs(packs);
        ResourceReload reload = manager.reload(prepareExecutor, throttled, initialStage, packs);
        ReloadDebug.log("manager.reload returned in {} ms (prepare started)", (System.nanoTime() - t0) / 1_000_000);
        return reload;
    }

    @Redirect(
        method = "method_29339",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/render/WorldRenderer;reload()V"
        )
    )
    private void rocketload$maybeSkipChunkRebuild(WorldRenderer worldRenderer) {
        ChunkRebuildGuard.maybeReload((MinecraftClient) (Object) this, worldRenderer);
    }
}
