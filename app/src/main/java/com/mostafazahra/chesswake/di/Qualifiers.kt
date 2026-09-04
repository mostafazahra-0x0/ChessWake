package com.mostafazahra.chesswake.di

import javax.inject.Qualifier

/**
 * Dispatcher qualifiers.
 *
 * Injecting dispatchers rather than hardcoding `Dispatchers.IO` at call sites is
 * what makes the repositories testable: a unit test can supply
 * `UnconfinedTestDispatcher` and still exercise the real code path.
 */
@Retention(AnnotationRetention.BINARY)
@Qualifier
annotation class IoDispatcher

@Retention(AnnotationRetention.BINARY)
@Qualifier
annotation class DefaultDispatcher

@Retention(AnnotationRetention.BINARY)
@Qualifier
annotation class MainDispatcher

/** Application-wide scope for work that should outlive any single screen. */
@Retention(AnnotationRetention.BINARY)
@Qualifier
annotation class ApplicationScope
