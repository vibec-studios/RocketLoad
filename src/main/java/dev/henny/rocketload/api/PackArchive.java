package dev.henny.rocketload.api;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

public interface PackArchive extends AutoCloseable {
    enum Format {
        ZIP,
        DIRECTORY,
        ROCKETFUEL,
        MEMORY
    }

    Optional<Path> source();

    Format format();

    int size();

    boolean contains(String path);

    Optional<PackEntry> entry(String path);

    Stream<PackEntry> entries();

    default Stream<PackEntry> entries(EntryFilter filter) {
        return this.entries().filter(filter::test);
    }

    default byte[] read(String path) throws IOException {
        PackEntry entry = this.entry(path).orElseThrow(() -> new IOException("Nicht im Pack: " + path));
        return entry.read();
    }

    default InputStream open(String path) throws IOException {
        PackEntry entry = this.entry(path).orElseThrow(() -> new IOException("Nicht im Pack: " + path));
        return entry.open();
    }

    Set<String> namespaces(String root);

    Optional<PackMeta> meta();

    int extract(EntryFilter filter, Path targetDirectory) throws IOException;

    PackBuilder toBuilder(EntryFilter filter);

    @Override
    void close();
}
