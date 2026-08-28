package com.multistore.lint

import com.android.tools.lint.checks.infrastructure.TestFile
import com.android.tools.lint.checks.infrastructure.TestLintTask.lint
import org.junit.Test

/**
 * Tests for [ComposeHardcodedTextDetector].
 *
 * A guardrail with no tests is a promise, not a guarantee: if somebody refactors the detector tomorrow
 * and makes it blind, the build stays green and nobody notices — indeed everything will *look* better,
 * because the errors disappear.
 *
 * The negative cases (the ones that must NOT be reported) count as much as the positive ones: a
 * detector that flagged `stringResource` or typographic separators would be switched off within a
 * week, and at that point rule 1 would no longer exist.
 */
class ComposeHardcodedTextDetectorTest {

    private fun lintTask(vararg files: TestFile) = lint()
        .files(COMPOSE_RUNTIME_STUB, COMPOSE_PREVIEW_STUB, COMPOSE_UI_STUB, *files)
        .issues(ComposeHardcodedTextDetector.ISSUE)
        .allowMissingSdk()

    // --------------------------------------------------------------------- cases to report

    @Test
    fun `it reports a bare literal`() {
        lintTask(
            kotlinFile(
                """
                package test

                import androidx.compose.runtime.Composable
                import androidx.compose.ui.Text

                @Composable
                fun Screen() {
                    Text(text = "No results")
                }
                """,
            ),
        )
            .run()
            .expectErrorCount(1)
            .expectContains("No results")
    }

    @Test
    fun `it reports a string template, which was the biggest hole`() {
        lintTask(
            kotlinFile(
                """
                package test

                import androidx.compose.runtime.Composable
                import androidx.compose.ui.Text

                @Composable
                fun Screen(count: Int) {
                    Text(text = "Updates available: ${'$'}count")
                }
                """,
            ),
        )
            .run()
            .expectErrorCount(1)
            .expectContains("Updates available")
    }

    @Test
    fun `it reports a string extracted into a constant`() {
        lintTask(
            kotlinFile(
                """
                package test

                import androidx.compose.runtime.Composable
                import androidx.compose.ui.Text

                private const val ETICHETTA = "Text extracted into a constant"

                @Composable
                fun Screen() {
                    Text(text = ETICHETTA)
                }
                """,
            ),
        )
            .run()
            .expectErrorCount(1)
            .expectContains("Text extracted into a constant")
    }

    @Test
    fun `it reports a hardcoded contentDescription`() {
        lintTask(
            kotlinFile(
                """
                package test

                import androidx.compose.runtime.Composable
                import androidx.compose.ui.Icon

                @Composable
                fun Screen() {
                    Icon(contentDescription = "store icon")
                }
                """,
            ),
        )
            .run()
            .expectErrorCount(1)
            .expectContains("contentDescription")
    }

    // ----------------------------------------------------------------- cases NOT to report

    @Test
    fun `it does not report stringResource`() {
        lintTask(
            kotlinFile(
                """
                package test

                import androidx.compose.runtime.Composable
                import androidx.compose.ui.Text
                import androidx.compose.ui.stringResource

                @Composable
                fun Screen() {
                    Text(text = stringResource(42))
                }
                """,
            ),
        )
            .run()
            .expectClean()
    }

    @Test
    fun `it does not report typographic separators with no letters`() {
        lintTask(
            kotlinFile(
                """
                package test

                import androidx.compose.runtime.Composable
                import androidx.compose.ui.Text

                @Composable
                fun Screen() {
                    Text(text = " • ")
                    Text(text = "")
                    Text(text = "—")
                }
                """,
            ),
        )
            .run()
            .expectClean()
    }

    @Test
    fun `it does not report technical parameters, not user-visible`() {
        lintTask(
            kotlinFile(
                """
                package test

                import androidx.compose.runtime.Composable
                import androidx.compose.ui.Tagged

                @Composable
                fun Screen() {
                    Tagged(testTag = "home_screen", route = "home")
                }
                """,
            ),
        )
            .run()
            .expectClean()
    }

    @Test
    fun `it does not report inside a Preview, where the placeholder text is the point`() {
        lintTask(
            kotlinFile(
                """
                package test

                import androidx.compose.runtime.Composable
                import androidx.compose.ui.Text
                import androidx.compose.ui.tooling.preview.Preview

                @Preview
                @Composable
                fun ScreenPreview() {
                    Text(text = "Testo di example per l'anteprima")
                }
                """,
            ),
        )
            .run()
            .expectClean()
    }

    @Test
    fun `it does not report a function that is neither composable nor a known sink`() {
        lintTask(
            kotlinFile(
                """
                package test

                fun log(text: String) = Unit

                fun caller() {
                    log(text = "this is not user-facing text")
                }
                """,
            ),
        )
            .run()
            .expectClean()
    }

    private companion object {

        fun kotlinFile(@Language("kotlin") source: String): TestFile =
            com.android.tools.lint.checks.infrastructure.TestFiles.kotlin(source.trimIndent())

        val COMPOSE_RUNTIME_STUB: TestFile = com.android.tools.lint.checks.infrastructure.TestFiles.kotlin(
            """
            package androidx.compose.runtime

            @Retention(AnnotationRetention.BINARY)
            @Target(
                AnnotationTarget.FUNCTION,
                AnnotationTarget.TYPE,
                AnnotationTarget.TYPE_PARAMETER,
                AnnotationTarget.PROPERTY_GETTER,
            )
            annotation class Composable
            """.trimIndent(),
        )

        val COMPOSE_PREVIEW_STUB: TestFile = com.android.tools.lint.checks.infrastructure.TestFiles.kotlin(
            """
            package androidx.compose.ui.tooling.preview

            @Retention(AnnotationRetention.BINARY)
            @Target(AnnotationTarget.FUNCTION, AnnotationTarget.ANNOTATION_CLASS)
            annotation class Preview
            """.trimIndent(),
        )

        /** A minimal stub of the composables used in the tests: only the signatures that matter. */
        val COMPOSE_UI_STUB: TestFile = com.android.tools.lint.checks.infrastructure.TestFiles.kotlin(
            """
            package androidx.compose.ui

            import androidx.compose.runtime.Composable

            @Composable
            fun Text(text: String) = Unit

            @Composable
            fun Icon(contentDescription: String?) = Unit

            @Composable
            fun Tagged(testTag: String, route: String) = Unit

            @Composable
            fun stringResource(id: Int): String = ""
            """.trimIndent(),
        )
    }
}

/** Placeholder: `org.intellij.lang.annotations.Language` is not on the lint tests' classpath. */
@Retention(AnnotationRetention.SOURCE)
private annotation class Language(val value: String)
