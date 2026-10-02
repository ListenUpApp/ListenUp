package com.calypsan.listenup.server.hardcover

import com.calypsan.listenup.server.db.UserRoleColumn
import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase
import com.calypsan.listenup.server.testing.seedTestUser
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers

/** The admin token every #1542 test stores. Obviously fake: no real token appears in any test. */
const val ADMIN_TOKEN = "hc_admin_test_token_abc123"

/**
 * A real API-token store, connection store and token provider over [sql], wired into a
 * [HardcoverCatalogToken]. Hardcover's OAuth endpoint is never reached: every seeded connection's access
 * token is fresh. A connected user's token is `at-<userId>`, so a request's bearer names whose it was.
 */
class HardcoverCatalogRig(
    val sql: ListenUpDatabase,
) {
    val cipher = HardcoverTokenCipher(HardcoverTokenCipher.deriveKey("secret"))
    val apiTokens = HardcoverApiTokenStore(sql, cipher)
    val connections = HardcoverConnectionStore(sql, cipher)
    private val oauth =
        HardcoverOAuthClient(
            HttpClient(
                MockEngine {
                    respond("{}", HttpStatusCode.InternalServerError)
                },
            ),
            "id",
            "https://hc.test",
        )
    private val linker =
        HardcoverLinker(
            oauth,
            HardcoverGraphQlClient(HttpClient(MockEngine { respond("{}") })),
            connections,
            CoroutineScope(Dispatchers.Unconfined),
        )
    val connection = HardcoverRatingConnection(connections, HardcoverTokenProvider(oauth, connections, linker))
    val catalog = HardcoverCatalogToken(apiTokens, connection)

    /** Connects [userId] with role [role]; their access token is `at-<userId>`. */
    suspend fun connect(
        userId: String,
        role: UserRoleColumn = UserRoleColumn.MEMBER,
    ) {
        sql.seedTestUser(userId, role)
        connections.save(
            userId,
            HardcoverMe(1, "hc-$userId"),
            HardcoverTokens("at-$userId", "rt-$userId", 604_800, "scope"),
        )
    }

    /** Stores [ADMIN_TOKEN] as `simon`'s, as Admin → Hardcover does once `me` accepts it. */
    suspend fun saveAdminToken(token: String = ADMIN_TOKEN) = apiTokens.save(token, "simon")
}
