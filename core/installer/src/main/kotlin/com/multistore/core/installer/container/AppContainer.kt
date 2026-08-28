package com.multistore.core.installer.container

import com.multistore.core.model.ArtifactType
import com.multistore.core.model.BundlePart
import com.multistore.core.model.SplitKind

/**
 * What is inside a file downloaded from a store, read **from the content and not from the name**.
 *
 * ### Why not from the name, with the measurement alongside
 *
 * On 26/08/2026, the XAPK apkcombo delivers for Duolingo is the object
 * `…/com.duolingo/6.93.6/2440.….apks` on R2 — extension `.apks` — served with
 * `content-disposition: attachment; filename="Duolingo_6.93.6_apkcombo.com.xapk"` and
 * `content-type: application/xapk-package-archive`. Three declarations, two named formats, and the
 * content is an XAPK. The same holds in reverse: `ArtifactType` is populated by eight adapters out of
 * nine by reading an extension, and an extension is what somebody else wrote.
 *
 * Hence the rule: **the extension decides what to show, the content decides what to do.**
 */
sealed interface ContainerContents {

    /**
     * The file is an APK, and installs as it is.
     *
     * Recognised because the zip has an `AndroidManifest.xml` **at the root** — what no container has,
     * and what every APK has by construction.
     */
    data object SingleApk : ContainerContents

    /** The file is a split container, possibly with expansions. */
    data class Bundle(
        val artifactType: ArtifactType,
        /** Every useful entry, including those that will not be installed. */
        val parts: List<BundlePart>,
        /** The package the container **declares**, where it declares one. It is not proof. */
        val declaredPackageName: String?,
        val declaredVersionCode: Long?,
    ) : ContainerContents {
        val base: BundlePart? get() = parts.firstOrNull { it.kind == SplitKind.BASE }
    }
}

sealed interface ContainerReadResult {

    data class Read(val contents: ContainerContents) : ContainerReadResult

    /**
     * It could not be opened, or it is a container with no base.
     *
     * A container with no base is not a theoretical case to be treated indulgently: it is a file from
     * which nothing can be installed, and carrying on would mean a `PackageInstaller` session made
     * only of splits, which the system refuses with an error that does not name the cause.
     */
    data class Unreadable(val reason: String) : ContainerReadResult
}
