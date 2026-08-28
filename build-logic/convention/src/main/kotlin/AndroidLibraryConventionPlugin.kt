import com.android.build.api.dsl.LibraryExtension
import com.multistore.buildlogic.configureDependencyRuleCheck
import com.multistore.buildlogic.configureKotlinAndroid
import com.multistore.buildlogic.wireCustomLintChecks
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure

class AndroidLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("com.android.library")

        extensions.configure<LibraryExtension> {
            configureKotlinAndroid(this)
            defaultConfig.apply {
                testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
                // Library modules do not consume `consumerProguardFiles` by default;
                // modules that need R8 rules declare them themselves.
            }
            // Libraries do not need BuildConfig: fewer tasks and less generated source.
            buildFeatures.buildConfig = false
        }
        wireCustomLintChecks()
        configureDependencyRuleCheck()
    }
}
