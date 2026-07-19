package com.calypsan.listenup.client.data.sync

import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.Tag
import com.calypsan.listenup.client.data.sync.testing.withTagSyncEngineAgainstServer
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

private const val ROUND_TRIP_TIMEOUT_SECONDS = 30
private const val HEAL_TIMEOUT_SECONDS = 15

/**
 * DRIFT-1 regression: an offline optimistic curation edit that the server later **rejects
 * non-retryably** must not leave the rejected value in Room forever.
 *
 * The reproduction: a client `renameTag` to an invalid name (`"!!!"` normalizes to an empty slug)
 * is written to Room optimistically — the client does not validate before enqueuing — and queued.
 * On drain the real in-process `TagService.renameTag` returns `TagError.InvalidName`
 * (`isRetryable = false`), so the outbox dead-letters the op ([FailureDisposition] `Terminal`).
 *
 * Nothing in the convergence machinery heals this by construction: the digest hashes only
 * `(id, revision)` and the optimistic edit never bumped `revision`, so `SyncReconciler` sees no
 * drift; catch-up returns only `revision > cursor`, so the unchanged-revision row is skipped; and
 * the server never accepted the edit, so no SSE echo arrives. Without the dead-letter heal the
 * rejected `"!!!"` name persists on this device permanently while the server holds the real one.
 *
 * The fix drains a heal signal on dead-letter/dismiss and re-fetches current server truth by id,
 * applying it over the phantom (equal-revision applies pass the strict `>` `ServerWins` guard).
 * This test asserts the Room row converges back to the server's name.
 */
class DeadLetterPhantomWriteE2ETest :
    FunSpec({

        test("a rejected offline tag rename heals back to server truth after dead-letter") {
            withTagSyncEngineAgainstServer {
                engine.start(currentUserId = "u1")

                // Server holds the real tag; it syncs into client Room at revision 0.
                tagRepo.upsert(serverTag("tag-1", "Science Fiction", "science-fiction"))
                withTimeout(ROUND_TRIP_TIMEOUT_SECONDS.seconds) {
                    while (clientDatabase.tagDao().getById("tag-1") == null) delay(POLL_MS)
                }
                clientDatabase.tagDao().getById("tag-1")?.name shouldBe "Science Fiction"

                // Offline optimistic rename to an invalid name — the client does not validate, so the
                // phantom "!!!" is written to Room and enqueued. renameTag returns Success optimistically.
                clientTagRepo.renameTag("tag-1", "!!!").shouldBeInstanceOf<AppResult.Success<*>>()

                // The engine drains the op; the server rejects it non-retryably → the op dead-letters.
                withTimeout(ROUND_TRIP_TIMEOUT_SECONDS.seconds) {
                    queue.observeDeadLetterCount().first { it >= 1 }
                }

                // The heal must re-fetch server truth and overwrite the phantom. RED before the fix:
                // this poll times out (the "!!!" phantom never heals) and the final assertion shows
                // the divergence crisply. GREEN after the fix: the row reverts to the server's name.
                withTimeoutOrNull(HEAL_TIMEOUT_SECONDS.seconds) {
                    while (clientDatabase.tagDao().getById("tag-1")?.name != "Science Fiction") delay(POLL_MS)
                }
                clientDatabase.tagDao().getById("tag-1")?.name shouldBe "Science Fiction"
            }
        }
    })

private const val POLL_MS = 50L

private fun serverTag(
    id: String,
    name: String,
    slug: String,
): Tag {
    val now = System.currentTimeMillis()
    return Tag(id = id, name = name, slug = slug, revision = 0L, updatedAt = now, deletedAt = null)
}
