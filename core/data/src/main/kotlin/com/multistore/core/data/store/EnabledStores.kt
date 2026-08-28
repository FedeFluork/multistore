package com.multistore.core.data.store

import com.multistore.core.database.dao.StoreDao
import com.multistore.store.api.StoreAdapter
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The stores the user has left switched on, and the first-launch trap.
 *
 * The two conditions are different and have to be kept different. A store **never registered** is on
 * by default — it is the first-launch situation, before `registerKnownStores` has written the rows —
 * and filtering it out would give a search that queries nobody. A store **registered and off** stays
 * off, even when it is the last one left: deducing it from the enabled list alone, and treating "empty
 * list" as "take them all", would switch it back on behind the user's back.
 *
 * It lives here and not inside a repository because two callers now ask for it — the search and the
 * cross-store matching — and two copies of the same rule would diverge at the first change, leaving a
 * search that queries a store and a detail screen that ignores it.
 */
@Singleton
class EnabledStores @Inject constructor(
    private val registry: StoreRegistry,
    private val storeDao: StoreDao,
) {

    suspend fun adapters(): List<StoreAdapter> {
        val registered = storeDao.registeredIds().toSet()
        val enabled = storeDao.enabled().map { it.storeId }.toSet()
        return registry.all.filter { it.id !in registered || it.id in enabled }
    }
}
