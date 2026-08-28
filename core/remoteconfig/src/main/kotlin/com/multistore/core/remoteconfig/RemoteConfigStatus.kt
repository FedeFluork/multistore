package com.multistore.core.remoteconfig

import com.multistore.core.model.StoreId
import kotlin.time.Instant

/**
 * What the app is using now, and how the last attempt at updating it went.
 *
 * They are two separate things and must be told separately, because **a downloaded document does
 * not become active immediately**: the adapters receive the configuration when they are constructed,
 * once per process. Merging the two pieces of information would produce the most wrong sentence
 * possible — "configuration updated" above an app still using the previous one.
 */
data class RemoteConfigStatus(
    val active: ActiveConfig = ActiveConfig.CompiledDefaults,
    val lastAttempt: FetchAttempt? = null,
    /**
     * The active document's keys that no compiled configuration knows.
     *
     * It fills up **as** the adapters ask for their own configuration, not when the document is
     * read: only the configuration's type knows which keys exist. In practice they are all known
     * after startup, which is when the adapters are all constructed together.
     */
    val ignoredKeys: List<String> = emptyList(),
    /** Stores named in the document that this version of the app does not know. */
    val unknownStores: List<String> = emptyList(),
    /** Stores whose override was discarded because a value had the wrong type. */
    val rejectedStores: List<StoreId> = emptyList(),
)

/** The configuration the adapters of this process received. */
sealed interface ActiveConfig {

    /** No valid document: the compiled defaults apply, and they always exist. */
    data object CompiledDefaults : ActiveConfig

    data class Applied(
        val schemaVersion: Int,
        /** When the pipeline produced it, if it declares it in a readable form. */
        val generatedAt: Instant?,
        /** When this installation accepted it: the cached file's date. */
        val storedAt: Instant?,
        val stores: Set<StoreId>,
    ) : ActiveConfig
}

/** The outcome of the last attempt to download a new document. */
sealed interface FetchAttempt {

    val at: Instant

    /**
     * Downloaded, verified, written to cache. **Active on the next launch.**
     *
     * That it becomes active later and not immediately is a consequence of how the configuration
     * reaches the adapters — by construction, once — and changing that mechanism would mean an
     * adapter could see two different configurations in the same session, with a search halfway
     * between them.
     */
    data class Accepted(override val at: Instant, val schemaVersion: Int) : FetchAttempt

    /** The document was there and was not accepted. The active configuration does not change. */
    data class Rejected(override val at: Instant, val reason: ConfigRejection) : FetchAttempt

    /** It could not be downloaded: no network, unreachable host, 404, 500. */
    data class Unreachable(override val at: Instant, val httpCode: Int?) : FetchAttempt

    /** The server answered that nothing has changed. */
    data class NotModified(override val at: Instant) : FetchAttempt

    /**
     * Verified and then not stored: the disk said no.
     *
     * A variant of its own and not a [Rejected] because the document was fine. Saying so matters
     * because the remedy is different — there is nothing to republish here — and because without
     * this case a full disk would be reported as a wrong signature.
     */
    data class NotStored(override val at: Instant) : FetchAttempt
}
