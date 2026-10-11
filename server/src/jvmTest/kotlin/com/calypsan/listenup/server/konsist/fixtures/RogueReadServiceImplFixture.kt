package com.calypsan.listenup.server.konsist.fixtures

import com.calypsan.listenup.server.auth.OpenRead

/** The contract half of [RogueReadServiceImpl]. */
internal interface RogueReadService {
    suspend fun getEverything(): List<String>

    suspend fun listAllBooks(): List<String>

    suspend fun getMine(): List<String>

    suspend fun listVisible(): List<String>

    suspend fun getServerName(): String
}

/**
 * Deliberately violates [com.calypsan.listenup.server.konsist.ReadRpcsNameTheirCallerRule], so the
 * self-test can prove the rule fires. Lives under `jvmTest`, so production scope never sees it.
 *
 * Exactly two offenders: [getEverything] and [listAllBooks] never look at their caller.
 * [getMine] reads the principal, [listVisible] delegates to a helper that does, and
 * [getServerName] carries a reasoned `@OpenRead`.
 */
internal class RogueReadServiceImpl(
    private val principal: RoguePrincipalProvider,
) : RogueReadService {
    private val everything = listOf("a", "b")

    override suspend fun getEverything(): List<String> = everything

    override suspend fun listAllBooks(): List<String> = everything

    override suspend fun getMine(): List<String> = listOf(principal.current())

    override suspend fun listVisible(): List<String> = everything.filter(visibleToCaller())

    @OpenRead(reason = "same for everyone")
    override suspend fun getServerName(): String = "server"

    private fun visibleToCaller(): (String) -> Boolean {
        val caller = principal.current()
        return { it != caller }
    }

    /** Stands in for the production principal provider. */
    fun interface RoguePrincipalProvider {
        /** The caller's id. */
        fun current(): String
    }
}
