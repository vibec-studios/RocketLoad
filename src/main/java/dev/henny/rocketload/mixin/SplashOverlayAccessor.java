package dev.henny.rocketload.mixin;

import java.util.Optional;
import java.util.function.Consumer;
import net.minecraft.client.gui.screen.SplashOverlay;
import net.minecraft.resource.ResourceReload;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(SplashOverlay.class)
public interface SplashOverlayAccessor {

    @Accessor("reload")
    ResourceReload rocketload_getReload();

    @Accessor("exceptionHandler")
    Consumer<Optional<Throwable>> rocketload_getExceptionHandler();
}
