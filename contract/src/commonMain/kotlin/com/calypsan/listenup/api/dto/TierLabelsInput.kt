package com.calypsan.listenup.api.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * REST body for `PUT /api/v1/books/{id}/chapter-tiers`, the REST mirror of
 * [com.calypsan.listenup.api.BookService.setBookTierLabels]. Carries the two renamable
 * tier-vocabulary labels unvalidated — the server validates both the RPC and REST entry
 * points identically inside `BookServiceImpl.setBookTierLabels`, so this DTO stays a plain
 * transport shape (no `init` block, unlike [ChapterInput]).
 */
@Serializable
@SerialName("TierLabelsInput")
data class TierLabelsInput(
    @SerialName("bookTierLabel") val bookTierLabel: String? = null,
    @SerialName("partTierLabel") val partTierLabel: String? = null,
)
