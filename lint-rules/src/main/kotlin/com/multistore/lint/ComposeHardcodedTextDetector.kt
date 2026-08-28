package com.multistore.lint

import com.android.tools.lint.client.api.UElementHandler
import com.android.tools.lint.detector.api.Category
import com.android.tools.lint.detector.api.ConstantEvaluator
import com.android.tools.lint.detector.api.Detector
import com.android.tools.lint.detector.api.Implementation
import com.android.tools.lint.detector.api.Issue
import com.android.tools.lint.detector.api.JavaContext
import com.android.tools.lint.detector.api.Scope
import com.android.tools.lint.detector.api.Severity
import com.android.tools.lint.detector.api.SourceCodeScanner
import com.intellij.psi.PsiMethod
import org.jetbrains.uast.UAnnotated
import org.jetbrains.uast.UCallExpression
import org.jetbrains.uast.UElement
import org.jetbrains.uast.UExpression
import org.jetbrains.uast.ULiteralExpression
import org.jetbrains.uast.UParenthesizedExpression
import org.jetbrains.uast.UPolyadicExpression

/**
 * Rule 1, applied to Compose code.
 *
 * Reports a string literal passed to a *user-visible* parameter of a `@Composable` function —
 * `Text("Search")`, `contentDescription = "icon"`, `label = "Name"` — that should instead be
 * `stringResource(R.string.…)` and live in all 5 languages.
 *
 * Why a custom check is needed: AGP's `HardcodedText` inspects `android:text` in layout XML.
 * MultiStore has no XML layouts. Without this detector rule 1 would not be automatically verifiable,
 * and the exit criterion ("add a hardcoded string to a composable -> the build must fail") would be
 * unreachable.
 *
 * What it deliberately does NOT report:
 * - empty strings or ones made only of punctuation/whitespace (`""`, `" "`, `"•"`, `"—"`): they are
 *   typographic separators, not text to translate;
 * - parameters that are not user-visible (`testTag`, `key`, `route`, `tag`);
 * - files under a test source set and composables annotated `@Preview`, where the placeholder text is
 *   the point.
 */
@Suppress("UnstableApiUsage")
class ComposeHardcodedTextDetector : Detector(), SourceCodeScanner {

    override fun getApplicableUastTypes(): List<Class<out UElement>> =
        listOf(UCallExpression::class.java)

    override fun createUastHandler(context: JavaContext) = Handler(context)

    class Handler(private val context: JavaContext) : UElementHandler() {

        override fun visitCallExpression(node: UCallExpression) {
            val method: PsiMethod = node.resolve() ?: return
            val watchedParameters = watchedParametersFor(method) ?: return
            if (node.isInsidePreview()) return

            val mapping = context.evaluator.computeArgumentMapping(node, method)
            for ((argument, parameter) in mapping) {
                val parameterName = parameter.name
                if (parameterName !in watchedParameters) continue
                val literal = argument.translatableTextOrNull(context) ?: continue
                context.report(
                    issue = ISSUE,
                    scope = argument,
                    location = context.getLocation(argument),
                    message = "Hardcoded text `\"$literal\"` passed to `$parameterName` of " +
                        "`${method.name}`. Use `stringResource(R.string.…)` and add the key to " +
                        "all 5 languages (values, values-it, values-fr, values-es, values-de).",
                )
            }
        }

        /**
         * The parameters to watch for this call, or `null` if the call is not a place where
         * user-visible text can end up.
         */
        private fun watchedParametersFor(method: PsiMethod): Set<String>? = when {
            method.isComposable() -> USER_VISIBLE_PARAMETERS
            // Some sinks are not composable but show text all the same, and they are exactly the places
            // where an error message ends up written by hand.
            else -> NON_COMPOSABLE_SINKS[method.name]
        }

        private fun PsiMethod.isComposable(): Boolean =
            annotations.any { it.qualifiedName == COMPOSABLE_ANNOTATION }

        private fun UCallExpression.isInsidePreview(): Boolean {
            var current: UElement? = this
            while (current != null) {
                val annotations = (current as? UAnnotated)?.uAnnotations
                if (annotations?.any { it.qualifiedName in PREVIEW_ANNOTATIONS } == true) return true
                current = current.uastParent
            }
            return false
        }
    }

    companion object {
        private const val COMPOSABLE_ANNOTATION = "androidx.compose.runtime.Composable"

        private val PREVIEW_ANNOTATIONS = setOf(
            "androidx.compose.ui.tooling.preview.Preview",
            "androidx.compose.desktop.ui.tooling.preview.Preview",
        )

        /**
         * Parameters whose value ends up in front of the user's eyes — directly or via TalkBack. The
         * list is an allowlist and not a heuristic: a false positive on a technical parameter would
         * block the build for nothing.
         */
        internal val USER_VISIBLE_PARAMETERS: Set<String> = setOf(
            "text",
            "title",
            "subtitle",
            "label",
            "placeholder",
            "contentDescription",
            "supportingText",
            "headlineText",
            "supportingTextValue",
            "errorMessage",
            "hint",
            "description",
            "actionLabel",
            "message",
            "confirmButtonText",
            "dismissButtonText",
        )

        /** Non-`@Composable` methods that display text: name -> watched parameters. */
        internal val NON_COMPOSABLE_SINKS: Map<String, Set<String>> = mapOf(
            // SnackbarHostState.showSnackbar(message, actionLabel, ...)
            "showSnackbar" to setOf("message", "actionLabel"),
            // Toast.makeText(context, text, duration)
            "makeText" to setOf("text"),
            // AnnotatedString.Builder.append(text)
            "append" to setOf("text"),
        )

        val ISSUE: Issue = Issue.create(
            id = "MultiStoreComposeHardcodedText",
            briefDescription = "Stringa hardcoded in un composable",
            explanation = """
                MultiStore e' localizzato in 5 lingue e la parita' fra i `strings.xml` e'
                verificata da `TranslationParityTest`. Una stringa scritta direttamente nel
                codice Kotlin sfugge a entrambi i meccanismi: non e' traducibile, e non
                risulta mancante da nessuna parte.

                Sposta il testo in `res/values/strings.xml` e aggiungilo *contemporaneamente*
                alle 5 lingue supportate, poi usa `stringResource(R.string.chiave)`.

                Il nome della chiave segue `<feature>_<contesto>_<significato>`, per example
                `search_filters_title`.
                """,
            category = Category.I18N,
            priority = 9,
            severity = Severity.ERROR,
            implementation = Implementation(
                ComposeHardcodedTextDetector::class.java,
                Scope.JAVA_FILE_SCOPE,
            ),
        )

        /**
         * The translatable text contained in the expression, or `null`.
         *
         * Two strategies in cascade, because they cover different cases:
         *  - the **literal parts**, which work even when the expression is not constant (a template with
         *    a variable inside);
         *  - lint's **constant evaluation**, which resolves references to `const val` and entirely
         *    constant concatenations.
         */
        internal fun UExpression.translatableTextOrNull(context: JavaContext): String? {
            literalParts()?.takeIf { it.isTranslatable() }?.let { return it }
            val constant = ConstantEvaluator.evaluate(context, this) as? String
            return constant?.takeIf { it.isTranslatable() }
        }

        /**
         * Concatenates only the literal portions of the expression.
         *
         * The delicate point is [UPolyadicExpression], which in UAST represents both `+` concatenation
         * and Kotlin templates. The first version of this detector gave up (`return null`) as soon as an
         * operand was not a literal — that is, always, for any template — and that was the guardrail's
         * biggest hole: whoever already uses stringResource does not write `Text("Hello")`, they write
         * the string with a number inside. Here the non-literal parts are skipped and what remains is
         * evaluated: `"Updates: $count"` keeps "Updates: ", which is text to translate.
         */
        private fun UExpression.literalParts(): String? = when (this) {
            is UParenthesizedExpression -> expression.literalParts()
            is ULiteralExpression -> value as? String
            is UPolyadicExpression ->
                operands.mapNotNull { it.literalParts() }
                    .joinToString(separator = " ")
                    .takeIf { it.isNotEmpty() }
            else -> null
        }

        /** At least one letter is required: `"•"`, `" "`, `"—"`, `"/"` are not text to translate. */
        private fun String.isTranslatable(): Boolean = any(Char::isLetter)
    }
}
