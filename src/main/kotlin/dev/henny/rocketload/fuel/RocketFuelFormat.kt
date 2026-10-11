package dev.henny.rocketload.fuel

object RocketFuelFormat {
    val MAGIC: ByteArray = byteArrayOf(
        'R'.code.toByte(), 'K'.code.toByte(), 'T'.code.toByte(), 'F'.code.toByte(),
        'U'.code.toByte(), 'E'.code.toByte(), 'L'.code.toByte(), 0x01,
    )
    const val VERSION: Int = 1
    const val HEADER_SIZE: Int = 44
    const val FILE_EXTENSION: String = ".rocketfuel"
    const val WRITER_VERSION: Int = 4
}
