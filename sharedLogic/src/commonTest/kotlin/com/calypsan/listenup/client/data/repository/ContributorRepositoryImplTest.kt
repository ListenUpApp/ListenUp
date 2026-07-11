package com.calypsan.listenup.client.data.repository

import com.calypsan.listenup.core.Timestamp
import com.calypsan.listenup.client.data.local.db.BookDao
import com.calypsan.listenup.client.data.local.db.ContributorDao
import com.calypsan.listenup.client.data.local.db.ContributorEntity
import com.calypsan.listenup.client.data.local.db.ContributorWithAliases
import com.calypsan.listenup.client.data.local.db.SearchDao
import com.calypsan.listenup.client.data.remote.ContributorApiContract
import com.calypsan.listenup.api.ContributorService
import com.calypsan.listenup.api.sync.ContributorSyncPayload
import com.calypsan.listenup.client.data.remote.RpcChannel
import com.calypsan.listenup.client.data.remote.forTest
import com.calypsan.listenup.client.data.sync.SyncDomainHandler
import com.calypsan.listenup.client.domain.repository.ImageStorage
import com.calypsan.listenup.client.domain.repository.NetworkMonitor
import dev.mokkery.MockMode
import dev.mokkery.answering.returns
import dev.mokkery.every
import dev.mokkery.everySuspend
import dev.mokkery.mock
import dev.mokkery.verify
import dev.mokkery.verifySuspend
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest

/**
 * Tests for ContributorRepositoryImpl.
 *
 * Verifies:
 * - All ContributorRepository interface methods are correctly delegated to ContributorDao
 * - ContributorEntity to Contributor domain model conversion
 * - Proper handling of empty results and null cases
 * - Flow emissions for reactive queries
 * - Alias parsing from comma-separated string to list
 */
class ContributorRepositoryImplTest :
    FunSpec({

        // ========== Test Fixtures ==========

        fun createMockDao(): ContributorDao = mock<ContributorDao>(MockMode.autoUnit)

        fun createRepository(dao: ContributorDao): ContributorRepositoryImpl {
            val networkMonitor = mock<NetworkMonitor>()
            // Stub isOnline() to false so the cache-miss RPC path is never triggered
            // in these unit tests; the jvmTest RPC-fallback tests exercise that path.
            every { networkMonitor.isOnline() } returns false
            return ContributorRepositoryImpl(
                contributorDao = dao,
                bookDao = mock<BookDao>(MockMode.autoUnit),
                searchDao = mock<SearchDao>(MockMode.autoUnit),
                api = mock<ContributorApiContract>(),
                networkMonitor = networkMonitor,
                imageStorage = mock<ImageStorage>(),
                channel = RpcChannel.forTest(mock<ContributorService>(MockMode.autoUnit)),
                contributorSyncHandler = mock<SyncDomainHandler<ContributorSyncPayload>>(MockMode.autoUnit),
            )
        }

        fun createTestContributorEntity(
            id: String = "contrib-1",
            name: String = "Brandon Sanderson",
            description: String? = null,
            imagePath: String? = null,
            imageBlurHash: String? = null,
            website: String? = null,
            birthDate: String? = null,
            deathDate: String? = null,
            createdAt: Long = 1000L,
            updatedAt: Long = 1000L,
        ): ContributorEntity =
            ContributorEntity(
                id =
                    com.calypsan.listenup.core
                        .ContributorId(id),
                name = name,
                description = description,
                imagePath = imagePath,
                imageBlurHash = imageBlurHash,
                website = website,
                birthDate = birthDate,
                deathDate = deathDate,
                createdAt = Timestamp(createdAt),
                updatedAt = Timestamp(updatedAt),
            )

        fun createTestContributorWithAliases(
            entity: ContributorEntity = createTestContributorEntity(),
            aliases: List<String> = emptyList(),
        ): ContributorWithAliases = ContributorWithAliases(contributor = entity, aliases = aliases)

        // ========== observeAll Tests ==========

        test("observeAll returns empty list when no contributors exist") {
            runTest {
                // Given
                val dao = createMockDao()
                every { dao.observeAllWithAliases() } returns flowOf(emptyList())
                val repository = createRepository(dao)

                // When
                val result = repository.observeAll().first()

                // Then
                (result.isEmpty()) shouldBe true
            }
        }

        test("observeAll transforms entities to domain models") {
            runTest {
                // Given
                val rows =
                    listOf(
                        createTestContributorWithAliases(
                            entity =
                                createTestContributorEntity(
                                    id = "contrib-1",
                                    name = "Brandon Sanderson",
                                    description = "Fantasy author",
                                ),
                        ),
                        createTestContributorWithAliases(
                            entity =
                                createTestContributorEntity(
                                    id = "contrib-2",
                                    name = "Michael Kramer",
                                    description = "Audiobook narrator",
                                ),
                        ),
                    )
                val dao = createMockDao()
                every { dao.observeAllWithAliases() } returns flowOf(rows)
                val repository = createRepository(dao)

                // When
                val result = repository.observeAll().first()

                // Then
                result.size shouldBe 2
                result[0].id.value shouldBe "contrib-1"
                result[0].name shouldBe "Brandon Sanderson"
                result[0].description shouldBe "Fantasy author"
                result[1].id.value shouldBe "contrib-2"
                result[1].name shouldBe "Michael Kramer"
                result[1].description shouldBe "Audiobook narrator"
            }
        }

        test("observeAll preserves entity order from dao") {
            runTest {
                // Given - entities ordered by name
                val rows =
                    listOf(
                        createTestContributorWithAliases(entity = createTestContributorEntity(id = "c1", name = "Aaron")),
                        createTestContributorWithAliases(entity = createTestContributorEntity(id = "c2", name = "Brandon")),
                        createTestContributorWithAliases(entity = createTestContributorEntity(id = "c3", name = "Cory")),
                    )
                val dao = createMockDao()
                every { dao.observeAllWithAliases() } returns flowOf(rows)
                val repository = createRepository(dao)

                // When
                val result = repository.observeAll().first()

                // Then
                result[0].name shouldBe "Aaron"
                result[1].name shouldBe "Brandon"
                result[2].name shouldBe "Cory"
            }
        }

        test("observeAll delegates to dao observeAllWithAliases") {
            runTest {
                // Given
                val dao = createMockDao()
                every { dao.observeAllWithAliases() } returns flowOf(emptyList())
                val repository = createRepository(dao)

                // When
                repository.observeAll().first()

                // Then
                verify { dao.observeAllWithAliases() }
            }
        }

        // ========== observeById Tests ==========

        test("observeById returns null when contributor not found") {
            runTest {
                // Given
                val dao = createMockDao()
                every { dao.observeByIdWithAliases("nonexistent") } returns flowOf(null)
                val repository = createRepository(dao)

                // When
                val result = repository.observeById("nonexistent").first()

                // Then
                result shouldBe null
            }
        }

        test("observeById returns contributor when found") {
            runTest {
                // Given
                val row =
                    createTestContributorWithAliases(
                        entity =
                            createTestContributorEntity(
                                id = "contrib-1",
                                name = "Stephen King",
                                description = "Horror author",
                                website = "https://stephenking.com",
                            ),
                    )
                val dao = createMockDao()
                every { dao.observeByIdWithAliases("contrib-1") } returns flowOf(row)
                val repository = createRepository(dao)

                // When
                val result = repository.observeById("contrib-1").first()

                // Then
                result.shouldNotBeNull()
                result.id.value shouldBe "contrib-1"
                result.name shouldBe "Stephen King"
                result.description shouldBe "Horror author"
                result.website shouldBe "https://stephenking.com"
            }
        }

        test("observeById transforms entity correctly") {
            runTest {
                // Given — aliases seeded in arbitrary order to prove repository-level sorting
                val row =
                    createTestContributorWithAliases(
                        entity =
                            createTestContributorEntity(
                                id = "contrib-42",
                                name = "Test Author",
                                description = "Test description",
                                imagePath = "/images/author.jpg",
                                imageBlurHash = "LEHV6nWB2yk8pyo0adR*.7kCMdnj",
                                website = "https://example.com",
                                birthDate = "1947-09-21",
                                deathDate = null,
                            ),
                        aliases = listOf("Richard Bachman", "John Swithen"),
                    )
                val dao = createMockDao()
                every { dao.observeByIdWithAliases("contrib-42") } returns flowOf(row)
                val repository = createRepository(dao)

                // When
                val result = repository.observeById("contrib-42").first()

                // Then
                result.shouldNotBeNull()
                result.id.value shouldBe "contrib-42"
                result.name shouldBe "Test Author"
                result.description shouldBe "Test description"
                result.imagePath shouldBe "/images/author.jpg"
                result.imageBlurHash shouldBe "LEHV6nWB2yk8pyo0adR*.7kCMdnj"
                result.website shouldBe "https://example.com"
                result.birthDate shouldBe "1947-09-21"
                result.deathDate shouldBe null
                // Sorted case-insensitively at the domain boundary
                result.aliases shouldBe listOf("John Swithen", "Richard Bachman")
            }
        }

        test("observeById delegates to dao with correct id") {
            runTest {
                // Given
                val dao = createMockDao()
                every { dao.observeByIdWithAliases("target-id") } returns flowOf(null)
                val repository = createRepository(dao)

                // When
                repository.observeById("target-id").first()

                // Then
                verify { dao.observeByIdWithAliases("target-id") }
            }
        }

        // ========== getById Tests ==========

        test("getById returns null when contributor not found") {
            runTest {
                // Given
                val dao = createMockDao()
                everySuspend { dao.getByIdWithAliases("nonexistent") } returns null
                val repository = createRepository(dao)

                // When
                val result = repository.getById("nonexistent")

                // Then
                result shouldBe null
            }
        }

        test("getById returns contributor when found") {
            runTest {
                // Given
                val row =
                    createTestContributorWithAliases(
                        entity =
                            createTestContributorEntity(
                                id = "contrib-1",
                                name = "Neil Gaiman",
                                description = "Fantasy/Horror author",
                            ),
                    )
                val dao = createMockDao()
                everySuspend { dao.getByIdWithAliases("contrib-1") } returns row
                val repository = createRepository(dao)

                // When
                val result = repository.getById("contrib-1")

                // Then
                result.shouldNotBeNull()
                result.id.value shouldBe "contrib-1"
                result.name shouldBe "Neil Gaiman"
                result.description shouldBe "Fantasy/Horror author"
            }
        }

        test("getById transforms all entity fields correctly") {
            runTest {
                // Given — seed in arbitrary order to prove repository-level sorting
                val row =
                    createTestContributorWithAliases(
                        entity =
                            createTestContributorEntity(
                                id = "complete-contrib",
                                name = "Complete Author",
                                description = "Full biography here",
                                imagePath = "/path/to/image.jpg",
                                imageBlurHash = "ABC123",
                                website = "https://author.com",
                                birthDate = "1960-01-15",
                                deathDate = "2020-12-31",
                            ),
                        aliases = listOf("Pen Name Two", "Pen Name One", "Pen Name Three"),
                    )
                val dao = createMockDao()
                everySuspend { dao.getByIdWithAliases("complete-contrib") } returns row
                val repository = createRepository(dao)

                // When
                val result = repository.getById("complete-contrib")

                // Then
                result.shouldNotBeNull()
                result.id.value shouldBe "complete-contrib"
                result.name shouldBe "Complete Author"
                result.description shouldBe "Full biography here"
                result.imagePath shouldBe "/path/to/image.jpg"
                result.imageBlurHash shouldBe "ABC123"
                result.website shouldBe "https://author.com"
                result.birthDate shouldBe "1960-01-15"
                result.deathDate shouldBe "2020-12-31"
                // Sorted case-insensitively at the domain boundary:
                // "Pen Name One" < "Pen Name Three" < "Pen Name Two" (O < Th < Tw)
                result.aliases.size shouldBe 3
                result.aliases[0] shouldBe "Pen Name One"
                result.aliases[1] shouldBe "Pen Name Three"
                result.aliases[2] shouldBe "Pen Name Two"
            }
        }

        test("getById passes correct id to dao") {
            runTest {
                // Given
                val dao = createMockDao()
                everySuspend { dao.getByIdWithAliases("target-id") } returns null
                val repository = createRepository(dao)

                // When
                repository.getById("target-id")

                // Then
                verifySuspend { dao.getByIdWithAliases("target-id") }
            }
        }

        // ========== observeByBookId Tests ==========

        test("observeByBookId returns empty list when book has no contributors") {
            runTest {
                // Given
                val dao = createMockDao()
                every { dao.observeByBookId("book-1") } returns flowOf(emptyList())
                val repository = createRepository(dao)

                // When
                val result = repository.observeByBookId("book-1").first()

                // Then
                (result.isEmpty()) shouldBe true
            }
        }

        test("observeByBookId returns contributors for book") {
            runTest {
                // Given
                val entities =
                    listOf(
                        createTestContributorEntity(id = "author-1", name = "Brandon Sanderson"),
                        createTestContributorEntity(id = "narrator-1", name = "Michael Kramer"),
                    )
                val dao = createMockDao()
                every { dao.observeByBookId("book-1") } returns flowOf(entities)
                val repository = createRepository(dao)

                // When
                val result = repository.observeByBookId("book-1").first()

                // Then
                result.size shouldBe 2
                result[0].name shouldBe "Brandon Sanderson"
                result[1].name shouldBe "Michael Kramer"
            }
        }

        test("observeByBookId transforms entities to domain models") {
            runTest {
                // Given
                val entity =
                    createTestContributorEntity(
                        id = "contrib-1",
                        name = "Kate Reading",
                        description = "Narrator",
                    )
                val dao = createMockDao()
                every { dao.observeByBookId("book-42") } returns flowOf(listOf(entity))
                val repository = createRepository(dao)

                // When
                val result = repository.observeByBookId("book-42").first()

                // Then — aliases come from the junction table and are not covered by this path;
                // the entity field will be removed entirely. Scope this test to the non-alias fields.
                result.size shouldBe 1
                result[0].id.value shouldBe "contrib-1"
                result[0].name shouldBe "Kate Reading"
                result[0].description shouldBe "Narrator"
            }
        }

        test("observeByBookId passes correct bookId to dao") {
            runTest {
                // Given
                val dao = createMockDao()
                every { dao.observeByBookId("my-book-id") } returns flowOf(emptyList())
                val repository = createRepository(dao)

                // When
                repository.observeByBookId("my-book-id").first()

                // Then
                verify { dao.observeByBookId("my-book-id") }
            }
        }

        // ========== getByBookId Tests ==========

        test("getByBookId returns empty list when book has no contributors") {
            runTest {
                // Given
                val dao = createMockDao()
                everySuspend { dao.getByBookId("book-1") } returns emptyList()
                val repository = createRepository(dao)

                // When
                val result = repository.getByBookId("book-1")

                // Then
                (result.isEmpty()) shouldBe true
            }
        }

        test("getByBookId returns contributors for book") {
            runTest {
                // Given
                val entities =
                    listOf(
                        createTestContributorEntity(id = "c1", name = "Author One"),
                        createTestContributorEntity(id = "c2", name = "Author Two"),
                        createTestContributorEntity(id = "c3", name = "Narrator One"),
                    )
                val dao = createMockDao()
                everySuspend { dao.getByBookId("book-1") } returns entities
                val repository = createRepository(dao)

                // When
                val result = repository.getByBookId("book-1")

                // Then
                result.size shouldBe 3
                result[0].name shouldBe "Author One"
                result[1].name shouldBe "Author Two"
                result[2].name shouldBe "Narrator One"
            }
        }

        test("getByBookId transforms entities to domain models") {
            runTest {
                // Given
                val entity =
                    createTestContributorEntity(
                        id = "contrib-5",
                        name = "Tim Gerard Reynolds",
                        description = "Audiobook narrator",
                        imagePath = "/images/tgr.jpg",
                    )
                val dao = createMockDao()
                everySuspend { dao.getByBookId("book-1") } returns listOf(entity)
                val repository = createRepository(dao)

                // When
                val result = repository.getByBookId("book-1")

                // Then
                result.size shouldBe 1
                result[0].id.value shouldBe "contrib-5"
                result[0].name shouldBe "Tim Gerard Reynolds"
                result[0].description shouldBe "Audiobook narrator"
                result[0].imagePath shouldBe "/images/tgr.jpg"
            }
        }

        test("getByBookId calls dao with correct bookId") {
            runTest {
                // Given
                val dao = createMockDao()
                everySuspend { dao.getByBookId("specific-book-id") } returns emptyList()
                val repository = createRepository(dao)

                // When
                repository.getByBookId("specific-book-id")

                // Then
                verifySuspend { dao.getByBookId("specific-book-id") }
            }
        }

        // ========== getBookIdsForContributor Tests ==========

        test("getBookIdsForContributor returns empty list when contributor has no books") {
            runTest {
                // Given
                val dao = createMockDao()
                everySuspend { dao.getBookIdsForContributor("contrib-1") } returns emptyList()
                val repository = createRepository(dao)

                // When
                val result = repository.getBookIdsForContributor("contrib-1")

                // Then
                (result.isEmpty()) shouldBe true
            }
        }

        test("getBookIdsForContributor returns book IDs for contributor") {
            runTest {
                // Given
                val bookIds = listOf("book-1", "book-2", "book-3")
                val dao = createMockDao()
                everySuspend { dao.getBookIdsForContributor("contrib-1") } returns bookIds
                val repository = createRepository(dao)

                // When
                val result = repository.getBookIdsForContributor("contrib-1")

                // Then
                result.size shouldBe 3
                result[0] shouldBe "book-1"
                result[1] shouldBe "book-2"
                result[2] shouldBe "book-3"
            }
        }

        test("getBookIdsForContributor passes correct contributorId to dao") {
            runTest {
                // Given
                val dao = createMockDao()
                everySuspend { dao.getBookIdsForContributor("specific-contrib-id") } returns emptyList()
                val repository = createRepository(dao)

                // When
                repository.getBookIdsForContributor("specific-contrib-id")

                // Then
                verifySuspend { dao.getBookIdsForContributor("specific-contrib-id") }
            }
        }

        // ========== observeBookIdsForContributor Tests ==========

        test("observeBookIdsForContributor returns empty list when contributor has no books") {
            runTest {
                // Given
                val dao = createMockDao()
                every { dao.observeBookIdsForContributor("contrib-1") } returns flowOf(emptyList())
                val repository = createRepository(dao)

                // When
                val result = repository.observeBookIdsForContributor("contrib-1").first()

                // Then
                (result.isEmpty()) shouldBe true
            }
        }

        test("observeBookIdsForContributor returns book IDs for contributor") {
            runTest {
                // Given
                val bookIds = listOf("book-a", "book-b")
                val dao = createMockDao()
                every { dao.observeBookIdsForContributor("contrib-1") } returns flowOf(bookIds)
                val repository = createRepository(dao)

                // When
                val result = repository.observeBookIdsForContributor("contrib-1").first()

                // Then
                result.size shouldBe 2
                result[0] shouldBe "book-a"
                result[1] shouldBe "book-b"
            }
        }

        test("observeBookIdsForContributor passes correct contributorId to dao") {
            runTest {
                // Given
                val dao = createMockDao()
                every { dao.observeBookIdsForContributor("target-contrib") } returns flowOf(emptyList())
                val repository = createRepository(dao)

                // When
                repository.observeBookIdsForContributor("target-contrib").first()

                // Then
                verify { dao.observeBookIdsForContributor("target-contrib") }
            }
        }

        // ========== Entity to Domain Conversion Tests ==========

        test("toDomain converts all entity fields correctly") {
            runTest {
                // Given
                val row =
                    createTestContributorWithAliases(
                        entity =
                            createTestContributorEntity(
                                id = "conversion-test",
                                name = "Full Name",
                                description = "Full description",
                                imagePath = "/full/path.jpg",
                                imageBlurHash = "BLURHASH",
                                website = "https://full.website.com",
                                birthDate = "1980-06-15",
                                deathDate = "2050-12-31",
                            ),
                        aliases = listOf("Alias Two", "Alias One"),
                    )
                val dao = createMockDao()
                everySuspend { dao.getByIdWithAliases("conversion-test") } returns row
                val repository = createRepository(dao)

                // When
                val result = repository.getById("conversion-test")

                // Then - verify all fields are mapped, aliases sorted case-insensitively
                result.shouldNotBeNull()
                result.id.value shouldBe "conversion-test"
                result.name shouldBe "Full Name"
                result.description shouldBe "Full description"
                result.imagePath shouldBe "/full/path.jpg"
                result.imageBlurHash shouldBe "BLURHASH"
                result.website shouldBe "https://full.website.com"
                result.birthDate shouldBe "1980-06-15"
                result.deathDate shouldBe "2050-12-31"
                result.aliases.size shouldBe 2
                result.aliases[0] shouldBe "Alias One"
                result.aliases[1] shouldBe "Alias Two"
            }
        }

        test("toDomain handles null optional fields") {
            runTest {
                // Given
                val row =
                    createTestContributorWithAliases(
                        entity =
                            createTestContributorEntity(
                                id = "minimal-contrib",
                                name = "Minimal Author",
                                description = null,
                                imagePath = null,
                                imageBlurHash = null,
                                website = null,
                                birthDate = null,
                                deathDate = null,
                            ),
                        aliases = emptyList(),
                    )
                val dao = createMockDao()
                everySuspend { dao.getByIdWithAliases("minimal-contrib") } returns row
                val repository = createRepository(dao)

                // When
                val result = repository.getById("minimal-contrib")

                // Then
                result.shouldNotBeNull()
                result.id.value shouldBe "minimal-contrib"
                result.name shouldBe "Minimal Author"
                result.description shouldBe null
                result.imagePath shouldBe null
                result.imageBlurHash shouldBe null
                result.website shouldBe null
                result.birthDate shouldBe null
                result.deathDate shouldBe null
                (result.aliases.isEmpty()) shouldBe true
            }
        }

        // ========== Alias Projection + Sorting Tests ==========
        // Aliases are sourced from the `contributor_aliases` junction table (projected by
        // ContributorWithAliases) and sorted case-insensitively at the domain boundary, since
        // Room's @Relation does not support ORDER BY on the projected child collection.

        test("toDomain returns single alias unchanged") {
            runTest {
                // Given
                val row =
                    createTestContributorWithAliases(
                        entity = createTestContributorEntity(),
                        aliases = listOf("Richard Bachman"),
                    )
                val dao = createMockDao()
                everySuspend { dao.getByIdWithAliases("contrib-1") } returns row
                val repository = createRepository(dao)

                // When
                val result = repository.getById("contrib-1")

                // Then
                result.shouldNotBeNull()
                result.aliases.size shouldBe 1
                result.aliases[0] shouldBe "Richard Bachman"
            }
        }

        test("toDomain sorts multiple aliases alphabetically") {
            runTest {
                // Given — seed in arbitrary order
                val row =
                    createTestContributorWithAliases(
                        entity = createTestContributorEntity(),
                        aliases = listOf("Alias Three", "Alias One", "Alias Two"),
                    )
                val dao = createMockDao()
                everySuspend { dao.getByIdWithAliases("contrib-1") } returns row
                val repository = createRepository(dao)

                // When
                val result = repository.getById("contrib-1")

                // Then
                result.shouldNotBeNull()
                result.aliases.size shouldBe 3
                result.aliases[0] shouldBe "Alias One"
                result.aliases[1] shouldBe "Alias Three"
                result.aliases[2] shouldBe "Alias Two"
            }
        }

        test("toDomain sorts aliases case-insensitively") {
            runTest {
                // Given — mixed case; case-insensitive sort must group regardless of case
                val row =
                    createTestContributorWithAliases(
                        entity = createTestContributorEntity(),
                        aliases = listOf("charlie", "Bravo", "alpha", "Delta"),
                    )
                val dao = createMockDao()
                everySuspend { dao.getByIdWithAliases("contrib-1") } returns row
                val repository = createRepository(dao)

                // When
                val result = repository.getById("contrib-1")

                // Then
                result.shouldNotBeNull()
                result.aliases shouldBe listOf("alpha", "Bravo", "charlie", "Delta")
            }
        }

        test("toDomain returns empty list when no aliases projected") {
            runTest {
                // Given
                val row =
                    createTestContributorWithAliases(
                        entity = createTestContributorEntity(),
                        aliases = emptyList(),
                    )
                val dao = createMockDao()
                everySuspend { dao.getByIdWithAliases("contrib-1") } returns row
                val repository = createRepository(dao)

                // When
                val result = repository.getById("contrib-1")

                // Then
                result.shouldNotBeNull()
                (result.aliases.isEmpty()) shouldBe true
            }
        }

        // ========== Multiple Items Tests ==========

        test("observeAll handles large number of contributors") {
            runTest {
                // Given
                val rows =
                    (1..100).map { i ->
                        createTestContributorWithAliases(
                            entity =
                                createTestContributorEntity(
                                    id = "contrib-$i",
                                    name = "Contributor $i",
                                ),
                        )
                    }
                val dao = createMockDao()
                every { dao.observeAllWithAliases() } returns flowOf(rows)
                val repository = createRepository(dao)

                // When
                val result = repository.observeAll().first()

                // Then
                result.size shouldBe 100
                result[0].id.value shouldBe "contrib-1"
                result[99].id.value shouldBe "contrib-100"
            }
        }

        test("getByBookId handles multiple contributors for book") {
            runTest {
                // Given
                val entities =
                    (1..10).map { i ->
                        createTestContributorEntity(
                            id = "contrib-$i",
                            name = "Contributor $i",
                        )
                    }
                val dao = createMockDao()
                everySuspend { dao.getByBookId("book-1") } returns entities
                val repository = createRepository(dao)

                // When
                val result = repository.getByBookId("book-1")

                // Then
                result.size shouldBe 10
            }
        }

        test("getBookIdsForContributor handles large number of books") {
            runTest {
                // Given
                val bookIds = (1..200).map { "book-$it" }
                val dao = createMockDao()
                everySuspend { dao.getBookIdsForContributor("prolific-author") } returns bookIds
                val repository = createRepository(dao)

                // When
                val result = repository.getBookIdsForContributor("prolific-author")

                // Then
                result.size shouldBe 200
                result[0] shouldBe "book-1"
                result[199] shouldBe "book-200"
            }
        }

        // ========== Domain Model Behavior Tests ==========

        test("domain model matchesName matches primary name case-insensitively") {
            runTest {
                // Given
                val row =
                    createTestContributorWithAliases(
                        entity = createTestContributorEntity(name = "Stephen King"),
                        aliases = emptyList(),
                    )
                val dao = createMockDao()
                everySuspend { dao.getByIdWithAliases("contrib-1") } returns row
                val repository = createRepository(dao)

                // When
                val result = repository.getById("contrib-1")

                // Then
                result.shouldNotBeNull()
                (result.matchesName("Stephen King")) shouldBe true
                (result.matchesName("STEPHEN KING")) shouldBe true
                (result.matchesName("stephen king")) shouldBe true
            }
        }

        test("domain model matchesName matches alias case-insensitively") {
            runTest {
                // Given
                val row =
                    createTestContributorWithAliases(
                        entity = createTestContributorEntity(name = "Stephen King"),
                        aliases = listOf("Richard Bachman"),
                    )
                val dao = createMockDao()
                everySuspend { dao.getByIdWithAliases("contrib-1") } returns row
                val repository = createRepository(dao)

                // When
                val result = repository.getById("contrib-1")

                // Then
                result.shouldNotBeNull()
                (result.matchesName("Richard Bachman")) shouldBe true
                (result.matchesName("RICHARD BACHMAN")) shouldBe true
                (result.matchesName("richard bachman")) shouldBe true
            }
        }

        test("domain model matchesName returns false for non-matching names") {
            runTest {
                // Given
                val row =
                    createTestContributorWithAliases(
                        entity = createTestContributorEntity(name = "Stephen King"),
                        aliases = listOf("Richard Bachman"),
                    )
                val dao = createMockDao()
                everySuspend { dao.getByIdWithAliases("contrib-1") } returns row
                val repository = createRepository(dao)

                // When
                val result = repository.getById("contrib-1")

                // Then
                result.shouldNotBeNull()
                (!result.matchesName("Neil Gaiman")) shouldBe true
                (!result.matchesName("Random Name")) shouldBe true
            }
        }

        // ========== B2a Enrichment Field Round-Trip Tests (M3, M4) ==========

        test("toDomain carries sortName and asin through entity→domain boundary") {
            runTest {
                val row =
                    createTestContributorWithAliases(
                        entity =
                            ContributorEntity(
                                id =
                                    com.calypsan.listenup.core
                                        .ContributorId("contrib-enriched"),
                                name = "Brandon Sanderson",
                                sortName = "Sanderson, Brandon",
                                asin = "B001H6UB8C",
                                description = "Fantasy author",
                                imagePath = null,
                                createdAt = Timestamp(1000L),
                                updatedAt = Timestamp(1000L),
                            ),
                        aliases = emptyList(),
                    )
                val dao = createMockDao()
                everySuspend { dao.getByIdWithAliases("contrib-enriched") } returns row
                val repository = createRepository(dao)

                val result = repository.getById("contrib-enriched")

                result.shouldNotBeNull()
                result.sortName shouldBe "Sanderson, Brandon"
                result.asin shouldBe "B001H6UB8C"
            }
        }

        test("toDomain maps null sortName and asin as null") {
            runTest {
                val row =
                    createTestContributorWithAliases(
                        entity =
                            createTestContributorEntity(
                                id = "contrib-bare",
                                name = "Bare Author",
                            ),
                        aliases = emptyList(),
                    )
                val dao = createMockDao()
                everySuspend { dao.getByIdWithAliases("contrib-bare") } returns row
                val repository = createRepository(dao)

                val result = repository.getById("contrib-bare")

                result.shouldNotBeNull()
                result.sortName shouldBe null
                result.asin shouldBe null
            }
        }

        test("ContributorEntity-only toDomain carries sortName and asin") {
            // The entity-only path (observeByBookId / getByBookId) must also carry
            // the enrichment fields — the ContributorEntity.toDomain() private fn.
            runTest {
                val entity =
                    ContributorEntity(
                        id =
                            com.calypsan.listenup.core
                                .ContributorId("contrib-role"),
                        name = "Michael Kramer",
                        sortName = "Kramer, Michael",
                        asin = "B0029Y7RL0",
                        description = "Narrator",
                        imagePath = null,
                        createdAt = Timestamp(1000L),
                        updatedAt = Timestamp(1000L),
                    )
                val dao = createMockDao()
                everySuspend { dao.getByBookId("book-1") } returns listOf(entity)
                val repository = createRepository(dao)

                val result = repository.getByBookId("book-1")

                result.size shouldBe 1
                result[0].sortName shouldBe "Kramer, Michael"
                result[0].asin shouldBe "B0029Y7RL0"
            }
        }
    })
