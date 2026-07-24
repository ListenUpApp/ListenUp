package com.calypsan.listenup.client.data.repository

import com.calypsan.listenup.client.data.local.db.BookTagDao
import com.calypsan.listenup.client.data.local.db.BookTagEntity
import com.calypsan.listenup.client.data.local.db.TagDao
import com.calypsan.listenup.api.TagService
import com.calypsan.listenup.client.data.local.db.TagEntity
import com.calypsan.listenup.client.data.remote.RpcChannel
import com.calypsan.listenup.client.data.remote.forTest
import com.calypsan.listenup.client.domain.model.Tag
import com.calypsan.listenup.client.test.fake.noopOfflineEditor
import dev.mokkery.answering.returns
import dev.mokkery.every
import dev.mokkery.mock
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest

/**
 * Unit tests for [TagRepositoryImpl] — Room-observation layer.
 *
 * Mutation methods (addTagToBook, removeTagFromBook, renameTag, deleteTag) dispatch
 * through the [RpcChannel] for [TagService] and are covered by end-to-end jvmTest
 * integration tests instead. This file focuses on the Room observation layer, which is
 * fully testable with mocks.
 */
class TagRepositoryImplTest :
    FunSpec({

        fun repo(
            tagDao: TagDao = mock(),
            bookTagDao: BookTagDao = mock(),
            service: TagService = mock(),
        ) = TagRepositoryImpl(
            channel = RpcChannel.forTest(service),
            tagDao = tagDao,
            bookTagDao = bookTagDao,
            offlineEditor = noopOfflineEditor(),
        )

        fun tagEntity(
            id: String,
            name: String,
            slug: String,
        ) = TagEntity(id = id, name = name, slug = slug, revision = 1L, updatedAt = 100L)

        fun bookTagEntity(
            bookId: String,
            tagId: String,
        ) = BookTagEntity(bookId = bookId, tagId = tagId, syncId = "$bookId:$tagId", createdAt = 1L)

        // ── observeAllTags ────────────────────────────────────────────────────

        test("observeAllTags delegates to TagDao.observeAll and maps to domain") {
            runTest {
                val dao = mock<TagDao>()
                every { dao.observeAll() } returns
                    flowOf(
                        listOf(tagEntity("t1", "Sci-Fi", "sci-fi"), tagEntity("t2", "Fantasy", "fantasy")),
                    )
                val result = repo(tagDao = dao).observeAllTags().first()
                result.map { it.id } shouldContainExactly listOf("t1", "t2")
                result.map { it.name } shouldContainExactly listOf("Sci-Fi", "Fantasy")
                result.map { it.slug } shouldContainExactly listOf("sci-fi", "fantasy")
            }
        }

        test("observeAllTags emits empty list when dao emits none") {
            runTest {
                val dao = mock<TagDao>()
                every { dao.observeAll() } returns flowOf(emptyList())
                repo(tagDao = dao).observeAllTags().first().shouldBeEmpty()
            }
        }

        // ── observeAll (compat alias) ─────────────────────────────────────────

        test("observeAll is an alias for observeAllTags") {
            runTest {
                val dao = mock<TagDao>()
                every { dao.observeAll() } returns
                    flowOf(
                        listOf(tagEntity("t1", "Sci-Fi", "sci-fi")),
                    )
                val result = repo(tagDao = dao).observeAll().first()
                result.map { it.id } shouldContainExactly listOf("t1")
            }
        }

        // ── observeTagsForBook ────────────────────────────────────────────────

        test("observeTagsForBook delegates to TagDao.observeForBook with the raw bookId") {
            runTest {
                val dao = mock<TagDao>()
                every { dao.observeForBook("b1") } returns
                    flowOf(
                        listOf(tagEntity("t1", "Sci-Fi", "sci-fi")),
                    )
                val result = repo(tagDao = dao).observeTagsForBook("b1").first()
                result.map { it.id } shouldContainExactly listOf("t1")
            }
        }

        test("observeTagsForBook emits empty list when book has no tags") {
            runTest {
                val dao = mock<TagDao>()
                every { dao.observeForBook("b1") } returns flowOf(emptyList())
                repo(tagDao = dao).observeTagsForBook("b1").first().shouldBeEmpty()
            }
        }

        // ── observeById ───────────────────────────────────────────────────────

        test("observeById emits tag when DAO returns entity") {
            runTest {
                val dao = mock<TagDao>()
                every { dao.observeById("t1") } returns flowOf(tagEntity("t1", "Sci-Fi", "sci-fi"))
                val result = repo(tagDao = dao).observeById("t1").first()
                result shouldNotBe null
                result!!.id shouldBe "t1"
                result.name shouldBe "Sci-Fi"
            }
        }

        test("observeById emits null when DAO returns null") {
            runTest {
                val dao = mock<TagDao>()
                every { dao.observeById("missing") } returns flowOf(null)
                repo(tagDao = dao).observeById("missing").first() shouldBe null
            }
        }

        // ── observeBookIdsForTag ──────────────────────────────────────────────

        test("observeBookIdsForTag delegates to BookTagDao.observeForTag and extracts bookIds") {
            runTest {
                val bookTagDao = mock<BookTagDao>()
                every { bookTagDao.observeForTag("t1") } returns
                    flowOf(
                        listOf(bookTagEntity("b1", "t1"), bookTagEntity("b2", "t1")),
                    )
                val result = repo(bookTagDao = bookTagDao).observeBookIdsForTag("t1").first()
                result shouldContainExactly listOf("b1", "b2")
            }
        }

        test("observeBookIdsForTag emits empty list when no books have the tag") {
            runTest {
                val bookTagDao = mock<BookTagDao>()
                every { bookTagDao.observeForTag("t1") } returns flowOf(emptyList())
                repo(bookTagDao = bookTagDao).observeBookIdsForTag("t1").first().shouldBeEmpty()
            }
        }

        // ── domain mapping ────────────────────────────────────────────────────

        test("toDomain preserves id, name, and slug from entity") {
            runTest {
                val dao = mock<TagDao>()
                every { dao.observeAll() } returns
                    flowOf(
                        listOf(TagEntity(id = "x", name = "Found Family", slug = "found-family", revision = 7L, updatedAt = 999L)),
                    )
                val tag = repo(tagDao = dao).observeAllTags().first().first()
                tag shouldBe Tag(id = "x", name = "Found Family", slug = "found-family")
            }
        }

        test("Tag.displayName() derives from slug for backward-compat callers") {
            runTest {
                val dao = mock<TagDao>()
                every { dao.observeAll() } returns
                    flowOf(
                        listOf(tagEntity("t1", "Found Family", "found-family")),
                    )
                val tag = repo(tagDao = dao).observeAllTags().first().first()
                tag.displayName() shouldBe "Found Family"
            }
        }
    })
