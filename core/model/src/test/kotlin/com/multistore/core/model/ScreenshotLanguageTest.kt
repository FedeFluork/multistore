package com.multistore.core.model

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Which screenshots a reader is shown, when the source files them by language.
 *
 * ### The measurement that made this necessary
 *
 * `Screenshot` carried no locale while nothing drew it, and the F-Droid adapter discarded the tag
 * on the reasonable grounds that nobody was looking. The first strip on a real listing showed what
 * that costs: **95 images for AntennaPod**, the same handful of screens repeated in every language
 * the index pruning keeps. Eight stores out of nine publish no tag at all, so the rule has to serve
 * both shapes without a branch anybody has to remember.
 *
 * ### Why the ladder is not re-tested here
 *
 * `LocalizedText.resolve` has its own tests, and `forLanguages` deliberately delegates to it rather
 * than repeating the rules. What is tested here is what delegation cannot prove by itself: that the
 * winning tag actually filters, that an untagged source is untouched, and that a tag nobody asked
 * for still yields *something* — an empty strip would be worse than a strip in the wrong language.
 */
@DisplayName("Which screenshots a reader is shown")
class ScreenshotLanguageTest {

    @Test
    fun `the reader's language wins, and the others are not shown`() {
        val shots = listOf(
            shot("en-1", "en-US"),
            shot("de-1", "de"),
            shot("en-2", "en-US"),
            shot("it-1", "it"),
        )

        assertThat(shots.forLanguages(listOf("it")).map { it.url }).containsExactly("it-1")
        assertThat(shots.forLanguages(listOf("de-DE")).map { it.url }).containsExactly("de-1")
        // Through the ladder: `en` has no exact match, `en-US` is the same language with a region.
        assertThat(shots.forLanguages(listOf("en")).map { it.url })
            .containsExactly("en-1", "en-2").inOrder()
    }

    /**
     * A source that publishes no tag keeps every image.
     *
     * This is eight stores out of nine, so it is the ordinary case rather than the fallback:
     * resolving over an empty set of tags would leave those listings with no strip at all, which is
     * the opposite of what the field exists for.
     */
    @Test
    fun `a source that files nothing under a language keeps everything`() {
        val shots = listOf(shot("a"), shot("b"), shot("c"))

        assertThat(shots.forLanguages(listOf("it")).map { it.url })
            .containsExactly("a", "b", "c").inOrder()
    }

    /**
     * A language nobody asked for still yields images.
     *
     * The ladder falls back to English and then to whatever is there, so an app translated only into
     * Japanese shows its Japanese screens rather than none: a picture one cannot read the caption of
     * still says what the app looks like.
     */
    @Test
    fun `a language nobody asked for is better than an empty strip`() {
        val shots = listOf(shot("ja-1", "ja"), shot("ja-2", "ja"))

        assertThat(shots.forLanguages(listOf("it")).map { it.url })
            .containsExactly("ja-1", "ja-2").inOrder()
    }

    /** A source mixing the two — none today, but the model allows it — hides nothing untagged. */
    @Test
    fun `untagged images survive alongside tagged ones`() {
        val shots = listOf(shot("plain"), shot("en-1", "en-US"), shot("de-1", "de"))

        assertThat(shots.forLanguages(listOf("en")).map { it.url })
            .containsExactly("plain", "en-1").inOrder()
    }

    private fun shot(url: String, locale: String? = null) = Screenshot(url = url, locale = locale)
}
