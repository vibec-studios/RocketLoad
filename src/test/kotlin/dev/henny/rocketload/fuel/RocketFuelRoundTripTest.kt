package dev.henny.rocketload.fuel

import com.google.gson.JsonParser
import dev.henny.rocketload.pack.ZipRootReader
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Executors
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.imageio.ImageIO

class RocketFuelRoundTripTest {

    @TempDir
    lateinit var dir: Path

    private fun png(width: Int, height: Int, seed: Int): ByteArray {
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until height) for (x in 0 until width) {
            image.setRGB(x, y, (0xFF shl 24) or ((x * 31 + seed) and 0xFF shl 16) or ((y * 17) and 0xFF shl 8) or (seed and 0xFF))
        }
        return ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()
    }

    private fun pixels(bytes: ByteArray): IntArray {
        val image = ImageIO.read(ByteArrayInputStream(bytes))
        return image.getRGB(0, 0, image.width, image.height, null, 0, image.width)
    }

    private fun zip(entries: Map<String, ByteArray>, level: Int = Deflater.DEFAULT_COMPRESSION): Path {
        val file = dir.resolve("pack-${entries.size}-$level.zip")
        ZipOutputStream(Files.newOutputStream(file)).use { out ->
            out.setLevel(level)
            for ((name, data) in entries) {
                out.putNextEntry(ZipEntry(name))
                out.write(data)
                out.closeEntry()
            }
        }
        return file
    }

    private val texture = png(16, 16, 7)

    private val entries = linkedMapOf(
        "pack.mcmeta" to """{ "pack": { "pack_format": 75, "description": "Test §aPack" } }""".toByteArray(),
        "pack.png" to png(8, 8, 3),
        "assets/minecraft/textures/block/stone.png" to texture,
        "assets/minecraft/textures/block/dirt.png" to texture,
        "assets/minecraft/models/block/stone.json" to "{\n  \"parent\" : \"block/cube_all\"\n}".toByteArray(),
        "assets/minecraft/lang/en_us.json" to "{ // kein strenges JSON\n \"a\": \"b\" }".toByteArray(),
        "assets/example/textures/item/thing.png" to png(32, 32, 99),
        "assets/minecraft/optifine/ctm/glass/1.png" to png(16, 16, 1),
        "__MACOSX/._stone.png" to ByteArray(10),
        "assets/.DS_Store" to ByteArray(3),
    )

    @Test
    fun convertsAndReadsBack() {
        val source = zip(entries)
        val target = dir.resolve("out.rocketfuel")
        val workers = Executors.newFixedThreadPool(3)
        val stats = try {
            RocketFuelWriter.convert(source, target, RocketFuelWriter.Options(), workers)
        } finally {
            workers.shutdown()
        }
        assertEquals(2, stats.junkSkipped.get())
        assertTrue(stats.dedupedBytes.get() > 0)

        RocketFuelFile.open(target).use { fuel ->
            assertEquals(Files.size(source), fuel.sourceSize)
            assertEquals(RocketFuelFormat.WRITER_VERSION, fuel.writerVersion)
            assertEquals(8, fuel.entries.size)
            assertFalse(fuel.contains("__MACOSX/._stone.png"))

            val stone = fuel.entry("assets/minecraft/textures/block/stone.png")!!
            val dirt = fuel.entry("assets/minecraft/textures/block/dirt.png")!!
            assertEquals(stone.offset, dirt.offset)
            assertArrayEquals(pixels(texture), pixels(fuel.readBytes(stone)))

            val model = String(fuel.readBytes(fuel.entry("assets/minecraft/models/block/stone.json")!!))
            assertEquals("""{"parent":"block/cube_all"}""", model)
            assertEquals(
                JsonParser.parseString(String(entries["pack.mcmeta"]!!)),
                JsonParser.parseString(String(fuel.readBytes(fuel.entry("pack.mcmeta")!!))),
            )
            assertArrayEquals(entries["assets/minecraft/lang/en_us.json"], fuel.readBytes(fuel.entry("assets/minecraft/lang/en_us.json")!!))

            assertEquals(setOf("minecraft", "example"), fuel.namespaces("assets"))
            assertTrue(fuel.domains.textures)
            assertTrue(fuel.domains.blockModels)
            assertTrue(fuel.domains.lang)
            assertTrue(fuel.domains.unknown)
            assertTrue(fuel.domains.visuals)
            assertFalse(fuel.domains.sounds)
        }
    }

    @Test
    fun readSurvivesInterruptAndKeepsFlag() {
        val source = zip(entries)
        val target = dir.resolve("interrupt.rocketfuel")
        RocketFuelWriter.convert(source, target)
        RocketFuelFile.open(target).use { fuel ->
            val entry = fuel.entry("assets/example/textures/item/thing.png")!!
            Thread.currentThread().interrupt()
            val first = fuel.readBytes(entry)
            assertTrue(Thread.interrupted())
            val second = fuel.readBytes(entry)
            assertArrayEquals(first, second)
        }
    }

    @Test
    fun streamSupportsDirectRead() {
        val source = zip(entries)
        val target = dir.resolve("stream.rocketfuel")
        RocketFuelWriter.convert(source, target)
        RocketFuelFile.open(target).use { fuel ->
            val entry = fuel.entry("pack.png")!!
            val stream = fuel.openStream(entry) as FuelEntryStream
            assertTrue(stream.untouched)
            val buffer = ByteBuffer.allocateDirect(stream.size)
            stream.readInto(buffer)
            buffer.flip()
            val direct = ByteArray(buffer.remaining()).also { buffer.get(it) }
            assertArrayEquals(fuel.readBytes(entry), direct)
            assertEquals(-1, stream.read())
            assertArrayEquals(direct, fuel.openStream(entry).readAllBytes())
        }
    }

    @Test
    fun pngOptimizerKeepsPixels() {
        val original = png(64, 64, 42)
        val optimized = RocketFuelWriter.PngOptimizer.optimize(original, 9)
        assertNotNull(optimized)
        assertArrayEquals(pixels(original), pixels(optimized!!))
        assertNull(RocketFuelWriter.PngOptimizer.optimize("kein png".toByteArray()))
    }

    @Test
    fun pngOptimizerRejectsTruncatedData() {
        val original = png(32, 32, 5)
        assertNull(RocketFuelWriter.PngOptimizer.optimize(original.copyOf(original.size - 20)))
    }

    @Test
    fun zipRootReaderFindsRootFiles() {
        val source = zip(entries, Deflater.BEST_COMPRESSION)
        val roots = ZipRootReader.read(source.toFile(), setOf("pack.mcmeta", "pack.png"))
        assertArrayEquals(entries["pack.mcmeta"], roots["pack.mcmeta"])
        assertArrayEquals(entries["pack.png"], roots["pack.png"])
    }

    @Test
    fun corruptCacheIsRejected() {
        val source = zip(entries)
        val target = dir.resolve("corrupt.rocketfuel")
        RocketFuelWriter.convert(source, target)
        val bytes = Files.readAllBytes(target)
        Files.write(target, bytes.copyOf(bytes.size - 7))
        var failed = false
        try {
            RocketFuelFile.open(target).close()
        } catch (_: java.io.IOException) {
            failed = true
        }
        assertTrue(failed)
    }
}
