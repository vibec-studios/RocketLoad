package dev.henny.rocketload.mixin;

import com.llamalad7.mixinextras.sugar.Local;
import dev.henny.rocketload.client.ReloadTimeline;
import java.util.concurrent.Executor;
import net.minecraft.server.packs.resources.PreparableReloadListener;
import net.minecraft.server.packs.resources.SimpleReloadInstance;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

@Mixin(SimpleReloadInstance.class)
public abstract class SimpleReloadInstanceMixin {
    private static final String CREATE = "Lnet/minecraft/server/packs/resources/SimpleReloadInstance$StateFactory;create(Lnet/minecraft/server/packs/resources/PreparableReloadListener$SharedState;Lnet/minecraft/server/packs/resources/PreparableReloadListener$PreparationBarrier;Lnet/minecraft/server/packs/resources/PreparableReloadListener;Ljava/util/concurrent/Executor;Ljava/util/concurrent/Executor;)Ljava/util/concurrent/CompletableFuture;";

    @ModifyArg(method = "prepareTasks", at = @At(value = "INVOKE", target = CREATE), index = 1)
    private PreparableReloadListener.PreparationBarrier rocketload$timeBarrier(PreparableReloadListener.PreparationBarrier barrier,
                                                                              @Local PreparableReloadListener listener) {
        return ReloadTimeline.barrier(this, listener, barrier);
    }

    @ModifyArg(method = "prepareTasks", at = @At(value = "INVOKE", target = CREATE), index = 3)
    private Executor rocketload$timeTasks(Executor executor, @Local PreparableReloadListener listener) {
        return ReloadTimeline.tasks(this, listener, executor);
    }

    @ModifyArg(method = "prepareTasks", at = @At(value = "INVOKE", target = CREATE), index = 4)
    private Executor rocketload$timeApply(Executor executor, @Local PreparableReloadListener listener) {
        return ReloadTimeline.apply(this, listener, executor);
    }
}
