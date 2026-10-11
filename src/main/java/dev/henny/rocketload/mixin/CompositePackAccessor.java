package dev.henny.rocketload.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
//? if >=26.3 {
import net.minecraft.server.packs.OverlayedPackResources;
import net.minecraft.server.packs.PackMetadataResources;
//?} else {
/*import net.minecraft.server.packs.CompositePackResources;
import net.minecraft.server.packs.PackResources;
*///?}

//? if >=26.3 {
@Mixin(OverlayedPackResources.class)
//?} else {
/*@Mixin(CompositePackResources.class)
*///?}
public interface CompositePackAccessor {

    //? if >=26.3 {
    @Accessor("primaryPackMetadataResources")
    PackMetadataResources rocketload_primary();
    //?} else {
    /*@Accessor("primaryPackResources")
    PackResources rocketload_primary();
    *///?}
}
