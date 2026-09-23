package dev.henny.rocketload.mixin;

import dev.henny.rocketload.client.AtlasGpuUpload;
import dev.henny.rocketload.client.BackgroundReloadDriver;
import dev.henny.rocketload.client.ReloadDebug;
import dev.henny.rocketload.client.ReloadSkips;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import net.minecraft.client.texture.AtlasManager;
import net.minecraft.client.texture.Sprite;
import net.minecraft.client.util.SpriteIdentifier;
import net.minecraft.resource.ResourceReloader;
import net.minecraft.util.Unit;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(AtlasManager.class)
public abstract class AtlasManagerMixin {

    @Inject(method = "reload", at = @At("HEAD"), cancellable = true)
    private void rocketload$skipUnchangedAtlases(
            ResourceReloader.Store store,
            Executor prepareExecutor,
            ResourceReloader.Synchronizer synchronizer,
            Executor applyExecutor,
            CallbackInfoReturnable<CompletableFuture<Void>> cir) {
        if (ReloadSkips.shouldSkipAtlasReload(store.getResourceManager())) {
            cir.setReturnValue(
                CompletableFuture.completedFuture(Unit.INSTANCE)
                    .thenCompose(synchronizer::whenPrepared)
                    .thenAcceptAsync(unit -> {}, applyExecutor)
            );
        }
    }

    @Redirect(
        method = "reload",
        at = @At(
            value = "INVOKE",
            target = "Ljava/util/concurrent/CompletableFuture;thenAcceptAsync(Ljava/util/function/Consumer;Ljava/util/concurrent/Executor;)Ljava/util/concurrent/CompletableFuture;"
        )
    )
    private CompletableFuture<Void> rocketload$splitAtlasApply(
            CompletableFuture<?> prepared,
            Consumer<Object> apply,
            Executor applyExecutor,
            ResourceReloader.Store store,
            Executor prepareExecutor,
            ResourceReloader.Synchronizer synchronizer,
            Executor ignoredApplyExecutor) {
        AtlasManager.Stitch stitch = store.getOrThrow(AtlasManager.stitchKey);
        if (!BackgroundReloadDriver.isQueuedApply(applyExecutor)) {
            return prepared.thenAcceptAsync(apply, applyExecutor);
        }
        List<?> entries = ((AtlasManagerStitchAccessor) (Object) stitch).rocketload_getEntries();
        Map<SpriteIdentifier, Sprite> spriteMap = new HashMap<>();
        return prepared.thenComposeAsync(ignored -> {
            CompletableFuture<Void> step = CompletableFuture.completedFuture(null);
            int atlasIndex = 0;
            for (Object entry : entries) {
                AtlasManagerCompletableEntryInvoker invoker = (AtlasManagerCompletableEntryInvoker) entry;
                final int index = atlasIndex++;
                step = step.thenComposeAsync(unused -> {
                    long t0 = System.nanoTime();
                    int before = spriteMap.size();
                    BackgroundReloadDriver.beginAtlasSpread();
                    try {
                        invoker.rocketload_fillSpriteMap(spriteMap);
                    } finally {
                        BackgroundReloadDriver.endAtlasSpread();
                    }
                    ReloadDebug.log(
                        "atlas cpu #{} sprites {}->{} in {} ms",
                        index,
                        before,
                        spriteMap.size(),
                        (System.nanoTime() - t0) / 1_000_000
                    );
                    return AtlasGpuUpload.consumeFuture();
                }, applyExecutor);
            }
            return step.thenRunAsync(() -> {
                long t0 = System.nanoTime();
                BackgroundReloadDriver.putPrefilledSpriteMap(stitch, spriteMap);
                try {
                    apply.accept(null);
                } finally {
                    BackgroundReloadDriver.removePrefilledSpriteMap(stitch);
                    ReloadDebug.log("atlas apply-finalize {} ms sprites={}", (System.nanoTime() - t0) / 1_000_000, spriteMap.size());
                }
            }, applyExecutor);
        }, applyExecutor);
    }
}
