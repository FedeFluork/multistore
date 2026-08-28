package com.multistore.core.installer.container

import com.multistore.core.installer.shell.PrivilegedShell

/**
 * Puts game data in its place by going through the privileged shell.
 *
 * ### Why `cat >` and not a file copy
 *
 * For the same reason `ShellInstaller` writes the APK to `pm install-write`'s standard input: the
 * extracted file sits in `filesDir`, which is private to the app, and the `shell` user cannot read
 * it. Copying it first into a world-readable directory would mean putting a file just extracted from
 * a verified container in the clear, for the duration of the operation. With `cat > destination` we
 * write the bytes ourselves, from our own process.
 *
 * ### The name is not touched
 *
 * Android looks for an OBB with a precise name — `main.<versionCode>.<package>.obb` — and looks for
 * it by name, not by content. Renaming it would make it invisible to the app, which would start and
 * behave as though the data were not there. What is instead **not** taken from the container is the
 * **directory**: that is decided by the `packageName` verification read from the base APK, because
 * an `install_path` obeyed literally would be a path written by the store.
 */
class ShellExpansionWriter(private val shell: PrivilegedShell) : ExpansionWriter {

    override suspend fun place(
        packageName: String,
        expansions: List<ExtractedPart>,
    ): ExpansionResult {
        if (expansions.isEmpty()) return ExpansionResult.Placed(files = 0, bytes = 0)
        if (!PACKAGE_SAFE.matches(packageName)) return ExpansionResult.Failed(unsafe(packageName))

        val directory = "$OBB_ROOT/$packageName"
        val made = shell.exec("mkdir -p $directory")
        if (!made.ok) return ExpansionResult.Failed(made.output.ifBlank { MKDIR_FAILED })

        var bytes = 0L
        for (expansion in expansions) {
            val name = expansion.file.name
            if (!NAME_SAFE.matches(name)) return ExpansionResult.Failed(unsafe(name))
            val written = shell.exec("cat > $directory/$name") { output ->
                expansion.file.inputStream().buffered().use { it.copyTo(output) }
            }
            if (!written.ok) return ExpansionResult.Failed(written.output.ifBlank { writeFailed(name) })
            bytes += expansion.file.length()
        }
        return ExpansionResult.Placed(files = expansions.size, bytes = bytes)
    }

    private companion object {

        /**
         * Where Android looks for game data.
         *
         * Written `/sdcard` and not derived from `Environment`: the path that counts is the one
         * **seen by the shell**, which runs in another process with another view of the mount.
         * `/sdcard` is the link both identities resolve to the same place.
         */
        const val OBB_ROOT = "/sdcard/Android/obb"

        val PACKAGE_SAFE = Regex("[A-Za-z0-9_.]{1,255}")
        val NAME_SAFE = Regex("[A-Za-z0-9_.+-]{1,255}\\.obb")

        const val MKDIR_FAILED = "the expansion-data folder could not be created"

        fun unsafe(value: String) = "name not usable in a shell line: $value"
        fun writeFailed(name: String) = "$name could not be written"
    }
}
