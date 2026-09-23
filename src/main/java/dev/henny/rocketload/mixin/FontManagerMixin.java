package dev.henny.rocketload.mixin;

import dev.henny.rocketload.client.ReloadSkips;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import net.minecraft.client.font.FontManager;
import net.minecraft.resource.ResourceReloader;
import net.minecraft.util.Unit;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(FontManager.class)
public abstract class FontManagerMixin {

    @Inject(method = "reload", at = @At("HEAD"), cancellable = true)
    private void rocketload$skipUnchangedFonts(
            ResourceReloader.Store store,
            Executor prepareExecutor,
            ResourceReloader.Synchronizer synchronizer,
            Executor applyExecutor,
            CallbackInfoReturnable<CompletableFuture<Void>> cir) {
        if (ReloadSkips.shouldSkipFontReload(store.getResourceManager())) {
            cir.setReturnValue(
                CompletableFuture.completedFuture(Unit.INSTANCE)
                    .thenCompose(synchronizer::whenPrepared)
                    .thenAcceptAsync(unit -> {}, applyExecutor)
            );
        }
    }
}
