pluginManagement {
    includeBuild("build-logic")
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
                includeGroupByRegex("android.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
                includeGroupByRegex("android.*")
                includeGroupByRegex("org\\.chromium.*")
            }
        }
        mavenCentral()
    }
}

rootProject.name = "multistore"

enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

// --- Application -----------------------------------------------------------
include(":app")

// --- Executable guardrails --------------------------------------------------
include(":lint-rules")   // custom lint checks, incl. hardcoded strings in composables
include(":guardrails")   // translation parity, settings coverage, screenshot coverage

// --- Core ------------------------------------------------------------------
include(":core:model")          // pure data classes — no Android dependency
include(":core:common")         // Result/AppError, dispatchers, RateLimiter, CircuitBreaker
include(":core:network")        // OkHttp, interceptors, Jsoup — pure Kotlin
include(":core:database")       // Room
include(":core:datastore")      // Proto DataStore
include(":core:designsystem")   // theme and tokens
include(":core:ui")             // shared components
include(":core:domain")         // use cases
include(":core:data")           // repositories
include(":core:installer")      // Installer + pre-install verification
include(":core:download")       // download engine
include(":core:updates")        // periodic update check
include(":core:remoteconfig")   // signed index.json / parsers.json
include(":core:challenge")      // the escalation rungs that need Android
include(":core:testing")        // shared test infrastructure (testImplementation only)

// --- Store -----------------------------------------------------------------
include(":store:api")
include(":store:common")
include(":store:fdroid")
include(":store:apkcombo")
include(":store:apkmirror")
include(":store:apkmody")
include(":store:modyolo")
include(":store:an1")
include(":store:pdalife")
include(":store:uptodown")
include(":store:liteapks")

// --- Publishing pipeline ----------------------------------------------------
// Produces `index.json`. It does **not** ship in the APK: `:app` does not depend on it. It is
// a Gradle module rather than a standalone script because it calls the real adapters instead
// of reimplementing their parsers — see the note atop `tools/index/build.gradle.kts`.
include(":tools:index")

// --- Feature ---------------------------------------------------------------
include(":feature:home")
include(":feature:search")
include(":feature:appdetail")
include(":feature:storelisting")
include(":feature:myapps")
include(":feature:settings")
include(":feature:webviewdownload")
