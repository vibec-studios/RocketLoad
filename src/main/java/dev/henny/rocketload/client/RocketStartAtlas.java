package dev.henny.rocketload.client;

import dev.henny.rocketload.RocketLoadConfig;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

/** Asks RocketStart (if installed) whether its direct atlas upload will handle the next atlas switch. */
final class RocketStartAtlas {
    private static volatile MethodHandle available;
    private static volatile boolean looked;

    private RocketStartAtlas() {
    }

    static boolean direct() {
        if (!RocketLoadConfig.getInstance().getPreferDirectAtlasUpload()) {
            return false;
        }
        if (!looked) {
            try {
                Class<?> type = Class.forName("dev.henny.rocketstart.atlas.FastAtlasUpload", false, RocketStartAtlas.class.getClassLoader());
                available = MethodHandles.publicLookup().findStatic(type, "available", MethodType.methodType(boolean.class));
            } catch (ReflectiveOperationException | LinkageError absent) {
                available = null;
            }
            looked = true;
        }
        MethodHandle handle = available;
        if (handle == null) {
            return false;
        }
        try {
            return (boolean) handle.invokeExact();
        } catch (Throwable error) {
            return false;
        }
    }
}
