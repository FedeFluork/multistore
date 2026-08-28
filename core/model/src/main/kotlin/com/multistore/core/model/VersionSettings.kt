package com.multistore.core.model

/**
 * Which versions the app may pick **on its own**.
 *
 * A group of its own for a single field, because `SettingsRepository` splits its flows by
 * consumer rather than by topic — one flow would wake everyone on every switch. This field's
 * consumer is `selectVersion`: the two repositories that decide which version to offer, the app
 * detail screen and the update check. No other group has those two readers.
 */
data class VersionSettings(
    /**
     * Also offer beta, alpha and release candidates.
     *
     * Off at the zero value, which is the prudent behaviour: publishing into a preview channel is
     * a statement that the version is not finished, and choosing it on the user's behalf is not
     * something to do unasked. In the F-Droid index that is 28 versions out of 12,871 — few, but
     * among them the highest of `org.fdroid.fdroid`, i.e. exactly the case where taking "the
     * highest versionCode" would be wrong.
     *
     * **It is not the way to install *one* beta.** That is a one-off gesture on a listing's
     * version history, which is not remembered and concerns no other app. This switch changes
     * what the app chooses by itself, for all of them.
     */
    val allowPreviewChannels: Boolean = false,
)
