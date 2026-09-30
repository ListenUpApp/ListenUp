package com.calypsan.listenup.client.presentation.admin.upload

import com.calypsan.listenup.api.dto.uploads.UploadLimits
import com.calypsan.listenup.client.domain.repository.UploadCandidate
import com.calypsan.listenup.core.FileSource
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.ktor.utils.io.ByteReadChannel

/**
 * [uploadSelectionRefusal] — the one rule every client applies before a byte goes out.
 *
 * The server enforces every cap per request; refusing up front is the courtesy that turns a wasted
 * evening into a sentence. One rule, so Android, web and iOS can never disagree about what fits.
 */
class UploadSelectionRefusalTest :
    FunSpec({
        fun candidate(
            name: String,
            size: Long?,
        ) = UploadCandidate(relPath = name, source = SizedSource(name, size))

        test("a selection within every cap is not refused") {
            uploadSelectionRefusal(listOf(candidate("01.m4b", 1_000), candidate("02.m4b", 2_000))).shouldBeNull()
        }

        test("more files than one session carries is refused, naming both counts") {
            val tooMany = (0..UploadLimits.MAX_FILES).map { candidate("$it.mp3", 1) }

            uploadSelectionRefusal(tooMany) shouldBe
                UploadSelectionRefusal.TooManyFiles(count = UploadLimits.MAX_FILES + 1, limit = UploadLimits.MAX_FILES)
        }

        test("one file over the per-file cap is refused by name") {
            val huge = UploadLimits.MAX_FILE_BYTES + 1

            uploadSelectionRefusal(listOf(candidate("small.m4b", 10), candidate("huge.m4b", huge))) shouldBe
                UploadSelectionRefusal.FileTooLarge(
                    filename = "huge.m4b",
                    bytes = huge,
                    limitBytes = UploadLimits.MAX_FILE_BYTES,
                )
        }

        test("a selection over the session cap is refused with its total") {
            val each = UploadLimits.MAX_FILE_BYTES
            val files = (1..5).map { candidate("$it.m4b", each) }

            uploadSelectionRefusal(files) shouldBe
                UploadSelectionRefusal.TooLarge(bytes = each * 5, limitBytes = UploadLimits.MAX_SESSION_BYTES)
        }

        test("the file count is checked before any size") {
            val files = (0..UploadLimits.MAX_FILES).map { candidate("$it.m4b", UploadLimits.MAX_FILE_BYTES + 1) }

            uploadSelectionRefusal(files) shouldBe
                UploadSelectionRefusal.TooManyFiles(count = UploadLimits.MAX_FILES + 1, limit = UploadLimits.MAX_FILES)
        }

        // A cloud-backed provider may not know a file's size. That is not a reason to refuse — the
        // server still enforces the cap as the bytes arrive.
        test("unknown sizes count as nothing rather than refusing") {
            uploadSelectionRefusal(listOf(candidate("cloud.m4b", null), candidate("local.m4b", 10))).shouldBeNull()
        }
    })

/** A [FileSource] that claims a size without holding any bytes — the rule reads sizes only. */
private class SizedSource(
    override val filename: String,
    override val size: Long?,
) : FileSource {
    override fun openChannel(): ByteReadChannel = error("the refusal rule never reads content")
}
