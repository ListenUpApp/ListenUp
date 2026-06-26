package com.calypsan.listenup.server.auth

/**
 * Strategy for handlers asking "who is the current caller?" without coupling service
 * implementations to Ktor types. The default is [None] (used in unit tests); the Ktor-backed
 * implementation that reads [UserPrincipal] from `call.principal()` is wired at the route layer.
 */
fun interface PrincipalProvider {
    fun current(): UserPrincipal?

    object None : PrincipalProvider {
        override fun current(): UserPrincipal? = null
    }
}
