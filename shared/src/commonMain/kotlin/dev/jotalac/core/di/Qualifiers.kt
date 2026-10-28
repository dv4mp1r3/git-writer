package dev.jotalac.core.di

import org.koin.core.qualifier.Qualifier
import org.koin.core.qualifier.named

/**
 * Qualifier of the platform git implementation - the vault decorator wraps it under the plain interface.
 *
 * Lives in its own file: platform modules reference it while [coreModule] includes them, and
 * top-level properties of one file initialize in declaration order - shared with coreModule,
 * reading this from a platform module during module construction would see an uninitialized value.
 */
val gitSyncBase: Qualifier = named("gitSyncBase")
