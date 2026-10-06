package com.calypsan.listenup.server.matching.review

import com.calypsan.listenup.api.dto.match.LabelSetReview
import com.calypsan.listenup.api.dto.match.LabelSuggestion
import com.calypsan.listenup.server.metadata.spi.MetadataProviderId
import com.calypsan.listenup.server.metadata.spi.toMetadataSource

/**
 * How labels are identified, so a suggestion that is one you already have is not suggested again. [same] says
 * whether a suggested label resolves (genres: curator alias, then the normaliser; moods: the slug) to one of yours.
 */
internal fun interface LabelIdentity {
    suspend fun same(
        suggested: String,
        yours: List<String>,
    ): Boolean
}

/** A reviewed suggestion and the providers that made it, in route order. */
internal data class ReviewedLabel(
    val suggestion: LabelSuggestion,
    val providers: List<MetadataProviderId>,
)

/** The genres or moods half of Review (spec, *Review*, step 4). */
internal object LabelReviewer {
    /**
     * [yours] kept as they are; suggestions are every routed provider's labels in route [order], deduplicated
     * case-insensitively (each listing every source), minus any that [identity] says you already have.
     */
    suspend fun review(
        yours: List<String>,
        byProvider: Map<MetadataProviderId, List<String>>,
        order: List<MetadataProviderId>,
        identity: LabelIdentity,
    ): Pair<LabelSetReview, List<ReviewedLabel>> {
        val grouped = linkedMapOf<String, Pair<String, MutableList<MetadataProviderId>>>()
        order.forEach { provider ->
            byProvider[provider].orEmpty().map(String::trim).filter(String::isNotEmpty).forEach { label ->
                val entry = grouped.getOrPut(ReviewKeys.text(label)) { label to mutableListOf() }
                if (provider !in entry.second) entry.second += provider
            }
        }
        val suggestions =
            grouped.values
                .filterNot { (label, _) -> identity.same(label, yours) }
                .map { (label, providers) ->
                    ReviewedLabel(
                        LabelSuggestion(label, providers.map { it.toMetadataSource() }.distinctBy { it.id }),
                        providers.toList(),
                    )
                }
        return LabelSetReview(yours, suggestions.map { it.suggestion }) to suggestions
    }
}
