package com.calypsan.listenup.api.dto.match

import com.calypsan.listenup.api.contractJson
import com.calypsan.listenup.api.error.AppError
import com.calypsan.listenup.api.error.MetadataError
import com.calypsan.listenup.api.metadata.MetadataLocale
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

private val AUDIBLE = MetadataSource(id = "audible", label = "Audible")
private val HARDCOVER = MetadataSource(id = "hardcover", label = "Hardcover")

private val CANDIDATE =
    BookCandidate(
        key = BookCandidateKey(listOf(ExternalRef("audible", "B08G9PRS1K", "us"), ExternalRef("hardcover", "427578"))),
        title = "Project Hail Mary",
        subtitle = null,
        authors = listOf("Andy Weir"),
        narrators = listOf("Ray Porter"),
        durationMs = 58_200_000L,
        year = 2021,
        format = EditionFormat.UNABRIDGED,
        chapterCount = 36,
        coverUrl = "https://m.media-amazon.com/images/I/phm.jpg",
        foundIn = listOf(FoundIn(AUDIBLE, "us"), FoundIn(HARDCOVER)),
        tier = MatchTier.STRONG,
        score = 0.98,
        isBest = true,
        isCurrentLink = true,
        reasons =
            listOf(
                MatchReason.SameNarrator,
                MatchReason.SameLength,
                MatchReason.SameChapterCount(36),
                MatchReason.DifferentNarrators,
                MatchReason.LengthWithin(1),
                MatchReason.LengthDiffers(-386),
                MatchReason.DifferentChapterCount(40),
                MatchReason.DifferentStore(MetadataLocale("uk")),
                MatchReason.DifferentEdition(EditionFormat.DRAMATIZED),
            ),
    )

private val RESULT =
    BookFindResult(
        yourCopy = YourCopy(58_200_000L, listOf("Ray Porter"), 36, 2021, EditionFormat.UNABRIDGED),
        steps =
            listOf(
                SearchStep.ExistingLink(AUDIBLE),
                SearchStep.Identifier(IdentifierKind.ISBN),
                SearchStep.TitleAuthorLength,
                SearchStep.YourQuery("hail mary weir"),
            ),
        candidates = listOf(CANDIDATE),
        sources =
            listOf(
                SourceStatus.Answered(AUDIBLE, 4),
                SourceStatus.TimedOut(HARDCOVER),
                SourceStatus.RateLimited(HARDCOVER, 30),
                SourceStatus.Failed(HARDCOVER),
                SourceStatus.NotFoundInStore(
                    AUDIBLE,
                    MetadataLocale("uk"),
                    listOf(MetadataLocale("us"), MetadataLocale("au")),
                ),
                SourceStatus.Unavailable(HARDCOVER, UnavailableReason.NOT_CONFIGURED),
            ),
        region = RegionContext(AUDIBLE, MetadataLocale("uk"), RegionOrigin.LIBRARY, MetadataLocale.SUPPORTED),
    )

/** Every Find DTO survives the wire, and an older rate-limit error (no retry-after) still decodes. */
class BookFindContractTest :
    FunSpec({
        test("a full Find result round-trips with every status, step and reason") {
            contractJson.decodeFromString<BookFindResult>(contractJson.encodeToString(RESULT)) shouldBe RESULT
        }

        test("a Find request round-trips, and an empty one means automatic in the library's store") {
            val request =
                BookFindRequest(
                    query = "hail mary",
                    strategy = FindStrategy.TITLE_AUTHOR,
                    regionOverride = MetadataLocale("au"),
                )
            contractJson.decodeFromString<BookFindRequest>(contractJson.encodeToString(request)) shouldBe request
            contractJson.decodeFromString<BookFindRequest>("{}") shouldBe
                BookFindRequest(null, FindStrategy.AUTOMATIC, null)
        }

        test("a candidate key needs at least one ref") {
            shouldThrow<IllegalArgumentException> { BookCandidateKey(emptyList()) }
        }

        test("a rate limit carries its retry-after, and one from an older server decodes without it") {
            val limited: AppError = MetadataError.ExternalRateLimited(retryAfterSeconds = 42)
            contractJson.decodeFromString<AppError>(contractJson.encodeToString(limited)) shouldBe limited

            val older = """{"type":"MetadataError.ExternalRateLimited","correlationId":null,"debugInfo":null}"""
            val decoded = contractJson.decodeFromString<AppError>(older) as MetadataError.ExternalRateLimited
            decoded.retryAfterSeconds shouldBe null
        }
    })
