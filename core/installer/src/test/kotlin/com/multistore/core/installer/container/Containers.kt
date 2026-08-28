package com.multistore.core.installer.container

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Builds test containers around the **real** APK fixtures in `fixtures/apk/`.
 *
 * The metadata the tests feed the reader come, where possible, from the two real containers
 * committed in `fixtures/container/`: it is the only part a parser interprets, and reading a
 * hand-written `manifest.json` would prove we can read what we can write.
 */
internal object Containers {

    fun fixture(name: String): ByteArray =
        checkNotNull(javaClass.classLoader?.getResourceAsStream("fixtures/$name")) {
            "missing fixture: $name"
        }.use { it.readBytes() }

    fun text(name: String): String = fixture(name).decodeToString()

    /** A zip with the given entries, all compressed. */
    fun zip(into: File, entries: List<Pair<String, ByteArray>>): File {
        ZipOutputStream(into.outputStream().buffered()).use { out ->
            for ((name, bytes) in entries) {
                out.putNextEntry(ZipEntry(name))
                out.write(bytes)
                out.closeEntry()
            }
        }
        return into
    }
}
