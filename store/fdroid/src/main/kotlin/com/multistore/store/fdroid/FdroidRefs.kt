package com.multistore.store.fdroid

import com.multistore.core.model.Sha256
import com.multistore.core.model.StoreAppRef
import com.multistore.core.model.VersionRef

/**
 * How F-Droid encodes the contract's two opaque references.
 *
 * [StoreAppRef] is simply the `packageName`, which F-Droid publishes on every package.
 *
 * [VersionRef] deserves an explanation, because the choice has a precise architectural consequence.
 * The contract says the ref is **opaque to the core**: it is the adapter that decides what goes in.
 * Here we put everything needed to resolve the download — SHA-256, size and file name — instead of
 * just an identifier.
 *
 * The reason is that F-Droid is a local-index store: the adapter has no access to the database
 * where the index is kept, so from a bare identifier it could not work back to the file name. And
 * the file name **is not derivable**: 45 entries out of 12,871 use
 * `<pkg>_<versionCode>_<githash>.apk` instead of the canonical pattern. Building the URL from the
 * version code would work for 99.7% of cases and fail silently on the rest.
 *
 * With a self-sufficient ref, `getDownloadLink` is pure string manipulation: no network, no state
 * access, and therefore no possible exception.
 */
object FdroidRefs {

    private const val SEPARATOR = "|"
    private const val FIELDS = 3

    fun appRef(packageName: String): StoreAppRef = StoreAppRef(packageName)

    fun packageName(ref: StoreAppRef): String = ref.value

    fun versionRef(sha256: Sha256, sizeBytes: Long, fileName: String): VersionRef =
        VersionRef(sha256.hex + SEPARATOR + sizeBytes + SEPARATOR + fileName)

    /** `null` if the ref was not produced by [versionRef]. No exceptions. */
    fun decode(ref: VersionRef): DecodedVersion? {
        val parts = ref.value.split(SEPARATOR, limit = FIELDS)
        if (parts.size != FIELDS) return null
        val sha = Sha256.parseOrNull(parts[0]) ?: return null
        val size = parts[1].toLongOrNull() ?: return null
        val fileName = parts[2].takeIf { it.isNotBlank() } ?: return null
        return DecodedVersion(sha, size, fileName)
    }

    data class DecodedVersion(val sha256: Sha256, val sizeBytes: Long, val fileName: String)
}
