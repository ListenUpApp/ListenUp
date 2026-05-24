package com.calypsan.listenup.api.resources

import io.ktor.resources.Resource

/**
 * REST root for the contributors domain — parent for the nested [Detail] and
 * [Books] routes.
 */
@Resource("/api/v1/contributors")
class ContributorResources {
    /**
     * REST mirror of [com.calypsan.listenup.api.ContributorService.getContributor] —
     * `GET /api/v1/contributors/{id}` returns the contributor aggregate as a
     * [com.calypsan.listenup.api.sync.ContributorSyncPayload?]. Responds 200 on
     * success, 404 when no contributor with the given id exists. Requires JWT
     * authentication.
     */
    @Resource("{id}")
    class Detail(
        val parent: ContributorResources = ContributorResources(),
        val id: String,
    )

    /**
     * REST mirror of [com.calypsan.listenup.api.ContributorService.listBooksByContributor] —
     * `GET /api/v1/contributors/{id}/books` returns all books associated with the
     * contributor as [List]<[com.calypsan.listenup.api.sync.BookSyncPayload]>.
     * Responds 200 with an empty list when the contributor exists but has no
     * books. Requires JWT authentication.
     */
    @Resource("{id}/books")
    class Books(
        val parent: ContributorResources = ContributorResources(),
        val id: String,
    )
}
