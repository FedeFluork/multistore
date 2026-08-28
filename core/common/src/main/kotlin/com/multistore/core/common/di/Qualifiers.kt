package com.multistore.core.common.di

import javax.inject.Qualifier

/**
 * The adapters' working directory: temporary files that can be deleted at any moment without
 * losing anything important.
 *
 * On Android that is `cacheDir`. The qualifier lives here rather than in `:core:network` because
 * its consumer — a store adapter — cannot see Android modules, and its provider (`:app`) is the
 * only one that knows where that directory actually is.
 */
@Qualifier
@Retention(AnnotationRetention.RUNTIME)
annotation class StoreWorkDir
