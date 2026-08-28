package com.multistore.core.remoteconfig

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The cached index, and **the asymmetry this class exists to fix in place**.
 *
 * `RemoteConfigStore` promises the opposite: an accepted document does not become active in the same
 * process, because the adapters receive the configuration from their constructor. Here the promise
 * is reversed — an accepted index **is seen immediately** — and the two promises are equally easy to
 * break without noticing. The first test holds both still.
 */
class RemoteIndexStoreTest {

    @get:Rule val folder = TemporaryFolder()

    private val keys = SigningFixture()
    private val clock = FixedClock()

    private fun store(directory: File = folder.newFolder()): RemoteIndexStore =
        RemoteIndexStore(directory, keys.documents(), clock)

    @Test
    fun `an accepted index is seen immediately, without restarting`() {
        val store = store()

        val attempt = store.accept(keys.envelope(DOCUMENT))

        assertThat(attempt).isInstanceOf(FetchAttempt.Accepted::class.java)
        // The difference from `parsers.json`, in one line: there this assertion would be `isNull`.
        assertThat(store.document.value?.popular?.map { it.title }).containsExactly("CapCut")
        assertThat(store.status.value.entryCount).isEqualTo(2)
    }

    @Test
    fun `a cached document is read at startup`() {
        val directory = folder.newFolder()
        File(directory, IndexDocument.FILE_NAME).writeBytes(keys.envelope(DOCUMENT))

        val store = store(directory)

        assertThat(store.document.value?.recent?.single()?.title).isEqualTo("Winter Burrow")
    }

    @Test
    fun `signed with another key, it is discarded and the file deleted`() {
        val directory = folder.newFolder()
        val file = File(directory, IndexDocument.FILE_NAME)
        file.writeBytes(SigningFixture(seed = 2).envelope(DOCUMENT))

        val store = store(directory)

        assertThat(store.document.value).isNull()
        // It is deleted, and that is not tidying: a cache that does not verify would never be
        // replaced by itself, because the update would skip it, finding it recent.
        assertThat(file.exists()).isFalse()
        assertThat((store.status.value.lastAttempt as FetchAttempt.Rejected).reason)
            .isEqualTo(ConfigRejection.BAD_SIGNATURE)
    }

    @Test
    fun `a later schema is discarded instead of half-read`() {
        val store = store()

        val attempt = store.accept(keys.envelope("""{"schemaVersion":99,"popular":[]}"""))

        assertThat((attempt as FetchAttempt.Rejected).reason).isEqualTo(ConfigRejection.UNSUPPORTED_SCHEMA)
        assertThat(store.document.value).isNull()
    }

    @Test
    fun `an unknown key inside an entry does not bring the index down`() {
        // The deliberate difference from `parsers.json`, where `Json` is strict: there an unknown key
        // means a different schema; here it is a field a newer pipeline has added, and discarding
        // everything would mean an empty Home for whoever has not updated the app yet.
        val store = store()

        val attempt = store.accept(
            keys.envelope(
                """{"schemaVersion":1,"popular":[{"store":"uptodown","ref":"a","title":"A","futuro":7}]}""",
            ),
        )

        assertThat(attempt).isInstanceOf(FetchAttempt.Accepted::class.java)
        assertThat(store.document.value?.popular?.single()?.title).isEqualTo("A")
    }

    @Test
    fun `the self-update travels in the same document`() {
        val store = store()

        store.accept(keys.envelope(DOCUMENT))

        val release = store.document.value?.selfUpdate
        assertThat(release?.versionCode).isEqualTo(2)
        assertThat(release?.sha256).isEqualTo("ab".repeat(32))
    }

    private companion object {
        val DOCUMENT = """
            {
              "schemaVersion": 1,
              "generatedAt": "2026-08-25T21:00:00Z",
              "popular": [ { "store": "uptodown", "ref": "capcut", "title": "CapCut", "sources": 2 } ],
              "recent":  [ { "store": "pdalife", "ref": "winter-burrow-android-a51917", "title": "Winter Burrow" } ],
              "stores":  [ { "store": "an1", "reachable": false, "detail": "403" } ],
              "selfUpdate": {
                "versionCode": 2,
                "versionName": "0.5.0",
                "minSdk": 26,
                "url": "https://example.invalid/multistore.apk",
                "sha256": "${"ab".repeat(32)}"
              }
            }
        """.trimIndent()
    }
}
