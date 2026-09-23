package dev.henny.rocketload.mixin;

import dev.henny.rocketload.client.PackIconCache;
import dev.henny.rocketload.client.PackOptimize;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.pack.PackListWidget;
import net.minecraft.client.gui.screen.pack.PackScreen;
import net.minecraft.client.gui.screen.pack.ResourcePackOrganizer;
import net.minecraft.client.gui.tooltip.Tooltip;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.resource.ResourcePackProfile;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.gen.Invoker;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.nio.file.Path;

@Mixin(PackScreen.class)
public abstract class PackScreenMixin extends Screen {

    @Shadow
    @Final
    private Path file;

    @Shadow
    @Final
    static Identifier UNKNOWN_PACK;

    @Shadow
    private PackListWidget selectedPackList;

    @Unique
    private ButtonWidget rocketload$optimize;

    @Unique
    private boolean rocketload$flushingLists;

    @Unique
    private boolean rocketload$listsDirty;

    @Unique
    private ResourcePackOrganizer.AbstractPack rocketload$focused;

    @Invoker("updatePackLists")
    abstract void rocketload_invokeUpdatePackLists(ResourcePackOrganizer.AbstractPack focused);

    protected PackScreenMixin(Text title) {
        super(title);
    }

    @Inject(method = "close", at = @At("HEAD"))
    private void rocketload$leaveScreenBeforeApply(CallbackInfo ci) {
        if (!dev.henny.rocketload.RocketLoadConfig.getInstance().getClosePackScreenImmediately()) return;
        MinecraftClient client = MinecraftClient.getInstance();
        if (client != null && client.world != null) {
            client.setScreen(null);
        }
    }

    @Inject(method = "init", at = @At("TAIL"))
    private void rocketload$addOptimize(CallbackInfo ci) {
        this.rocketload$optimize = this.addDrawableChild(
            ButtonWidget.builder(Text.translatable("rocketload.pack.optimize"), button -> PackOptimize.start(this.file))
                .dimensions(4, 4, 80, 16)
                .tooltip(Tooltip.of(Text.translatable("rocketload.pack.optimize_tooltip")))
                .build()
        );
        this.rocketload$positionButton();
        this.rocketload$flushingLists = true;
        try {
            this.rocketload_invokeUpdatePackLists(null);
        } finally {
            this.rocketload$flushingLists = false;
            this.rocketload$listsDirty = false;
        }
    }

    @Inject(method = "refreshWidgetPositions", at = @At("TAIL"))
    private void rocketload$repositionButton(CallbackInfo ci) {
        this.rocketload$positionButton();
    }

    @Inject(method = "tick", at = @At("TAIL"))
    private void rocketload$flushLists(CallbackInfo ci) {
        if (this.rocketload$optimize != null) {
            this.rocketload$optimize.active = !PackOptimize.isRunning();
        }
        if (this.rocketload$listsDirty && !this.rocketload$flushingLists) {
            this.rocketload$listsDirty = false;
            this.rocketload$flushingLists = true;
            try {
                this.rocketload_invokeUpdatePackLists(this.rocketload$focused);
            } finally {
                this.rocketload$flushingLists = false;
            }
        }
    }

    @Inject(method = "updatePackLists", at = @At("HEAD"), cancellable = true)
    private void rocketload$debounceLists(ResourcePackOrganizer.AbstractPack focused, CallbackInfo ci) {
        if (this.rocketload$flushingLists) return;
        this.rocketload$focused = focused;
        this.rocketload$listsDirty = true;
        ci.cancel();
    }

    @Inject(method = "getPackIconTexture", at = @At("HEAD"), cancellable = true)
    private void rocketload$asyncIcons(ResourcePackProfile profile, CallbackInfoReturnable<Identifier> cir) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null) return;
        cir.setReturnValue(PackIconCache.get(profile, UNKNOWN_PACK, client.getTextureManager()));
    }

    @Unique
    private void rocketload$positionButton() {
        if (this.selectedPackList == null || this.rocketload$optimize == null) return;
        int y = Math.max(2, this.selectedPackList.getY() - 18);
        this.rocketload$optimize.setPosition(this.selectedPackList.getX() + this.selectedPackList.getWidth() - 80, y);
    }
}
