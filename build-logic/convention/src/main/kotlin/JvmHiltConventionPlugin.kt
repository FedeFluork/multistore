import com.multistore.buildlogic.addLib
import com.multistore.buildlogic.applyPlugin
import org.gradle.api.Plugin
import org.gradle.api.Project

/**
 * Hilt for pure-Kotlin modules: `hilt-core` at compile time, no Android dependency at runtime.
 * Used by modules that expose `@Module`/`@Provides` but do not live on Android.
 *
 * **The processor is `hilt-compiler`, not `dagger-compiler`, and the difference is not
 * cosmetic.** `dagger-compiler` alone ignores `@InstallIn`: it does not emit the
 * `@AggregatedDeps` metadata the application uses to collect modules scattered across
 * projects. A `@Module @InstallIn` in a JVM module would compile without a warning and then
 * **not exist** in the graph — the error only arrives downstream, in `:app`, as "cannot be
 * provided without an @Provides-annotated method" for a dependency that was just provided.
 *
 * `hilt-compiler` brings the Dagger processor with it, so declaring both is unnecessary.
 */
class JvmHiltConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        applyPlugin("ksp")
        addLib("implementation", "hilt-core")
        addLib("ksp", "hilt-compiler")
    }
}
