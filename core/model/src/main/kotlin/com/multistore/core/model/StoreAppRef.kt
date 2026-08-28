package com.multistore.core.model

/**
 * The reference by which **one** store identifies an app.
 *
 * It is opaque to the core: only the adapter interprets it, and the core never builds a URL. For
 * F-Droid it is the `packageName`; for a scraped store it may be a slug, a numeric id or a path.
 * Anything outside the adapter can only keep it and hand it back.
 *
 * A `value class` rather than a `String` precisely to make the mistake visible: passing a
 * `packageName` where a ref is expected, or concatenating it onto a base URL, does not compile.
 */
@JvmInline
value class StoreAppRef(val value: String) {
    init {
        require(value.isNotBlank()) { "An empty StoreAppRef identifies nothing" }
    }
}

/**
 * The reference to one specific version inside a listing.
 *
 * Opaque like [StoreAppRef]. For F-Droid it is the file's SHA-256, which the index already uses
 * as the key of its versions map.
 */
@JvmInline
value class VersionRef(val value: String) {
    init {
        require(value.isNotBlank()) { "An empty VersionRef identifies nothing" }
    }
}
