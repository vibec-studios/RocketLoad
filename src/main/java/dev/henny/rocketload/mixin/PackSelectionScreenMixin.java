package dev.henny.rocketload.mixin;

import dev.henny.rocketload.client.PackIconCache;
import dev.henny.rocketload.client.PackOptimize;
import java.nio.file.Path;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.packs.PackSelectionScreen;
import net.minecraft.client.gui.screens.packs.TransferableSelectionList;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.repository.Pack;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(PackSelectionScreen.class)
public abstract class PackSelectionScreenMixin extends Screen {

    @Shadow
    @Final
    private static Identifier DEFAULT_ICON;

    @Shadow
    @Final
    private Path packDir;

    @Shadow
    private TransferableSelectionList selectedPackList;

    @Unique
    private Button rocketload$optimize;

    protected PackSelectionScreenMixin(Component title) {
        super(title);
    }

    @Unique
    private boolean rocketload$isResourcePackScreen() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null) {
            return false;
        }
        Path resourcePacks = minecraft.getResourcePackDirectory();
        return resourcePacks != null && resourcePacks.toAbsolutePath().normalize().equals(this.packDir.toAbsolutePath().normalize());
    }

    @Inject(method = "init", at = @At("TAIL"))
    private void rocketload$addOptimizeButton(CallbackInfo ci) {
        if (!this.rocketload$isResourcePackScreen()) {
            this.rocketload$optimize = null;
            return;
        }
        this.rocketload$optimize = this.addRenderableWidget(
            Button.builder(Component.translatable("rocketload.pack.optimize"), button -> PackOptimize.start(this.packDir))
                .bounds(4, 4, 80, 16)
                .tooltip(Tooltip.create(Component.translatable("rocketload.pack.optimize_tooltip")))
                .build()
        );
        this.rocketload$positionButton();
    }

    @Inject(method = "repositionElements", at = @At("TAIL"))
    private void rocketload$repositionButton(CallbackInfo ci) {
        this.rocketload$positionButton();
    }

    @Inject(method = "tick", at = @At("TAIL"))
    private void rocketload$updateButton(CallbackInfo ci) {
        if (this.rocketload$optimize != null) {
            this.rocketload$optimize.active = !PackOptimize.isRunning();
        }
    }

    @Inject(method = "getPackIcon", at = @At("HEAD"), cancellable = true)
    private void rocketload$asyncIcon(Pack pack, CallbackInfoReturnable<Identifier> cir) {
        cir.setReturnValue(PackIconCache.get(pack, DEFAULT_ICON));
    }

    @Unique
    private void rocketload$positionButton() {
        if (this.selectedPackList == null || this.rocketload$optimize == null) {
            return;
        }
        int y = Math.max(2, this.selectedPackList.getY() - 18);
        this.rocketload$optimize.setPosition(this.selectedPackList.getX() + this.selectedPackList.getWidth() - 80, y);
    }
}
