package com.calypsan.listenup.api.dto.scanner

import com.calypsan.listenup.api.contractJson
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.encodeToString

class CoverSourceSpooledTest :
    FunSpec({
        test("CoverSource.Spooled round-trips through contractJson") {
            val original: CoverSource = CoverSource.Spooled(path = "/home/x/scan-spool/s1/abc.img", mime = "image/jpeg")
            val decoded = contractJson.decodeFromString<CoverSource>(contractJson.encodeToString(original))
            decoded shouldBe original
        }

        test("withoutArtwork() nulls a Spooled cover and the embedded artwork") {
            val book =
                AnalyzedBook(
                    candidate = CandidateBook(rootRelPath = "Author/Title", isFile = false, files = emptyList()),
                    title = "Title",
                    cover = CoverSource.Spooled(path = "/tmp/s1/x.img", mime = "image/jpeg"),
                )
            book.withoutArtwork().cover shouldBe null
        }
    })
