package com.calypsan.listenup.server.sync

import com.calypsan.listenup.api.dto.auth.UserRole
import com.calypsan.listenup.api.sync.DomainDigest
import com.calypsan.listenup.api.sync.Page
import com.calypsan.listenup.api.sync.SyncEvent
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import kotlinx.coroutines.test.runTest

/**
 * [firehoseGateReason] must fail closed on a gated junction domain whose content payload it can't
 * resolve to a `bookId` — an unrecognised payload type, which only happens if a future junction
 * domain forgets its [junctionPayloadBookId] branch. Drives `firehoseGateReason` directly (it's
 * `internal`, visible from this same-module test) with a hand-built [BusEvent] rather than the
 * full `testApplication` + [rpcFirehose] harness [BookRatingsFirehoseAccessTest] uses — no real
 * domain repository ever produces a mismatched payload, so there is no way to provoke this state
 * through the public write path; only a fabricated [BusEvent] can.
 */
class BookJunctionFirehoseFailClosedTest :
    FunSpec({
        test("a gated junction domain with an unresolvable payload is withheld, not delivered") {
            val repoWithUnresolvedPayload =
                object : SyncableRepo<Any> {
                    override val domainName: String = BOOK_RATINGS_DOMAIN

                    override fun encodeItemAsJson(item: Any): String = error("not exercised by this test")

                    override fun encodeSyncEventAsJson(event: SyncEvent<*>): String = error("not exercised by this test")

                    override suspend fun pullSince(
                        userId: String?,
                        cursor: Long,
                        limit: Int,
                        extraWhere: SqlFragment?,
                    ): Page<Any> = error("not exercised by this test")

                    override suspend fun pullByIds(
                        userId: String?,
                        matchColumn: String,
                        matchValues: List<String>,
                        extraWhere: SqlFragment?,
                    ): Page<Any> = error("not exercised by this test")

                    override suspend fun digest(
                        userId: String?,
                        cursor: Long,
                        extraWhere: SqlFragment?,
                    ): DomainDigest = error("not exercised by this test")
                }
            // A payload type none of the three junction branches recognise — the shape
            // [junctionPayloadBookId] would see if a future domain forgot its branch.
            val busEvent =
                BusEvent(
                    repo = repoWithUnresolvedPayload,
                    event = SyncEvent.Created(id = "r1", revision = 1L, occurredAt = 0L, payload = "not a junction payload"),
                )

            runTest {
                val reason =
                    firehoseGateReason(
                        busEvent = busEvent,
                        userId = "member",
                        role = UserRole.MEMBER,
                        // Never resolved for this event — proves the withholding happens before any
                        // access probe, on the unresolved payload alone.
                        bookAccessPolicy = { error("must not probe access for an unresolvable payload") },
                    )

                reason.shouldNotBeNull()
            }
        }
    })
