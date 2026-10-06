package com.calypsan.listenup.api.sync

import com.calypsan.listenup.api.contractJson
import com.calypsan.listenup.api.dto.match.ExternalRef
import com.calypsan.listenup.api.metadata.ContributorField
import com.calypsan.listenup.api.metadata.FieldProvenance
import com.calypsan.listenup.api.metadata.FieldSourceKind
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.maps.shouldContainExactly
import io.kotest.matchers.shouldBe

private val PAYLOAD =
    ContributorSyncPayload(
        id = "c1",
        name = "Ray Porter",
        sortName = "Porter, Ray",
        revision = 3,
        updatedAt = 10,
        createdAt = 1,
        deletedAt = null,
    )

/**
 * Matching (PR 4) gives a contributor its catalogue refs and per-field provenance on the wire. Both are
 * additive: a payload from an older server decodes with empty defaults, and a field this build doesn't know
 * is dropped rather than freezing the contributors domain.
 */
class ContributorMatchingFieldsWireTest :
    FunSpec({
        test("a payload without refs or provenance decodes with empty defaults") {
            val legacy =
                """{"id":"c1","name":"Ray Porter","sortName":null,"revision":1,"updatedAt":0,"createdAt":0,"deletedAt":null}"""
            val decoded = contractJson.decodeFromString(ContributorSyncPayload.serializer(), legacy)
            decoded.externalRefs shouldBe emptyList()
            decoded.fieldProvenance shouldBe emptyMap()
        }

        test("refs and provenance round-trip") {
            val full =
                PAYLOAD.copy(
                    externalRefs = listOf(ExternalRef("hardcover", "250716")),
                    fieldProvenance =
                        mapOf(
                            ContributorField.BIOGRAPHY to FieldProvenance(FieldSourceKind.USER, at = 5, by = "u1"),
                            ContributorField.PHOTO to FieldProvenance(FieldSourceKind.ENRICHMENT, provider = "hardcover"),
                        ),
                )
            val json = contractJson.encodeToString(ContributorSyncPayload.serializer(), full)
            contractJson.decodeFromString(ContributorSyncPayload.serializer(), json) shouldBe full
        }

        test("a provenance key this build doesn't know is dropped, not fatal") {
            val json =
                """{"id":"c1","name":"Ray Porter","sortName":null,"revision":1,"updatedAt":0,"createdAt":0,""" +
                    """"deletedAt":null,"fieldProvenance":{"PHOTO":{"kind":"USER"},"WEBSITE_FROM_THE_FUTURE":{"kind":"USER"}}}"""
            contractJson
                .decodeFromString(ContributorSyncPayload.serializer(), json)
                .fieldProvenance
                .shouldContainExactly(mapOf(ContributorField.PHOTO to FieldProvenance(FieldSourceKind.USER)))
        }
    })
