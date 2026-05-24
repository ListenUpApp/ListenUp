package com.calypsan.listenup.server.routes

import com.calypsan.listenup.api.MetadataLookupService
import com.calypsan.listenup.api.error.AppError
import com.calypsan.listenup.api.error.ValidationError
import com.calypsan.listenup.api.metadata.AudibleRegion
import com.calypsan.listenup.api.resources.MetadataResources
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.ContributorId
import com.calypsan.listenup.server.plugins.toHttpStatus
import com.calypsan.listenup.server.plugins.withCorrelationId
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.plugins.callid.callId
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
    contributorMetadataRoutes(metadataLookupService)
}

private fun Route.bookMetadataRoutes(service: MetadataLookupService) {
    get<MetadataResources> { resource ->
        val region = resource.region?.let { AudibleRegion.fromCodeOrNull(it) }
        if (resource.query.isBlank()) {
            return@get call.respond(HttpStatusCode.BadRequest, ValidationError(message = "query must not be blank."))
        }
        when (val result = service.searchBooks(resource.query, region)) {
            is AppResult.Success -> call.respond(result.data)
            is AppResult.Failure -> call.respondBareAppError(result.error)
        }
    }

    get<MetadataResources.Book> { resource ->
        val region = call.resolveRegion(resource.region) ?: return@get
        when (val result = service.getBookMetadata(resource.asin, region)) {
            is AppResult.Success -> call.respondNullableOrNoContent(result.data)
            is AppResult.Failure -> call.respondBareAppError(result.error)
        }
    }

    get<MetadataResources.Chapters> { resource ->
        val region = call.resolveRegion(resource.region) ?: return@get
        when (val result = service.getBookChapters(resource.asin, region)) {
            is AppResult.Success -> call.respondNullableOrNoContent(result.data)
            is AppResult.Failure -> call.respondBareAppError(result.error)
        }
    }

    post<MetadataResources.BookRefresh> { resource ->
        val region = call.resolveRegion(resource.region) ?: return@post
        when (val result = service.refreshBookMetadata(resource.asin, region)) {
            is AppResult.Success -> call.respondNullableOrNoContent(result.data)
            is AppResult.Failure -> call.respondBareAppError(result.error)
        }
    }

    post<MetadataResources.ApplyBook> { resource ->
        val region = call.resolveRegion(resource.region) ?: return@post
        when (val result = service.applyBookMetadata(BookId(resource.bookId), resource.asin, region)) {
            is AppResult.Success -> call.respond(HttpStatusCode.OK)
            is AppResult.Failure -> call.respondBareAppError(result.error)
        }
    }
}

private fun Route.contributorMetadataRoutes(service: MetadataLookupService) {
    get<MetadataResources.ContributorSearch> { resource ->
        when (val result = service.searchContributorMetadata(resource.query)) {
            is AppResult.Success -> call.respond(result.data)
            is AppResult.Failure -> call.respondBareAppError(result.error)
        }
    }

    get<MetadataResources.Contributor> { resource ->
        val region = call.resolveRegion(resource.region) ?: return@get
        when (val result = service.getContributorMetadata(resource.asin, region)) {
            is AppResult.Success -> call.respondNullableOrNoContent(result.data)
            is AppResult.Failure -> call.respondBareAppError(result.error)
        }
    }

    post<MetadataResources.ApplyContributor> { resource ->
        val region = call.resolveRegion(resource.region) ?: return@post
        val id = ContributorId(resource.contributorId)
        when (val result = service.applyContributorMetadata(id, resource.asin, region)) {
            is AppResult.Success -> call.respond(HttpStatusCode.OK)
            is AppResult.Failure -> call.respondBareAppError(result.error)
        }
    }
}

/**
 * Resolves [regionCode] to an [AudibleRegion], responding with 400 Bad Request
 * and returning `null` when the code is unrecognised.
 */
private suspend fun ApplicationCall.resolveRegion(regionCode: String): AudibleRegion? {
    val region = AudibleRegion.fromCodeOrNull(regionCode)
    if (region == null) {
        respond(
            HttpStatusCode.BadRequest,
            ValidationError(message = "Unknown region code '$regionCode'."),
        )
    }
    return region
}

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
