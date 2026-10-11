package dev.henny.rocketload.mixin;

import net.minecraft.client.renderer.ViewArea;
import org.spongepowered.asm.mixin.Mixin;
//? if <26.2 {
/*import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import org.spongepowered.asm.mixin.gen.Accessor;
*///?}

@Mixin(ViewArea.class)
public interface ViewAreaAccessor {
    //? if <26.2 {
    /*@Accessor("sections")
    SectionRenderDispatcher.RenderSection[] rocketload_sections();
    *///?}
}
