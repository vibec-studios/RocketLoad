package dev.henny.rocketload.mixin;

import com.mojang.blaze3d.platform.NativeImage;
import dev.henny.rocketload.fuel.FuelEntryStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import org.lwjgl.system.MemoryUtil;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(NativeImage.class)
public abstract class NativeImageMixin {

    @Inject(method = "read(Ljava/io/InputStream;)Lcom/mojang/blaze3d/platform/NativeImage;", at = @At("HEAD"), cancellable = true)
    private static void rocketload$readDirect(InputStream stream, CallbackInfoReturnable<NativeImage> cir) throws IOException {
        if (!(stream instanceof FuelEntryStream fuelStream) || !fuelStream.getUntouched() || fuelStream.getSize() <= 0) {
            return;
        }
        ByteBuffer buffer = MemoryUtil.memAlloc(fuelStream.getSize());
        try {
            fuelStream.readInto(buffer);
            buffer.flip();
            cir.setReturnValue(NativeImage.read(buffer));
        } finally {
            MemoryUtil.memFree(buffer);
            stream.close();
        }
    }
}
