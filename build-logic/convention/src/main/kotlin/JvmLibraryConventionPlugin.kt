import com.multistore.buildlogic.COMMON_COMPILER_ARGS
import com.multistore.buildlogic.addLib
import com.multistore.buildlogic.addPlatform
import com.multistore.buildlogic.applyPlugin
import com.multistore.buildlogic.configureDependencyRuleCheck
import com.multistore.buildlogic.jvmTargetVersion
import org.gradle.api.JavaVersion
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.api.tasks.testing.Test
import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.named
import org.gradle.kotlin.dsl.withType
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension

/** The JUnit tag for tests that talk to the real sites. See the `canaryTest` task. */
const val CANARY_TAG: String = "canary"

/**
 * A pure-Kotlin module, with no Android dependency at all.
 *
 * `:core:model`, `:core:common` and the `:store:*` modules compile and test on the JVM without
 * Robolectric. Using this convention plugin *is* how that constraint is enforced: from here an
 * Android API is simply not reachable, so it is a fact of the classpath rather than a
 * convention to remember.
 */
class JvmLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        applyPlugin("kotlin-jvm")

        // No JVM toolchain: we compile with whatever JDK runs Gradle and emit bytecode 17,
        // the same target as the Android modules. These jars end up in the APK, so they have
        // to speak the same language as D8.
        val jvmTargetVersion = jvmTargetVersion()

        extensions.configure<JavaPluginExtension> {
            sourceCompatibility = JavaVersion.toVersion(jvmTargetVersion)
            targetCompatibility = JavaVersion.toVersion(jvmTargetVersion)
        }

        tasks.withType<JavaCompile>().configureEach {
            options.release.set(jvmTargetVersion.toInt())
        }

        extensions.configure<KotlinJvmProjectExtension> {
            compilerOptions {
                jvmTarget.set(JvmTarget.fromTarget(jvmTargetVersion))
                freeCompilerArgs.addAll(COMMON_COMPILER_ARGS)
            }
        }

        addPlatform("testImplementation", "junit-bom")
        addLib("testImplementation", "junit-jupiter")
        addLib("testImplementation", "kotlin-test")
        addLib("testImplementation", "truth")
        addLib("testImplementation", "turbine")
        addLib("testImplementation", "kotlinx-coroutines-test")
        addLib("testRuntimeOnly", "junit-platform-launcher")

        configureDependencyRuleCheck()

        tasks.withType<Test>().configureEach {
            useJUnitPlatform()
            testLogging {
                exceptionFormat = TestExceptionFormat.FULL
                showStackTraces = true
            }
        }

        // The `canary` exclusion sits **only** on `test`, not on every Test task, and the
        // difference is not cosmetic: `useJUnitPlatform { … }` accumulates filters on the same
        // options object, so an `excludeTags` applied with `configureEach` would survive into
        // `canaryTest` — which asks for those very tags — and the intersection of the two
        // filters is empty. The result would be a canary that finishes in a second having run
        // nothing, and reports green.
        tasks.named<Test>("test").configure {
            // Those tests **touch the network**, which a unit test in this project must
            // never do. They live in the same source set because they exercise the same
            // parsers on the same classes — only the responder differs — but `canaryTest`
            // runs them, in a nightly non-blocking pipeline.
            useJUnitPlatform { excludeTags(CANARY_TAG) }
        }
    }
}
