import com.multistore.buildlogic.addLib
import com.multistore.buildlogic.applyPlugin
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.tasks.testing.Test
import org.gradle.kotlin.dsl.dependencies
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.project
import org.gradle.kotlin.dsl.register

/**
 * A `:store:<name>` module.
 *
 * A store adapter depends only on `:store:api`, `:store:common`, `:core:model`, `:core:common`
 * and `:core:network`. It does not see Room, Compose or `:core:data`. This plugin wires exactly
 * those dependencies and nothing else, so the rule is the default rather than something to
 * remember.
 */
class StoreAdapterConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("multistore.jvm.library")
        pluginManager.apply("multistore.jvm.hilt")
        applyPlugin("kotlin-serialization")

        dependencies {
            add("implementation", project(":store:api"))
            add("implementation", project(":store:common"))
            add("implementation", project(":core:model"))
            add("implementation", project(":core:common"))
            add("implementation", project(":core:network"))
        }

        addLib("implementation", "kotlinx-serialization-json")
        addLib("implementation", "kotlinx-coroutines-core")
        addLib("implementation", "okhttp")
        addLib("implementation", "jsoup")

        addLib("testImplementation", "okhttp-mockwebserver")

        registerCanaryTask()
    }

    /**
     * `canaryTest`: the same parsers, against the **real** sites.
     *
     * Fixtures freeze the markup of the day they were captured, so a green suite says nothing
     * about what the store answers today — which is exactly how a scraping adapter dies
     * unnoticed. The canary is non-blocking and opens an issue when an adapter breaks.
     *
     * These tests are tagged `@Tag("canary")` and live next to the others, but `test`
     * **excludes** them: a unit test that touches the network would make the local build
     * depend on a third-party site being up.
     */
    private fun Project.registerCanaryTask() {
        val sourceSets = extensions.getByType<JavaPluginExtension>().sourceSets
        val testSourceSet = sourceSets.getByName("test")
        tasks.register<Test>("canaryTest") {
            group = "verification"
            description = "Check this adapter's selectors against the real site (needs network)."
            testClassesDirs = testSourceSet.output.classesDirs
            classpath = testSourceSet.runtimeClasspath
            useJUnitPlatform { includeTags(CANARY_TAG) }
            // Without this, two runs close together would reuse the cached result and the
            // canary would report green without having talked to anyone. It is the one place
            // in this project where *not* caching is the right answer.
            outputs.upToDateWhen { false }
        }
    }
}
