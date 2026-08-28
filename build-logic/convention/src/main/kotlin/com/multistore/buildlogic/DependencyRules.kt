package com.multistore.buildlogic

import javax.inject.Inject
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.artifacts.ProjectDependency
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import org.gradle.kotlin.dsl.register

/**
 * The module dependency rules, verified rather than merely written down.
 *
 * Every module inspects **only itself**, so the check is compatible with the configuration
 * cache and introduces no coupling between projects.
 *
 * Run across the whole repository with: `./gradlew checkDependencyRules`
 */
internal object DependencyRules {

    private const val STORE_API = ":store:api"
    private const val STORE_COMMON = ":store:common"
    private const val CORE_MODEL = ":core:model"
    private const val CORE_COMMON = ":core:common"
    private const val CORE_NETWORK = ":core:network"
    private const val APP = ":app"

    /**
     * The pipeline that produces `index.json`. It does **not** ship in the APK.
     *
     * It is the only module besides `:app` allowed to know the concrete stores, because it
     * calls the real adapters instead of reimplementing their parsers — so the pipeline breaks
     * **together with** the app rather than separately. The exception is written here and not
     * only there: an exception declared in a guardrail is one somebody decided on, one the
     * guardrail cannot see is an oversight.
     */
    private const val TOOLS_INDEX = ":tools:index"

    /** `:store:fdroid` is concrete; `:store:api` and `:store:common` are infrastructure. */
    fun isConcreteStore(path: String): Boolean =
        path.startsWith(":store:") && path != STORE_API && path != STORE_COMMON

    fun violations(path: String, deps: Set<String>, androidPluginApplied: Boolean): List<String> {
        val problems = mutableListOf<String>()

        // R1 — core and feature modules know no concrete store.
        if (path.startsWith(":core:") || path.startsWith(":feature:")) {
            deps.filter(::isConcreteStore).forEach {
                problems += "$path depends on the concrete store $it. " +
                    "Only :app may know the adapters, via Hilt @IntoSet. " +
                    "Core and feature modules see only :store:api."
            }
        }

        // R1-bis — no other module knows a concrete store either.
        //
        // R1 covers `:core:` and `:feature:`. Stating the perimeter in full matters because a
        // module with a new prefix could otherwise wire in an adapter unseen.
        if (path != APP && path != TOOLS_INDEX && !isConcreteStore(path)) {
            deps.filter(::isConcreteStore).forEach {
                problems += "$path depends on the concrete store $it. " +
                    "Only :app (via Hilt @IntoSet) and :tools:index (the pipeline, which does " +
                    "not ship in the APK) may know the adapters."
            }
        }

        // R2 — a feature never depends on another feature.
        if (path.startsWith(":feature:")) {
            deps.filter { it.startsWith(":feature:") }.forEach {
                problems += "$path depends on the feature $it. " +
                    "Features are siblings: what they share belongs in :core:ui or :core:domain."
            }
        }

        // R3 — the exact perimeter of a store adapter.
        if (isConcreteStore(path)) {
            val allowed = setOf(STORE_API, STORE_COMMON, CORE_MODEL, CORE_COMMON, CORE_NETWORK)
            (deps - allowed).forEach {
                problems += "$path depends on $it, outside the permitted perimeter " +
                    "($allowed). An adapter sees neither Room, nor Compose, nor :core:data."
            }
        }

        // R4 — perimeters of the foundational modules, innermost first.
        val foundationLimits = mapOf(
            CORE_MODEL to emptySet(),
            CORE_COMMON to setOf(CORE_MODEL),
            CORE_NETWORK to setOf(CORE_MODEL, CORE_COMMON),
            STORE_API to setOf(CORE_MODEL, CORE_COMMON),
            STORE_COMMON to setOf(STORE_API, CORE_MODEL, CORE_COMMON, CORE_NETWORK),
        )
        foundationLimits[path]?.let { allowed ->
            (deps - allowed).forEach {
                problems += "$path depends on $it, outside the permitted perimeter ($allowed)."
            }
        }

        // R5 — purity: these modules must test on the JVM without Robolectric.
        val mustBePureKotlin = path in setOf(CORE_MODEL, CORE_COMMON, CORE_NETWORK, STORE_API, STORE_COMMON) ||
            isConcreteStore(path)
        if (mustBePureKotlin && androidPluginApplied) {
            problems += "$path has an Android plugin applied but must stay pure Kotlin " +
                "(testable on the JVM without Robolectric)."
        }

        return problems
    }

    /** Configurations that represent real production dependencies. */
    val CHECKED_CONFIGURATIONS = setOf("api", "implementation", "compileOnly", "runtimeOnly")
}

@Suppress("unused")
internal abstract class CheckDependencyRulesTask @Inject constructor() : DefaultTask() {

    @get:Input
    abstract val projectPath: Property<String>

    @get:Input
    abstract val projectDependencies: ListProperty<String>

    @get:Input
    abstract val androidPluginApplied: Property<Boolean>

    @get:OutputFile
    abstract val receipt: org.gradle.api.file.RegularFileProperty

    @TaskAction
    fun check() {
        val path = projectPath.get()
        val deps = projectDependencies.get().toSet()
        val problems = DependencyRules.violations(path, deps, androidPluginApplied.get())
        if (problems.isNotEmpty()) {
            throw GradleException(
                buildString {
                    appendLine("Module dependency rules violated:")
                    problems.forEach { appendLine("  - $it") }
                },
            )
        }
        receipt.get().asFile.writeText("ok $path deps=${deps.sorted()}\n")
    }
}

/**
 * Registers the check on the current module and hooks it to `check`, so `./gradlew build`
 * runs it without anyone having to remember.
 */
internal fun Project.configureDependencyRuleCheck() {
    val deps = provider {
        configurations
            .filter { it.name in DependencyRules.CHECKED_CONFIGURATIONS }
            .flatMap { conf ->
                conf.dependencies.withType(ProjectDependency::class.java).map { it.path }
            }
            .distinct()
            .sorted()
    }
    val androidApplied = provider {
        pluginManager.hasPlugin("com.android.base") ||
            pluginManager.hasPlugin("com.android.library") ||
            pluginManager.hasPlugin("com.android.application")
    }

    val task = tasks.register<CheckDependencyRulesTask>("checkDependencyRules") {
        group = "verification"
        description = "Check the module dependency rules."
        projectPath.set(this@configureDependencyRuleCheck.path)
        projectDependencies.set(deps)
        androidPluginApplied.set(androidApplied)
        receipt.set(layout.buildDirectory.file("reports/dependency-rules/receipt.txt"))
    }
    tasks.named("check") { dependsOn(task) }
}
