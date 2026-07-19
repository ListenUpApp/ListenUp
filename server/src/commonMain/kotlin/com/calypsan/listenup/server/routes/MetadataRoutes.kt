package com.calypsan.listenup.server.routes

import com.calypsan.listenup.api.MetadataLookupService
import com.calypsan.listenup.api.dto.MetadataApplySelection
import com.calypsan.listenup.api.error.AppError
import com.calypsan.listenup.api.error.ValidationError
import com.calypsan.listenup.api.metadata.MetadataLocale
import com.calypsan.listenup.server.metadata.audible.AudibleRegion
import com.calypsan.listenup.server.routes.resources.MetadataResources
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.ContributorId
import com.calypsan.listenup.server.api.MetadataLookupServiceImpl
import com.calypsan.listenup.server.auth.PrincipalProvider
import com.calypsan.listenup.server.plugins.toHttpStatus
import com.calypsan.listenup.server.plugins.userPrincipalOrNull
import com.calypsan.listenup.server.plugins.withCorrelationId
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.plugins.callid.callId
import io.ktor.server.request.receive
import io.ktor.server.resources.get
import io.ktor.server.resources.post
import io.ktor.server.response.respond
import io.ktor.server.routing.Route

/**
 * REST mirror of [MetadataLookupService]. Mounts under `/api/v1/metadata/`.
 *
 * All routes require JWT authentication (mounted inside the `authenticate` block
 * in `Application.kt`). Responds bare types (unwrapped from [AppResult]) per
 * the third-party REST surface convention — failures are mapped to their
 * canonical HTTP status codes via [AppError.toHttpStatus].
 *
 * Nullable results (e.g. unknown ASIN) respond with HTTP 204 No Content rather
 * than a JSON null body, consistent with other nullable routes in this server.
 */
fun Route.metadataRoutes(metadataLookupService: MetadataLookupService) {
    bookMetadataRoutes(metadataLookupService)
    bookCoverRoutes(metadataLookupService)
    contributorMetadataRoutes(metadataLookupService)
}

private fun Route.bookMetadataRoutes(service: MetadataLookupService) {
    get<MetadataResources> { resource ->
        val region = resource.region.toLocaleOrNull()
        if (resource.query.isBlank()) {
            return@get call.respond(HttpStatusCode.BadRequest, ValidationError(message = "query must not be blank."))
        }
        when (val result = service.searchBooks(resource.query, region, resource.bookId?.let(::BookId))) {
            is AppResult.Success -> call.respond(result.data)
            is AppResult.Failure -> call.respondBareAppError(result.error)
        }
    }

    get<MetadataResources.Book> { resource ->
        val region = call.resolveLocale(resource.region) ?: return@get
        when (val result = service.getBookMetadata(resource.asin, region)) {
            is AppResult.Success -> call.respondNullableOrNoContent(result.data)
            is AppResult.Failure -> call.respondBareAppError(result.error)
        }
    }

    get<MetadataResources.Chapters> { resource ->
        val region = call.resolveLocale(resource.region) ?: return@get
        when (val result = service.getBookChapters(resource.asin, region)) {
            is AppResult.Success -> call.respondNullableOrNoContent(result.data)
            is AppResult.Failure -> call.respondBareAppError(result.error)
        }
    }

    post<MetadataResources.BookRefresh> { resource ->
        val region = call.resolveLocale(resource.region) ?: return@post
        when (val result = call.scoped(service).refreshBookMetadata(resource.asin, region)) {
            is AppResult.Success -> call.respondNullableOrNoContent(result.data)
            is AppResult.Failure -> call.respondBareAppError(result.error)
        }
    }

    post<MetadataResources.ApplyBook> { resource ->
        val region = call.resolveLocale(resource.region) ?: return@post
        val selection = call.receive<MetadataApplySelection>()
        when (
            val result =
                call.scoped(service).applyBookMetadata(BookId(resource.bookId), resource.asin, region, selection)
        ) {
            is AppResult.Success -> call.respond(HttpStatusCode.OK)
            is AppResult.Failure -> call.respondBareAppError(result.error)
        }
    }

    post<MetadataResources.ApplyChapters> { resource ->
        val region = call.resolveLocale(resource.region) ?: return@post
        val result =
            call.scoped(service).applyChapterNames(
                BookId(resource.bookId),
                resource.asin,
                region,
                resource.ordinals.toSet(),
            )
        when (result) {
            is AppResult.Success -> call.respond(HttpStatusCode.OK)
            is AppResult.Failure -> call.respondBareAppError(result.error)
        }
    }
}

private fun Route.bookCoverRoutes(service: MetadataLookupService) {
    get<MetadataResources.SearchCovers> { resource ->
        val region = resource.region.toLocaleOrNull()
        when (val result = call.scoped(service).searchCovers(BookId(resource.bookId), region)) {
            is AppResult.Success -> call.respond(result.data)
            is AppResult.Failure -> call.respondBareAppError(result.error)
        }
    }

    post<MetadataResources.ApplyCover> { resource ->
        if (resource.url.isBlank()) {
            return@post call.respond(HttpStatusCode.BadRequest, ValidationError(message = "url must not be blank."))
        }
        when (val result = call.scoped(service).applyCover(BookId(resource.bookId), resource.url)) {
            is AppResult.Success -> call.respond(HttpStatusCode.OK)
            is AppResult.Failure -> call.respondBareAppError(result.error)
        }
    }
}

private fun Route.contributorMetadataRoutes(service: MetadataLookupService) {
    get<MetadataResources.ContributorSearch> { resource ->
        when (val result = service.searchContributorMetadata(resource.query, resource.region.toLocaleOrNull())) {
            is AppResult.Success -> call.respond(result.data)
            is AppResult.Failure -> call.respondBareAppError(result.error)
        }
    }

    get<MetadataResources.Contributor> { resource ->
        val region = call.resolveLocale(resource.region) ?: return@get
        when (val result = service.getContributorMetadata(resource.asin, region)) {
            is AppResult.Success -> call.respondNullableOrNoContent(result.data)
            is AppResult.Failure -> call.respondBareAppError(result.error)
        }
    }

    post<MetadataResources.ApplyContributor> { resource ->
        val region = call.resolveLocale(resource.region) ?: return@post
        val id = ContributorId(resource.contributorId)
        when (val result = call.scoped(service).applyContributorMetadata(id, resource.asin, region)) {
            is AppResult.Success -> call.respond(HttpStatusCode.OK)
            is AppResult.Failure -> call.respondBareAppError(result.error)
        }
    }
}

/**
 * Scopes [service] to the authenticated caller so the apply/refresh handlers gate on the
 * caller's `canEdit` flag. Reaching this without a principal is an auth-wall regression.
 */
private fun ApplicationCall.scoped(service: MetadataLookupService): MetadataLookupService {
    val p = userPrincipalOrNull() ?: error(METADATA_AUTH_WALL_REGRESSION_MSG)
    return (service as MetadataLookupServiceImpl).copyWith(PrincipalProvider { p })
}

private const val METADATA_AUTH_WALL_REGRESSION_MSG =
    "metadata REST mount reached without a principal — auth wall regression"

/**
 * Resolves a required [regionCode] to a provider-neutral [MetadataLocale], responding with
 * 400 Bad Request and returning `null` when the code is not a recognised region.
 *
 * Validation is against the Audible storefronts the REST surface exposes; the resolved value
 * is the neutral locale the service speaks.
 */
private suspend fun ApplicationCall.resolveLocale(regionCode: String): MetadataLocale? {
    val region = AudibleRegion.fromCodeOrNull(regionCode)
    if (region == null) {
        respond(
            HttpStatusCode.BadRequest,
            ValidationError(message = "Unknown region code '$regionCode'."),
        )
    }
    return region?.let { MetadataLocale(it.code) }
}

/**
 * Resolves an optional region-code query param to a [MetadataLocale], or `null` when the param
 * is absent or unrecognised — the search/cover paths silently fall back to the default region.
 */
private fun String?.toLocaleOrNull(): MetadataLocale? =
    this?.let { AudibleRegion.fromCodeOrNull(it) }?.let { MetadataLocale(it.code) }

/**
 * Responds with the [value] if non-null, otherwise responds with HTTP 204 No Content.
 */
private suspend inline fun <reified T : Any> ApplicationCall.respondNullableOrNoContent(value: T?) {
    if (value != null) respond(value) else respond(HttpStatusCode.NoContent)
}

private suspend fun ApplicationCall.respondBareAppError(error: AppError) {
    val typed = error.withCorrelationId(callId)
    respond(typed.toHttpStatus(), typed)
}
