package com.multistore.core.installer.container

import com.google.common.truth.Truth.assertThat
import com.multistore.core.model.ArtifactType
import com.multistore.core.model.BundlePart
import com.multistore.core.model.BundleSummary
import com.multistore.core.model.Sha256
import com.multistore.core.model.SplitKind
import java.io.File
import java.security.MessageDigest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** What comes out of the container, and what must not be able to. */
class ContainerExtractorTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val extractor = ContainerExtractor()

    private fun summary(vararg parts: BundlePart) = BundleSummary(
        artifactType = ArtifactType.XAPK,
        install = parts.filter { it.kind != SplitKind.EXPANSION },
        skipped = emptyList(),
        expansions = parts.filter { it.kind == SplitKind.EXPANSION },
    )

    private fun sha256(bytes: ByteArray) =
        Sha256.ofBytes(MessageDigest.getInstance("SHA-256").digest(bytes))

    @Test
    fun `it extracts only what is asked, with the digest of what it wrote`() {
        val base = ByteArray(64) { 1 }
        val split = ByteArray(32) { 2 }
        val container = Containers.zip(
            folder.newFile("app.xapk"),
            listOf(
                "base.apk" to base,
                "config.arm64_v8a.apk" to split,
                "config.x86.apk" to ByteArray(999),
                "icon.png" to ByteArray(8),
            ),
        )

        val result = extractor.extract(
            container,
            summary(
                BundlePart("base.apk", SplitKind.BASE, base.size.toLong()),
                BundlePart("config.arm64_v8a.apk", SplitKind.ABI, split.size.toLong(), "arm64_v8a"),
            ),
            File(folder.root, "opened"),
        )

        val bundle = (result as ExtractionResult.Done).bundle
        assertThat(bundle.apks.map { it.file.name })
            .containsExactly("base.apk", "config.arm64_v8a.apk")
        // The digest is that of the bytes that came out, and it is what `SessionInstaller` will
        // re-compare while the same bytes enter the session: without it, between extraction and
        // installation there would remain a window in which the file on disk can change.
        assertThat(bundle.base.sha256).isEqualTo(sha256(base))
        assertThat(bundle.base.file.readBytes()).isEqualTo(base)
        assertThat(File(folder.root, "opened").list()).hasLength(2)
    }

    @Test
    fun `an entry name climbing directories does not become a path`() {
        // A zip can contain `../../databases/multistore.db`, and `File(destination, name)` would
        // resolve it **outside** the directory. Here only the last segment is taken.
        val payload = ByteArray(16) { 9 }
        val container = Containers.zip(
            folder.newFile("cattivo.xapk"),
            listOf("base.apk" to ByteArray(8), "../../fuori.apk" to payload),
        )
        val into = File(folder.root, "opened")

        val result = extractor.extract(
            container,
            summary(
                BundlePart("base.apk", SplitKind.BASE, 8),
                BundlePart("../../fuori.apk", SplitKind.FEATURE, payload.size.toLong()),
            ),
            into,
        )

        val bundle = (result as ExtractionResult.Done).bundle
        assertThat(bundle.apks.map { it.file.name }).containsExactly("base.apk", "fuori.apk")
        assertThat(bundle.apks.all { it.file.parentFile == into }).isTrue()
        assertThat(File(folder.root, "fuori.apk").exists()).isFalse()
    }

    @Test
    fun `an entry the container lacks stops everything instead of being skipped`() {
        val container = Containers.zip(folder.newFile("monco.xapk"), listOf("base.apk" to ByteArray(8)))

        val result = extractor.extract(
            container,
            summary(
                BundlePart("base.apk", SplitKind.BASE, 8),
                BundlePart("config.arm64_v8a.apk", SplitKind.ABI, 20, "arm64_v8a"),
            ),
            File(folder.root, "opened"),
        )

        // A missing piece is an app with no native code: the fault would arrive at the first launch,
        // where nobody links it to the installation.
        assertThat((result as ExtractionResult.Failed).reason).contains("config.arm64_v8a.apk")
    }

    @Test
    fun `the OBB keeps its name, because Android looks for it by name`() {
        val obb = ByteArray(24) { 5 }
        // The zip entry is called one thing and `install_path` another. The first draft of this test
        // made them coincide, and the injection ignoring `install_path` stayed **green**: not because
        // the defence is useless, but because the fixture did not contain the case the defence
        // handles.
        val container = Containers.zip(
            folder.newFile("game.xapk"),
            listOf("base.apk" to ByteArray(8), "expansion1.obb" to obb),
        )

        val result = extractor.extract(
            container,
            summary(
                BundlePart("base.apk", SplitKind.BASE, 8),
                BundlePart(
                    entryName = "expansion1.obb",
                    kind = SplitKind.EXPANSION,
                    sizeBytes = obb.size.toLong(),
                    tag = "Android/obb/com.example/main.4.com.example.obb",
                ),
            ),
            File(folder.root, "opened"),
        )

        val bundle = (result as ExtractionResult.Done).bundle
        // `main.<versionCode>.<package>.obb`: renaming it would make it invisible to the app, which
        // would start behaving as though the data were not there. The **directory** by contrast is
        // not taken from here: that is decided by the verified packageName.
        assertThat(bundle.expansions.single().file.name).isEqualTo("main.4.com.example.obb")
        assertThat(bundle.apks.map { it.file.name }).containsExactly("base.apk")
    }

    @Test
    fun `a broken container leaves the directory clean`() {
        val broken = folder.newFile("rotto.xapk").apply { writeBytes(ByteArray(40) { 3 }) }
        val into = File(folder.root, "opened")

        val result = extractor.extract(broken, summary(BundlePart("base.apk", SplitKind.BASE, 8)), into)

        assertThat(result).isInstanceOf(ExtractionResult.Failed::class.java)
        // Half an extraction left there would be a directory the sweep finds and throws away, but
        // only at the next launch: meanwhile it occupies as much as the container.
        assertThat(into.exists()).isFalse()
    }

    @Test
    fun `two entries that would land on the same file stop the extraction`() {
        val container = Containers.zip(
            folder.newFile("collisione.xapk"),
            listOf("uno/base.apk" to ByteArray(8), "due/base.apk" to ByteArray(9)),
        )

        val result = extractor.extract(
            container,
            summary(
                BundlePart("uno/base.apk", SplitKind.BASE, 8),
                BundlePart("due/base.apk", SplitKind.FEATURE, 9),
            ),
            File(folder.root, "opened"),
        )

        // Only the last segment of the name remains: overwriting would give an app missing a split
        // and no error anywhere.
        assertThat((result as ExtractionResult.Failed).reason).contains("base.apk")
    }
}
