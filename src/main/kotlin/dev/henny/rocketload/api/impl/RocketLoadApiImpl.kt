package dev.henny.rocketload.api.impl

import dev.henny.rocketload.api.EntryFilter
import dev.henny.rocketload.api.PackArchive
import dev.henny.rocketload.api.PackBuilder
import dev.henny.rocketload.api.PackDiff
import dev.henny.rocketload.api.ReloadDomain
import dev.henny.rocketload.api.ReloadStatus
import dev.henny.rocketload.api.RocketLoadApi
import dev.henny.rocketload.api.RocketLoadListener
import dev.henny.rocketload.api.SkipVeto
import dev.henny.rocketload.client.BackgroundReloadDriver
import dev.henny.rocketload.fuel.RocketFuelCache
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.SharedConstants
import net.minecraft.client.Minecraft
import net.minecraft.server.packs.PackType
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.util.Optional
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList

object RocketLoadApiImpl : RocketLoadApi {
    private val logger = LoggerFactory.getLogger("RocketLoad")

    private val listeners = CopyOnWriteArrayList<RocketLoadListener>()
    private val vetoes = CopyOnWriteArrayList<SkipVeto>()

    init {
        PackBuilderImpl.defaultFormat = {
            runCatching { SharedConstants.getCurrentVersion().packVersion(PackType.CLIENT_RESOURCES).major() }.getOrNull()
        }
    }

    override fun apiVersion(): Int = RocketLoadApi.API_VERSION

    override fun open(path: Path): PackArchive = ArchiveOpener.open(path)

    override fun openCached(sourcePack: Path): Optional<PackArchive> {
        val cached = RocketFuelCache.cachedFile(sourcePack) ?: return Optional.empty()
        return Optional.of(FuelArchive(cached))
    }

    override fun convert(sourcePack: Path): CompletableFuture<Path> = RocketFuelCache.convertAsync(sourcePack)

    override fun newPack(): PackBuilder = PackBuilderImpl()

    override fun merge(archivesLowestPriorityFirst: List<PackArchive>, filter: EntryFilter): PackBuilder {
        val builder = PackBuilderImpl()
        for (archive in archivesLowestPriorityFirst) builder.putAll(archive, filter)
        return builder
    }

    override fun diff(before: PackArchive, after: PackArchive): PackDiff = RocketLoadApiDiff.diff(before, after)

    override fun extract(pack: Path, filter: EntryFilter, targetDirectory: Path): Int =
        open(pack).use { it.extract(filter, targetDirectory) }

    override fun cacheDirectory(): Path = RocketFuelCache.cacheDir

    override fun resourcePackDirectory(): Path = FabricLoader.getInstance().gameDir.resolve("resourcepacks")

    override fun availablePacks(): List<Path> {
        val dir = resourcePackDirectory()
        if (!Files.isDirectory(dir)) return emptyList()
        return Files.list(dir).use { stream ->
            stream.filter { path ->
                val name = path.fileName.toString()
                !name.startsWith(".") &&
                    ((Files.isRegularFile(path) && name.endsWith(".zip", true)) ||
                        (Files.isDirectory(path) && Files.isRegularFile(path.resolve("pack.mcmeta"))))
            }.sorted().toList()
        }
    }

    override fun activePackIds(): List<String> =
        Minecraft.getInstance()?.resourcePackRepository?.selectedIds?.toList() ?: emptyList()

    override fun packFile(packId: String): Optional<Path> {
        if (!packId.startsWith("file/")) return Optional.empty()
        val path = resourcePackDirectory().resolve(packId.substring(5))
        return if (Files.exists(path)) Optional.of(path) else Optional.empty()
    }

    override fun reloadStatus(): ReloadStatus =
        ReloadStatus(BackgroundReloadDriver.isReloadActive(), BackgroundReloadDriver.progress())

    override fun requestReload(): CompletableFuture<Void> {
        val mc = Minecraft.getInstance() ?: return CompletableFuture.failedFuture(IllegalStateException("Kein Client"))
        val result = CompletableFuture<Void>()
        mc.execute {
            mc.reloadResourcePacks().whenComplete { _, error ->
                if (error != null) result.completeExceptionally(error) else result.complete(null)
            }
        }
        return result
    }

    override fun addListener(listener: RocketLoadListener) {
        listeners.addIfAbsent(listener)
    }

    override fun removeListener(listener: RocketLoadListener) {
        listeners.remove(listener)
    }

    override fun addSkipVeto(veto: SkipVeto) {
        vetoes.addIfAbsent(veto)
    }

    override fun removeSkipVeto(veto: SkipVeto) {
        vetoes.remove(veto)
    }

    @JvmStatic
    fun vetoSkip(domain: ReloadDomain): Boolean = vetoes.any { veto ->
        runCatching { veto.vetoSkip(domain) }.onFailure { logger.warn("[RocketLoad] Skip-Veto wirft Fehler", it) }.getOrDefault(true)
    }

    private inline fun dispatch(action: (RocketLoadListener) -> Unit) {
        for (listener in listeners) {
            try {
                action(listener)
            } catch (t: Throwable) {
                logger.warn("[RocketLoad] API-Listener wirft Fehler", t)
            }
        }
    }

    @JvmStatic
    fun fireReloadStarted() = dispatch { it.onReloadStarted() }

    @JvmStatic
    fun fireReloadFinished(success: Boolean, millis: Long) = dispatch { it.onReloadFinished(success, millis) }

    @JvmStatic
    fun fireSkipDecision(domain: ReloadDomain, skipped: Boolean) = dispatch { it.onSkipDecision(domain, skipped) }

    @JvmStatic
    fun firePackConverted(source: Path, cacheFile: Path) = dispatch { it.onPackConverted(source, cacheFile) }
}
