package com.multistore.core.remoteconfig

import com.google.common.truth.Truth.assertThat
import com.multistore.core.model.StoreId
import java.io.File
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlinx.serialization.Serializable
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The override on top of the compiled defaults, tested against a configuration shaped like the real
 * ones: fields with defaults, a nested selectors block, a `Duration`, a list.
 *
 * It deliberately does not use a real store's configuration. `:core:remoteconfig` does not see
 * `:store:*` — it could not, the dependency rule forbids it — and a stand-in faithful in shape proves
 * the same thing without making this module depend on an adapter that changes tomorrow.
 */
class RemoteParsersTest {

    @get:Rule val folder = TemporaryFolder()

    @Serializable
    data class Selectors(
        val searchItem: String = "#content-list .item",
        val searchTitle: String = ".name a",
    )

    @Serializable
    data class TestConfig(
        val baseUrl: String = "https://en.uptodown.com",
        val permitsPerSecond: Double = 1.0,
        val listingTtl: Duration = 6.hours,
        val mirrors: List<String> = listOf("a", "b"),
        val selectors: Selectors = Selectors(),
    )

    private val keys = SigningFixture()
    private val clock = FixedClock()

    private fun parsersWith(document: String): Pair<RemoteParsers, RemoteConfigStore> {
        val directory = folder.newFolder("config")
        File(directory, RemoteConfigStore.FILE_NAME).writeBytes(keys.envelope(document))
        val store = RemoteConfigStore(directory, keys.documents(), clock)
        return RemoteParsers(store) to store
    }

    @Test
    fun `with no document the compiled configuration comes back identical`() {
        val store = RemoteConfigStore(folder.newFolder("empty"), keys.documents(), clock)

        val config = RemoteParsers(store).override(StoreId.UPTODOWN, TestConfig(), TestConfig.serializer())

        assertThat(config).isEqualTo(TestConfig())
    }

    /**
     * The linchpin of the whole mechanism: **a field left at its default is still overridable**.
     *
     * It looks obvious and is not. The compiled configurations are made *only* of defaults, and
     * kotlinx by default omits from serialisation every field equal to its default: without
     * `encodeDefaults = true` the starting object would be `{}`, every override key would come out
     * unknown, and the remote config would accept the document without applying anything. Remove
     * that flag and this test turns red.
     */
    @Test
    fun `a partial override changes only what it names`() {
        val (parsers, _) = parsersWith(
            """{"schemaVersion":1,"stores":{"uptodown":{"selectors":{"searchItem":".card"}}}}""",
        )

        val config = parsers.override(StoreId.UPTODOWN, TestConfig(), TestConfig.serializer())

        assertThat(config.selectors.searchItem).isEqualTo(".card")
        assertThat(config.selectors.searchTitle).isEqualTo(".name a")
        assertThat(config.baseUrl).isEqualTo("https://en.uptodown.com")
        assertThat(config.listingTtl).isEqualTo(6.hours)
    }

    @Test
    fun `a domain change is repaired without a release`() {
        val (parsers, _) = parsersWith(
            """{"schemaVersion":1,"stores":{"uptodown":{"baseUrl":"https://it.uptodown.com"}}}""",
        )

        assertThat(parsers.override(StoreId.UPTODOWN, TestConfig(), TestConfig.serializer()).baseUrl)
            .isEqualTo("https://it.uptodown.com")
    }

    @Test
    fun `the document does not touch the stores it does not name`() {
        val (parsers, _) = parsersWith(
            """{"schemaVersion":1,"stores":{"uptodown":{"baseUrl":"https://x.test"}}}""",
        )

        assertThat(parsers.override(StoreId.APKMIRROR, TestConfig(), TestConfig.serializer())).isEqualTo(TestConfig())
    }

    /** A `Duration` travels as ISO-8601: it is the form kotlinx gives it. */
    @Test
    fun `a listing's TTL can be changed`() {
        val (parsers, _) = parsersWith(
            """{"schemaVersion":1,"stores":{"uptodown":{"listingTtl":"PT2H"}}}""",
        )

        assertThat(parsers.override(StoreId.UPTODOWN, TestConfig(), TestConfig.serializer()).listingTtl).isEqualTo(2.hours)
    }

    /**
     * A list is replaced entirely.
     *
     * Merging element by element would look finer and would make it **impossible to remove** an
     * entry: a list could only grow, and a dead mirror would sit there forever.
     */
    @Test
    fun `a list is replaced, not merged`() {
        val (parsers, _) = parsersWith(
            """{"schemaVersion":1,"stores":{"uptodown":{"mirrors":["c"]}}}""",
        )

        assertThat(parsers.override(StoreId.UPTODOWN, TestConfig(), TestConfig.serializer()).mirrors).containsExactly("c")
    }

    @Test
    fun `a wrongly typed value costs that store and not the others`() {
        val (parsers, store) = parsersWith(
            """{"schemaVersion":1,"stores":{
                "uptodown":{"permitsPerSecond":"veloce"},
                "apkmirror":{"baseUrl":"https://m.apkmirror.com"}
            }}""",
        )

        assertThat(parsers.override(StoreId.UPTODOWN, TestConfig(), TestConfig.serializer())).isEqualTo(TestConfig())
        assertThat(parsers.override(StoreId.APKMIRROR, TestConfig(), TestConfig.serializer()).baseUrl)
            .isEqualTo("https://m.apkmirror.com")
        assertThat(store.status.value.rejectedStores).containsExactly(StoreId.UPTODOWN)
    }

    /**
     * A typo in a key is the most likely defect of a hand-written document, and it is also the most
     * silent: valid signature, document accepted, no effect. Counting it is what lets the Settings
     * screen say so.
     */
    @Test
    fun `an unknown key applies nothing and is reported with its path`() {
        val (parsers, store) = parsersWith(
            """{"schemaVersion":1,"stores":{"uptodown":{"selectors":{"searchItm":".card"}}}}""",
        )

        val config = parsers.override(StoreId.UPTODOWN, TestConfig(), TestConfig.serializer())

        assertThat(config).isEqualTo(TestConfig())
        assertThat(store.status.value.ignoredKeys).containsExactly("uptodown.selectors.searchItm")
    }
}
