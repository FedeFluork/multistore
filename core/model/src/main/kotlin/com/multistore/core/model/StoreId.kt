package com.multistore.core.model

/**
 * The nine stores MultiStore aggregates.
 *
 * An enum rather than a free string because the set is not open at runtime: adding a store means
 * adding a `:store:<name>` module, not a line of configuration. The remote configuration can
 * change an existing store's URLs, selectors and rate limit — it cannot invent one.
 *
 * [wireName] is the form that ends up in Room, in `settings.proto` and in `parsers.json`. It is
 * deliberately separate from the constant's name: renaming the Kotlin constant must not
 * invalidate the database of someone who already has the app installed.
 */
enum class StoreId(val wireName: String) {
    FDROID("f-droid"),
    APKCOMBO("apkcombo"),
    APKMIRROR("apkmirror"),
    APKMODY("apkmody"),
    MODYOLO("modyolo"),
    AN1("an1"),
    PDALIFE("pdalife"),
    UPTODOWN("uptodown"),
    LITEAPKS("liteapks"),
    ;

    companion object {
        fun fromWireNameOrNull(wireName: String): StoreId? =
            entries.firstOrNull { it.wireName == wireName }
    }
}
