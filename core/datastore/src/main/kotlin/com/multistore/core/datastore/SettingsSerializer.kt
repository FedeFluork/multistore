package com.multistore.core.datastore

import androidx.datastore.core.CorruptionException
import androidx.datastore.core.Serializer
import com.google.protobuf.InvalidProtocolBufferException
import com.multistore.core.datastore.proto.Settings
import java.io.InputStream
import java.io.OutputStream
import javax.inject.Inject

/**
 * The [Settings] serializer for Proto DataStore.
 *
 * **Rule for defaults: they must coincide with proto3's zero value.**
 *
 * proto3 has no explicit defaults — every field's zero value *is* its default. Expressing a
 * different default means repeating it by hand everywhere a [Settings] is built, and one of those
 * places eventually falls behind: it already happened in this file, where the I/O-error fallback
 * used `getDefaultInstance()` (with `dynamic_color = false`) while the default declared here was
 * `true`.
 *
 * Hence the choice for `dynamic_color`: it starts **off**, at its zero value. The brand palette
 * becomes the default experience — which is also the only one the golden screenshots can verify,
 * since a wallpaper-derived palette is not reproducible — and dynamic colour stays one tap away
 * in Settings.
 */
class SettingsSerializer @Inject constructor() : Serializer<Settings> {

    // Every field is at its zero value, so this IS `getDefaultInstance()`. The equality is
    // intended: it is what makes it impossible for two places in the code to disagree about what
    // an "not yet chosen" setting is.
    //   theme_mode    = THEME_MODE_SYSTEM (0) -> follow the system
    //   dynamic_color = false                 -> brand palette
    //   language_tag  = ""                    -> follow the system language, fallback "en"
    override val defaultValue: Settings = Settings.getDefaultInstance()

    override suspend fun readFrom(input: InputStream): Settings =
        try {
            Settings.parseFrom(input)
        } catch (exception: InvalidProtocolBufferException) {
            // DataStore reacts to a CorruptionException by rewriting the default; any other
            // exception would crash the app at startup over a broken preferences file.
            throw CorruptionException("Could not read settings.pb", exception)
        }

    override suspend fun writeTo(t: Settings, output: OutputStream) = t.writeTo(output)
}
