plugins {
    alias(libs.plugins.multistore.android.library)
    alias(libs.plugins.multistore.android.hilt)
}

android {
    namespace = "com.multistore.core.data"
}

// :core:data — repositories. The only place where Room, DataStore, RemoteConfig and the StoreAdapters meet.
dependencies {
    api(projects.core.model)
    implementation(projects.core.common)
    implementation(projects.core.database)
    api(projects.core.datastore)
    implementation(projects.core.network)
    // `api` for the same reason as `:core:installer` below: `RemoteConfigRepository` exposes
    // `RemoteConfigStatus` and `FetchAttempt`, so those types are part of its contract. The Settings
    // screen has to be able to tell "invalid signature" from "unreachable" — two different sentences
    // and two different remedies.
    api(projects.core.remoteconfig)
    implementation(projects.core.download)
    // `api` and not `implementation`: `InstallStep.Rejected` carries a
    // `PreInstallVerifier.VerificationOutcome`, so that type is part of `InstallRepository`'s
    // contract. Anyone consuming the repository has to be able to name it — the detail screen, say,
    // needs to tell "wrong hash" from "signature different from the installed one", because only the
    // second has a way out to offer.
    api(projects.core.installer)
    implementation(projects.store.api)
    // `api` and not `implementation`: `SearchRepository.browsePaged` returns a
    // `Flow<PagingData<…>>`, so that type is part of the contract and its consumer —
    // `:feature:storelisting` — has to be able to name it.
    api(libs.androidx.paging.runtime)
    implementation(libs.kotlinx.coroutines.android)

    // Room appears in the signature of the in-memory database the tests build.
    testImplementation(libs.androidx.room.runtime)
    testImplementation(libs.junit4)
    testImplementation(libs.truth)
    testImplementation(libs.robolectric)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.kotlinx.serialization.json)
    testImplementation(libs.androidx.paging.testing)
    testImplementation(libs.turbine)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.test.ext.junit)
}
