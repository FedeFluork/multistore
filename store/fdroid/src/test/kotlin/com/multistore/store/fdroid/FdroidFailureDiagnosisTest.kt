package com.multistore.store.fdroid

import com.google.common.truth.Truth.assertThat
import com.multistore.core.model.BlockKind
import com.multistore.store.api.StoreError
import com.multistore.store.fdroid.index.FdroidIndexClient
import java.io.IOException
import kotlin.time.Duration.Companion.seconds
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * f-droid's failure diagnosis, exercised **offline**.
 *
 * This is the canary of the nine that had no branching at all, so every one of these messages is a
 * line that has never printed. A green night runs none of them, and the most important — the pin —
 * would print for the first time on the worst possible night. That is the definition of a
 * diagnostic nobody has checked.
 *
 * No `@Tag("canary")`: it touches nothing and runs with the offline suite on every build.
 */
@DisplayName("f-droid — reading a failure")
class FdroidFailureDiagnosisTest {

    /**
     * **The pin, and it is the message that matters most.**
     *
     * A wrong signer is `WrongSigner` and reaches us as `ParseFailure(SELECTOR_SIGNER, …)`. Read as
     * "a selector stopped matching" — which is what `canary.yml`'s issue body suggests by default
     * — somebody would go looking for a CSS selector on the one store that has none.
     */
    @Test
    @DisplayName("a wrong signer names the pin, and refuses to widen it")
    fun wrongSignerNamesThePin() {
        val message = fdroidFailureMessage(
            what = "openIndex",
            host = FdroidHost.REPO,
            error = StoreError.ParseFailure(FdroidIndexClient.SELECTOR_SIGNER, "expected=AA found=BB"),
        )

        assertThat(message).contains("not the pinned one")
        assertThat(message).contains("outside this channel")
        assertThat(message).contains("**not** widened")
        // It must say what is already happening, because a stale catalogue is the visible symptom.
        assertThat(message).contains("search, detail, updates and categories")
        // And it must not send the reader to a selector.
        assertThat(message).doesNotContain("recapture the fixture")
    }

    /**
     * The neighbouring selector is a **different** claim, and the names nearly match.
     *
     * `entry.jar/signature` is `Tampered`/`Unsigned`: no certificate was ever compared. Conflating
     * the two would either raise a false key-rotation question or bury a real one.
     */
    @Test
    @DisplayName("an unverifiable signature is not the same message as a wrong signer")
    fun tamperedIsNotThePin() {
        val signer = fdroidFailureMessage(
            "openIndex",
            FdroidHost.REPO,
            StoreError.ParseFailure(FdroidIndexClient.SELECTOR_SIGNER, "expected=AA found=BB"),
        )
        val signature = fdroidFailureMessage(
            "openIndex",
            FdroidHost.REPO,
            StoreError.ParseFailure(FdroidIndexClient.SELECTOR_SIGNATURE, "not signed"),
        )

        assertThat(signature).isNotEqualTo(signer)
        assertThat(signature).contains("not validly signed")
        assertThat(signature).contains("different claim from the pin")
    }

    @Test
    @DisplayName("a hash mismatch is one mirror, and says so")
    fun hashMismatchIsAMirror() {
        val message = fdroidFailureMessage(
            "openIndex",
            FdroidHost.REPO,
            StoreError.ParseFailure(FdroidIndexClient.SELECTOR_INDEX_HASH, "abc"),
        )

        assertThat(message).contains("does not match the bytes")
        assertThat(message).contains("not the pin")
        assertThat(message).contains("one mirror")
    }

    /**
     * An unrecognised selector is the index schema, and the message says the issue body's default
     * advice does not apply here.
     */
    @Test
    @DisplayName("any other selector is the index schema, not a CSS selector")
    fun otherSelectorIsTheSchema() {
        val message = fdroidFailureMessage(
            "openIndex",
            FdroidHost.REPO,
            StoreError.ParseFailure("packages/versions/manifest", "abc"),
        )

        assertThat(message).contains("changed shape")
        assertThat(message).contains("PackageProjection")
        assertThat(message).contains("this store has none")
    }

    /**
     * **The two hosts get two messages**, which is the second half of what was broken here.
     *
     * `search.f-droid.org` is a separate self-hosted machine with no mirror and no fallback, and
     * three of the six checks depend on it alone. One sentence for both meant a search outage and a
     * repository outage read identically.
     */
    @Test
    @DisplayName("the same network error on the two hosts gives two different messages")
    fun theTwoHostsAreDistinguished() {
        val error = StoreError.Network(IOException("timeout"), null)

        val repo = fdroidFailureMessage("openIndex", FdroidHost.REPO, error)
        val search = fdroidFailureMessage("search", FdroidHost.SEARCH, error)

        assertThat(repo).isNotEqualTo(search)
        assertThat(repo).contains(FdroidConfig.HOST)
        assertThat(search).contains("search.f-droid.org")
        assertThat(search).contains("separate self-hosted machine")
        // The reading that stops somebody panicking about the catalogue.
        assertThat(search).contains("the index is untouched")
    }

    @Test
    @DisplayName("a 404 on the search host does not implicate the index")
    fun notFoundOnSearchHost() {
        val search = fdroidFailureMessage("search", FdroidHost.SEARCH, StoreError.NotFound)
        val repo = fdroidFailureMessage("openIndex", FdroidHost.REPO, StoreError.NotFound)

        assertThat(search).contains("DEFAULT_SEARCH_API_URL")
        assertThat(search).contains("says nothing about the index")
        assertThat(repo).isNotEqualTo(search)
    }

    /**
     * The readings stay distinct, which is the assertion that survives any rewording.
     *
     * The defect being guarded against is precisely a **collapse**: one sentence for every failure,
     * which is what stood here for as long as this canary existed. If someone simplifies this back
     * to `"$what: F-Droid answered $error"`, this goes red whatever the words are.
     */
    @Test
    @DisplayName("every reading is a different message")
    fun readingsDoNotCollapse() {
        val cases = listOf(
            FdroidHost.REPO to StoreError.ParseFailure(FdroidIndexClient.SELECTOR_SIGNER, "x"),
            FdroidHost.REPO to StoreError.ParseFailure(FdroidIndexClient.SELECTOR_SIGNATURE, "x"),
            FdroidHost.REPO to StoreError.ParseFailure(FdroidIndexClient.SELECTOR_ENTRY_HASH, "x"),
            FdroidHost.REPO to StoreError.ParseFailure(FdroidIndexClient.SELECTOR_ENTRY_JAR, "x"),
            FdroidHost.REPO to StoreError.ParseFailure("something/else", "x"),
            FdroidHost.REPO to StoreError.Blocked(BlockKind.FORBIDDEN),
            FdroidHost.REPO to StoreError.RateLimited(30.seconds),
            FdroidHost.REPO to StoreError.NotFound,
            FdroidHost.SEARCH to StoreError.NotFound,
            FdroidHost.REPO to StoreError.Network(IOException("x"), null),
            FdroidHost.SEARCH to StoreError.Network(IOException("x"), null),
            FdroidHost.REPO to StoreError.Unsupported("openIndex"),
            FdroidHost.REPO to StoreError.Unexpected(IllegalStateException("x")),
        )

        val messages = cases.map { (host, error) -> fdroidFailureMessage("openIndex", host, error) }

        assertThat(messages.toSet()).hasSize(messages.size)
        // And every one of them names the check, because that is the first thing a reader needs.
        messages.forEach { assertThat(it).startsWith("openIndex: ") }
    }
}
