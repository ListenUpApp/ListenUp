package com.calypsan.listenup.server.routes.resources

import io.ktor.resources.Resource

/**
 * REST root for the contributors domain — parent for the nested [Detail],
 * [Books], [Merge], and [Unmerge] routes.
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

    /**
     * `PUT /api/v1/contributors/{id}/image` — uploads a replacement contributor photo
     * (multipart, `file` part). Gated on the caller's `canEdit` permission via the scoped
     * service. Responds 204 on success, 403 when the caller lacks `canEdit`, 413 when the
     * part exceeds 10 MiB, 400 when no file part is present, 422 for non-image bytes.
     * Requires JWT authentication.
     */
    @Resource("{id}/image")
    class Image(
        val parent: ContributorResources = ContributorResources(),
        val id: String,
    )

    /**
     * REST mirror of [com.calypsan.listenup.api.ContributorService.mergeContributors] —
     * `POST /api/v1/contributors/merge` merges the `source` contributor into the
     * `target` contributor (body: `{ "source": "<id>", "target": "<id>" }`).
     * Responds 204 on success, 404 when either contributor is missing or
     * already tombstoned, 400 when `source == target`. Requires JWT authentication.
     */
    @Resource("merge")
    class Merge(
        val parent: ContributorResources = ContributorResources(),
    )

    /**
     * REST mirror of [com.calypsan.listenup.api.ContributorService.unmergeContributor] —
     * `POST /api/v1/contributors/{id}/unmerge` unmerges an alias back out of the
     * contributor identified by [id] (body: `{ "aliasName": "<name>" }`).
     * Responds 200 with the new contributor's id on success, 404 when no
     * contributor with [id] exists or when the alias isn't on the contributor.
     * Requires JWT authentication.
     */
    @Resource("{id}/unmerge")
    class Unmerge(
        val parent: ContributorResources = ContributorResources(),
        val id: String,
    )
}
