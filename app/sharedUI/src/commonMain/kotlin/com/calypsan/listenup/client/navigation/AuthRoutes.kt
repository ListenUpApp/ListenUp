package com.calypsan.listenup.client.navigation

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

/**
 * Type-safe navigation routes for auth flow.
 *
 * These routes are shared across platforms and used for the
 * authentication screens before the user reaches the main app.
 */
@Serializable
sealed interface AuthRoute : NavKey

/**
 * Server selection screen - shows discovered servers via mDNS.
 * User can select a discovered server or enter URL manually.
 */
@Serializable
data object ServerSelect : AuthRoute

/**
 * Server setup screen - manual URL entry when no servers discovered.
 * User enters and verifies their ListenUp server URL.
 */
@Serializable
data object ServerSetup : AuthRoute

/**
 * Setup screen - root user creation when server has no users.
 * User creates the first admin account.
 */
@Serializable
data object Setup : AuthRoute

/**
 * Login screen - authentication when server is configured.
 * User enters credentials to access their account.
 */
@Serializable
data object Login : AuthRoute

/**
 * Register screen - create new account when open registration is enabled.
 * User enters email, password, and name to request an account (pending admin approval).
 */
@Serializable
data object Register : AuthRoute
