package com.calypsan.listenup.server.organize

import com.calypsan.listenup.api.contractJson
import com.calypsan.listenup.api.dto.organize.OrganizeAuthorForm
import com.calypsan.listenup.api.dto.organize.OrganizePreset
import com.calypsan.listenup.api.dto.organize.OrganizeSeriesPrefix
import com.calypsan.listenup.api.dto.organize.OrganizeSettingsDto
import com.calypsan.listenup.server.logging.loggerFor
import com.calypsan.listenup.server.settings.ServerSettingsRepository
import kotlinx.coroutines.CancellationException

private val logger = loggerFor<OrganizerSettingsStore>()

/** The `server_settings` key the organizer schema persists under (one JSON blob). */
private const val KEY_ORGANIZER_SETTINGS = "organizer_settings"

/**
 * Persistence for the admin's organizer schema — one JSON-encoded [OrganizeSettingsDto] in the
 * generic `server_settings` key/value store (no migration needed). An unreadable stored value
 * (older/newer schema, manual tampering) falls back to defaults with a warning rather than
 * failing — the admin just re-picks; nothing on disk is affected.
 */
class OrganizerSettingsStore(
    private val settings: ServerSettingsRepository,
) {
    /** The persisted settings, or defaults when unset/unreadable. */
    suspend fun get(): OrganizeSettingsDto {
        val raw = settings.getValue(KEY_ORGANIZER_SETTINGS) ?: return OrganizeSettingsDto()
        return try {
            contractJson.decodeFromString<OrganizeSettingsDto>(raw)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn(e) { "unreadable organizer_settings value — falling back to defaults" }
            OrganizeSettingsDto()
        }
    }

    /** Replaces the persisted settings with [dto]. */
    suspend fun set(dto: OrganizeSettingsDto) {
        settings.setValue(KEY_ORGANIZER_SETTINGS, contractJson.encodeToString(dto))
    }
}

/** Maps the wire schema onto the planner's domain settings — the DTO↔domain boundary translation. */
fun OrganizeSettingsDto.toPlannerSettings(): OrganizerSettings =
    OrganizerSettings(
        enabled = enabled,
        preset =
            when (preset) {
                OrganizePreset.AUTHOR_TITLE -> StructurePreset.AUTHOR_TITLE
                OrganizePreset.AUTHOR_SERIES_TITLE -> StructurePreset.AUTHOR_SERIES_TITLE
                OrganizePreset.FLAT_TITLE -> StructurePreset.FLAT_TITLE
            },
        seriesPrefix =
            when (seriesPrefix) {
                OrganizeSeriesPrefix.BOOK_N_DASH -> SeriesPrefixStyle.BOOK_N_DASH
                OrganizeSeriesPrefix.N_DASH -> SeriesPrefixStyle.N_DASH
                OrganizeSeriesPrefix.BRACKET_N -> SeriesPrefixStyle.BRACKET_N
                OrganizeSeriesPrefix.NONE -> SeriesPrefixStyle.NONE
            },
        authorForm =
            when (authorForm) {
                OrganizeAuthorForm.FIRST_LAST -> AuthorForm.FIRST_LAST
                OrganizeAuthorForm.LAST_FIRST -> AuthorForm.LAST_FIRST
            },
    )
