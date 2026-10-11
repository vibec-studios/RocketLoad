package dev.henny.rocketload.api;

import java.io.IOException;
import java.io.InputStream;
import java.util.Optional;

public interface PackEntry {
    String path();

    long size();

    default AssetCategory category() {
        return AssetCategory.of(this.path());
    }

    default Optional<String> namespace() {
        String path = this.path();
        int first = path.indexOf('/');
        if (first < 0) {
            return Optional.empty();
        }
        int second = path.indexOf('/', first + 1);
        return second < 0 ? Optional.empty() : Optional.of(path.substring(first + 1, second));
    }

    default String fileName() {
        String path = this.path();
        return path.substring(path.lastIndexOf('/') + 1);
    }

    byte[] read() throws IOException;

    InputStream open() throws IOException;
}
