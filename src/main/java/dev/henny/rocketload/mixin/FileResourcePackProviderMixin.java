package dev.henny.rocketload.mixin;

import dev.henny.rocketload.pack.PackMetaCache;
import java.nio.file.Path;
import java.util.function.Consumer;
import net.minecraft.resource.FileResourcePackProvider;
import net.minecraft.resource.ResourcePackProfile;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(FileResourcePackProvider.class)
public abstract class FileResourcePackProviderMixin {

    @Shadow
    @Final
    private Path packsDir;

    @Inject(method = "register", at = @At("HEAD"))
    private void rocketload$prefetchPackMeta(Consumer<ResourcePackProfile> profileAdder, CallbackInfo ci) {
        PackMetaCache.INSTANCE.prefetchBlocking(this.packsDir);
    }
}
