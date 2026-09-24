package com.multistore.core.model

/**
 * What kind of source a store is, in the one question a person choosing between nine actually asks:
 * **who built this file, and can anybody check?**
 *
 * ### Three values, and they partition the nine
 *
 * Each store is in exactly one, and that is not an accident of the current catalogue — it is what
 * makes the tab labels honest. "All 5/9" has to be the sum of the others, or a count next to a tab
 * name is a number the reader has to distrust.
 *
 * ### It is derived from declarations, not from a table
 *
 * Both halves come from `StoreCapabilities`, which every adapter fills in and the contract test
 * holds to: `openSourceOnly` and `redistributesModifiedBuilds`. A hand-written map here would be a
 * tenth place to remember when a store is added — and the one place nothing would fail if it were
 * forgotten, because a missing entry has a plausible answer.
 *
 * The two declarations are not opposites, which is why [ORIGINAL] exists: apkcombo, apkmirror and
 * uptodown mirror the developer's own builds without being open-source-only, so they are neither.
 */
enum class StoreCategory {
    /**
     * Free and open-source software only — today f-droid alone.
     *
     * The strongest of the three for verification: the catalogue is signed, every version carries a
     * checksum, and the expected signing key is published, so a download can be checked end to end.
     */
    OPEN_SOURCE,

    /**
     * Mirrors of the developer's own builds: apkcombo, apkmirror, uptodown.
     *
     * The file is the one its author signed, so step 5 of the pre-install pipeline has a real
     * signature to compare against — what differs between them is how much each one publishes to
     * check it with.
     */
    ORIGINAL,

    /**
     * Redistributes builds somebody other than the developer has reworked: apkmody, modyolo, an1,
     * pdalife, liteapks.
     *
     * The declared limit of R5 applies to all of them: for a rework there is no original developer
     * signature to compare against, so the pipeline protects against the package being substituted
     * and not against the archive being tampered with upstream.
     */
    MODIFIED,
    ;

    companion object {
        /** Which of the three, from the two declarations the adapter makes. */
        fun of(openSourceOnly: Boolean, redistributesModifiedBuilds: Boolean): StoreCategory = when {
            // Checked first, and the order is a decision: a store that were both would be filed
            // under the claim that promises **less**, because the tab is read as a guarantee.
            redistributesModifiedBuilds -> MODIFIED
            openSourceOnly -> OPEN_SOURCE
            else -> ORIGINAL
        }
    }
}
