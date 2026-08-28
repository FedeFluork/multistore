plugins {
    alias(libs.plugins.multistore.jvm.library)
}

/*
 * A `JavaExec` and not the `application` plugin, and that is not a preference.
 *
 * `application` also registers `distTar`/`distZip`, which gather every jar on the classpath into a
 * flat folder — and in this project **two modules produce `common.jar`** (`:core:common` and
 * `:store:common`). The result is `build` failing with "Entry index/lib/common.jar is a duplicate",
 * over an artifact this pipeline does not need: nothing is distributed, it is simply run.
 *
 *   ./gradlew :tools:index:buildIndex --args="build/index-payload.json 2026-08-25T21:00:00Z"
 */
tasks.register<JavaExec>("buildIndex") {
    group = "multistore"
    description = "Produces the index.json payload by querying the real stores."
    mainClass.set("com.multistore.tools.index.BuildIndex")
    classpath = sourceSets["main"].runtimeClasspath
    // Paths on the command line are relative to the **repository root**, not to this module. A
    // JavaExec defaults to its own project directory, and with that default the command written in
    // `tools/README.md` resolved `build/self-update.json` under `tools/index/` — where it is not.
    workingDir = rootProject.projectDir
    // It touches the network: it is never "already done".
    outputs.upToDateWhen { false }
}

/*
 * :tools:index — the pipeline that produces `index.json`. **It does not go into the APK.**
 *
 * The plan originally decided it would not be a Gradle module. The first half of that decision —
 * the pipeline lives in this repo, under `tools/` — stays true, the second does not, and the reason
 * became apparent while writing the code.
 *
 * A script outside Gradle would have had to **redo the parsers**: read two RSS feeds, a ranking page
 * and a JSON-LD block, and then deduplicate by app what apkmirror publishes per release. Those would
 * have been parsers no fixture covers and no canary watches, bound to drift silently from the real
 * ones — and the first symptom would have been a **signed** `index.json` full of wrong data, which
 * every installation applies.
 *
 * As a module, the pipeline calls the real adapters. When a store changes markup it breaks together
 * with the app, not separately, and the nightly canary already covers it.
 *
 * It sits under `tools/` and not under `:core:`/`:feature:` because it is not the app: `:app` does
 * not depend on it, and `checkDependencyRules` says so explicitly — it is the only module besides
 * `:app` allowed to know the concrete stores.
 */
dependencies {
    implementation(projects.core.model)
    implementation(projects.core.common)
    implementation(projects.core.network)
    implementation(projects.store.api)

    // The real adapters, and the reason this module exists. Five out of nine: the other four have no
    // surface to read from — see `Adapters`.
    implementation(projects.store.apkcombo)
    implementation(projects.store.apkmirror)
    implementation(projects.store.apkmody)
    implementation(projects.store.pdalife)
    implementation(projects.store.uptodown)

    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)

    // JUnit 5, Truth and kotlin-test are already wired by the convention plugin.
}
