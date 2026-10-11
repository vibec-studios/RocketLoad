package dev.henny.rocketload.mixin;

import dev.henny.rocketload.client.ChunkRebuildGuard;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
//? if >=26.2 {
import net.minecraft.client.renderer.extract.LevelExtractor;
//?} else {
/*import net.minecraft.client.renderer.LevelRenderer;
*///?}

//? if >=26.2 {
@Mixin(LevelExtractor.class)
//?} else {
/*@Mixin(LevelRenderer.class)
*///?}
public abstract class AllChangedMixin {

    @Inject(method = "allChanged", at = @At("HEAD"), cancellable = true)
    private void rocketload$guardChunkRebuild(CallbackInfo ci) {
        if (ChunkRebuildGuard.interceptAllChanged()) {
            ci.cancel();
        }
    }
}
