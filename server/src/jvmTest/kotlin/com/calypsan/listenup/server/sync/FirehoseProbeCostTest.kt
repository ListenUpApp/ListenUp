package com.calypsan.listenup.server.sync

import com.calypsan.listenup.api.sync.DomainDigest
import com.calypsan.listenup.api.sync.Page
import com.calypsan.listenup.api.sync.SyncEvent
import com.calypsan.listenup.api.sync.SyncFrame
import com.calypsan.listenup.api.sync.Tag
import com.calypsan.listenup.api.streaming.RpcEvent
import com.calypsan.listenup.server.api.BookAccessPolicy
import com.calypsan.listenup.server.auth.PrincipalProvider
import com.calypsan.listenup.server.testing.memberPrincipal
import com.calypsan.listenup.server.testing.rootPrincipal
import com.calypsan.listenup.server.testing.withSqlDatabase
import io.github.oshai.kotlinlogging.KotlinLogging
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

private val log = KotlinLogging.logger("com.calypsan.listenup.server.sync.FirehoseProbeCostTest")

private const val GATED_EVENTS = 200
private const val MEMBERS = 5

/**
 * A measurement, not a regression gate: how many access-policy database reads the firehose's
 * per-event gate costs when several members are attached. Every book-gated event makes each member
 * subscriber resolve [BookAccessPolicy] and run one `canAccess` transaction; admins skip the probe.
 */
class FirehoseProbeCostTest :
    FunSpec({
        test("access probes per gated event per member subscriber") {
            withSqlDatabase {
                runTest {
                    val bus = ChangeBus()
                    val tags = TagRepository(db = sql, bus = bus, registry = SyncRegistry())
                    val accessReads = AtomicInteger()
                    val policy = BookAccessPolicy(db = sql, driver = driver)
                    val countingPolicy: () -> BookAccessPolicy = {
                        accessReads.incrementAndGet()
                        policy
                    }

                    fun streamFor(principal: PrincipalProvider) =
                        SyncStreamServiceImpl(
                            bus = bus,
                            registry = SyncRegistry(),
                            bookAccessPolicy = countingPolicy,
                            principal = principal,
                        )

                    val gatedBooks = booksRepo()
                    val started = TimeSource.Monotonic.markNow()
                    val (memberFrames, adminFrames) =
                        withContext(Dispatchers.Default) {
                            withTimeout(60.seconds) {
                                // Each subscriber reads until the tags sentinel, which is published after
                                // every gated event: the sentinel arriving means that subscriber's gate
                                // has judged all of them.
                                val members =
                                    (1..MEMBERS).map { n ->
                                        async { streamFor(memberPrincipal("member-$n")).domainFramesUntilTags() }
                                    }
                                val admin = async { streamFor(rootPrincipal()).domainFramesUntilTags() }
                                delay(500) // let all six subscriptions attach before the burst
                                repeat(GATED_EVENTS) { i ->
                                    bus.publish(
                                        gatedBooks,
                                        SyncEvent.Created(id = "book-$i", revision = i + 1L, occurredAt = 0L, payload = "book-$i"),
                                    )
                                }
                                tags.upsert(Tag("sentinel", "sentinel", "sentinel", 0, 0))
                                members.awaitAll() to admin.await()
                            }
                        }
                    val elapsedMs = started.elapsedNow().inWholeMilliseconds

                    log.info {
                        "PROBE firehose.accessReads=${accessReads.get()} events=$GATED_EVENTS members=$MEMBERS elapsedMs=$elapsedMs"
                    }
                    // Members hold no collection, so the books are not theirs to see: only the sentinel.
                    memberFrames.forEach { frames -> frames.map { it.domain } shouldBe listOf("tags") }
                    // The admin is entitled to every book event plus the sentinel.
                    adminFrames.size shouldBe GATED_EVENTS + 1
                }
            }
        }
    })

/** Domain frames up to and including the first `tags` frame, then the stream is dropped. */
private suspend fun SyncStreamServiceImpl.domainFramesUntilTags(): List<SyncFrame> {
    val seen = mutableListOf<SyncFrame>()
    observeEvents(sinceRevision = null)
        .map { (it as RpcEvent.Data<SyncFrame>).value }
        .takeWhile { frame ->
            if (frame.domain != SyncFrame.CONTROL) seen += frame
            seen.none { it.domain == "tags" }
        }.toList()
    return seen
}

/** A bare `books`-domain repo: the gate reads only [SyncableRepo.domainName] and the event itself. */
private fun booksRepo(): SyncableRepo<Any> =
    object : SyncableRepo<Any> {
        override val domainName: String = BOOKS_DOMAIN

        override fun encodeItemAsJson(item: Any): String = "{}"

        override fun encodeSyncEventAsJson(event: SyncEvent<*>): String = "{}"

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
