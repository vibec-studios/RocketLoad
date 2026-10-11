package dev.henny.rocketload.api;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Predicate;

public interface PackBuilder {
    PackBuilder description(String description);

    PackBuilder packFormat(int minFormat, int maxFormat);

    PackBuilder conflictPolicy(ConflictPolicy policy);

    PackBuilder put(String path, byte[] data);

    PackBuilder put(String path, Path file);

    PackBuilder putAll(PackArchive archive, EntryFilter filter);

    PackBuilder putDirectory(Path directory, String targetPrefix);

    PackBuilder remove(Predicate<String> paths);

    PackBuilder relocate(String fromPrefix, String toPrefix);

    PackBuilder renameNamespace(String from, String to);

    PackBuilder transform(Predicate<String> paths, BiFunction<String, byte[], byte[]> transformer);

    PackBuilder optimize(boolean minifyJson, boolean optimizePng, boolean deduplicate);

    Set<String> paths();

    int size();

    Path writeRocketFuel(Path target) throws IOException;

    Path writeZip(Path target) throws IOException;

    Path writeDirectory(Path target) throws IOException;

    PackArchive build() throws IOException;
}
