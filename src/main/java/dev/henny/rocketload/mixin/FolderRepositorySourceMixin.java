package dev.henny.rocketload.mixin;

import dev.henny.rocketload.pack.PackMetaCache;
import java.nio.file.Path;
import java.util.function.Consumer;
import net.minecraft.server.packs.repository.FolderRepositorySource;
import net.minecraft.server.packs.repository.Pack;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(FolderRepositorySource.class)
public abstract class FolderRepositorySourceMixin {

    @Shadow
    @Final
    private Path folder;

    @Inject(method = "loadPacks", at = @At("HEAD"))
    private void rocketload$prefetchPackMeta(Consumer<Pack> result, CallbackInfo ci) {
        PackMetaCache.INSTANCE.prefetchBlocking(this.folder);
    }
}
