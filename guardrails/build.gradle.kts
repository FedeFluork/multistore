plugins {
    alias(libs.plugins.multistore.jvm.library)
}

// :guardrails — the project's non-negotiable rules in executable form.
//
// These tests do not test the app's behaviour: they test that the *repository* respects its own
// constraints. They run on the JVM, touch neither Android nor the network, so they cost a few
// seconds and can sit in the CI of every push.
//
// It has no production sources: it is a verification-only module.

// The tests inspect the repo tree: the path arrives as a system property, never derived from
// `user.dir` (which changes depending on how Gradle is launched).
val repoRoot: String = rootDir.absolutePath

tasks.withType<Test>().configureEach {
    systemProperty("multistore.repoRoot", repoRoot)
    // Resource files change without the classpath changing: without this the task would stay
    // UP-TO-DATE after a translation had been broken.
    outputs.upToDateWhen { false }
}
