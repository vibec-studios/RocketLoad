package dev.henny.rocketload.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import dev.henny.rocketload.client.BackgroundReloadDriver;
import dev.henny.rocketload.client.ReloadDebug;
import dev.henny.rocketload.client.ReloadSkips;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Overlay;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.resources.ReloadInstance;
import net.minecraft.server.packs.resources.ReloadableResourceManager;
import net.minecraft.util.Unit;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
//? if >=26.2 {
import net.minecraft.client.gui.Gui;
//?}

@Mixin(Minecraft.class)
public abstract class MinecraftMixin {

    //? if >=26.2 {
    private static final String RELOAD = "reloadResourcePacks(ZLnet/minecraft/client/GameLoadCookie;)Ljava/util/concurrent/CompletableFuture;";
    private static final String SET_OVERLAY = "Lnet/minecraft/client/gui/Gui;setOverlay(Lnet/minecraft/client/gui/screens/Overlay;)V";
    //?} else {
    /*private static final String RELOAD = "reloadResourcePacks(ZLnet/minecraft/client/Minecraft$GameLoadCookie;)Ljava/util/concurrent/CompletableFuture;";
    private static final String SET_OVERLAY = "Lnet/minecraft/client/Minecraft;setOverlay(Lnet/minecraft/client/gui/screens/Overlay;)V";
    *///?}
    private static final String CREATE_RELOAD = "Lnet/minecraft/server/packs/resources/ReloadableResourceManager;createReload(Ljava/util/concurrent/Executor;Ljava/util/concurrent/Executor;Ljava/util/concurrent/CompletableFuture;Ljava/util/List;)Lnet/minecraft/server/packs/resources/ReloadInstance;";

    @Inject(method = "isPaused", at = @At("HEAD"), cancellable = true)
    private void rocketload$pauseDuringReload(CallbackInfoReturnable<Boolean> cir) {
        if (BackgroundReloadDriver.shouldPauseIntegratedServer((Minecraft) (Object) this)) {
            cir.setReturnValue(true);
        }
    }

    @Inject(method = "runTick", at = @At("TAIL"))
    private void rocketload$pumpApply(boolean advanceGameTime, CallbackInfo ci) {
        BackgroundReloadDriver.pumpApply();
    }

    @Inject(method = RELOAD, at = @At("HEAD"), cancellable = true)
    private void rocketload$interceptReload(boolean isRecovery, @Coerce Object loadCookie, CallbackInfoReturnable<CompletableFuture<Void>> cir) {
        CompletableFuture<Void> replacement = BackgroundReloadDriver.interceptReloadRequest((Minecraft) (Object) this, isRecovery);
        if (replacement != null) {
            cir.setReturnValue(replacement);
        }
    }

    //? if >=26.2 {
    @WrapOperation(method = RELOAD, at = @At(value = "INVOKE", target = SET_OVERLAY))
    private void rocketload$suppressSplash(Gui gui, Overlay overlay, Operation<Void> original, @Local(argsOnly = true) boolean isRecovery) {
        if (isRecovery || !BackgroundReloadDriver.begin((Minecraft) (Object) this, overlay)) {
            original.call(gui, overlay);
        }
    }
    //?} else {
    /*@WrapOperation(method = RELOAD, at = @At(value = "INVOKE", target = SET_OVERLAY))
    private void rocketload$suppressSplash(Minecraft minecraft, Overlay overlay, Operation<Void> original, @Local(argsOnly = true) boolean isRecovery) {
        if (isRecovery || !BackgroundReloadDriver.begin((Minecraft) (Object) this, overlay)) {
            original.call(minecraft, overlay);
        }
    }
    *///?}

    @WrapOperation(method = RELOAD, at = @At(value = "INVOKE", target = CREATE_RELOAD))
    private ReloadInstance rocketload$throttleApply(
            ReloadableResourceManager manager,
            Executor backgroundExecutor,
            Executor mainThreadExecutor,
            CompletableFuture<Unit> initialTask,
            List<PackResources> packs,
            Operation<ReloadInstance> original,
            @Local(argsOnly = true) boolean isRecovery) {
        ReloadSkips.beginReload(packs);
        ReloadDebug.packs(packs);
        Executor apply = BackgroundReloadDriver.applyExecutor(mainThreadExecutor, isRecovery);
        return original.call(manager, backgroundExecutor, apply, initialTask, packs);
    }
}
