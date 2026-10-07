package com.calypsan.listenup.api.dto.readingorder

import com.calypsan.listenup.api.contractJson
import com.calypsan.listenup.core.ReadingOrderId
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
private data class KindHolder(
    @SerialName("kind") val kind: ReadingOrderChoiceKind = ReadingOrderChoiceKind.SERIES,
)

class ReadingOrderChoiceTest :
    FunSpec({
        test("every choice round-trips over the RPC wire") {
            listOf(
                ReadingOrderChoice.SeriesOrder,
                ReadingOrderChoice.PublicationOrder,
                ReadingOrderChoice.UserMade(ReadingOrderId("ro-1")),
            ).forEach { choice ->
                contractJson.decodeFromString<ReadingOrderChoice>(contractJson.encodeToString(choice)) shouldBe choice
            }
        }

        test("kind and id map back to the same choice") {
            val made = ReadingOrderChoice.UserMade(ReadingOrderId("ro-1"))
            ReadingOrderChoice.from(made.kind, made.readingOrderIdOrNull()) shouldBe made
            ReadingOrderChoice.from(ReadingOrderChoiceKind.PUBLICATION, null) shouldBe
                ReadingOrderChoice.PublicationOrder
            ReadingOrderChoice.from(ReadingOrderChoiceKind.SERIES, null) shouldBe ReadingOrderChoice.SeriesOrder
        }

        test("a built-in carries no order id, even if one is supplied") {
            ReadingOrderChoice.from(ReadingOrderChoiceKind.SERIES, ReadingOrderId("ro-1")) shouldBe
                ReadingOrderChoice.SeriesOrder
            ReadingOrderChoice.PublicationOrder.readingOrderIdOrNull() shouldBe null
        }

        test("an ORDER kind without an id reads as Series order rather than failing") {
            ReadingOrderChoice.from(ReadingOrderChoiceKind.ORDER, null) shouldBe ReadingOrderChoice.SeriesOrder
        }

        test("a kind a newer server adds decodes as SERIES on this build") {
            contractJson.decodeFromString<KindHolder>("""{"kind":"CHRONOLOGICAL"}""").kind shouldBe
                ReadingOrderChoiceKind.SERIES
        }
    })
