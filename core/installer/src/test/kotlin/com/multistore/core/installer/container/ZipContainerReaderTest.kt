package com.multistore.core.installer.container

import com.google.common.truth.Truth.assertThat
import com.multistore.core.model.ArtifactType
import com.multistore.core.model.SplitKind
import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * What the reader recognises, and what it refuses to guess.
 *
 * The two `manifest`/`info` files are Duolingo's and Firefox's real ones: reading a JSON written here
 * would only prove we can re-read what we can write.
 */
class ZipContainerReaderTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val reader = ZipContainerReader()

    private fun read(name: String, entries: List<Pair<String, ByteArray>>) =
        reader.read(Containers.zip(folder.newFile(name), entries))

    private fun bundleOf(result: ContainerReadResult): ContainerContents.Bundle {
        assertThat(result).isInstanceOf(ContainerReadResult.Read::class.java)
        val contents = (result as ContainerReadResult.Read).contents
        assertThat(contents).isInstanceOf(ContainerContents.Bundle::class.java)
        return contents as ContainerContents.Bundle
    }

    @Test
    fun `an APK is an APK, recognised by the manifest at the root`() {
        // It is the only thing every APK has and no container: a rule about the file name would be
        // wrong on apkcombo, which delivers an `.apks` object calling it `.xapk`.
        val result = read("app.apk", listOf("AndroidManifest.xml" to byteArrayOf(1, 2, 3)))

        assertThat((result as ContainerReadResult.Read).contents)
            .isEqualTo(ContainerContents.SingleApk)
    }

    @Test
    fun `an XAPK declares its base, which is not called base`() {
        val bundle = bundleOf(
            read(
                "duolingo.xapk",
                listOf(
                    "manifest.json" to Containers.fixture("container/xapk-manifest.json"),
                    "com.duolingo.apk" to ByteArray(10),
                    "config.arm64_v8a.apk" to ByteArray(20),
                    "config.xxhdpi.apk" to ByteArray(5),
                    "icon.png" to ByteArray(3),
                ),
            ),
        )

        assertThat(bundle.artifactType).isEqualTo(ArtifactType.XAPK)
        assertThat(bundle.declaredPackageName).isEqualTo("com.duolingo")
        assertThat(bundle.declaredVersionCode).isEqualTo(2440)
        // `com.duolingo.apk`, not `base.apk`: it is why the base is declared by `split_apks` with
        // `id: "base"` instead of being deduced from the file name.
        assertThat(bundle.base?.entryName).isEqualTo("com.duolingo.apk")
        assertThat(bundle.parts.filter { it.kind == SplitKind.ABI }.map { it.tag })
            .containsExactly("arm64_v8a", "x86", "armeabi_v7a", "x86_64")
        assertThat(bundle.parts.count { it.kind == SplitKind.DENSITY }).isEqualTo(7)
    }

    @Test
    fun `an APKM lists the entries from the zip, because info json does not list them`() {
        val bundle = bundleOf(
            read(
                "firefox.apkm",
                listOf(
                    "info.json" to Containers.fixture("container/apkm-info.json"),
                    "base.apk" to ByteArray(10),
                    "split_config.arm64_v8a.apk" to ByteArray(20),
                    "split_config.xxhdpi.apk" to ByteArray(5),
                    "META-INF/MANIFEST.MF" to ByteArray(2),
                    "icon.png" to ByteArray(3),
                ),
            ),
        )

        assertThat(bundle.artifactType).isEqualTo(ArtifactType.APKM)
        // `versioncode` is a number in `info.json` and a string in `manifest.json`: two formats, two
        // conventions, and reading only one of the two would give `null` on the other.
        assertThat(bundle.declaredVersionCode).isEqualTo(2016178287)
        assertThat(bundle.declaredPackageName).isEqualTo("org.mozilla.firefox")
        assertThat(bundle.base?.entryName).isEqualTo("base.apk")
        assertThat(bundle.parts.single { it.kind == SplitKind.ABI }.tag).isEqualTo("arm64_v8a")
        assertThat(bundle.parts.filter { it.kind == SplitKind.METADATA }.map { it.entryName })
            .containsExactly("info.json", "META-INF/MANIFEST.MF", "icon.png")
    }

    @Test
    fun `a bundletool APK Set is recognised and not opened`() {
        // `toc.pb` is a protobuf no dependency can read, and guessing the splits from the names
        // would give an installation that looks successful and is missing something.
        val result = read("app.apks", listOf("toc.pb" to ByteArray(4), "splits/base-master.apk" to ByteArray(9)))

        assertThat((result as ContainerReadResult.Unreadable).reason).contains("bundletool")
    }

    @Test
    fun `a zip with two base candidates stops instead of choosing one`() {
        // It is the reader's only point where guessing would change **which app** gets installed.
        val result = read(
            "ambiguo.zip",
            listOf("uno.apk" to ByteArray(4), "due.apk" to ByteArray(4)),
        )

        assertThat((result as ContainerReadResult.Unreadable).reason).contains("base")
    }

    @Test
    fun `a zip of splits only, with no base, is a refusal`() {
        val result = read(
            "no-base.zip",
            listOf("config.arm64_v8a.apk" to ByteArray(4), "config.xxhdpi.apk" to ByteArray(4)),
        )

        assertThat(result).isInstanceOf(ContainerReadResult.Unreadable::class.java)
    }

    @Test
    fun `a zip with no APK inside is not a container`() {
        val result = read("nothing.zip", listOf("readme.txt" to ByteArray(4)))

        assertThat((result as ContainerReadResult.Unreadable).reason).contains("no APK")
    }

    @Test
    fun `a file that is not even a zip goes back to verification as an APK`() {
        // Not "unreadable container": a truncated download must produce the verification pipeline's
        // message, which talks about that file, and not one talking about a format that file has
        // nothing to do with.
        val corrupt = folder.newFile("truncated.apk").apply { writeBytes(ByteArray(64) { 7 }) }

        assertThat((reader.read(corrupt) as ContainerReadResult.Read).contents)
            .isEqualTo(ContainerContents.SingleApk)
    }

    @Test
    fun `a file that does not exist blows nothing up`() {
        assertThat(reader.read(File(folder.root, "missing.apk")))
            .isInstanceOf(ContainerReadResult.Read::class.java)
    }

    @Test
    fun `an XAPK whose manifest declares no base is a refusal`() {
        // The case is **constructed**, and that has to be said: the two real committed containers
        // both declare their base. Without this test the injection removing the check stayed green —
        // there was already a test on a zip with no base, but it went through the generic branch,
        // which is a different check.
        val result = read(
            "no-base.xapk",
            listOf(
                "manifest.json" to """
                    {"xapk_version":"2","package_name":"com.example","version_code":"1",
                     "split_apks":[{"file":"config.arm64_v8a.apk","id":"config.arm64_v8a"}]}
                """.trimIndent().toByteArray(),
                "config.arm64_v8a.apk" to ByteArray(4),
            ),
        )

        assertThat((result as ContainerReadResult.Unreadable).reason).contains("base")
    }

    @Test
    fun `a split that cannot be classified is installed all the same`() {
        val bundle = bundleOf(
            read(
                "modulare.apkm",
                listOf(
                    "info.json" to Containers.fixture("container/apkm-info.json"),
                    "base.apk" to ByteArray(10),
                    "split_mappe.apk" to ByteArray(7),
                ),
            ),
        )

        // One split too many costs space; one split too few is a part of the app that is missing, and
        // the symptom arrives inside the app where nobody links it to whoever installed it. The test
        // goes through the reader and not through `kindOfTag` directly: the classification is its.
        assertThat(bundle.parts.single { it.entryName == "split_mappe.apk" }.kind)
            .isEqualTo(SplitKind.FEATURE)
    }

    @Test
    fun `with no metadata the base is the only APK not shaped like a split`() {
        // The base's name **contains dots** — it is `<packageName>.apk` — so recognising a split by
        // cutting at the first dot would mistake it for a split and the container would come out
        // with no base. It is the case that makes it necessary to look for the `config.` marker
        // instead of looking at the punctuation.
        val bundle = bundleOf(
            read(
                "no-metadata.zip",
                listOf(
                    "com.example.app.apk" to ByteArray(10),
                    "config.arm64_v8a.apk" to ByteArray(20),
                ),
            ),
        )

        assertThat(bundle.base?.entryName).isEqualTo("com.example.app.apk")
        assertThat(bundle.parts.single { it.kind == SplitKind.ABI }.tag).isEqualTo("arm64_v8a")
    }
}
