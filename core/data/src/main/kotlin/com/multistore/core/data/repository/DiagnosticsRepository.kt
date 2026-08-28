package com.multistore.core.data.repository

import android.content.Context
import android.content.pm.PackageInfo
import android.os.Build
import com.multistore.core.model.OwnPackage
import com.multistore.core.remoteconfig.ActiveConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Clock
import kotlinx.coroutines.flow.first

/**
 * The diagnostic report, in a string the user can save and send to somebody.
 *
 * The promise from the first line: no automatic telemetry to a server, diagnostics **local and
 * exportable by the user**. The first half was true from the start; the second was not —
 * `health_events` filled up and nobody could read it, which makes the sentence true only in the part
 * that is useless.
 *
 * ### What it contains, and why exactly that
 *
 * The questions a report of this kind has to be able to answer are five, and each decides a section:
 * *which version do I have* (a report about an old build wastes everyone's time), *on what device*
 * (the `minSdk` is 26 and the ABIs decide which file can be installed), *which stores are on and how
 * they are doing* (an open breaker explains half the "it finds nothing"), *which configuration is in
 * use* (compiled defaults or a downloaded document), and *what went wrong recently*.
 *
 * ### Why it is text, and in English
 *
 * It is not an exception to the no-hardcoded-strings rule: that one concerns strings **visible in the
 * interface**, and here the interface — the button's label, the description, the outcome message —
 * is translated into all five languages like everything else. The file's content is instead a
 * technical artefact meant to be pasted into a report, and its keys are field names (`versionCode`,
 * `minSdk`, `resolverTier`), not prose. Translating them would make comparing two reports five times
 * harder, and would force the reader to guess which field `Firmatario previsto` was.
 *
 * ### What it does **not** contain
 *
 * No cookies, no tokens, no file paths. The request log — which exists only if the user switched it
 * on — does contain the full addresses, **including the search terms** where a store puts them in
 * the path: that is written in the switch's description and repeated in the export's, because a
 * report gets sent to somebody.
 */
interface DiagnosticsRepository {

    /** The report, ready to be written to a file. */
    suspend fun report(): String
}

@Singleton
internal class DiagnosticsRepositoryImpl @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val health: StoreHealthRepository,
    private val installs: InstallRepository,
    private val config: RemoteConfigRepository,
    private val settings: SettingsRepository,
    private val ownPackage: OwnPackage,
    private val clock: Clock,
) : DiagnosticsRepository {

    override suspend fun report(): String = buildString {
        appendLine("MultiStore diagnostics")
        appendLine("generatedAt: ${clock.now()}")
        appendLine()

        section("app")
        val info = runCatching {
            context.packageManager.getPackageInfo(ownPackage.name, 0)
        }.getOrNull()
        appendLine("packageName: ${ownPackage.name}")
        appendLine("versionName: ${info?.versionName ?: "unknown"}")
        appendLine("versionCode: ${info?.let(::versionCodeOf) ?: "unknown"}")
        appendLine()

        section("device")
        appendLine("manufacturer: ${Build.MANUFACTURER}")
        appendLine("model: ${Build.MODEL}")
        appendLine("android: ${Build.VERSION.RELEASE} (sdk ${Build.VERSION.SDK_INT})")
        // The ABIs decide which variant of an APK can be installed, and they are the first thing to
        // look at when somebody says "installation fails without saying why".
        appendLine("abis: ${Build.SUPPORTED_ABIS.joinToString(", ")}")
        appendLine()

        section("installers")
        val availability = runCatching { installs.installerAvailability() }.getOrNull()
        appendLine("supported: ${availability?.supported?.joinToString(", ").orEmpty()}")
        appendLine("usable: ${availability?.usable?.joinToString(", ").orEmpty()}")
        appendLine()

        section("stores")
        health.observeStores().first().forEach { store ->
            appendLine(
                "${store.storeId.wireName}: enabled=${store.enabled} " +
                    "state=${store.health.state} " +
                    "failures=${store.health.windowFailures}/${store.health.windowCalls} " +
                    "lastSuccess=${store.health.lastSuccessAt ?: "never"}",
            )
        }
        appendLine()

        section("remoteConfig")
        val status = config.status.first()
        appendLine(
            when (val active = status.active) {
                ActiveConfig.CompiledDefaults -> "active: compiled defaults"
                is ActiveConfig.Applied ->
                    "active: downloaded, ${active.stores.size} store(s) overridden"
            },
        )
        appendLine("lastAttempt: ${status.lastAttempt ?: "none"}")
        appendLine("ignoredKeys: ${status.ignoredKeys.joinToString(", ").ifEmpty { "none" }}")
        appendLine("unknownStores: ${status.unknownStores.joinToString(", ").ifEmpty { "none" }}")
        appendLine("rejectedStores: ${status.rejectedStores.joinToString(", ").ifEmpty { "none" }}")
        appendLine()

        section("settings")
        appendLine("search: ${settings.search.first()}")
        appendLine("updates: ${settings.updates.first()}")
        appendLine("security: ${settings.security.first()}")
        appendLine("network: ${settings.network.first()}")
        appendLine("remoteConfig: ${settings.remoteConfig.first()}")
        appendLine("notifications: ${settings.notifications.first()}")
        appendLine("diagnostics: ${settings.diagnostics.first()}")
        appendLine()

        val events = health.recentEvents(EVENT_LIMIT)
        section("events (${events.size}, newest first)")
        if (events.isEmpty()) {
            // An empty log is not an empty report: it is information, and without this line the
            // reader does not know whether the section or the events are missing.
            appendLine("none recorded")
        }
        events.forEach { event ->
            append("${event.at} ${event.storeId.wireName} ${event.kind}")
            event.selector?.let { append(" selector=$it") }
            event.resolverTier?.let { append(" resolverTier=$it") }
            event.durationMillis?.let { append(" ${it}ms") }
            event.detail?.let { append(" $it") }
            appendLine()
        }
    }

    private fun StringBuilder.section(name: String) {
        appendLine("--- $name")
    }

    /**
     * The `versionCode`, with the branch the `minSdk` imposes.
     *
     * `longVersionCode` exists from API 28 and the `minSdk` is 26: it is the same family as
     * `GET_SIGNING_CERTIFICATES`, and on `SelfUpdateRepository` lint caught it before it reached a
     * device. Up to API 27 the `versionCode` **is** 32-bit, so the deprecated field loses nothing.
     */
    @Suppress("DEPRECATION")
    private fun versionCodeOf(info: PackageInfo): Long =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.longVersionCode
        } else {
            info.versionCode.toLong()
        }

    private companion object {
        /**
         * How many events to export.
         *
         * The same limit as the DAO. Further back is unnecessary: diagnostics answers "what went
         * wrong **recently**", and a thousand-line report is a report nobody reads to the end.
         */
        const val EVENT_LIMIT = 200
    }
}
