package dev.henny.rocketload.mixin;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.texture.AtlasManager;
import net.minecraft.client.texture.SpriteLoader;
import net.minecraft.util.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(AtlasManager.Stitch.class)
public interface AtlasManagerStitchAccessor {

    @Accessor("entries")
    List<?> rocketload_getEntries();

    @Accessor("preparations")
    Map<Identifier, CompletableFuture<SpriteLoader.StitchResult>> rocketload_getPreparations();
}
