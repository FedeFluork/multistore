import java.util.Properties

plugins {
    alias(libs.plugins.multistore.android.application.compose)
    alias(libs.plugins.multistore.android.hilt)
    alias(libs.plugins.kotlin.serialization)
}

android {
    // The namespace decides where the R class ends up: it must match the module's Kotlin
    // package (com.multistore.<module>.<layer>).
    namespace = "com.multistore.app"

    defaultConfig {
        // The applicationId, on the other hand, stays the bare domain: it is the app's identity
        // on the device, not a source package.
        applicationId = "com.multistore"
        versionCode = 2
        versionName = "0.5.0-BETA"

        /**
         * Where to download `parsers.json` from, when it is not the pinned address.
         *
         * Empty in every normal build: `RemoteConfigAppModule` falls back to
         * `ParsersKey.PARSERS_URL`, which stays the only constant and lives next to the public
         * key. It is for pointing a test build at a document being published:
         *
         *     ./gradlew :app:installDebug -Pmultistore.parsersUrl=http://10.0.2.2:8000/parsers.json
         *
         * Not a hole: the signature stays pinned, so a different address can at most deliver no
         * configuration at all — never one we did not sign.
         */
        buildConfigField(
            "String",
            "PARSERS_URL_OVERRIDE",
            "\"" + (providers.gradleProperty("multistore.parsersUrl").orNull ?: "") + "\"",
        )

        /**
         * The same, for `index.json`.
         *
         *     ./gradlew :app:installDebug -Pmultistore.indexUrl=http://10.0.2.2:8000/index.json
         *
         * Two properties and not one because the two documents are published separately: testing
         * a new index must not force republishing the selectors too.
         */
        buildConfigField(
            "String",
            "INDEX_URL_OVERRIDE",
            "\"" + (providers.gradleProperty("multistore.indexUrl").orNull ?: "") + "\"",
        )
    }

    /**
     * The distribution key, if this machine has it.
     *
     * The keystore is not in the repository — `.gitignore` excludes `.secrets/`, the same folder
     * as the Ed25519 private key — so on any machine other than the publisher's this block
     * produces nothing and `release` stays **unsigned**. That is not a silent degradation:
     * `tools/release.sh` verifies the signature with `apksigner` and refuses to proceed, so an
     * unsigned APK cannot get published by accident.
     *
     * A file rather than four `-P` flags because a password passed on the command line ends up in
     * the shell history and in the process list. The file sits next to the key it protects, has
     * the same permissions, and only **the path** is passed to Gradle.
     *
     *     .secrets/keystore.properties
     *     storeFile=/absolute/path/multistore-release.jks
     *     storePassword=…
     *     keyAlias=multistore
     *     keyPassword=…
     */
    val releaseKeystore = rootProject.file(".secrets/keystore.properties")
    val releaseSigning = if (releaseKeystore.exists()) {
        Properties().apply { releaseKeystore.inputStream().use { stream -> load(stream) } }
    } else {
        null
    }

    signingConfigs {
        if (releaseSigning != null) {
            create("release") {
                storeFile = file(releaseSigning.getProperty("storeFile"))
                storePassword = releaseSigning.getProperty("storePassword")
                keyAlias = releaseSigning.getProperty("keyAlias")
                keyPassword = releaseSigning.getProperty("keyPassword")
                // v1 included: the `minSdk` is 26 and scheme v2 has existed since 24, but v1 is
                // what this app's pre-install verification can read at **every** level — and
                // signing MultiStore's own update in a way MultiStore itself cannot compare would
                // be a joke.
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        getByName("debug") {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        getByName("release") {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                file("proguard-rules.pro"),
            )
            // The distribution key **if there is one**, otherwise nothing. Without the
            // `.secrets/keystore.properties` file the release stays unsigned and not installable,
            // which is what must happen on any machine other than the publisher's: a release
            // signed with the debug key is worse than an unsigned one, because it installs. To
            // TEST the minified build there is the `minified` variant, below.
            signingConfig = signingConfigs.findByName("release")
        }

        /**
         * `minified` — the release build, but installable.
         *
         * It exists for a precise reason, learned the hard way: R8 only runs in release, and the
         * libraries that resolve by name (protobuf-lite, Room, Retrofit, kotlinx.serialization,
         * apksig) fail in a way the debug build never shows. R8 once renamed `themeMode_` to `e`
         * and the first DataStore read went to `NoSuchFieldException`: `assembleRelease` was
         * green, the app crashed at startup.
         *
         * It differs from `release` only in what is needed to install it and keep it distinct:
         * debug signing and a suffixed applicationId, so it coexists with the debug build on the
         * same device. Minification, shrinking and R8 rules are identical — if they were only
         * partly so, testing it would say nothing about the real release.
         *
         *   ./gradlew :app:installMinified
         *   adb shell am start -n com.multistore.minified/com.multistore.app.MainActivity
         */
        create("minified") {
            initWith(getByName("release"))
            applicationIdSuffix = ".minified"
            versionNameSuffix = "-minified"
            signingConfig = signingConfigs.getByName("debug")
            // Library modules only have debug and release: without this, their variant for
            // `minified` would not exist and resolution would fail.
            matchingFallbacks += "release"
        }
    }

    buildFeatures {
        buildConfig = true
    }
}

// :app — the DI root, the NavHost, and the ONLY place in the project that knows the concrete
// adapters. Concrete adapters are wired exclusively in :app via Hilt @IntoSet.
dependencies {
    // Core
    implementation(projects.core.model)
    implementation(projects.core.common)
    implementation(projects.core.designsystem)
    implementation(projects.core.ui)
    implementation(projects.core.data)
    implementation(projects.core.domain)
    implementation(projects.core.database)
    implementation(projects.core.datastore)
    implementation(projects.core.download)
    implementation(projects.core.updates)
    implementation(projects.core.installer)
    implementation(projects.core.network)
    implementation(projects.core.remoteconfig)
    implementation(projects.core.challenge)

    // Feature
    implementation(projects.feature.home)
    implementation(projects.feature.search)
    implementation(projects.feature.appdetail)
    implementation(projects.feature.storelisting)
    implementation(projects.feature.myapps)
    implementation(projects.feature.settings)
    implementation(projects.feature.webviewdownload)

    // Stores — only here. The @IntoSet multibinding lives in :app's Hilt module.
    implementation(projects.store.api)
    implementation(projects.store.fdroid)
    implementation(projects.store.apkcombo)
    implementation(projects.store.apkmirror)
    implementation(projects.store.apkmody)
    implementation(projects.store.modyolo)
    implementation(projects.store.an1)
    implementation(projects.store.pdalife)
    implementation(projects.store.uptodown)
    implementation(projects.store.liteapks)

    // AndroidX
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.hilt.navigation.compose)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.hilt.work)
    implementation(libs.androidx.profileinstaller)
    ksp(libs.androidx.hilt.compiler)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    // Coil, and no longer only transitively through :core:ui. `:app` builds the singleton
    // `ImageLoader` because it is the DI root and the only place where `Application` can implement
    // `SingletonImageLoader.Factory`: without it, Coil builds one of its own, with its own OkHttp
    // and a size equal to 2% of the free space. See `ImageModule`.
    implementation(libs.coil.core)
    implementation(libs.coil.network.okhttp)

    testImplementation(libs.junit4)
    testImplementation(libs.truth)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.compose.ui.test.junit4)
    // The navigation graph is the only piece of :app with logic of its own, and it has exactly
    // one: which jumps push a back-stack entry and which do not. `navigation-testing` is what lets
    // that be tested without drawing a screen.
    testImplementation(libs.androidx.navigation.testing)

    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.hilt.android.testing)
}
