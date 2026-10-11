package dev.henny.rocketload.api;

import java.nio.file.Path;

public interface RocketLoadListener {
    default void onReloadStarted() {
    }

    default void onReloadFinished(boolean success, long millis) {
    }

    default void onSkipDecision(ReloadDomain domain, boolean skipped) {
    }

    default void onPackConverted(Path source, Path cacheFile) {
    }
}
