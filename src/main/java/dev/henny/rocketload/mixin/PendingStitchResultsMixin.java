package dev.henny.rocketload.mixin;

import dev.henny.rocketload.client.AtlasSpread;
import java.util.List;
import java.util.Map;
import net.minecraft.client.resources.model.sprite.AtlasManager;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(AtlasManager.PendingStitchResults.class)
public abstract class PendingStitchResultsMixin implements AtlasSpread.PendingStitches {

    @Shadow
    @Final
    List<?> pendingStitches;

    @Override
    public List<?> rocketload_pendingStitches() {
        return this.pendingStitches;
    }

    @Inject(method = "joinAndUpload", at = @At("HEAD"), cancellable = true)
    @SuppressWarnings({"rawtypes", "unchecked"})
    private void rocketload$usePrefilled(CallbackInfoReturnable cir) {
        Map<?, ?> prefilled = AtlasSpread.takePrefilled(this);
        if (prefilled != null) {
            cir.setReturnValue(prefilled);
        }
    }
}
