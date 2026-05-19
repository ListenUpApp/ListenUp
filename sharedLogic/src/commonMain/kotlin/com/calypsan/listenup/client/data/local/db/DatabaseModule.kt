package com.calypsan.listenup.client.data.local.db

import org.koin.core.module.Module

/**
 * Platform-specific database module.
 * Each platform provides its own implementation with proper database location and configuration.
 */
expect val platformDatabaseModule: Module
