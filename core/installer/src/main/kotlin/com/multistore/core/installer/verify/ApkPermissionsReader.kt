package com.multistore.core.installer.verify

import android.content.Context
import android.content.pm.PackageManager
import com.multistore.core.model.UsesPermission
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * What a downloaded archive asks the operating system for.
 *
 * ### Why this is not `ApkArchiveReader`
 *
 * That one exists to decide whether an installation may **start**, and every field it returns is
 * non-nullable precisely because a value it could not read must fail the read rather than become
 * `null`. This one answers a question with no security consequence at all — it tells the reader
 * something, it refuses nothing — and its correct behaviour on an unreadable file is to say "I do
 * not know" and let the pipeline carry on. Two questions with opposite failure modes do not belong
 * behind one interface.
 *
 * ### `getPackageArchiveInfo`, and why CLAUDE.md's warning about it does not apply here
 *
 * That warning is about **signatures**, and it is right: the API collects declared certificates
 * without verifying the archive matches them, and `GET_SIGNING_CERTIFICATES` only exists from API
 * 28 while `minSdk` is 26 — so on 26 and 27 the signer would silently read as absent. None of that
 * touches permissions: `GET_PERMISSIONS` has been there since API 1, `requestedPermissions` is a
 * plain list of names, and by the time this runs the archive has already been through `apksig`.
 * Writing an `AndroidManifest.xml` binary-XML parser to obtain a list the platform hands over would
 * be a new parser, with its own fixtures and its own way of breaking, for the same answer.
 *
 * ### What is lost, and it is worth naming
 *
 * `android:maxSdkVersion` does not come through — the platform returns names and
 * `requestedPermissionsFlags`, which is about granting and not about ceilings. So every permission
 * read this way carries `maxSdk = null`, and [UsesPermission.isRequestedOn] treats that as "still
 * requested". It is the prudent of the two errors: a permission shown that the device would not be
 * asked for over-informs the reader, one hidden that it would be asked for misleads them. F-Droid's
 * index, which does publish the ceiling, is unaffected.
 */
fun interface ApkPermissionsReader {

    /**
     * The permissions [file] declares, or `null` if it could not be read.
     *
     * The empty list is a real answer — "this build asks for nothing" — and is common. `null` is the
     * other one, and the two must not be conflated anywhere downstream.
     */
    fun read(file: File): List<UsesPermission>?
}

@Singleton
class PackageManagerPermissionsReader @Inject constructor(
    @ApplicationContext private val context: Context,
) : ApkPermissionsReader {

    override fun read(file: File): List<UsesPermission>? {
        // `getPackageArchiveInfo` answers `null` for anything it cannot parse and, historically, has
        // thrown on some malformed archives instead. Neither is an error worth propagating: nothing
        // here decides whether the installation happens, so an unreadable manifest is "not known"
        // and the screen already has a sentence for that.
        val info = runCatching {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageArchiveInfo(file.absolutePath, PackageManager.GET_PERMISSIONS)
        }.getOrNull() ?: return null

        // `requestedPermissions` is `null`, not empty, for a package that declares none — so the
        // distinction this reader exists to preserve would be inverted by a plain `?: return null`.
        // A parsed archive that asks for nothing is an **empty** answer, not an absent one.
        val names = info.requestedPermissions ?: return emptyList()
        return names
            .filter { it.isNotBlank() }
            .distinct()
            .map { UsesPermission(name = it) }
    }
}
