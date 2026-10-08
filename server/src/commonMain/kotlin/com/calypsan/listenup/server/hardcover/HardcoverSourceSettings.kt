package com.calypsan.listenup.server.hardcover

import com.calypsan.listenup.api.dto.admin.HardcoverSourceStatus
import com.calypsan.listenup.api.dto.admin.RatingSourceUnavailable
import com.calypsan.listenup.api.error.AdminError
import com.calypsan.listenup.api.error.HardcoverError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.server.settings.ServerSettingsRepository

/** `server_settings` key for the "Hardcover metadata" switch. Absent = on. */
const val HARDCOVER_METADATA_ENABLED_KEY = "hardcover.metadata.enabled"

/** Hardcover's tokens are JWTs of a few hundred characters; anything past this is not one. */
private const val MAX_API_TOKEN_LENGTH = 4_096

/** Hardcover's API page shows the token as `Bearer eyJ…`; a pasted prefix is not part of it. */
private val BEARER_PREFIX = Regex("^bearer\\s+", RegexOption.IGNORE_CASE)

/** Constant debug detail for an unreachable `me` check: no per-call detail, so never the token. */
private const val TOKEN_CHECK_UNAVAILABLE = "hardcover api token: me unavailable"

/**
 * Admin → Hardcover (#1542): the API token, the "Hardcover metadata" switch, and the status the admin
 * sees. A token is checked with Hardcover (`me`, paced by the shared [rateLimiter]) before anything is
 * stored, and is never logged, put in an error, or returned: [status] describes it by its owner only.
 */
class HardcoverSourceSettings(
    private val apiTokens: HardcoverApiTokenStore,
    private val catalogToken: HardcoverCatalogToken,
    private val graphQl: HardcoverGraphQlClient,
    private val rateLimiter: HardcoverRateLimiter,
    private val settings: ServerSettingsRepository,
) {
    /** What Admin → Hardcover shows. */
    suspend fun status(): HardcoverSourceStatus =
        HardcoverSourceStatus(
            apiToken = apiTokens.status(),
            metadataEnabled = metadataEnabled(),
            metadataUnavailable = RatingSourceUnavailable.NO_CONNECTION.takeUnless { catalogToken.isAvailable() },
        )

    /** Whether Hardcover may fill gaps when a book is matched. On unless an admin switched it off. */
    suspend fun metadataEnabled(): Boolean =
        settings.getValue(HARDCOVER_METADATA_ENABLED_KEY)?.toBooleanStrictOrNull() != false

    /** Switches Hardcover metadata on or off. */
    suspend fun setMetadataEnabled(enabled: Boolean): HardcoverSourceStatus {
        settings.setValue(HARDCOVER_METADATA_ENABLED_KEY, enabled.toString())
        return status()
    }

    /**
     * Checks [raw] with Hardcover and, when Hardcover says whose it is, stores it sealed (replacing any
     * earlier token and clearing a rejection). Hardcover refusing it is [HardcoverError.TokenRejected];
     * Hardcover unreachable is [HardcoverError.Unavailable]; a blank or overlong value is
     * [AdminError.InvalidInput]. Nothing is stored unless Hardcover accepted it.
     */
    suspend fun setApiToken(raw: String): AppResult<HardcoverSourceStatus> {
        val token = raw.trim().replace(BEARER_PREFIX, "").trim()
        if (token.isEmpty() || token.length > MAX_API_TOKEN_LENGTH || token.any { it.isWhitespace() }) {
            return AppResult.Failure(AdminError.InvalidInput())
        }
        rateLimiter.await()
        return when (val me = graphQl.me(token)) {
            is MeResult.Found -> {
                apiTokens.save(token, me.me.username)
                AppResult.Success(status())
            }

            MeResult.Unauthorized -> {
                AppResult.Failure(HardcoverError.TokenRejected())
            }

            is MeResult.Unavailable -> {
                AppResult.Failure(HardcoverError.Unavailable(debugInfo = TOKEN_CHECK_UNAVAILABLE))
            }
        }
    }

    /** Removes the token; catalogue reads borrow a connected account again. */
    suspend fun clearApiToken(): HardcoverSourceStatus {
        apiTokens.clear()
        return status()
    }

    /** Whose Hardcover account catalogue reads (ratings included) use right now. */
    suspend fun accountName(): String? = catalogToken.accountName()
}
