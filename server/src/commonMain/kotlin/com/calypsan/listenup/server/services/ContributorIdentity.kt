package com.calypsan.listenup.server.services

import com.calypsan.listenup.api.dto.match.ExternalRef
import com.calypsan.listenup.api.metadata.ContributorField
import com.calypsan.listenup.api.metadata.ContributorFieldProvenanceMapSerializer
import com.calypsan.listenup.api.metadata.FieldProvenance
import com.calypsan.listenup.server.hardcover.HARDCOVER_AUTHOR_KEY_PREFIX
import kotlinx.serialization.json.Json

private const val HARDCOVER = "hardcover"

private val contributorProvenanceJson = Json { ignoreUnknownKeys = true }

/**
 * A contributor's identity invariants, as pure functions — the contributor counterpart of
 * [BookIdentityColumns]. `contributors.asin` stays for older clients and stays the truth for the ref its value
 * names: a plain value is the `audible` ref (an Audnexus key is an Audible ASIN), the legacy Hardcover apply's
 * `hardcover:author:<id>` is the `hardcover` ref.
 */
internal object ContributorIdentity {
    /**
     * [refs] reconciled to [asin]: the ref the column names is rebuilt from it (an Audible ref already naming
     * that ASIN keeps its store); a column naming Hardcover leaves no Audible ref; a blank column leaves no
     * Audible ref. Other providers pass through, one per provider (first wins), sorted by provider.
     */
    fun reconcileRefs(
        asin: String?,
        refs: List<ExternalRef>,
    ): List<ExternalRef> {
        val column = asin?.trim()?.takeIf { it.isNotEmpty() }
        val hardcoverId =
            column
                ?.takeIf {
                    it.startsWith(HARDCOVER_AUTHOR_KEY_PREFIX)
                }?.removePrefix(HARDCOVER_AUTHOR_KEY_PREFIX)
        val fromColumn =
            when {
                column == null -> {
                    null
                }

                hardcoverId != null -> {
                    hardcoverId.takeIf { it.isNotEmpty() }?.let { ExternalRef(HARDCOVER, it) }
                }

                else -> {
                    refs.firstOrNull { it.provider == ExternalRef.AUDIBLE && it.id == column }
                        ?: ExternalRef(ExternalRef.AUDIBLE, column)
                }
            }
        val governed = setOfNotNull(ExternalRef.AUDIBLE, fromColumn?.provider)
        val others = refs.filter { it.provider !in governed }.distinctBy { it.provider }
        return (listOfNotNull(fromColumn) + others).sortedBy { it.provider }
    }
}

/** A provenance map as `contributors.field_provenance` stores it; the empty map is `{}`, the column default. */
internal fun Map<ContributorField, FieldProvenance>.toContributorProvenanceColumn(): String =
    contributorProvenanceJson.encodeToString(ContributorFieldProvenanceMapSerializer, this)

/** The `contributors.field_provenance` column read back; a field this build doesn't know is dropped. */
internal fun String.toContributorProvenance(): Map<ContributorField, FieldProvenance> =
    if (isBlank()) {
        emptyMap()
    } else {
        contributorProvenanceJson.decodeFromString(
            ContributorFieldProvenanceMapSerializer,
            this,
        )
    }
