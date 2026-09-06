package com.multistore.core.data.repository

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File

/**
 * Where the APKs waiting to be installed live.
 *
 * `filesDir` and not `cacheDir`: both are private to the app, but the system can empty the cache at
 * any moment — including the one between an APK's verification and its commit into the installation
 * session — and it happens precisely when the device is full, i.e. while something is being
 * downloaded.
 *
 * It exists as an object instead of as a private constant of `DownloadRepositoryImpl` because that
 * directory has **three** users: whoever writes the stores' downloads into it, whoever writes
 * MultiStore's APK into it when it updates itself, and whoever cleans it. Three copies of the same
 * string would be three copies that can diverge, and the third — the one that cleans — would silently
 * do nothing if it pointed elsewhere.
 *
 * ### Public and not `internal`, and a green injection decided it
 *
 * The first draft was `internal`, i.e. invisible to `:core:domain` — where `InstallSelfUpdateUseCase`
 * kept **its** copy of the string. With `internal` the copies stayed two, in two different modules,
 * and the one that counts is the second: if its own moved, the startup sweep would stop finding
 * MultiStore's APK and no test would say so — the defect is precisely "a file nobody cleans", which
 * is invisible by definition.
 *
 * The injection moving this constant stays **green**, and now it is right that it should: with a
 * single definition, writer and sweeper move together, and the divergence is no longer
 * representable. Before it was, and in a module this test does not look at.
 */
object Staging {

    /**
     * With no explicit type, and that is not style.
     *
     * `BackupExclusionTest` derives from the sources the directories created under `filesDir` and
     * resolves a constant with `const val NAME = "..."`. Written `const val DIRECTORY: String =
     * "staging"` that regex does not see it, and the guardrail — correctly — stops saying it does not
     * know which directory is being written to. Adding a branch for the type annotation to the
     * guardrail would be widening a rule to let a line through.
     */
    const val DIRECTORY = "staging"

    /**
     * The suffix of the directory a container is opened into: `12.apk` -> `12.split/`.
     *
     * Staging no longer holds only files. An XAPK produces about ten, and they have to sit **next
     * to** the file they come from and not mixed in with the other downloads: two containers opened
     * together both have a `base.apk`.
     *
     * The "downloaded file / its directory" pair is known by this object and nobody else, and it is
     * the earlier lesson applied before the defect exists: whoever extracts and whoever cleans read
     * the same rule, so they cannot diverge. If they did, the symptom would be a two-hundred-megabyte
     * directory nobody throws away.
     */
    const val SPLIT_SUFFIX = ".split"

    /** The authority's suffix, the other half of `${applicationId}.staging` in the manifest. */
    private const val PROVIDER_SUFFIX = "staging"

    fun dir(context: Context): File = File(context.filesDir, DIRECTORY).apply { mkdirs() }

    /** Where the pieces of the [download] container are opened. */
    fun splitsOf(download: File): File = File(download.parentFile, download.nameWithoutExtension + SPLIT_SUFFIX)

    /** The download the [splits] directory belongs to, or `null` if it is not one of those. */
    fun ownerOf(splits: File): File? =
        splits.name.takeIf { it.endsWith(SPLIT_SUFFIX) }
            ?.removeSuffix(SPLIT_SUFFIX)
            ?.let { File(splits.parentFile, "$it.apk") }

    /**
     * The authority of the `FileProvider` that lets one of these files leave the app.
     *
     * Derived from the package name rather than written out, because the three variants that live
     * side by side on the same device — `com.multistore.debug`, `.minified` and the release — need
     * three different authorities, and two providers claiming the same one make the second install
     * fail with `INSTALL_FAILED_CONFLICTING_PROVIDER`. `${applicationId}.staging` in the manifest and
     * this suffix are the two halves of the same string, which is why they sit next to the constant
     * naming the directory they both point at.
     */
    fun authority(context: Context): String = "${context.packageName}.$PROVIDER_SUFFIX"

    /**
     * A `content://` URI for a staged file, or `null` if that file is not in staging.
     *
     * The `null` branch is the point. `FileProvider.getUriForFile` throws
     * `IllegalArgumentException` for a path outside the declared subtree, and the caller here is a
     * screen holding a `File` that came out of a database row — a row whose path was written by a
     * previous version of the app, or by a code path that put the file somewhere else. A share
     * button that closes the app is worse than a share button that is not drawn.
     */
    fun shareableUri(context: Context, file: File): Uri? =
        runCatching { FileProvider.getUriForFile(context, authority(context), file) }.getOrNull()

    /**
     * Top-level files **and directories**: what whoever cleans up, and whoever measures, has to
     * look at.
     *
     * There used to be a `files()` next to this one that filtered on `isFile`, and it was the
     * measurement's — which is how the size of an opened container read as zero while the sweep
     * deleted it. One function, so the two answers cannot diverge again.
     */
    fun entries(context: Context): List<File> =
        File(context.filesDir, DIRECTORY).listFiles().orEmpty().toList()
}
