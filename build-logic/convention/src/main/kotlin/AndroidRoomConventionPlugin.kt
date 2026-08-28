import androidx.room.gradle.RoomExtension
import com.multistore.buildlogic.addLib
import com.multistore.buildlogic.applyPlugin
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure

class AndroidRoomConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        applyPlugin("ksp")
        applyPlugin("room")

        extensions.configure<RoomExtension> {
            // Versioned schemas are what makes migration tests possible.
            schemaDirectory("$projectDir/schemas")
        }

        addLib("implementation", "androidx-room-runtime")
        addLib("implementation", "androidx-room-ktx")
        addLib("ksp", "androidx-room-compiler")
        addLib("testImplementation", "androidx-room-testing")
    }
}
