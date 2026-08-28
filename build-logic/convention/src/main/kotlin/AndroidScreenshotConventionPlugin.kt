import com.multistore.buildlogic.applyPlugin
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.dependencies
import org.gradle.kotlin.dsl.project

/**
 * Roborazzi screenshot tests for a module with UI.
 *
 * Rule 3 asks for one screenshot in light and one in dark for every screen. This plugin
 * provides the equipment; `ScreenshotCoverageTest` in `:guardrails` checks it was used.
 */
class AndroidScreenshotConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        applyPlugin("roborazzi")
        dependencies {
            add("testImplementation", project(":core:testing"))
        }
    }
}
