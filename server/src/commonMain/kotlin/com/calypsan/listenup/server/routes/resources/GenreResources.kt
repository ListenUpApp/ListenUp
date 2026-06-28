package com.calypsan.listenup.server.routes.resources

import io.ktor.resources.Resource

/**
 * REST mirror of [com.calypsan.listenup.api.GenreService] — the same DTOs as
 * the RPC surface, exposed under `/api/v1/genres` for third-party consumers.
 *
 * Routes:
 * - `GET /api/v1/genres` → `listGenres`
 * - `POST /api/v1/genres` → `createGenre` (body: `{parentId?, name, sortOrder?}`)
 * - `GET /api/v1/genres/{id}` → `getGenre`
 * - `PATCH /api/v1/genres/{id}` → `updateGenre`
 * - `DELETE /api/v1/genres/{id}` → `deleteGenre`
 * - `GET /api/v1/genres/{id}/children` → `getGenreChildren`
 * - `GET /api/v1/genres/{id}/books?includeDescendants=&limit=` → `browseBooks`
 * - `POST /api/v1/genres/{id}/move` → `moveGenre` (body: `{newParentId?}`)
 * - `POST /api/v1/genres/merge` → `mergeGenres` (body: `{source, target}`)
 * - `GET /api/v1/genres/unmapped` → `listUnmappedStrings`
 * - `POST /api/v1/genres/unmapped/map` → `mapUnmappedToGenre` (body: `{rawString, genreId}`)
 *
 * All routes require JWT authentication. Curator-mutation routes are gated by
 * the same `// TODO: gate by user permissions` boundary as the RPC surface.
 */
@Resource("/api/v1/genres")
class GenreResources {
    /** Single-genre routes nested under `/api/v1/genres/{id}` — GET, PATCH, DELETE plus subtree resources. */
    @Resource("{id}")
    class Detail(
        val parent: GenreResources = GenreResources(),
        val id: String,
    ) {
        /** `GET /api/v1/genres/{id}/children` — direct children only, not full descendants. */
        @Resource("children")
        class Children(
            val parent: Detail,
        )

        /** `GET /api/v1/genres/{id}/books` — books linked to this genre, optionally including subtree. */
        @Resource("books")
        class Books(
            val parent: Detail,
            val includeDescendants: Boolean = false,
            val limit: Int = 100,
        )

        /** `POST /api/v1/genres/{id}/move` — reparent the genre subtree. Body carries `newParentId?`. */
        @Resource("move")
        class Move(
            val parent: Detail,
        )
    }

    /** `POST /api/v1/genres/merge` — merge `source` genre into `target`. Body carries both ids. */
    @Resource("merge")
    class Merge(
        val parent: GenreResources = GenreResources(),
    )

    /** Routes for the unmapped-string curator queue under `/api/v1/genres/unmapped`. */
    @Resource("unmapped")
    class Unmapped(
        val parent: GenreResources = GenreResources(),
    ) {
        /** `POST /api/v1/genres/unmapped/map` — bind a raw string to a target genre. */
        @Resource("map")
        class Map(
            val parent: Unmapped,
        )
    }
}
