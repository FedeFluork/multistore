import com.android.build.api.dsl.ApplicationExtension
import com.multistore.buildlogic.configureDependencyRuleCheck
import com.multistore.buildlogic.configureKotlinAndroid
import com.multistore.buildlogic.intVersion
import com.multistore.buildlogic.libs
import com.multistore.buildlogic.wireCustomLintChecks
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure

class AndroidApplicationConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        // No `org.jetbrains.kotlin.android`: AGP 9 has Kotlin built in.
        pluginManager.apply("com.android.application")

        extensions.configure<ApplicationExtension> {
            configureKotlinAndroid(this)
            defaultConfig.apply {
                targetSdk = libs.intVersion("targetSdk")
                testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
            }
        }
        wireCustomLintChecks()
        configureDependencyRuleCheck()
    }
}
