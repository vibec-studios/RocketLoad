package dev.henny.rocketload.api

import com.google.gson.JsonParser
import dev.henny.rocketload.api.impl.ArchiveOpener
import dev.henny.rocketload.api.impl.PackBuilderImpl
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class PackApiTest {

    @TempDir
    lateinit var dir: Path

    private fun source(): PackBuilder = PackBuilderImpl()
        .put("pack.mcmeta", """{"pack":{"description":"Quelle","pack_format":75}}""".toByteArray())
        .put("assets/minecraft/textures/block/stone.png", byteArrayOf(1, 2, 3))
        .put("assets/minecraft/textures/block/stone.png.mcmeta", """{"animation":{}}""".toByteArray())
        .put("assets/minecraft/textures/colormap/grass.png", byteArrayOf(4))
        .put("assets/minecraft/models/block/stone.json", "{}".toByteArray())
        .put("assets/minecraft/sounds/dig/stone1.ogg", byteArrayOf(5, 6))
        .put("assets/mymod/textures/item/gem.png", byteArrayOf(7))
        .put("data/mymod/recipe/gem.json", "{}".toByteArray())

    @Test
    fun categoriesAreDetected() {
        assertEquals(AssetCategory.TEXTURE, AssetCategory.of("assets/minecraft/textures/block/a.png"))
        assertEquals(AssetCategory.TEXTURE_METADATA, AssetCategory.of("assets/minecraft/textures/block/a.png.mcmeta"))
        assertEquals(AssetCategory.COLORMAP, AssetCategory.of("assets/minecraft/textures/colormap/grass.png"))
        assertEquals(AssetCategory.SOUND_DEFINITION, AssetCategory.of("assets/minecraft/sounds.json"))
        assertEquals(AssetCategory.PACK_META, AssetCategory.of("pack.png"))
        assertEquals(AssetCategory.DATA, AssetCategory.of("data/x/y.json"))
        assertEquals(AssetCategory.OTHER, AssetCategory.of("assets/minecraft/optifine/ctm/a.png"))
    }

    @Test
    fun globMatchesLikeExpected() {
        val regex = Regex(EntryFilter.globToRegex("assets/*/textures/**/*.{png,mcmeta}"))
        assertTrue(regex.matches("assets/minecraft/textures/block/stone.png"))
        assertTrue(regex.matches("assets/minecraft/textures/stone.png"))
        assertTrue(regex.matches("assets/minecraft/textures/a/b/c.mcmeta"))
        assertFalse(regex.matches("assets/minecraft/models/block/stone.json"))
        assertFalse(regex.matches("assets/a/b/textures/x.png"))
    }

    @Test
    fun extractOnlyTexturesIntoNewRocketFuelPack() {
        val full = source().writeZip(dir.resolve("full.zip"))
        ArchiveOpener.open(full).use { archive ->
            assertEquals(PackArchive.Format.ZIP, archive.format())
            assertEquals(setOf("minecraft", "mymod"), archive.namespaces("assets"))
            assertEquals(75, archive.meta().get().maxFormat().asInt)

            val texturesOnly = archive.toBuilder(EntryFilter.textures())
                .description("Nur Texturen")
                .packFormat(75, 88)
                .writeRocketFuel(dir.resolve("textures.rocketfuel"))

            ArchiveOpener.open(texturesOnly).use { fuel ->
                assertEquals(PackArchive.Format.ROCKETFUEL, fuel.format())
                val paths = fuel.entries().map { it.path() }.toList().toSet()
                assertEquals(
                    setOf(
                        "pack.mcmeta",
                        "assets/minecraft/textures/block/stone.png",
                        "assets/minecraft/textures/block/stone.png.mcmeta",
                        "assets/minecraft/textures/colormap/grass.png",
                        "assets/mymod/textures/item/gem.png",
                    ),
                    paths,
                )
                val meta = fuel.meta().get()
                assertEquals("Nur Texturen", meta.description())
                assertEquals(75, meta.minFormat().asInt)
                assertEquals(88, meta.maxFormat().asInt)
                assertEquals(listOf<Byte>(1, 2, 3), fuel.read("assets/minecraft/textures/block/stone.png").toList())
            }
        }
    }

    @Test
    fun extractToDirectoryAndDiff() {
        val zip = source().writeZip(dir.resolve("a.zip"))
        val target = dir.resolve("extracted")
        val count = ArchiveOpener.open(zip).use { it.extract(EntryFilter.sounds().or(EntryFilter.namespaces("mymod")), target) }
        assertEquals(3, count)
        assertTrue(Files.isRegularFile(target.resolve("assets/minecraft/sounds/dig/stone1.ogg")))
        assertTrue(Files.isRegularFile(target.resolve("data/mymod/recipe/gem.json")))

        val changed = source()
            .put("assets/minecraft/models/block/stone.json", """{"parent":"x"}""".toByteArray())
            .remove { it.startsWith("data/") }
            .put("assets/minecraft/lang/de_de.json", "{}".toByteArray())
            .build()
        ArchiveOpener.open(zip).use { before ->
            val diff = dev.henny.rocketload.api.impl.RocketLoadApiDiff.diff(before, changed)
            assertEquals(listOf("assets/minecraft/lang/de_de.json"), diff.added())
            assertEquals(listOf("data/mymod/recipe/gem.json"), diff.removed())
            assertEquals(listOf("assets/minecraft/models/block/stone.json"), diff.changed())
        }
    }

    @Test
    fun renameNamespaceAndConflicts() {
        val built = source()
            .renameNamespace("mymod", "othermod")
            .conflictPolicy(ConflictPolicy.KEEP_EXISTING)
            .put("assets/minecraft/textures/block/stone.png", byteArrayOf(9))
            .build()
        assertTrue(built.contains("assets/othermod/textures/item/gem.png"))
        assertTrue(built.contains("data/othermod/recipe/gem.json"))
        assertFalse(built.contains("assets/mymod/textures/item/gem.png"))
        assertEquals(listOf<Byte>(1, 2, 3), built.read("assets/minecraft/textures/block/stone.png").toList())

        val failing = source().conflictPolicy(ConflictPolicy.FAIL)
        assertThrows(IllegalStateException::class.java) { failing.put("pack.mcmeta", ByteArray(0)) }
    }

    @Test
    fun rejectsPathTraversal() {
        assertThrows(IllegalArgumentException::class.java) { PackBuilderImpl().put("../evil.txt", ByteArray(1)) }
        assertThrows(IllegalArgumentException::class.java) { PackBuilderImpl().put("assets/../../evil.txt", ByteArray(1)) }
    }

    @Test
    fun writesDirectoryAndGeneratesMeta() {
        val out = PackBuilderImpl()
            .put("assets/minecraft/textures/a.png", byteArrayOf(1))
            .packFormat(70, 90)
            .writeDirectory(dir.resolve("folder"))
        val meta = JsonParser.parseString(Files.readString(out.resolve("pack.mcmeta"))).asJsonObject.getAsJsonObject("pack")
        assertEquals(70, meta.get("min_format").asInt)
        assertEquals(90, meta.get("max_format").asInt)
        assertFalse(meta.has("pack_format"))
        ArchiveOpener.open(out).use { assertEquals(PackArchive.Format.DIRECTORY, it.format()) }
    }

    @Test
    fun transformRewritesContent() {
        val built = source()
            .transform({ it.endsWith(".json") }) { _, bytes -> String(bytes).replace("{}", """{"x":1}""").toByteArray() }
            .build()
        assertEquals("""{"x":1}""", String(built.read("assets/minecraft/models/block/stone.json")))
    }
}
