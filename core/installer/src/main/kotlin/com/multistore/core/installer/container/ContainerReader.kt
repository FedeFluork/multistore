package com.multistore.core.installer.container

import com.multistore.core.model.ArtifactType
import com.multistore.core.model.BundlePart
import com.multistore.core.model.SplitKind
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/** Whoever can say what is inside a downloaded file. */
interface ContainerReader {
    fun read(file: File): ContainerReadResult
}

/**
 * The real reader: opens the zip, looks **inside**, and classifies every entry.
 *
 * ### The two measured formats, and the third that is recognised and not opened
 *
 * - **XAPK** (apkcombo, APKPure): `manifest.json` with `xapk_version`, `split_apks: [{file, id}]`
 *   and — where the app has any — `expansions: [{file, install_path}]`. The base is the element with
 *   `id == "base"`, and its file is called `<packageName>.apk`.
 * - **APKM** (apkmirror): `info.json` with `apkm_version`, `pname`, `versioncode`. It does not list
 *   the entries: the base is `base.apk`, the splits `split_config.<tag>.apk`.
 * - **APKS** (bundletool): `toc.pb`, with the APKs under `splits/`. It is **recognised** but not
 *   opened: the split choice is described by a protobuf none of our dependencies can read, and
 *   guessing it from the names would give an installation that looks successful and is missing
 *   something. Better a refusal that names the format.
 *
 * ### What it does when the container declares nothing
 *
 * That leaves the zips containing `.apk` files and no metadata. They are treated as generic
 * containers: the base is the only `.apk` that does not have the shape of a split. **If there is
 * more than one, it stops** — choosing one at random would mean installing the wrong app from a file
 * the user believes they know.
 */
@Singleton
class ZipContainerReader @Inject constructor() : ContainerReader {

    /**
     * ### A zip that does not open is **not** a broken container: it is a file to be verified
     *
     * The first draft returned [ContainerReadResult.Unreadable] on any exception, and an already
     * existing test contradicted it: a truncated download stopped producing "the file is unreadable"
     * and started producing "this is not a container this app knows how to open" — a sentence about
     * a format that file has nothing to do with.
     *
     * An APK **is** a zip: if `ZipFile` does not open it, neither will `apksig`. The right answer is
     * therefore "treat it as an APK" and let the verification pipeline say what is wrong, with the
     * vocabulary that pipeline already has. [ContainerReadResult.Unreadable] remains for the real
     * case: it **is** a container, and it cannot be used.
     */
    override fun read(file: File): ContainerReadResult = try {
        ZipFile(file).use { zip -> classify(zip) }
    } catch (_: Exception) {
        ContainerReadResult.Read(ContainerContents.SingleApk)
    }

    private fun classify(zip: ZipFile): ContainerReadResult {
        if (zip.getEntry(ANDROID_MANIFEST) != null) {
            return ContainerReadResult.Read(ContainerContents.SingleApk)
        }
        if (zip.getEntry(BUNDLETOOL_TOC) != null) {
            return ContainerReadResult.Unreadable(BUNDLETOOL_UNSUPPORTED)
        }

        val entries = zip.entries().asSequence().filterNot { it.isDirectory }.toList()
        val apks = entries.filter { it.name.endsWith(APK_SUFFIX, ignoreCase = true) }
        if (apks.isEmpty()) return ContainerReadResult.Unreadable(NO_APK_INSIDE)

        zip.metadata(XAPK_MANIFEST)?.let { return xapk(it, entries) }
        zip.metadata(APKM_INFO)?.let { return apkm(it, entries) }
        return generic(entries, apks)
    }

    /**
     * XAPK: the base is declared by `split_apks`, and the expansions are a field of their own.
     *
     * `id` and not the file name: Duolingo's base is called `com.duolingo.apk`, another XAPK's would
     * be called after **its** package, and neither is `base.apk`.
     */
    private fun xapk(manifest: JsonObject, entries: List<ZipEntry>): ContainerReadResult {
        val sizes = entries.associate { it.name to it.size }
        val declared = manifest[XAPK_SPLITS]?.jsonArray.orEmpty().mapNotNull { element ->
            val obj = element.jsonObject
            val name = obj[XAPK_SPLIT_FILE]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val id = obj[XAPK_SPLIT_ID]?.jsonPrimitive?.contentOrNull.orEmpty()
            BundlePart(
                entryName = name,
                kind = if (id == XAPK_BASE_ID) SplitKind.BASE else kindOfTag(splitTag(id)),
                sizeBytes = sizes[name] ?: 0,
                tag = splitTag(id),
            )
        }
        val expansions = manifest[XAPK_EXPANSIONS]?.jsonArray.orEmpty().mapNotNull { element ->
            val obj = element.jsonObject
            val name = obj[XAPK_EXPANSION_FILE]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            BundlePart(
                entryName = name,
                kind = SplitKind.EXPANSION,
                sizeBytes = sizes[name] ?: 0,
                // The destination path the container declares. It is not used as a path: it is used
                // to **read the file name**, and the directory is decided by whoever installs, from
                // the verified packageName. An `install_path` obeyed literally would be a directory
                // traversal written by the store.
                tag = obj[XAPK_EXPANSION_PATH]?.jsonPrimitive?.contentOrNull,
            )
        }
        val parts = declared + expansions
        if (parts.none { it.kind == SplitKind.BASE }) return ContainerReadResult.Unreadable(NO_BASE)
        return ContainerReadResult.Read(
            ContainerContents.Bundle(
                artifactType = ArtifactType.XAPK,
                parts = parts,
                declaredPackageName = manifest[XAPK_PACKAGE]?.jsonPrimitive?.contentOrNull,
                declaredVersionCode = manifest[XAPK_VERSION_CODE]?.jsonPrimitive?.contentOrNull?.toLongOrNull(),
            ),
        )
    }

    /** APKM: `info.json` does not list the entries, so the zip lists them. */
    private fun apkm(info: JsonObject, entries: List<ZipEntry>): ContainerReadResult {
        val parts = entries.mapNotNull { it.toPart(APKM_BASE_NAME) }
        if (parts.none { it.kind == SplitKind.BASE }) return ContainerReadResult.Unreadable(NO_BASE)
        return ContainerReadResult.Read(
            ContainerContents.Bundle(
                artifactType = ArtifactType.APKM,
                parts = parts,
                declaredPackageName = info[APKM_PACKAGE]?.jsonPrimitive?.contentOrNull,
                declaredVersionCode = info[APKM_VERSION_CODE]?.jsonPrimitive?.let {
                    it.longOrNull ?: it.contentOrNull?.toLongOrNull()
                },
            ),
        )
    }

    /**
     * A zip with APKs inside and no metadata.
     *
     * The base is the only `.apk` that does not have the shape of a split. **Two candidates are a
     * refusal**, not a choice: it is the only point in this reader where guessing would change
     * *which app* gets installed.
     */
    private fun generic(entries: List<ZipEntry>, apks: List<ZipEntry>): ContainerReadResult {
        val bases = apks.filter { splitTag(it.name.removeSuffix(APK_SUFFIX)) == null }
        if (bases.size != 1) return ContainerReadResult.Unreadable(AMBIGUOUS_BASE)
        val baseName = bases.single().name
        val parts = entries.mapNotNull { it.toPart(baseName) }
        return ContainerReadResult.Read(
            ContainerContents.Bundle(
                artifactType = ArtifactType.APKS,
                parts = parts,
                declaredPackageName = null,
                declaredVersionCode = null,
            ),
        )
    }

    private fun ZipEntry.toPart(baseName: String): BundlePart? {
        val stem = name.substringAfterLast('/')
        return when {
            name == baseName -> BundlePart(name, SplitKind.BASE, size)
            stem.endsWith(OBB_SUFFIX, ignoreCase = true) -> BundlePart(name, SplitKind.EXPANSION, size)
            !stem.endsWith(APK_SUFFIX, ignoreCase = true) -> BundlePart(name, SplitKind.METADATA, size)
            else -> {
                val tag = splitTag(stem.removeSuffix(APK_SUFFIX))
                BundlePart(name, kindOfTag(tag), size, tag)
            }
        }
    }

    private fun ZipFile.metadata(name: String): JsonObject? {
        val entry = getEntry(name) ?: return null
        return runCatching {
            getInputStream(entry).use { JSON.parseToJsonElement(it.readBytes().decodeToString()).jsonObject }
        }.getOrNull()
    }

    private companion object {

        val JSON = Json { ignoreUnknownKeys = true; isLenient = true }

        const val ANDROID_MANIFEST = "AndroidManifest.xml"
        const val BUNDLETOOL_TOC = "toc.pb"
        const val APK_SUFFIX = ".apk"
        const val OBB_SUFFIX = ".obb"

        const val XAPK_MANIFEST = "manifest.json"
        const val XAPK_SPLITS = "split_apks"
        const val XAPK_SPLIT_FILE = "file"
        const val XAPK_SPLIT_ID = "id"
        const val XAPK_BASE_ID = "base"
        const val XAPK_EXPANSIONS = "expansions"
        const val XAPK_EXPANSION_FILE = "file"
        const val XAPK_EXPANSION_PATH = "install_path"
        const val XAPK_PACKAGE = "package_name"
        const val XAPK_VERSION_CODE = "version_code"

        const val APKM_INFO = "info.json"
        const val APKM_BASE_NAME = "base.apk"
        const val APKM_PACKAGE = "pname"
        const val APKM_VERSION_CODE = "versioncode"

        const val BUNDLETOOL_UNSUPPORTED = "APK Set format (bundletool): toc.pb is not readable"
        const val NO_APK_INSIDE = "no APK inside the container"
        const val NO_BASE = "the container declares no base APK"
        const val AMBIGUOUS_BASE = "the container does not say which APK is the base"
    }
}
