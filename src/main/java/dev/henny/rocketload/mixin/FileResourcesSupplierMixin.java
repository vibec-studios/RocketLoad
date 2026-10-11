package dev.henny.rocketload.mixin;

import dev.henny.rocketload.RocketLoadHooks;
import dev.henny.rocketload.pack.PackListResources;
import java.io.File;
import net.minecraft.server.packs.FilePackResources;
import net.minecraft.server.packs.PackLocationInfo;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.repository.Pack;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
//? if >=26.3 {
import java.util.stream.Stream;
import net.minecraft.server.packs.PackMetadataResources;
//?}

@Mixin(FilePackResources.FileResourcesSupplier.class)
public abstract class FileResourcesSupplierMixin {

    @Shadow
    @Final
    private File content;

    //? if >=26.3 {
    @Inject(method = "openMetadata", at = @At("HEAD"), cancellable = true)
    private void rocketload$openMetadata(PackLocationInfo location, CallbackInfoReturnable<PackMetadataResources> cir) {
        PackListResources pack = RocketLoadHooks.openMetadata(this.content, location);
        if (pack != null) {
            cir.setReturnValue(pack);
        }
    }

    @Inject(method = "openResources", at = @At("HEAD"), cancellable = true)
    private void rocketload$openResources(PackLocationInfo location, Pack.Metadata metadata, CallbackInfoReturnable<Stream<PackResources>> cir) {
        if (!RocketLoadHooks.isFullOpen()) {
            return;
        }
        PackResources pack = RocketLoadHooks.openFull(this.content, location, metadata);
        if (pack != null) {
            cir.setReturnValue(Stream.of(pack));
        }
    }

    @Inject(method = "openResources", at = @At("RETURN"), cancellable = true)
    private void rocketload$trackVanilla(PackLocationInfo location, Pack.Metadata metadata, CallbackInfoReturnable<Stream<PackResources>> cir) {
        Stream<PackResources> result = cir.getReturnValue();
        if (result != null) {
            File file = this.content;
            cir.setReturnValue(result.peek(pack -> RocketLoadHooks.trackZip(pack, file)));
        }
    }
    //?} else {
    /*@Inject(method = "openPrimary", at = @At("HEAD"), cancellable = true)
    private void rocketload$openMetadata(PackLocationInfo location, CallbackInfoReturnable<PackResources> cir) {
        PackListResources pack = RocketLoadHooks.openMetadata(this.content, location);
        if (pack != null) {
            cir.setReturnValue(pack);
        }
    }

    @Inject(method = "openFull", at = @At("HEAD"), cancellable = true)
    private void rocketload$openResources(PackLocationInfo location, Pack.Metadata metadata, CallbackInfoReturnable<PackResources> cir) {
        if (!RocketLoadHooks.isFullOpen()) {
            return;
        }
        PackResources pack = RocketLoadHooks.openFull(this.content, location, metadata);
        if (pack != null) {
            cir.setReturnValue(pack);
        }
    }

    @Inject(method = "openFull", at = @At("RETURN"))
    private void rocketload$trackVanilla(PackLocationInfo location, Pack.Metadata metadata, CallbackInfoReturnable<PackResources> cir) {
        PackResources pack = cir.getReturnValue();
        if (pack != null) {
            RocketLoadHooks.trackZip(pack, this.content);
        }
    }
    *///?}
}
