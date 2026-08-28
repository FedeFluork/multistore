package com.multistore.core.remoteconfig

import com.google.common.truth.Truth.assertThat
import com.multistore.core.model.StoreId
import java.io.File
import kotlin.time.Instant
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The cached document: when it applies, when it is discarded, and what is left said.
 *
 * The case this class protects more than any other is the **penultimate** one: an accepted document
 * does not become active in the same process. It is a promise that can be broken without noticing —
 * re-reading the file after writing it would be enough — and breaking it would mean an adapter
 * changing selectors halfway through a search.
 */
class RemoteConfigStoreTest {

    @get:Rule val folder = TemporaryFolder()

    private val keys = SigningFixture()
    private val clock = FixedClock()

    private fun store(directory: File = folder.newFolder()): RemoteConfigStore =
        RemoteConfigStore(directory, keys.documents(), clock)

    private fun cached(document: String): File = folder.newFolder().also {
        File(it, RemoteConfigStore.FILE_NAME).writeBytes(keys.envelope(document))
    }

    @Test
    fun `with no cached file the compiled defaults apply`() {
        val store = store()

        assertThat(store.status.value.active).isEqualTo(ActiveConfig.CompiledDefaults)
        assertThat(store.parsersFor(StoreId.UPTODOWN)).isNull()
        assertThat(store.storedAt()).isNull()
    }

    @Test
    fun `a valid cached document becomes the active configuration`() {
        val store = store(
            cached(
                """{"schemaVersion":1,"generatedAt":"2026-08-25T09:00:00Z",
                   "stores":{"uptodown":{"baseUrl":"https://x.test"}}}""",
            ),
        )

        val active = store.status.value.active as ActiveConfig.Applied
        assertThat(active.schemaVersion).isEqualTo(1)
        assertThat(active.generatedAt).isEqualTo(Instant.parse("2026-08-25T09:00:00Z"))
        assertThat(active.stores).containsExactly(StoreId.UPTODOWN)
        assertThat(store.parsersFor(StoreId.UPTODOWN)).isNotNull()
    }

    @Test
    fun `a store this version does not know is listed, not applied`() {
        val store = store(
            cached("""{"schemaVersion":1,"stores":{"aptoide":{"baseUrl":"https://x.test"}}}"""),
        )

        assertThat(store.status.value.unknownStores).containsExactly("aptoide")
        assertThat((store.status.value.active as ActiveConfig.Applied).stores).isEmpty()
    }

    /**
     * The file is written by `accept`, which had already verified it: if it no longer verifies,
     * somebody has been at it. It has to be **deleted**, not merely ignored — otherwise it would sit
     * there looking recent, and the periodic update would skip it forever.
     */
    @Test
    fun `a tampered cache is discarded and deleted`() {
        val directory = folder.newFolder()
        val file = File(directory, RemoteConfigStore.FILE_NAME)
        file.writeBytes(SigningFixture(seed = 9).envelope("""{"schemaVersion":1,"stores":{}}"""))

        val store = store(directory)

        assertThat(store.status.value.active).isEqualTo(ActiveConfig.CompiledDefaults)
        assertThat((store.status.value.lastAttempt as FetchAttempt.Rejected).reason)
            .isEqualTo(ConfigRejection.BAD_SIGNATURE)
        assertThat(file.exists()).isFalse()
    }

    @Test
    fun `a schema newer than we can read is not half-applied`() {
        val store = store(cached("""{"schemaVersion":99,"stores":{"uptodown":{}}}"""))

        assertThat(store.status.value.active).isEqualTo(ActiveConfig.CompiledDefaults)
        assertThat((store.status.value.lastAttempt as FetchAttempt.Rejected).reason)
            .isEqualTo(ConfigRejection.UNSUPPORTED_SCHEMA)
    }

    @Test
    fun `a valid document is accepted and written to cache`() {
        val directory = folder.newFolder()
        val store = store(directory)

        val attempt = store.accept(keys.envelope("""{"schemaVersion":1,"stores":{}}"""))

        assertThat(attempt).isEqualTo(FetchAttempt.Accepted(clock.now(), schemaVersion = 1))
        assertThat(File(directory, RemoteConfigStore.FILE_NAME).exists()).isTrue()
    }

    @Test
    fun `an unsigned document is neither accepted nor written`() {
        val directory = folder.newFolder()
        val store = store(directory)

        val attempt = store.accept("""{"schemaVersion":1,"stores":{}}""".encodeToByteArray())

        assertThat(attempt).isEqualTo(
            FetchAttempt.Rejected(clock.now(), ConfigRejection.MISSING_SIGNATURE),
        )
        assertThat(File(directory, RemoteConfigStore.FILE_NAME).exists()).isFalse()
    }

    @Test
    fun `a future schema is refused instead of ending up in cache`() {
        val directory = folder.newFolder()
        val store = store(directory)

        val attempt = store.accept(keys.envelope("""{"schemaVersion":2,"stores":{}}"""))

        assertThat(attempt).isEqualTo(
            FetchAttempt.Rejected(clock.now(), ConfigRejection.UNSUPPORTED_SCHEMA),
        )
        assertThat(File(directory, RemoteConfigStore.FILE_NAME).exists()).isFalse()
    }

    @Test
    fun `an accepted document does NOT become active in this process`() {
        val directory = folder.newFolder()
        val store = store(directory)

        store.accept(keys.envelope("""{"schemaVersion":1,"stores":{"uptodown":{"baseUrl":"https://x.test"}}}"""))

        assertThat(store.status.value.active).isEqualTo(ActiveConfig.CompiledDefaults)
        assertThat(store.parsersFor(StoreId.UPTODOWN)).isNull()
    }

    @Test
    fun `it does on the next process`() {
        val directory = folder.newFolder()
        store(directory).accept(
            keys.envelope("""{"schemaVersion":1,"stores":{"uptodown":{"baseUrl":"https://x.test"}}}"""),
        )

        val restarted = store(directory)

        assertThat((restarted.status.value.active as ActiveConfig.Applied).stores)
            .containsExactly(StoreId.UPTODOWN)
        assertThat(restarted.parsersFor(StoreId.UPTODOWN)).isNotNull()
    }
}
