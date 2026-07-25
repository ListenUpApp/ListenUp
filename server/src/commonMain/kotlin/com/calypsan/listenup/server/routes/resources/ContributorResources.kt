package com.calypsan.listenup.server.routes.resources

import io.ktor.resources.Resource

/**
 * REST root for the contributors domain — parent for the nested [Detail],
 * [Books], [Merge], and [Unmerge] routes.
 */
@Resource("/api/v1/contributors")
class ContributorResources {
    /**
     * Contributor image bytes — `PUT /api/v1/contributors/{id}/image`.
     *
     * Blobs travel over plain HTTP, never the RPC channel; the upload streams as multipart.
     */
    @Resource("{id}/image")
    class Image(
        val parent: ContributorResources = ContributorResources(),
        val id: String,
    )
}
