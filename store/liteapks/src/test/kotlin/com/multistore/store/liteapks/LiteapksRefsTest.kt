package com.multistore.store.liteapks

import com.google.common.truth.Truth.assertThat
import com.multistore.core.model.ContentKind
import com.multistore.core.model.StoreAppRef
import com.multistore.core.model.VersionRef
import java.util.Base64
import kotlin.time.Instant
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * liteapks's refs, tested directly.
 *
 * They do not all come through a search: a ref also comes from Room, months later, without any page
 * having just produced it. The rules validating it therefore have to be proven here and not only
 * through the parser — it is the same reason an1 and pdalife have their own `RefsTest`.
 */
@DisplayName("Refs — liteapks")
class LiteapksRefsTest {

    @Nested
    @DisplayName("a listing's ref")
    inner class AppRefs {

        @Test
        @DisplayName("is derived from a listing URL, and only from that")
        fun refFromUrl() {
            assertThat(LiteapksRefs.refFromUrl("https://liteapks.com/telegram.html"))
                .isEqualTo(StoreAppRef("telegram"))
            assertThat(LiteapksRefs.refFromUrl("https://liteapks.com/plus-messenger-2.html"))
                .isEqualTo(StoreAppRef("plus-messenger-2"))

            // Pages that are **not** listings live deeper or without `.html`, and must be rejected:
            // without that, the "similar" section and the breadcrumbs would produce refs pointing at
            // categories and developers.
            assertThat(LiteapksRefs.refFromUrl("https://liteapks.com/apps")).isNull()
            assertThat(LiteapksRefs.refFromUrl("https://liteapks.com/apps/communication")).isNull()
            assertThat(LiteapksRefs.refFromUrl("https://liteapks.com/developer/mojang")).isNull()
            assertThat(LiteapksRefs.refFromUrl("https://liteapks.com/download/telegram-810")).isNull()
            assertThat(LiteapksRefs.refFromUrl("https://liteapks.com/")).isNull()
        }

        /**
         * A malformed slug does not become part of a URL.
         *
         * The contract test hands every adapter `../../etc/passwd?<script>&%00`. Without this
         * validation it would end up concatenated into `"$root/$slug.html"`.
         */
        @Test
        @DisplayName("a malformed slug produces no URL")
        fun malformedSlugIsRejected() {
            listOf(
                "../../etc/passwd?<script>&%00",
                "../telegram",
                "telegram/../../root",
                "Telegram",
                "telegram.html",
                "telegram?s=x",
            ).forEach { assertThat(LiteapksRefs.slug(StoreAppRef(it))).isNull() }

            assertThat(LiteapksRefs.slug(StoreAppRef("telegram"))).isEqualTo("telegram")
            // Surrounding slashes are tolerated: a ref saved in Room months ago may have them.
            assertThat(LiteapksRefs.slug(StoreAppRef("/telegram/"))).isEqualTo("telegram")
        }
    }

    @Nested
    @DisplayName("a file's ref")
    inner class VersionRefs {

        /**
         * The two kinds are told apart without prefixes: one starts with `https://`, the other does
         * not.
         *
         * A slot is `minecraft-11909/2`; an original is the URL itself, because it has no
         * intermediate page to derive it from.
         */
        @Test
        @DisplayName("a slot and a direct URL are not confused")
        fun slotsAndDirectUrlsAreDistinct() {
            val slot = LiteapksRefs.slotRef("minecraft-11909", 2)
            assertThat(LiteapksRefs.slotOf(slot))
                .isEqualTo(LiteapksRefs.Slot("minecraft-11909", 2))
            assertThat(LiteapksRefs.directUrlOf(slot)).isNull()

            val direct = LiteapksRefs.directRef("https://gp4.liteapks.com/x/y.xapk")
            assertThat(LiteapksRefs.slotOf(direct)).isNull()
            assertThat(LiteapksRefs.directUrlOf(direct)).isEqualTo("https://gp4.liteapks.com/x/y.xapk")
        }

        @Test
        @DisplayName("a malformed slot produces no URL")
        fun malformedSlotIsRejected() {
            listOf(
                "minecraft-11909",
                "minecraft/2",
                "../../etc/passwd/1",
                "minecraft-11909/0",
                "minecraft-11909/999",
                "MINECRAFT-11909/1",
            ).forEach { assertThat(LiteapksRefs.slotOf(VersionRef(it))).isNull() }
        }

        /** A ref that is neither a slot nor an https URL produces nothing. */
        @Test
        @DisplayName("a plain URL is not a file ref")
        fun plainHttpIsNotAFileRef() {
            assertThat(LiteapksRefs.directUrlOf(VersionRef("http://gp4.liteapks.com/x.apk"))).isNull()
            assertThat(LiteapksRefs.directUrlOf(VersionRef("ftp://x/y.apk"))).isNull()
        }
    }

    @Nested
    @DisplayName("the transit permit")
    inner class Token {

        /**
         * It is `btoa(btoa(seconds))`, i.e. exactly what their `site.js` computes.
         *
         * The test decodes rather than comparing against an expected string: that way it says
         * **what** the token is — a timestamp, twice in base64 — instead of freezing a value nobody
         * would be able to read back.
         */
        @Test
        @DisplayName("is the expiry timestamp in base64, twice")
        fun tokenIsDoubleBase64OfTheExpiry() {
            val expiry = Instant.parse("2026-08-25T15:00:00Z")

            val token = LiteapksRefs.downloadToken(expiry)

            val once = String(Base64.getDecoder().decode(token))
            val twice = String(Base64.getDecoder().decode(once))
            assertThat(twice.toLong()).isEqualTo(expiry.epochSeconds)
            // A single round of base64 the worker rejects: it is the case that separates "I
            // understood the rule" from "I copied a string".
            assertThat(token).isNotEqualTo(once)
        }
    }

    @Nested
    @DisplayName("app or game")
    inner class Kind {

        /**
         * Read from the breadcrumb's `href`, not from the label.
         *
         * The label is theme text and could be translated; `/apps` and `/games` are the
         * catalogue's division, and are the only two observed across thirty-one listings.
         */
        @Test
        @DisplayName("comes from the catalogue division, not from the label")
        fun kindComesFromTheBreadcrumbHref() {
            assertThat(LiteapksRefs.contentKindOf("https://liteapks.com/games"))
                .isEqualTo(ContentKind.GAME)
            assertThat(LiteapksRefs.contentKindOf("https://liteapks.com/apps/communication"))
                .isEqualTo(ContentKind.APP)
            assertThat(LiteapksRefs.contentKindOf("https://liteapks.com/news"))
                .isEqualTo(ContentKind.UNKNOWN)
            assertThat(LiteapksRefs.contentKindOf(null)).isEqualTo(ContentKind.UNKNOWN)
        }
    }
}
