@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.calypsan.listenup.server.sync

import com.calypsan.listenup.api.contractJson
import com.calypsan.listenup.api.error.SyncError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.Mood
import com.calypsan.listenup.api.sync.Mutated
import com.calypsan.listenup.api.sync.SyncEvent
import com.calypsan.listenup.api.sync.Tag
import com.calypsan.listenup.server.testing.withSqlDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext

/**
 * Pins the ambient frame-capture mechanism (echo-in-response part 2): a mutation run inside a
 * [FrameCapture] scope collects the [com.calypsan.listenup.api.sync.SyncFrame] of every syncable
 * write it performs — through the SAME `toSyncFrame` conversion the firehose uses — with no
 * per-write threading. This is the choke point that lets the RPC guard hand any mutation its own
 * frames back for free, however deep the fan-out.
 */
class FrameCaptureTest :
    FunSpec({

        test("an upsert inside a FrameCapture scope collects that write's frame") {
            withSqlDatabase {
                val repo = TagRepository(db = sql, bus = ChangeBus(), registry = SyncRegistry())
                runTest {
                    val capture = FrameCapture()
                    withContext(capture) {
                        repo.upsert(Tag(id = "t1", name = "sci-fi", slug = "sci-fi", revision = 0, updatedAt = 0))
                    }

                    val frames = capture.collected()
                    frames shouldHaveSize 1
                    val frame = frames.single()
                    frame.domain shouldBe "tags"
                    frame.revision shouldBe 1L
                    val event = contractJson.decodeFromString(SyncEvent.serializer(Tag.serializer()), frame.json)
                    event.shouldBeInstanceOf<SyncEvent.Created<Tag>>()
                    event.id shouldBe "t1"
                    event.payload.name shouldBe "sci-fi"
                }
            }
        }

        test("writes across two domains collect into one FrameCapture, each routed to its own domain") {
            withSqlDatabase {
                val bus = ChangeBus()
                val registry = SyncRegistry()
                val tags = TagRepository(db = sql, bus = bus, registry = registry)
                val moods = MoodRepository(db = sql, bus = bus, registry = registry)
                runTest {
                    val capture = FrameCapture()
                    withContext(capture) {
                        tags.upsert(Tag(id = "t1", name = "sci-fi", slug = "sci-fi", revision = 0, updatedAt = 0))
                        moods.upsert(Mood(id = "m1", name = "tense", slug = "tense", revision = 0, updatedAt = 0))
                    }

                    val frames = capture.collected()
                    frames shouldHaveSize 2
                    frames.map { it.domain } shouldContainExactlyInAnyOrder listOf("tags", "moods")
                }
            }
        }

        test("a FirehoseSuppressed write is not captured — capture mirrors firehose emission") {
            withSqlDatabase {
                val repo = TagRepository(db = sql, bus = ChangeBus(), registry = SyncRegistry())
                runTest {
                    val capture = FrameCapture()
                    withContext(capture + FirehoseSuppressed) {
                        repo.upsert(Tag(id = "t1", name = "sci-fi", slug = "sci-fi", revision = 0, updatedAt = 0))
                    }

                    capture.collected() shouldHaveSize 0
                }
            }
        }

        test("an upsert with no FrameCapture in context is unaffected") {
            withSqlDatabase {
                val repo = TagRepository(db = sql, bus = ChangeBus(), registry = SyncRegistry())
                runTest {
                    // No capture in context: the hook is inert, the write behaves exactly as before.
                    val result = repo.upsert(Tag(id = "t1", name = "sci-fi", slug = "sci-fi", revision = 0, updatedAt = 0))
                    result.shouldBeInstanceOf<AppResult.Success<Tag>>()
                }
            }
        }

        test("a softDelete inside a FrameCapture scope collects a Deleted frame") {
            withSqlDatabase {
                val repo = TagRepository(db = sql, bus = ChangeBus(), registry = SyncRegistry())
                runTest {
                    repo.upsert(Tag(id = "t1", name = "sci-fi", slug = "sci-fi", revision = 0, updatedAt = 0))

                    val capture = FrameCapture()
                    withContext(capture) {
                        repo.softDelete("t1")
                    }

                    val frames = capture.collected()
                    frames shouldHaveSize 1
                    frames.single().domain shouldBe "tags"
                    val event = contractJson.decodeFromString(SyncEvent.serializer(Tag.serializer()), frames.single().json)
                    event.shouldBeInstanceOf<SyncEvent.Deleted>()
                    event.id shouldBe "t1"
                }
            }
        }

        test("withCapturedFrames folds the block's value and its writes' frames into Mutated") {
            withSqlDatabase {
                val repo = TagRepository(db = sql, bus = ChangeBus(), registry = SyncRegistry())
                runTest {
                    val result =
                        withCapturedFrames {
                            repo.upsert(Tag(id = "t1", name = "sci-fi", slug = "sci-fi", revision = 0, updatedAt = 0))
                            AppResult.Success(Unit)
                        }

                    result.shouldBeInstanceOf<AppResult.Success<Mutated<Unit>>>()
                    val mutated = (result as AppResult.Success).data
                    mutated.value shouldBe Unit
                    mutated.frames shouldHaveSize 1
                    mutated.frames.single().domain shouldBe "tags"
                }
            }
        }

        test("withCapturedFrames passes a Failure through and carries no envelope") {
            withSqlDatabase {
                val repo = TagRepository(db = sql, bus = ChangeBus(), registry = SyncRegistry())
                runTest {
                    val result: AppResult<Mutated<Unit>> =
                        withCapturedFrames {
                            // A committed write whose overall operation then fails: the frame rides the
                            // firehose, but the failed response carries no Mutated envelope.
                            repo.upsert(Tag(id = "t1", name = "sci-fi", slug = "sci-fi", revision = 0, updatedAt = 0))
                            AppResult.Failure(SyncError.NotFound(domain = "tags", entityId = "x"))
                        }

                    result.shouldBeInstanceOf<AppResult.Failure>()
                }
            }
        }

        test("each withCapturedFrames call collects only its own writes") {
            withSqlDatabase {
                val repo = TagRepository(db = sql, bus = ChangeBus(), registry = SyncRegistry())
                runTest {
                    val first =
                        withCapturedFrames {
                            repo.upsert(Tag(id = "a", name = "a", slug = "a", revision = 0, updatedAt = 0))
                            AppResult.Success(Unit)
                        }
                    val second =
                        withCapturedFrames {
                            repo.upsert(Tag(id = "b", name = "b", slug = "b", revision = 0, updatedAt = 0))
                            AppResult.Success(Unit)
                        }

                    (first as AppResult.Success).data.frames.single().let { frame ->
                        val event = contractJson.decodeFromString(SyncEvent.serializer(Tag.serializer()), frame.json)
                        event.id shouldBe "a"
                    }
                    (second as AppResult.Success).data.frames.single().let { frame ->
                        val event = contractJson.decodeFromString(SyncEvent.serializer(Tag.serializer()), frame.json)
                        event.id shouldBe "b"
                    }
                }
            }
        }
    })
