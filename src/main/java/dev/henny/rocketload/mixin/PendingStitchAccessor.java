package dev.henny.rocketload.mixin;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.renderer.texture.SpriteLoader;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(targets = "net.minecraft.client.resources.model.sprite.AtlasManager$PendingStitch")
public interface PendingStitchAccessor {

    @Accessor("preparations")
    CompletableFuture<SpriteLoader.Preparations> rocketload_preparations();

    @Invoker("joinAndUpload")
    void rocketload_joinAndUpload(Map<?, ?> result);
}
