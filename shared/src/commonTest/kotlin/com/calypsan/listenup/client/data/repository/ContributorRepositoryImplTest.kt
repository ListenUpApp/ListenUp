package com.calypsan.listenup.client.data.repository

import com.calypsan.listenup.client.core.Timestamp
import com.calypsan.listenup.client.data.local.db.BookDao
import com.calypsan.listenup.client.data.local.db.ContributorDao
import com.calypsan.listenup.client.data.local.db.ContributorEntity
import com.calypsan.listenup.client.data.local.db.ContributorWithAliases
import com.calypsan.listenup.client.data.local.db.SearchDao
import com.calypsan.listenup.client.data.remote.ContributorApiContract
import com.calypsan.listenup.client.data.remote.MetadataApiContract
import com.calypsan.listenup.client.domain.repository.ImageStorage
import com.calypsan.listenup.client.domain.repository.NetworkMonitor
import dev.mokkery.MockMode
import dev.mokkery.answering.returns
import dev.mokkery.every
import dev.mokkery.everySuspend
import dev.mokkery.mock
import dev.mokkery.verify
import dev.mokkery.verifySuspend
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

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
class ContributorRepositoryImplTest {
    // ========== Test Fixtures ==========

    private fun createMockDao(): ContributorDao = mock<ContributorDao>(MockMode.autoUnit)

    private fun createRepository(dao: ContributorDao): ContributorRepositoryImpl =
        ContributorRepositoryImpl(
            contributorDao = dao,
            bookDao = mock<BookDao>(MockMode.autoUnit),
            searchDao = mock<SearchDao>(MockMode.autoUnit),
            api = mock<ContributorApiContract>(),
            metadataApi = mock<MetadataApiContract>(),
            networkMonitor = mock<NetworkMonitor>(),
            imageStorage = mock<ImageStorage>(),
        )

    private fun createTestContributorEntity(
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
                com.calypsan.listenup.client.core
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

    private fun createTestContributorWithAliases(
        entity: ContributorEntity = createTestContributorEntity(),
        aliases: List<String> = emptyList(),
    ): ContributorWithAliases = ContributorWithAliases(contributor = entity, aliases = aliases)

    // ========== observeAll Tests ==========

    @Test
    fun `observeAll returns empty list when no contributors exist`() =
        runTest {
            // Given
            val dao = createMockDao()
            every { dao.observeAllWithAliases() } returns flowOf(emptyList())
            val repository = createRepository(dao)

            // When
            val result = repository.observeAll().first()

            // Then
            assertTrue(result.isEmpty())
        }

    @Test
    fun `observeAll transforms entities to domain models`() =
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
            assertEquals(2, result.size)
            assertEquals("contrib-1", result[0].id.value)
            assertEquals("Brandon Sanderson", result[0].name)
            assertEquals("Fantasy author", result[0].description)
            assertEquals("contrib-2", result[1].id.value)
            assertEquals("Michael Kramer", result[1].name)
            assertEquals("Audiobook narrator", result[1].description)
        }

    @Test
    fun `observeAll preserves entity order from dao`() =
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
            assertEquals("Aaron", result[0].name)
            assertEquals("Brandon", result[1].name)
            assertEquals("Cory", result[2].name)
        }

    @Test
    fun `observeAll delegates to dao observeAllWithAliases`() =
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

    // ========== observeById Tests ==========

    @Test
    fun `observeById returns null when contributor not found`() =
        runTest {
            // Given
            val dao = createMockDao()
            every { dao.observeByIdWithAliases("nonexistent") } returns flowOf(null)
            val repository = createRepository(dao)

            // When
            val result = repository.observeById("nonexistent").first()

            // Then
            assertNull(result)
        }

    @Test
    fun `observeById returns contributor when found`() =
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
            assertNotNull(result)
            assertEquals("contrib-1", result.id.value)
            assertEquals("Stephen King", result.name)
            assertEquals("Horror author", result.description)
            assertEquals("https://stephenking.com", result.website)
        }

    @Test
    fun `observeById transforms entity correctly`() =
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
            assertNotNull(result)
            assertEquals("contrib-42", result.id.value)
            assertEquals("Test Author", result.name)
            assertEquals("Test description", result.description)
            assertEquals("/images/author.jpg", result.imagePath)
            assertEquals("LEHV6nWB2yk8pyo0adR*.7kCMdnj", result.imageBlurHash)
            assertEquals("https://example.com", result.website)
            assertEquals("1947-09-21", result.birthDate)
            assertNull(result.deathDate)
            // Sorted case-insensitively at the domain boundary
            assertEquals(listOf("John Swithen", "Richard Bachman"), result.aliases)
        }

    @Test
    fun `observeById delegates to dao with correct id`() =
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

    // ========== getById Tests ==========

    @Test
    fun `getById returns null when contributor not found`() =
        runTest {
            // Given
            val dao = createMockDao()
            everySuspend { dao.getByIdWithAliases("nonexistent") } returns null
            val repository = createRepository(dao)

            // When
            val result = repository.getById("nonexistent")

            // Then
            assertNull(result)
        }

    @Test
    fun `getById returns contributor when found`() =
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
            assertNotNull(result)
            assertEquals("contrib-1", result.id.value)
            assertEquals("Neil Gaiman", result.name)
            assertEquals("Fantasy/Horror author", result.description)
        }

    @Test
    fun `getById transforms all entity fields correctly`() =
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
            assertNotNull(result)
            assertEquals("complete-contrib", result.id.value)
            assertEquals("Complete Author", result.name)
            assertEquals("Full biography here", result.description)
            assertEquals("/path/to/image.jpg", result.imagePath)
            assertEquals("ABC123", result.imageBlurHash)
            assertEquals("https://author.com", result.website)
            assertEquals("1960-01-15", result.birthDate)
            assertEquals("2020-12-31", result.deathDate)
            // Sorted case-insensitively at the domain boundary:
            // "Pen Name One" < "Pen Name Three" < "Pen Name Two" (O < Th < Tw)
            assertEquals(3, result.aliases.size)
            assertEquals("Pen Name One", result.aliases[0])
            assertEquals("Pen Name Three", result.aliases[1])
            assertEquals("Pen Name Two", result.aliases[2])
        }

    @Test
    fun `getById passes correct id to dao`() =
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

    // ========== observeByBookId Tests ==========

    @Test
    fun `observeByBookId returns empty list when book has no contributors`() =
        runTest {
            // Given
            val dao = createMockDao()
            every { dao.observeByBookId("book-1") } returns flowOf(emptyList())
            val repository = createRepository(dao)

            // When
            val result = repository.observeByBookId("book-1").first()

            // Then
            assertTrue(result.isEmpty())
        }

    @Test
    fun `observeByBookId returns contributors for book`() =
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
            assertEquals(2, result.size)
            assertEquals("Brandon Sanderson", result[0].name)
            assertEquals("Michael Kramer", result[1].name)
        }

    @Test
    fun `observeByBookId transforms entities to domain models`() =
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
            // task 8 removes the entity field entirely. Scope this test to the non-alias fields.
            assertEquals(1, result.size)
            assertEquals("contrib-1", result[0].id.value)
            assertEquals("Kate Reading", result[0].name)
            assertEquals("Narrator", result[0].description)
        }

    @Test
    fun `observeByBookId passes correct bookId to dao`() =
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

    // ========== getByBookId Tests ==========

    @Test
    fun `getByBookId returns empty list when book has no contributors`() =
        runTest {
            // Given
            val dao = createMockDao()
            everySuspend { dao.getByBookId("book-1") } returns emptyList()
            val repository = createRepository(dao)

            // When
            val result = repository.getByBookId("book-1")

            // Then
            assertTrue(result.isEmpty())
        }

    @Test
    fun `getByBookId returns contributors for book`() =
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
            assertEquals(3, result.size)
            assertEquals("Author One", result[0].name)
            assertEquals("Author Two", result[1].name)
            assertEquals("Narrator One", result[2].name)
        }

    @Test
    fun `getByBookId transforms entities to domain models`() =
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
            assertEquals(1, result.size)
            assertEquals("contrib-5", result[0].id.value)
            assertEquals("Tim Gerard Reynolds", result[0].name)
            assertEquals("Audiobook narrator", result[0].description)
            assertEquals("/images/tgr.jpg", result[0].imagePath)
        }

    @Test
    fun `getByBookId calls dao with correct bookId`() =
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

    // ========== getBookIdsForContributor Tests ==========

    @Test
    fun `getBookIdsForContributor returns empty list when contributor has no books`() =
        runTest {
            // Given
            val dao = createMockDao()
            everySuspend { dao.getBookIdsForContributor("contrib-1") } returns emptyList()
            val repository = createRepository(dao)

            // When
            val result = repository.getBookIdsForContributor("contrib-1")

            // Then
            assertTrue(result.isEmpty())
        }

    @Test
    fun `getBookIdsForContributor returns book IDs for contributor`() =
        runTest {
            // Given
            val bookIds = listOf("book-1", "book-2", "book-3")
            val dao = createMockDao()
            everySuspend { dao.getBookIdsForContributor("contrib-1") } returns bookIds
            val repository = createRepository(dao)

            // When
            val result = repository.getBookIdsForContributor("contrib-1")

            // Then
            assertEquals(3, result.size)
            assertEquals("book-1", result[0])
            assertEquals("book-2", result[1])
            assertEquals("book-3", result[2])
        }

    @Test
    fun `getBookIdsForContributor passes correct contributorId to dao`() =
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

    // ========== observeBookIdsForContributor Tests ==========

    @Test
    fun `observeBookIdsForContributor returns empty list when contributor has no books`() =
        runTest {
            // Given
            val dao = createMockDao()
            every { dao.observeBookIdsForContributor("contrib-1") } returns flowOf(emptyList())
            val repository = createRepository(dao)

            // When
            val result = repository.observeBookIdsForContributor("contrib-1").first()

            // Then
            assertTrue(result.isEmpty())
        }

    @Test
    fun `observeBookIdsForContributor returns book IDs for contributor`() =
        runTest {
            // Given
            val bookIds = listOf("book-a", "book-b")
            val dao = createMockDao()
            every { dao.observeBookIdsForContributor("contrib-1") } returns flowOf(bookIds)
            val repository = createRepository(dao)

            // When
            val result = repository.observeBookIdsForContributor("contrib-1").first()

            // Then
            assertEquals(2, result.size)
            assertEquals("book-a", result[0])
            assertEquals("book-b", result[1])
        }

    @Test
    fun `observeBookIdsForContributor passes correct contributorId to dao`() =
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

    // ========== Entity to Domain Conversion Tests ==========

    @Test
    fun `toDomain converts all entity fields correctly`() =
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
            assertNotNull(result)
            assertEquals("conversion-test", result.id.value)
            assertEquals("Full Name", result.name)
            assertEquals("Full description", result.description)
            assertEquals("/full/path.jpg", result.imagePath)
            assertEquals("BLURHASH", result.imageBlurHash)
            assertEquals("https://full.website.com", result.website)
            assertEquals("1980-06-15", result.birthDate)
            assertEquals("2050-12-31", result.deathDate)
            assertEquals(2, result.aliases.size)
            assertEquals("Alias One", result.aliases[0])
            assertEquals("Alias Two", result.aliases[1])
        }

    @Test
    fun `toDomain handles null optional fields`() =
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
            assertNotNull(result)
            assertEquals("minimal-contrib", result.id.value)
            assertEquals("Minimal Author", result.name)
            assertNull(result.description)
            assertNull(result.imagePath)
            assertNull(result.imageBlurHash)
            assertNull(result.website)
            assertNull(result.birthDate)
            assertNull(result.deathDate)
            assertTrue(result.aliases.isEmpty())
        }

    // ========== Alias Projection + Sorting Tests ==========
    // Aliases are sourced from the `contributor_aliases` junction table (projected by
    // ContributorWithAliases) and sorted case-insensitively at the domain boundary, since
    // Room's @Relation does not support ORDER BY on the projected child collection.

    @Test
    fun `toDomain returns single alias unchanged`() =
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
            assertNotNull(result)
            assertEquals(1, result.aliases.size)
            assertEquals("Richard Bachman", result.aliases[0])
        }

    @Test
    fun `toDomain sorts multiple aliases alphabetically`() =
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
            assertNotNull(result)
            assertEquals(3, result.aliases.size)
            assertEquals("Alias One", result.aliases[0])
            assertEquals("Alias Three", result.aliases[1])
            assertEquals("Alias Two", result.aliases[2])
        }

    @Test
    fun `toDomain sorts aliases case-insensitively`() =
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
            assertNotNull(result)
            assertEquals(listOf("alpha", "Bravo", "charlie", "Delta"), result.aliases)
        }

    @Test
    fun `toDomain returns empty list when no aliases projected`() =
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
            assertNotNull(result)
            assertTrue(result.aliases.isEmpty())
        }

    // ========== Multiple Items Tests ==========

    @Test
    fun `observeAll handles large number of contributors`() =
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
            assertEquals(100, result.size)
            assertEquals("contrib-1", result[0].id.value)
            assertEquals("contrib-100", result[99].id.value)
        }

    @Test
    fun `getByBookId handles multiple contributors for book`() =
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
            assertEquals(10, result.size)
        }

    @Test
    fun `getBookIdsForContributor handles large number of books`() =
        runTest {
            // Given
            val bookIds = (1..200).map { "book-$it" }
            val dao = createMockDao()
            everySuspend { dao.getBookIdsForContributor("prolific-author") } returns bookIds
            val repository = createRepository(dao)

            // When
            val result = repository.getBookIdsForContributor("prolific-author")

            // Then
            assertEquals(200, result.size)
            assertEquals("book-1", result[0])
            assertEquals("book-200", result[199])
        }

    // ========== Domain Model Behavior Tests ==========

    @Test
    fun `domain model matchesName matches primary name case-insensitively`() =
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
            assertNotNull(result)
            assertTrue(result.matchesName("Stephen King"))
            assertTrue(result.matchesName("STEPHEN KING"))
            assertTrue(result.matchesName("stephen king"))
        }

    @Test
    fun `domain model matchesName matches alias case-insensitively`() =
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
            assertNotNull(result)
            assertTrue(result.matchesName("Richard Bachman"))
            assertTrue(result.matchesName("RICHARD BACHMAN"))
            assertTrue(result.matchesName("richard bachman"))
        }

    @Test
    fun `domain model matchesName returns false for non-matching names`() =
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
            assertNotNull(result)
            assertTrue(!result.matchesName("Neil Gaiman"))
            assertTrue(!result.matchesName("Random Name"))
        }
}
