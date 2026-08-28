import com.android.build.api.dsl.LibraryExtension
import com.multistore.buildlogic.addLib
import com.multistore.buildlogic.applyPlugin
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.project

/**
 * A `:feature:*` module.
 *
 * Dependency rule: **a feature never depends on another feature**, and never on a concrete
 * `:store:<name>`. This plugin wires only the permitted dependencies; the rule itself is
 * enforced by the `checkDependencyRules` task.
 */
class AndroidFeatureConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("multistore.android.library.compose")
        pluginManager.apply("multistore.android.hilt")
        pluginManager.apply("multistore.android.screenshot")
        // The **compiler plugin**, not just the library: type-safe navigation routes are
        // `@Serializable`, and without the plugin the annotation compiles without a warning
        // and no serializer is generated. The failure only surfaces on reaching that screen,
        // as "Serializer for class 'XRoute' is not found" at runtime.
        applyPlugin("kotlin-serialization")

        extensions.configure<LibraryExtension> {
            defaultConfig.apply {
                testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
            }
        }

        dependencies {
            add("implementation", project(":core:designsystem"))
            add("implementation", project(":core:ui"))
            add("implementation", project(":core:model"))
        }

        addLib("implementation", "androidx-core-ktx")
        addLib("implementation", "androidx-lifecycle-runtime-compose")
        addLib("implementation", "androidx-lifecycle-viewmodel-compose")
        addLib("implementation", "androidx-navigation-compose")
        addLib("implementation", "androidx-hilt-navigation-compose")
        // hiltViewModel() moved here from hilt-navigation-compose 1.4.
        addLib("implementation", "androidx-hilt-lifecycle-viewmodel-compose")
        addLib("implementation", "kotlinx-coroutines-android")
        addLib("implementation", "kotlinx-serialization-json")

        addLib("testImplementation", "junit4")
        addLib("testImplementation", "truth")
        addLib("testImplementation", "turbine")
        addLib("testImplementation", "kotlinx-coroutines-test")
        addLib("testImplementation", "robolectric")
        addLib("testImplementation", "androidx-compose-ui-test-junit4")

        addLib("androidTestImplementation", "androidx-test-ext-junit")
        addLib("androidTestImplementation", "androidx-test-espresso-core")
        addLib("androidTestImplementation", "androidx-compose-ui-test-junit4")
    }
}
