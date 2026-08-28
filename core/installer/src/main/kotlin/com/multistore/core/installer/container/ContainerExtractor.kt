package com.multistore.core.installer.container

import com.multistore.core.model.BundlePart
import com.multistore.core.model.BundleSummary
import com.multistore.core.model.Sha256
import com.multistore.core.model.SplitKind
import java.io.File
import java.security.DigestInputStream
import java.security.MessageDigest
import java.util.zip.ZipFile
import javax.inject.Inject
import javax.inject.Singleton

/** A piece of the container, pulled out and with its digest. */
data class ExtractedPart(
    val part: BundlePart,
    val file: File,
    val sha256: Sha256,
)

/** What came out of the container. */
data class ExtractedBundle(
    val summary: BundleSummary,
    val apks: List<ExtractedPart>,
    val expansions: List<ExtractedPart>,
) {
    /**
     * The base, which here exists by construction: [ZipContainerReader] refuses a container that does
     * not declare one, so arriving here without it would mean somebody skipped the reader.
     */
    val base: ExtractedPart get() = apks.first { it.part.kind == SplitKind.BASE }
}

sealed interface ExtractionResult {
    data class Done(val bundle: ExtractedBundle) : ExtractionResult
    data class Failed(val reason: String) : ExtractionResult
    data class NotEnoughSpace(val needBytes: Long, val freeBytes: Long) : ExtractionResult
}

/**
 * Opens a container and puts **only** the chosen pieces on disk.
 *
 * ### Three things this class does that are not obvious
 *
 * 1. **An entry's name never becomes a path.** A zip can contain `../../databases/multistore.db`, and
 *    `File(destination, name)` would resolve it outside the directory. Here **only the last segment**
 *    is used, and an entry whose name does not survive that cut makes extraction fail rather than
 *    being ignored: a missing piece is an APK that does not install, and discovering that later would
 *    be worse.
 * 2. **The digest is computed as the bytes come out.** It is the same reason `SessionInstaller`
 *    computes it as they go into the session: between extraction and writing into the session there
 *    is a file on disk, and that file has to be compared with what it was when we verified it.
 * 3. **Space is checked first.** An XAPK is `store` compressed and an APKM `deflate`: 286 MB
 *    downloaded become 624 opened. Starting and running out of bytes halfway would leave a directory
 *    of truncated files that verification would discard one by one, with a message about signatures
 *    instead of about space.
 */
@Singleton
class ContainerExtractor @Inject constructor() {

    fun extract(container: File, summary: BundleSummary, into: File): ExtractionResult = try {
        extractOrThrow(container, summary, into)
    } catch (e: Exception) {
        into.deleteRecursively()
        ExtractionResult.Failed(e.message ?: e::class.java.simpleName)
    }

    private fun extractOrThrow(container: File, summary: BundleSummary, into: File): ExtractionResult {
        val wanted = summary.install + summary.expansions
        val needed = wanted.sumOf { it.sizeBytes }
        into.mkdirs()
        val free = into.usableSpace
        // A margin and not the exact number: `usableSpace` is a snapshot, and between the question
        // and the last byte written the system may have put something else there.
        if (free in 1..<(needed + SPACE_MARGIN_BYTES)) {
            return ExtractionResult.NotEnoughSpace(needBytes = needed, freeBytes = free)
        }

        val extracted = mutableListOf<ExtractedPart>()
        val used = mutableSetOf<String>()
        ZipFile(container).use { zip ->
            for (part in wanted) {
                val entry = zip.getEntry(part.entryName)
                    ?: return ExtractionResult.Failed(missing(part.entryName))
                val name = safeName(part) ?: return ExtractionResult.Failed(unsafe(part.entryName))
                // Only the last segment of the name remains, so `a/base.apk` and `b/base.apk` become
                // the same file: the second would overwrite the first, and the outcome would be an
                // app missing a split **and** one with a duplicate, with no error at all. We do not
                // rename — the name also ends up in the session, and for an OBB it *is* the content —
                // we stop.
                if (!used.add(name)) return ExtractionResult.Failed(duplicate(name))
                val destination = File(into, name)
                val digest = MessageDigest.getInstance(SHA_256)
                zip.getInputStream(entry).use { source ->
                    DigestInputStream(source, digest).use { input ->
                        destination.outputStream().buffered().use { output -> input.copyTo(output) }
                    }
                }
                extracted += ExtractedPart(
                    // The size declared by the zip entry and that of the written file can differ:
                    // what counts is the second, and it is the only one anyone will re-read.
                    part = part.copy(sizeBytes = destination.length()),
                    file = destination,
                    sha256 = Sha256.ofBytes(digest.digest()),
                )
            }
        }

        return ExtractionResult.Done(
            ExtractedBundle(
                summary = summary,
                apks = extracted.filter { it.part.kind != SplitKind.EXPANSION },
                expansions = extracted.filter { it.part.kind == SplitKind.EXPANSION },
            ),
        )
    }

    /**
     * The name the piece ends up under on disk — and, for APKs, inside the session.
     *
     * Only the last segment of the entry's name, and only if something harmless remains. For
     * expansions the original name matters too, because Android requires
     * `main.<versionCode>.<package>.obb`: it is the one case where the file's name **is** the content,
     * and renaming it would make the OBB invisible to the app looking for it.
     */
    private fun safeName(part: BundlePart): String? {
        val declared = if (part.kind == SplitKind.EXPANSION) {
            part.tag?.substringAfterLast('/').orEmpty().ifEmpty { part.entryName }
        } else {
            part.entryName
        }
        val name = declared.substringAfterLast('/').substringAfterLast('\\')
        return name.takeIf { it.isNotEmpty() && it != "." && it != ".." && SAFE.matches(it) }
    }

    private companion object {
        const val SHA_256 = "SHA-256"
        const val SPACE_MARGIN_BYTES = 16L * 1024 * 1024
        val SAFE = Regex("[A-Za-z0-9._+-]{1,200}")

        fun missing(name: String) = "the container has no entry named $name"
        fun unsafe(name: String) = "entry name not usable as a file: $name"
        fun duplicate(name: String) = "two container entries are called $name"
    }
}
