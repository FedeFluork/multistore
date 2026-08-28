package com.multistore.core.model

/**
 * What **MultiStore itself** is called, on the device it is running on.
 *
 * A type rather than a bare `String` for the same reason as [DeviceProfile]: it is a fact about
 * the device that the code should receive rather than read from `Build` or a `Context`, so the
 * decisions using it can be tested on the JVM for every combination.
 *
 * The real value changes with the variant — `com.multistore.debug`, `com.multistore.minified`,
 * `com.multistore` — so a hand-written constant would be right for one build in three.
 *
 * It is needed wherever MultiStore appears **among the apps it manages**: it is an installable
 * app like any other, and the day one of the nine stores publishes it, it will turn up in its own
 * update list. Updating yourself kills the process midway through the commit, so it has to go
 * last — and going last requires recognising yourself.
 *
 * **It is not a `value class`, and that is not an oversight.** The name mangling Kotlin applies
 * to a function returning a value class (`provideOwnPackage-6rwtYDo`) is not a valid Java
 * identifier, and Dagger stops during generation with "not a valid name". [DeviceProfile] is a
 * `data class` for the same reason.
 */
data class OwnPackage(val name: String)
