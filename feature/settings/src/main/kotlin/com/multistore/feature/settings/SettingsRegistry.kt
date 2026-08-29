package com.multistore.feature.settings

import androidx.annotation.StringRes
import com.multistore.core.model.StorageLevel
import com.multistore.core.model.StoreId

/**
 * The Settings entries' registry — the UI half of rule 2.
 *
 * "Every configurable feature has an entry in Settings": `SettingsCoverageTest` compares the fields
 * declared in `core/datastore/src/main/proto/settings.proto` with the entries listed here and fails
 * if a field is left uncovered.
 *
 * The link between the two parts is [SettingKey.protoField], which carries **the proto field's name
 * exactly as written in the .proto** (snake_case). It is a string and not a typed reference because
 * the test has to be able to read both sides without compiling the proto.
 */
enum class SettingKey(val protoField: String) {
    THEME_MODE("theme_mode"),
    DYNAMIC_COLOR("dynamic_color"),
    LANGUAGE_TAG("language_tag"),
    UPDATE_INTERVAL("update_interval"),
    UPDATE_ONLY_WHEN_CHARGING("update_only_when_charging"),
    AUTO_DOWNLOAD_UPDATES("auto_download_updates"),
    AUTO_INSTALL_UPDATES("auto_install_updates"),
    ALLOW_PREVIEW_CHANNELS("allow_preview_channels"),
    MUTE_UPDATE_NOTIFICATIONS("mute_update_notifications"),
    INSTALLER_PREFERENCE("installer_preference"),
    AUTO_INSTALL_AFTER_DOWNLOAD("auto_install_after_download"),
    BLOCK_REMOTE_PARSERS("block_remote_parsers"),
    BLOCK_REMOTE_INDEX("block_remote_index"),
    BLOCK_SELF_UPDATE_CHECK("block_self_update_check"),
    ALLOW_UNVERIFIED_HASH("allow_unverified_hash"),
    ALLOW_SIGNER_MISMATCH("allow_signer_mismatch"),
    SHOW_NSFW_CONTENT("show_nsfw_content"),
    SEARCH_TIMEOUT("search_timeout_seconds"),
    DEFAULT_SORT("default_sort"),
    DEFAULT_CONTENT_KIND("default_content_kind"),
    MUTE_DOWNLOAD_NOTIFICATIONS("mute_download_notifications"),
    MUTE_INSTALL_NOTIFICATIONS("mute_install_notifications"),
    MUTE_STORE_ALERTS("mute_store_alerts"),
    DIAGNOSTICS_LOG_ENABLED("diagnostics_log_enabled"),
    METERED_NETWORK_ALLOWED("metered_network_allowed"),
    CHALLENGE_STRATEGY("challenge_strategy"),
    BLOCK_USER_ASSISTED_CHALLENGE("block_user_assisted_challenge"),
    ALLOW_WEB_ADS("allow_web_ads"),
    KEEP_APK_AFTER_INSTALL("keep_apk_after_install"),
    IMAGE_CACHE_MAX_MB("image_cache_max_mb"),
    CATALOG_RETENTION("catalog_retention"),
    DOWNLOAD_HISTORY_LIMIT("download_history_limit"),
}

/** The screen's sections, in the order they appear. */
enum class SettingsSection(@param:StringRes val titleRes: Int) {
    APPEARANCE(R.string.settings_section_appearance),
    STORES(R.string.settings_section_stores),
    CONTENT(R.string.settings_section_content),
    SEARCH(R.string.settings_section_search),
    UPDATES(R.string.settings_section_updates),
    NOTIFICATIONS(R.string.settings_section_notifications),
    INSTALLATION(R.string.settings_section_installation),
    SECURITY(R.string.settings_section_security),
    NETWORK(R.string.settings_section_network),
    CONFIGURATION(R.string.settings_section_configuration),
    STORAGE(R.string.settings_section_storage),
    DIAGNOSTICS(R.string.settings_section_diagnostics),

    /**
     * The last, and not by chance: whoever reaches the bottom of the screen got there by scrolling
     * through everything else. A request for support above the settings would be the first thing a
     * user sees when opening Settings to change the theme.
     */
    SUPPORT(R.string.settings_section_support),
}

/**
 * A store's translated description, or `null` if that store is not implemented yet.
 *
 * ### Why it is not a `settings.proto` field
 *
 * The "adding a new store" checklist used to ask for an entry in `settings.proto` (`StoreSettings`).
 * **That is no longer how it is done, and the reason is that there would be two sources of truth.**
 * Per-store enablement already lives in Room, in the `stores` table, next to the display order and
 * the circuit breaker's state; it is the column `SearchRepository` reads to decide who to query.
 * Duplicating it in the DataStore would mean two values that can diverge, with the search reading one
 * and Settings the other.
 *
 * What that checklist point really asked for — that no store arrives without its five translations —
 * stays guaranteed, and by a guardrail tighter than a proto field: `StoreCatalogTest` derives the list
 * of stores **from the modules that have sources** and demands each has a description here, in all
 * five languages.
 *
 * ### Why the `when` is exhaustive instead of having an `else`
 *
 * An `else -> null` would make this function silent precisely when it needs to speak: adding a tenth
 * store, the compiler would say nothing and the listing would appear with no description. This way
 * instead it does not compile until it has been decided what to write.
 */
@StringRes
internal fun storeDescriptionRes(storeId: StoreId): Int? = when (storeId) {
    StoreId.FDROID -> R.string.settings_store_f_droid_description
    StoreId.APKCOMBO -> R.string.settings_store_apkcombo_description
    StoreId.APKMIRROR -> R.string.settings_store_apkmirror_description
    StoreId.APKMODY -> R.string.settings_store_apkmody_description
    StoreId.UPTODOWN -> R.string.settings_store_uptodown_description
    StoreId.MODYOLO -> R.string.settings_store_modyolo_description
    StoreId.AN1 -> R.string.settings_store_an1_description
    StoreId.PDALIFE -> R.string.settings_store_pdalife_description
    StoreId.LITEAPKS -> R.string.settings_store_liteapks_description
}

/**
 * A Settings entry: which field it configures, what it is called, what it does.
 *
 * `descriptionRes` is deliberately not optional. A switch with no explanation forces the user to
 * guess what changes, and in an app that installs APKs guessing is expensive.
 */
data class SettingsEntry(
    val key: SettingKey,
    val section: SettingsSection,
    @param:StringRes val labelRes: Int,
    @param:StringRes val descriptionRes: Int,
)

/**
 * The complete list of the entries the screen exposes.
 *
 * Adding a field to `settings.proto` without adding the corresponding entry here makes
 * `SettingsCoverageTest` fail, and vice versa.
 *
 * ### This list is no longer only for the guardrail
 *
 * The screen used to write its own strings by hand — `stringResource(R.string.settings_theme_label)`
 * inside the row — and this list existed **alongside** it, for `SettingsCoverageTest` alone. The two
 * things could therefore diverge with nothing saying so: a row could show one label and the registry
 * declare another, and the test would stay green because it looks at the proto fields' names, not at
 * the strings.
 *
 * With the internal search that divergence stops being theoretical: searching "dark" filters by
 * comparing the **registry's** text, and if the row showed a different one the search would hide an
 * entry that on screen contains the searched word. The fix is not a second test but a single source:
 * the rows take a [SettingKey] and read label and description **from here**, so they cannot say
 * something different.
 */
val SETTINGS_REGISTRY: List<SettingsEntry> = listOf(
    SettingsEntry(
        key = SettingKey.THEME_MODE,
        section = SettingsSection.APPEARANCE,
        labelRes = R.string.settings_theme_label,
        descriptionRes = R.string.settings_theme_description,
    ),
    SettingsEntry(
        key = SettingKey.DYNAMIC_COLOR,
        section = SettingsSection.APPEARANCE,
        labelRes = R.string.settings_dynamic_color_label,
        descriptionRes = R.string.settings_dynamic_color_description,
    ),
    SettingsEntry(
        key = SettingKey.LANGUAGE_TAG,
        section = SettingsSection.APPEARANCE,
        labelRes = R.string.settings_language_label,
        descriptionRes = R.string.settings_language_description,
    ),
    SettingsEntry(
        key = SettingKey.UPDATE_INTERVAL,
        section = SettingsSection.UPDATES,
        labelRes = R.string.settings_update_interval_label,
        descriptionRes = R.string.settings_update_interval_description,
    ),
    SettingsEntry(
        key = SettingKey.UPDATE_ONLY_WHEN_CHARGING,
        section = SettingsSection.UPDATES,
        labelRes = R.string.settings_update_charging_label,
        descriptionRes = R.string.settings_update_charging_description,
    ),
    SettingsEntry(
        key = SettingKey.AUTO_DOWNLOAD_UPDATES,
        section = SettingsSection.UPDATES,
        labelRes = R.string.settings_update_auto_download_label,
        descriptionRes = R.string.settings_update_auto_download_description,
    ),
    SettingsEntry(
        key = SettingKey.AUTO_INSTALL_UPDATES,
        section = SettingsSection.UPDATES,
        labelRes = R.string.settings_update_auto_install_label,
        descriptionRes = R.string.settings_update_auto_install_description,
    ),
    // The four "Notifications" entries sit together here and not each next to the feature producing
    // them, and the two subdivisions deliberately do not coincide: in the domain the groups follow
    // **who reads** the value — `mute_update_notifications` is read by the periodic check's final
    // report, together with the interval — whereas here the question is a different one, "what does
    // MultiStore send me?", and it has a single answer.
    SettingsEntry(
        key = SettingKey.ALLOW_PREVIEW_CHANNELS,
        section = SettingsSection.UPDATES,
        labelRes = R.string.settings_preview_channels_label,
        descriptionRes = R.string.settings_preview_channels_description,
    ),
    SettingsEntry(
        key = SettingKey.MUTE_UPDATE_NOTIFICATIONS,
        section = SettingsSection.NOTIFICATIONS,
        labelRes = R.string.settings_update_mute_label,
        descriptionRes = R.string.settings_update_mute_description,
    ),
    SettingsEntry(
        key = SettingKey.MUTE_DOWNLOAD_NOTIFICATIONS,
        section = SettingsSection.NOTIFICATIONS,
        labelRes = R.string.settings_notify_download_label,
        descriptionRes = R.string.settings_notify_download_description,
    ),
    SettingsEntry(
        key = SettingKey.MUTE_INSTALL_NOTIFICATIONS,
        section = SettingsSection.NOTIFICATIONS,
        labelRes = R.string.settings_notify_install_label,
        descriptionRes = R.string.settings_notify_install_description,
    ),
    SettingsEntry(
        key = SettingKey.MUTE_STORE_ALERTS,
        section = SettingsSection.NOTIFICATIONS,
        labelRes = R.string.settings_notify_store_label,
        descriptionRes = R.string.settings_notify_store_description,
    ),
    SettingsEntry(
        key = SettingKey.DIAGNOSTICS_LOG_ENABLED,
        section = SettingsSection.DIAGNOSTICS,
        labelRes = R.string.settings_diagnostics_log_label,
        descriptionRes = R.string.settings_diagnostics_log_description,
    ),
    SettingsEntry(
        key = SettingKey.INSTALLER_PREFERENCE,
        section = SettingsSection.INSTALLATION,
        labelRes = R.string.settings_installer_label,
        descriptionRes = R.string.settings_installer_description,
    ),
    SettingsEntry(
        key = SettingKey.AUTO_INSTALL_AFTER_DOWNLOAD,
        section = SettingsSection.INSTALLATION,
        labelRes = R.string.settings_auto_install_after_download_label,
        descriptionRes = R.string.settings_auto_install_after_download_description,
    ),
    SettingsEntry(
        key = SettingKey.BLOCK_REMOTE_INDEX,
        section = SettingsSection.CONFIGURATION,
        labelRes = R.string.settings_block_remote_index_label,
        descriptionRes = R.string.settings_block_remote_index_description,
    ),
    SettingsEntry(
        key = SettingKey.BLOCK_SELF_UPDATE_CHECK,
        section = SettingsSection.UPDATES,
        labelRes = R.string.settings_block_self_update_label,
        descriptionRes = R.string.settings_block_self_update_description,
    ),
    SettingsEntry(
        key = SettingKey.ALLOW_UNVERIFIED_HASH,
        section = SettingsSection.SECURITY,
        labelRes = R.string.settings_allow_unverified_hash_label,
        descriptionRes = R.string.settings_allow_unverified_hash_description,
    ),
    SettingsEntry(
        key = SettingKey.ALLOW_SIGNER_MISMATCH,
        section = SettingsSection.SECURITY,
        labelRes = R.string.settings_allow_signer_mismatch_label,
        descriptionRes = R.string.settings_allow_signer_mismatch_description,
    ),
    SettingsEntry(
        key = SettingKey.BLOCK_REMOTE_PARSERS,
        section = SettingsSection.CONFIGURATION,
        labelRes = R.string.settings_config_block_label,
        descriptionRes = R.string.settings_config_block_description,
    ),
    SettingsEntry(
        key = SettingKey.SHOW_NSFW_CONTENT,
        section = SettingsSection.CONTENT,
        labelRes = R.string.settings_content_nsfw_label,
        descriptionRes = R.string.settings_content_nsfw_description,
    ),
    SettingsEntry(
        key = SettingKey.SEARCH_TIMEOUT,
        section = SettingsSection.SEARCH,
        labelRes = R.string.settings_search_timeout_label,
        descriptionRes = R.string.settings_search_timeout_description,
    ),
    SettingsEntry(
        key = SettingKey.DEFAULT_CONTENT_KIND,
        section = SettingsSection.SEARCH,
        labelRes = R.string.settings_search_content_kind_label,
        descriptionRes = R.string.settings_search_content_kind_description,
    ),
    SettingsEntry(
        key = SettingKey.DEFAULT_SORT,
        section = SettingsSection.SEARCH,
        labelRes = R.string.settings_search_sort_label,
        descriptionRes = R.string.settings_search_sort_description,
    ),
    SettingsEntry(
        key = SettingKey.METERED_NETWORK_ALLOWED,
        section = SettingsSection.NETWORK,
        labelRes = R.string.settings_metered_network_label,
        descriptionRes = R.string.settings_metered_network_description,
    ),
    SettingsEntry(
        key = SettingKey.CHALLENGE_STRATEGY,
        section = SettingsSection.NETWORK,
        labelRes = R.string.settings_challenge_strategy_label,
        descriptionRes = R.string.settings_challenge_strategy_description,
    ),
    SettingsEntry(
        key = SettingKey.BLOCK_USER_ASSISTED_CHALLENGE,
        section = SettingsSection.NETWORK,
        labelRes = R.string.settings_challenge_user_assisted_label,
        descriptionRes = R.string.settings_challenge_user_assisted_description,
    ),
    SettingsEntry(
        key = SettingKey.ALLOW_WEB_ADS,
        section = SettingsSection.NETWORK,
        labelRes = R.string.settings_allow_web_ads_label,
        descriptionRes = R.string.settings_allow_web_ads_description,
    ),
    SettingsEntry(
        key = SettingKey.KEEP_APK_AFTER_INSTALL,
        section = SettingsSection.STORAGE,
        labelRes = R.string.settings_keep_apk_label,
        descriptionRes = R.string.settings_keep_apk_description,
    ),
    SettingsEntry(
        key = SettingKey.CATALOG_RETENTION,
        section = SettingsSection.STORAGE,
        labelRes = R.string.settings_catalog_retention_label,
        descriptionRes = R.string.settings_catalog_retention_description,
    ),
    SettingsEntry(
        key = SettingKey.DOWNLOAD_HISTORY_LIMIT,
        section = SettingsSection.STORAGE,
        labelRes = R.string.settings_download_history_label,
        descriptionRes = R.string.settings_download_history_description,
    ),
    SettingsEntry(
        key = SettingKey.IMAGE_CACHE_MAX_MB,
        section = SettingsSection.STORAGE,
        labelRes = R.string.settings_image_cache_label,
        descriptionRes = R.string.settings_image_cache_description,
    ),
)

/**
 * The **actions** of Settings: things one does, not states one changes.
 *
 * They live in a list separate from [SETTINGS_REGISTRY] and that is not a stylistic choice.
 * `SettingsCoverageTest` ties every registry entry to a field of `settings.proto` and fails in both
 * directions; an action has no field, because there is nothing to remember between launches. Putting
 * one in the registry would mean inventing a proto field nobody reads — i.e. exactly the hidden state
 * that guardrail exists to prevent.
 *
 * The link with the guardrail stays structural all the same: [SettingsActionKey] carries no string in
 * parentheses, so it does not resemble a registry entry even when read with a regex.
 */
enum class SettingsActionKey {
    /**
     * The two donations, which are **actions** and not settings.
     *
     * They open an address in the browser and remember nothing between launches, so they have no field
     * in `settings.proto` — like the diagnostics export and like the install permission. They still go
     * through the registry so that they enter the internal search: looking for "paypal" or "donation"
     * finds them.
     */
    DONATE_KOFI,
    DONATE_PAYPAL,

    /** WAL checkpoint plus `VACUUM`: it gives back the space the database no longer uses. */
    RECLAIM_SPACE,

    /**
     * Writes the diagnostic report to a file the user chooses.
     *
     * An action and not a setting, for the same reason as the other two: there is nothing to remember
     * between launches. The promise from the start is diagnostics "local and exportable by the user",
     * and until recently the second half did not exist — `health_events` filled up and nobody could
     * read it.
     */
    EXPORT_DIAGNOSTICS,

    /**
     * Downloads `parsers.json` now instead of waiting the six hours.
     *
     * An action and not a setting: there is nothing to remember between launches. It exists because
     * without it the only way of verifying the channel works would be to wait — and because whoever
     * has just read that a store broke has no wish to wait six hours for the app to notice.
     */
    REFRESH_REMOTE_CONFIG,

    /**
     * The four levels are emptied one by one, and not with a single button.
     *
     * It is not granularity for its own sake: the four cost rebuilds of different orders of magnitude.
     * Refilling the icons is a few hundred kilobytes on the first scroll; refilling F-Droid's catalogue
     * is **18 MB compressed** to re-download, and on a metered network that is a decision, not a
     * detail. A single "empty the cache" doing all of them would make it impossible to know what
     * pressing it costs.
     */
    CLEAR_CATALOG,
    CLEAR_IMAGES,
    CLEAR_PAGES,
    CLEAR_STAGED_APKS,

    /**
     * Leads to the system's "install unknown apps" screen.
     *
     * An action and not a setting, and in the starkest of the five ways: that permission is not
     * remembered by us, it is remembered by Android, and a field in `settings.proto` would be a
     * **second copy** of a value we can only read — the same trap for which a store's enablement lives
     * in Room and not in the DataStore.
     *
     * It exists because that permission used to be reachable **only** from the sign on a listing whose
     * installation had already failed. Whoever had denied it by mistake, or who came from a ROM that
     * revokes it at every update, had to break something to find the way to fix it. And it is also the
     * place to say what to do when that screen does not exist: the R6 risk.
     */
    ALLOW_UNKNOWN_SOURCES,
}

data class SettingsAction(
    val key: SettingsActionKey,
    val section: SettingsSection,
    @param:StringRes val labelRes: Int,
    @param:StringRes val descriptionRes: Int,
    @param:StringRes val actionRes: Int,
)

val SETTINGS_ACTIONS: List<SettingsAction> = listOf(
    SettingsAction(
        key = SettingsActionKey.DONATE_KOFI,
        section = SettingsSection.SUPPORT,
        labelRes = R.string.settings_donate_kofi_label,
        descriptionRes = R.string.settings_donate_description,
        actionRes = R.string.settings_donate_kofi_action,
    ),
    SettingsAction(
        key = SettingsActionKey.DONATE_PAYPAL,
        section = SettingsSection.SUPPORT,
        labelRes = R.string.settings_donate_paypal_label,
        descriptionRes = R.string.settings_donate_description,
        actionRes = R.string.settings_donate_paypal_action,
    ),
    SettingsAction(
        key = SettingsActionKey.REFRESH_REMOTE_CONFIG,
        section = SettingsSection.CONFIGURATION,
        labelRes = R.string.settings_config_refresh_label,
        descriptionRes = R.string.settings_config_refresh_description,
        actionRes = R.string.settings_config_refresh_action,
    ),
    SettingsAction(
        key = SettingsActionKey.EXPORT_DIAGNOSTICS,
        section = SettingsSection.DIAGNOSTICS,
        labelRes = R.string.settings_diagnostics_export_label,
        descriptionRes = R.string.settings_diagnostics_export_description,
        actionRes = R.string.settings_diagnostics_export_action,
    ),
    SettingsAction(
        key = SettingsActionKey.CLEAR_CATALOG,
        section = SettingsSection.STORAGE,
        labelRes = R.string.settings_storage_catalog_label,
        descriptionRes = R.string.settings_storage_catalog_description,
        actionRes = R.string.settings_storage_clear_action,
    ),
    SettingsAction(
        key = SettingsActionKey.CLEAR_STAGED_APKS,
        section = SettingsSection.STORAGE,
        labelRes = R.string.settings_storage_apks_label,
        descriptionRes = R.string.settings_storage_apks_description,
        actionRes = R.string.settings_storage_clear_action,
    ),
    SettingsAction(
        key = SettingsActionKey.CLEAR_IMAGES,
        section = SettingsSection.STORAGE,
        labelRes = R.string.settings_storage_images_label,
        descriptionRes = R.string.settings_storage_images_description,
        actionRes = R.string.settings_storage_clear_action,
    ),
    SettingsAction(
        key = SettingsActionKey.CLEAR_PAGES,
        section = SettingsSection.STORAGE,
        labelRes = R.string.settings_storage_pages_label,
        descriptionRes = R.string.settings_storage_pages_description,
        actionRes = R.string.settings_storage_clear_action,
    ),
    SettingsAction(
        key = SettingsActionKey.ALLOW_UNKNOWN_SOURCES,
        section = SettingsSection.INSTALLATION,
        labelRes = R.string.settings_unknown_sources_label,
        descriptionRes = R.string.settings_unknown_sources_description,
        actionRes = R.string.settings_unknown_sources_action,
    ),
    SettingsAction(
        key = SettingsActionKey.RECLAIM_SPACE,
        section = SettingsSection.STORAGE,
        labelRes = R.string.settings_reclaim_space_label,
        descriptionRes = R.string.settings_reclaim_space_description,
        actionRes = R.string.settings_reclaim_space_action,
    ),
)

/**
 * The storage level an action empties.
 *
 * The link lives here and not in the screen for the same reason the registry became the source: the
 * row shows **that level's size** next to that action's label, and keeping the two things in two
 * places would mean being able to show the images' number next to the button that empties the pages —
 * with nothing saying so.
 *
 * The `when` is exhaustive with no `else`: a fifth level would not compile until it has been decided
 * which button empties it.
 */
internal fun SettingsActionKey.storageLevel(): StorageLevel? = when (this) {
    SettingsActionKey.CLEAR_CATALOG -> StorageLevel.CATALOG
    SettingsActionKey.CLEAR_IMAGES -> StorageLevel.IMAGES
    SettingsActionKey.CLEAR_PAGES -> StorageLevel.PAGES
    SettingsActionKey.CLEAR_STAGED_APKS -> StorageLevel.STAGED_APKS
    SettingsActionKey.RECLAIM_SPACE,
    SettingsActionKey.EXPORT_DIAGNOSTICS,
    SettingsActionKey.REFRESH_REMOTE_CONFIG,
    SettingsActionKey.ALLOW_UNKNOWN_SOURCES,
    SettingsActionKey.DONATE_KOFI,
    SettingsActionKey.DONATE_PAYPAL,
    -> null
}


/**
 * The entries indexed by key.
 *
 * `getValue` and not `get`: a key with no entry is a programming error, and a silent `null` here
 * would become a row with no title. The `SettingsScreenCoverageTest` guardrail catches it first, but
 * the map must not have a soft way out.
 */
internal val SETTINGS_ENTRIES: Map<SettingKey, SettingsEntry> =
    SETTINGS_REGISTRY.associateBy { it.key }

internal val SETTINGS_ACTION_ENTRIES: Map<SettingsActionKey, SettingsAction> =
    SETTINGS_ACTIONS.associateBy { it.key }

internal fun entryOf(key: SettingKey): SettingsEntry = SETTINGS_ENTRIES.getValue(key)

internal fun actionOf(key: SettingsActionKey): SettingsAction = SETTINGS_ACTION_ENTRIES.getValue(key)
