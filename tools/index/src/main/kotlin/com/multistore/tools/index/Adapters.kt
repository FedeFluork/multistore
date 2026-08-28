package com.multistore.tools.index

import com.multistore.core.network.http.StoreHttpClients
import com.multistore.store.api.StoreAdapter
import com.multistore.store.apkcombo.ApkComboConfig
import com.multistore.store.apkcombo.ApkComboStoreAdapter
import com.multistore.store.apkmirror.ApkMirrorConfig
import com.multistore.store.apkmirror.ApkMirrorStoreAdapter
import com.multistore.store.apkmody.ApkModyConfig
import com.multistore.store.apkmody.ApkModyStoreAdapter
import com.multistore.store.pdalife.PdalifeConfig
import com.multistore.store.pdalife.PdalifeStoreAdapter
import com.multistore.store.uptodown.UptodownConfig
import com.multistore.store.uptodown.UptodownStoreAdapter

/**
 * The real adapters, built by hand with their **compiled** configurations.
 *
 * The compiled defaults and not `parsers.json`, because the pipeline is also the check that those
 * defaults work. If it read the remote configuration, a fix published to patch a broken adapter would
 * turn the pipeline green too — and the compiled defaults, which must always exist and stay current,
 * could rot without anybody noticing until the CDN goes down.
 *
 * Only five stores out of nine, because only five have a surface to read from: the rankings of
 * uptodown and apkmody, the feeds of apkcombo, apkmirror and pdalife. The other four do not appear
 * here and that is not an oversight — F-Droid publishes no popularity and the app already has its
 * new releases in the local index; an1 serves the homepage in place of `/popular/` and its `rss.xml`
 * contains a single entry, "RSS in offline mode"; liteapks has neither (`/popular/` answers 404,
 * `/feed/` returns the homepage); modyolo has a feed that was deliberately not used, and the reason
 * is at the head of [BuildIndex].
 *
 * Adding them here without the capability would achieve nothing: `BuildIndex` filters on
 * `capabilities.trending` and `capabilities.recent`, which are each adapter's honest declaration of
 * what it can do.
 */
internal object Adapters {

    fun all(clients: StoreHttpClients): List<StoreAdapter> = listOf(
        ApkComboStoreAdapter(ApkComboConfig(), clients),
        ApkMirrorStoreAdapter(ApkMirrorConfig(), clients),
        ApkModyStoreAdapter(ApkModyConfig(), clients),
        PdalifeStoreAdapter(PdalifeConfig(), clients),
        UptodownStoreAdapter(UptodownConfig(), clients),
    )
}
