import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.android.lint)
}

// :lint-rules — the project's custom lint checks.
//
// Why it exists: AGP's `HardcodedText` inspects layout XML. MultiStore is entirely Compose, so that
// check would never see a real hardcoded string. The exit criterion is literally "add a hardcoded
// string to a composable -> the build must fail": without this module it is unattainable.
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    compileOnly(libs.lint.api)
    compileOnly(libs.lint.checks)

    testImplementation(libs.lint.api)
    testImplementation(libs.lint.checks)
    testImplementation(libs.lint.tests)
    testImplementation(libs.junit4)
    testImplementation(libs.truth)
}

tasks.withType<Test>().configureEach {
    useJUnit()
}
