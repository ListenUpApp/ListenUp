package com.calypsan.listenup.server.metadata.custom

import com.calypsan.listenup.api.dto.ContributorRole
import com.calypsan.listenup.server.metadata.spi.BookContributorMeta
import com.calypsan.listenup.server.metadata.spi.BookCoreMeta
import com.calypsan.listenup.server.metadata.spi.CharacterMeta
import com.calypsan.listenup.server.metadata.spi.CoverMeta
import com.calypsan.listenup.server.metadata.spi.GenreKind
import com.calypsan.listenup.server.metadata.spi.GenreMeta
import com.calypsan.listenup.server.metadata.spi.SeriesMeta
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// ─── The custom-provider JSON contract ───────────────────────────
//
// The wire shape an operator's endpoint returns, and the pure mappers onto the
// neutral `*Meta` SPI types the EnrichmentCoordinator composes. This is the operator's
// integration surface: an endpoint that emits these shapes participates in enrichment
// like any built-in provider. Every field is optional so an endpoint returns only what
// it knows; a 404 (handled in CustomMetadataClient) is an honest catalog miss.
//
// Endpoints (all GET, all keyed by the query params `asin`, `title`, `author`, `region`
// when the coordinator knows them):
//   GET {baseUrl}/book?asin=&title=&author=&region=   -> CustomBookJson (object)
//   GET {baseUrl}/characters?asin=&title=&region=      -> [CustomCharacterJson]
//   GET {baseUrl}/cover?asin=&title=&author=&region=   -> [CustomCoverJson]
//   GET {baseUrl}/genres?asin=&title=&region=          -> [CustomGenreJson]
//   GET {baseUrl}/series?asin=&title=&region=          -> [CustomSeriesJson]

/**
 * The `/book` response — provider-neutral core fields plus credits. Mirrors [BookCoreMeta];
 * [authors] carry the [ContributorRole.AUTHOR] role and [narrators] the
 * [ContributorRole.NARRATOR] role implicitly (an endpoint lists a person under the role
 * they served).
 */
@Serializable
@SerialName("CustomBookJson")
data class CustomBookJson(
    val title: String? = null,
    val subtitle: String? = null,
    val description: String? = null,
    val publisher: String? = null,
    val releaseDate: String? = null,
    val language: String? = null,
    val runtimeMinutes: Int? = null,
    val explicit: Boolean? = null,
    val abridged: Boolean? = null,
    val authors: List<CustomCreditJson> = emptyList(),
    val narrators: List<CustomCreditJson> = emptyList(),
)

/** One credited contributor on a `/book` response; [key] is the endpoint's stable person id when it has one. */
@Serializable
@SerialName("CustomCreditJson")
data class CustomCreditJson(
    val key: String? = null,
    val name: String,
)

/** One entry in a `/characters` response. */
@Serializable
@SerialName("CustomCharacterJson")
data class CustomCharacterJson(
    val name: String,
    val description: String? = null,
)

/** One entry in a `/cover` response; [sourceKey] identifies the catalog entry the cover came from. */
@Serializable
@SerialName("CustomCoverJson")
data class CustomCoverJson(
    val url: String,
    val maxSizeUrl: String? = null,
    val sourceKey: String? = null,
)

/** One entry in a `/genres` response; [kind] is `genre` (default) or `tag`. */
@Serializable
@SerialName("CustomGenreJson")
data class CustomGenreJson(
    val name: String,
    val kind: String? = null,
)

/** One entry in a `/series` response. */
@Serializable
@SerialName("CustomSeriesJson")
data class CustomSeriesJson(
    val key: String? = null,
    val title: String,
    val sequence: String? = null,
)

// ─── Mappers: custom JSON → neutral SPI meta ───────────────────────

private const val TAG_KIND = "tag"

private fun String?.orNullIfBlank(): String? = this?.takeIf { it.isNotBlank() }

/** Folds credits into [BookCoreMeta.authors]/[narrators]; blank string fields collapse to `null`. */
internal fun CustomBookJson.toBookCoreMeta(): BookCoreMeta =
    BookCoreMeta(
        title = title.orNullIfBlank(),
        subtitle = subtitle.orNullIfBlank(),
        description = description.orNullIfBlank(),
        publisher = publisher.orNullIfBlank(),
        releaseDate = releaseDate.orNullIfBlank(),
        language = language.orNullIfBlank(),
        runtimeMinutes = runtimeMinutes?.takeIf { it > 0 },
        explicit = explicit,
        abridged = abridged,
        authors = authors.mapNotNull { it.toBookContributorMeta(ContributorRole.AUTHOR) },
        narrators = narrators.mapNotNull { it.toBookContributorMeta(ContributorRole.NARRATOR) },
    )

private fun CustomCreditJson.toBookContributorMeta(role: ContributorRole): BookContributorMeta? {
    val cleanName = name.orNullIfBlank() ?: return null
    return BookContributorMeta(key = key.orNullIfBlank(), name = cleanName, role = role)
}

/** Drops unnamed characters; keeps a blank description as `null`. */
internal fun List<CustomCharacterJson>.toCharacterMetas(): List<CharacterMeta> =
    mapNotNull { json ->
        json.name.orNullIfBlank()?.let { CharacterMeta(name = it, description = json.description.orNullIfBlank()) }
    }

/** Drops covers with a blank [url]; falls back the missing [sourceKey] to a stable literal. */
internal fun List<CustomCoverJson>.toCoverMetas(): List<CoverMeta> =
    mapNotNull { json ->
        json.url.orNullIfBlank()?.let { url ->
            CoverMeta(
                url = url,
                maxSizeUrl = json.maxSizeUrl.orNullIfBlank(),
                sourceKey = json.sourceKey.orNullIfBlank() ?: "custom",
            )
        }
    }

/** Drops unnamed terms; a `kind` of `tag` marks a free-form tag, everything else a formal genre. */
internal fun List<CustomGenreJson>.toGenreMetas(): List<GenreMeta> =
    mapNotNull { json ->
        json.name.orNullIfBlank()?.let { name ->
            val isTag = json.kind?.trim()?.equals(TAG_KIND, ignoreCase = true) == true
            GenreMeta(name = name, kind = if (isTag) GenreKind.TAG else GenreKind.GENRE)
        }
    }

/** Drops untitled placements; keeps a blank key/sequence as `null`. */
internal fun List<CustomSeriesJson>.toSeriesMetas(): List<SeriesMeta> =
    mapNotNull { json ->
        json.title.orNullIfBlank()?.let { title ->
            SeriesMeta(key = json.key.orNullIfBlank(), title = title, sequence = json.sequence.orNullIfBlank())
        }
    }
