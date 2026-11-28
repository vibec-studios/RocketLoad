package dev.henny.rocketload.mixin;

import net.minecraft.client.renderer.texture.AbstractTexture;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
//? if >=26.3 {
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
//?} else {
/*import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
*///?}

@Mixin(AbstractTexture.class)
public interface AbstractTextureAccessor {

    @Accessor("texture")
    void rocketload_setTexture(GpuTexture texture);

    @Accessor("textureView")
    void rocketload_setTextureView(GpuTextureView view);
}
