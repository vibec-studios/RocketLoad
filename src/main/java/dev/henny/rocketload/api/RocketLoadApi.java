package dev.henny.rocketload.api;

import dev.henny.rocketload.api.impl.RocketLoadApiImpl;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

public interface RocketLoadApi {
    int API_VERSION = 1;

    static RocketLoadApi get() {
        return RocketLoadApiImpl.INSTANCE;
    }

    int apiVersion();

    PackArchive open(Path path) throws IOException;

    Optional<PackArchive> openCached(Path sourcePack) throws IOException;

    CompletableFuture<Path> convert(Path sourcePack);

    PackBuilder newPack();

    PackBuilder merge(List<PackArchive> archivesLowestPriorityFirst, EntryFilter filter);

    PackDiff diff(PackArchive before, PackArchive after) throws IOException;

    int extract(Path pack, EntryFilter filter, Path targetDirectory) throws IOException;

    Path cacheDirectory();

    Path resourcePackDirectory();

    List<Path> availablePacks();

    List<String> activePackIds();

    Optional<Path> packFile(String packId);

    ReloadStatus reloadStatus();

    CompletableFuture<Void> requestReload();

    void addListener(RocketLoadListener listener);

    void removeListener(RocketLoadListener listener);

    void addSkipVeto(SkipVeto veto);

    void removeSkipVeto(SkipVeto veto);
}
