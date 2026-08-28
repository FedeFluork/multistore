import com.multistore.buildlogic.addLib
import com.multistore.buildlogic.applyPlugin
import org.gradle.api.Plugin
import org.gradle.api.Project

class AndroidHiltConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        applyPlugin("ksp")
        applyPlugin("hilt")
        addLib("implementation", "hilt-android")
        addLib("ksp", "hilt-compiler")
    }
}
