package com.multistore.core.installer.container

import com.multistore.core.model.BundlePart
import com.multistore.core.model.BundleSummary
import com.multistore.core.model.DeviceProfile
import com.multistore.core.model.SplitKind

/**
 * Which pieces of a container to install on **this** device.
 *
 * ### Why not "all of them"
 *
 * A container carries every architecture and every density together, and the difference is large:
 * the Duolingo XAPK measured on 26/08/2026 weighs 238 MB, of which an arm64 needs the base (152 MB),
 * one ABI (26 MB) and one density (0.5 MB) — the other 59 MB are x86, x86_64, armeabi-v7a and six
 * density buckets that device will never open. Firefox's APKM is starker still: 624 MB uncompressed
 * for three ABIs, of which one is needed.
 *
 * ### The four rules, and why they differ from each other
 *
 * | type | rule | why |
 * |---|---|---|
 * | base | always | without it there is nothing to install |
 * | ABI | **one**, the first of `supportedAbis` the container has | it is the device's order of preference, and the first is the native one |
 * | density | **one**, the smallest bucket ≥ the device's | below it looks blurry, above it wastes — and blurry is worse |
 * | language | **all** | we do not have Play's on-demand channel: whoever changes system language would be left without |
 * | module / unknown | always | one split fewer is a part of the app that is missing |
 *
 * ### When no ABI matches
 *
 * The outcome is [Selection.Incompatible], and not "install without native code": an app missing its
 * `lib/` installs perfectly well and then dies at the first `System.loadLibrary`, i.e. produces a
 * fault nobody links to the installation. A container with **no** ABI split at all is instead a
 * normal case — the app has no native code — and there is nothing to match.
 */
object SplitSelection {

    sealed interface Selection {

        data class Install(val summary: BundleSummary) : Selection

        /**
         * The container has native code and none of its ABIs is the device's.
         *
         * [available] serves the message: "this file only carries x86" is a sentence that explains,
         * "incompatible" is not.
         */
        data class Incompatible(val available: List<String>) : Selection
    }

    fun select(bundle: ContainerContents.Bundle, device: DeviceProfile): Selection {
        val byKind = bundle.parts.groupBy { it.kind }

        val abis = byKind[SplitKind.ABI].orEmpty()
        val chosenAbi = abis.bestAbiFor(device)
        if (abis.isNotEmpty() && chosenAbi == null) {
            return Selection.Incompatible(abis.mapNotNull { it.tag }.map(::abiOfTag))
        }

        val densities = byKind[SplitKind.DENSITY].orEmpty()
        val chosenDensity = densities.bestDensityFor(device)

        val keep = buildSet {
            byKind[SplitKind.BASE]?.let(::addAll)
            byKind[SplitKind.LANGUAGE]?.let(::addAll)
            byKind[SplitKind.FEATURE]?.let(::addAll)
            chosenAbi?.let(::add)
            chosenDensity?.let(::add)
        }

        val installable = bundle.parts.filter { it.kind != SplitKind.METADATA && it.kind != SplitKind.EXPANSION }
        return Selection.Install(
            BundleSummary(
                artifactType = bundle.artifactType,
                install = installable.filter { it in keep },
                skipped = installable.filterNot { it in keep },
                expansions = byKind[SplitKind.EXPANSION].orEmpty(),
            ),
        )
    }

    /**
     * The device's first ABI the container has a split for.
     *
     * The order is `Build.SUPPORTED_ABIS`'s, which is already the order of preference: on an arm64 it
     * is `arm64-v8a, armeabi-v7a`, and taking the first available means taking the native one where
     * there is one and the emulated one where it is the only one.
     */
    private fun List<BundlePart>.bestAbiFor(device: DeviceProfile): BundlePart? {
        for (abi in device.supportedAbis) {
            firstOrNull { it.tag != null && abiOfTag(it.tag!!) == abi }?.let { return it }
        }
        return null
    }

    /**
     * The smallest bucket covering the device's density; if there is none, the largest.
     *
     * With `densityDpi` at zero — device unknown — we end up on the "largest" branch, which is the
     * prudent error: resources that are too large look fine, resources that are too small look
     * blurry.
     */
    private fun List<BundlePart>.bestDensityFor(device: DeviceProfile): BundlePart? {
        if (isEmpty()) return null
        val withDpi = mapNotNull { part -> DENSITY_BUCKETS[part.tag]?.let { part to it } }
        if (withDpi.isEmpty()) return null
        // Zero has to be excluded **explicitly** and not left to the comparison: `>= 0` is true for
        // every bucket, so the "smallest that covers" branch would pick `ldpi`. It is the same trap
        // as the numeric proto3 fields seen from another angle — a zero meaning "never written" that
        // a naive comparison treats as a domain value.
        if (device.densityDpi <= 0) return withDpi.maxByOrNull { it.second }?.first
        return withDpi.filter { it.second >= device.densityDpi }.minByOrNull { it.second }?.first
            ?: withDpi.maxByOrNull { it.second }?.first
    }
}
