package com.multistore.core.installer.container

import com.google.common.truth.Truth.assertThat
import com.multistore.core.model.ArtifactType
import com.multistore.core.model.BundlePart
import com.multistore.core.model.DeviceProfile
import com.multistore.core.model.SplitKind
import org.junit.Test

/**
 * What gets installed from a container, and what stays inside.
 *
 * The measurement making these rules necessary: Duolingo's XAPK weighs 238 MB, of which an arm64
 * needs 180. Installing everything is not "prudent", it is handing the device three architectures it
 * will never execute.
 */
class SplitSelectionTest {

    private fun part(name: String, kind: SplitKind, tag: String? = null, size: Long = 1) =
        BundlePart(entryName = name, kind = kind, sizeBytes = size, tag = tag)

    private fun bundle(vararg parts: BundlePart) = ContainerContents.Bundle(
        artifactType = ArtifactType.XAPK,
        parts = parts.toList(),
        declaredPackageName = "com.example",
        declaredVersionCode = 1,
    )

    private val arm64 = DeviceProfile(
        sdkInt = 34,
        supportedAbis = listOf("arm64-v8a", "armeabi-v7a"),
        densityDpi = 420,
    )

    private fun installed(selection: SplitSelection.Selection) =
        (selection as SplitSelection.Selection.Install).summary.install.map { it.entryName }

    @Test
    fun `only one ABI is taken, the first the device prefers`() {
        val selection = SplitSelection.select(
            bundle(
                part("base.apk", SplitKind.BASE),
                part("config.arm64_v8a.apk", SplitKind.ABI, "arm64_v8a"),
                part("config.armeabi_v7a.apk", SplitKind.ABI, "armeabi_v7a"),
                part("config.x86.apk", SplitKind.ABI, "x86"),
            ),
            arm64,
        )

        // `armeabi-v7a` runs on an arm64, but it is the device's second choice: taking it would mean
        // installing the emulated code while having the native one in the same file.
        assertThat(installed(selection)).containsExactly("base.apk", "config.arm64_v8a.apk")
    }

    @Test
    fun `with no matching ABI the outcome is not a truncated installation`() {
        val selection = SplitSelection.select(
            bundle(
                part("base.apk", SplitKind.BASE),
                part("config.x86.apk", SplitKind.ABI, "x86"),
                part("config.x86_64.apk", SplitKind.ABI, "x86_64"),
            ),
            arm64,
        )

        // An app without its `lib/` installs perfectly well and dies at the first
        // `System.loadLibrary`: a fault nobody links to the installation.
        assertThat(selection).isInstanceOf(SplitSelection.Selection.Incompatible::class.java)
        assertThat((selection as SplitSelection.Selection.Incompatible).available)
            .containsExactly("x86", "x86-64")
    }

    @Test
    fun `a container with no ABI split is not incompatible, it is an app with no native code`() {
        val selection = SplitSelection.select(
            bundle(part("base.apk", SplitKind.BASE), part("config.xxhdpi.apk", SplitKind.DENSITY, "xxhdpi")),
            arm64,
        )

        assertThat(selection).isInstanceOf(SplitSelection.Selection.Install::class.java)
    }

    @Test
    fun `the density chosen is the smallest bucket covering the device`() {
        val selection = SplitSelection.select(
            bundle(
                part("base.apk", SplitKind.BASE),
                part("config.hdpi.apk", SplitKind.DENSITY, "hdpi"),
                part("config.xhdpi.apk", SplitKind.DENSITY, "xhdpi"),
                part("config.xxhdpi.apk", SplitKind.DENSITY, "xxhdpi"),
                part("config.xxxhdpi.apk", SplitKind.DENSITY, "xxxhdpi"),
            ),
            arm64,
        )

        // 420 dpi: `xhdpi` (320) would be blurry, `xxhdpi` (480) is the first that covers it.
        assertThat(installed(selection)).containsExactly("base.apk", "config.xxhdpi.apk")
    }

    @Test
    fun `on a device of unknown density the largest bucket is taken`() {
        val selection = SplitSelection.select(
            bundle(
                part("base.apk", SplitKind.BASE),
                part("config.mdpi.apk", SplitKind.DENSITY, "mdpi"),
                part("config.hdpi.apk", SplitKind.DENSITY, "hdpi"),
            ),
            DeviceProfile(sdkInt = 34, supportedAbis = listOf("arm64-v8a"), densityDpi = 0),
        )

        // Zero is not a density in the domain: it is "I do not know", and the prudent error is
        // upward. Resources that are too large look fine, resources that are too small look bad.
        assertThat(installed(selection)).containsExactly("base.apk", "config.hdpi.apk")
    }

    @Test
    fun `the languages are all kept, and so are the modules`() {
        val selection = SplitSelection.select(
            bundle(
                part("base.apk", SplitKind.BASE),
                part("config.it.apk", SplitKind.LANGUAGE, "it"),
                part("config.zh.apk", SplitKind.LANGUAGE, "zh"),
                part("mappe.apk", SplitKind.FEATURE),
            ),
            arm64,
        )

        // The languages because we do not have Play's on-demand channel: whoever changes system
        // language would be left without, and with no way of fixing it. The modules because one
        // split fewer is a part of the app that is missing, and the symptom arrives inside the app.
        assertThat(installed(selection))
            .containsExactly("base.apk", "config.it.apk", "config.zh.apk", "mappe.apk")
    }

    @Test
    fun `the metadata are not installed and the expansions are a list of their own`() {
        val selection = SplitSelection.select(
            bundle(
                part("base.apk", SplitKind.BASE),
                part("icon.png", SplitKind.METADATA),
                part("main.4.com.example.obb", SplitKind.EXPANSION, size = 900),
            ),
            arm64,
        )
        val summary = (selection as SplitSelection.Selection.Install).summary

        assertThat(summary.install.map { it.entryName }).containsExactly("base.apk")
        assertThat(summary.skipped).isEmpty()
        assertThat(summary.hasExpansions).isTrue()
        assertThat(summary.expansionBytes).isEqualTo(900)
    }

    @Test
    fun `what is not installed is counted, instead of vanishing`() {
        val selection = SplitSelection.select(
            bundle(
                part("base.apk", SplitKind.BASE, size = 152),
                part("config.arm64_v8a.apk", SplitKind.ABI, "arm64_v8a", size = 26),
                part("config.x86.apk", SplitKind.ABI, "x86", size = 5),
                part("config.x86_64.apk", SplitKind.ABI, "x86_64", size = 24),
            ),
            arm64,
        )
        val summary = (selection as SplitSelection.Selection.Install).summary

        // It is the difference between the megabytes downloaded and those installed, and it is what
        // the screen shows: without it, they look like space that vanished.
        assertThat(summary.installBytes).isEqualTo(178)
        assertThat(summary.skipped.map { it.entryName })
            .containsExactly("config.x86.apk", "config.x86_64.apk")
    }
}
