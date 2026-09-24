package com.multistore.feature.settings

import com.multistore.core.common.net.StoreHealth
import com.multistore.core.data.repository.StoreEntry
import com.multistore.core.model.StoreCategory
import com.multistore.core.model.StoreHealthState
import com.multistore.core.model.StoreId

/**
 * The nine stores as the settings goldens draw them.
 *
 * Shared between the section's golden and the chooser's, because the two photograph the same list
 * from two distances: a sample that drifted between them would make the section's count disagree
 * with the tabs' — and that count is the one thing the section still says.
 */
internal object StoreSamples {

    /**
     * All nine, because the tab counts are part of what is being photographed.
     *
     * A three-store sample would draw "Open source 1/1 · Original 1/2 · Modified 0/0", and a tab
     * reading `0/0` is a state the real catalogue cannot produce — a golden of it would be a picture
     * of something nobody can reach. The three that are off are spread across two groups on purpose:
     * with them all in one, the select-all checkbox would be photographed in one state only.
     */
    val NINE: List<StoreEntry> = listOf(
        entry(StoreId.FDROID, "F-Droid", "f-droid.org", StoreCategory.OPEN_SOURCE),
        entry(StoreId.APKCOMBO, "APKCombo", "apkcombo.com", StoreCategory.ORIGINAL, enabled = false),
        entry(
            StoreId.APKMIRROR,
            "APKMirror",
            "www.apkmirror.com",
            StoreCategory.ORIGINAL,
            state = StoreHealthState.OPEN,
        ),
        entry(StoreId.UPTODOWN, "Uptodown", "en.uptodown.com", StoreCategory.ORIGINAL),
        entry(StoreId.APKMODY, "APKMODY", "apkmody.mobi", StoreCategory.MODIFIED),
        entry(StoreId.MODYOLO, "MODYOLO", "modyolo.com", StoreCategory.MODIFIED, enabled = false),
        entry(StoreId.AN1, "AN1", "an1.com", StoreCategory.MODIFIED),
        entry(StoreId.PDALIFE, "PDALIFE", "pdalife.com", StoreCategory.MODIFIED, enabled = false),
        entry(StoreId.LITEAPKS, "LiteAPKs", "liteapks.com", StoreCategory.MODIFIED),
    )

    private fun entry(
        storeId: StoreId,
        displayName: String,
        host: String,
        category: StoreCategory,
        enabled: Boolean = true,
        state: StoreHealthState = StoreHealthState.CLOSED,
    ) = StoreEntry(
        storeId = storeId,
        displayName = displayName,
        host = host,
        enabled = enabled,
        category = category,
        health = StoreHealth(storeId, state = state),
    )
}
