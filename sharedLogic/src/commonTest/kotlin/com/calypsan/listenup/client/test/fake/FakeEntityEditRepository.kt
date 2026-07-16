package com.calypsan.listenup.client.test.fake

import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.EntityKind
import com.calypsan.listenup.client.domain.model.Entity
import com.calypsan.listenup.client.domain.repository.EntityEditRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map

/**
 * In-memory fake of [EntityEditRepository]. Backed by a [MutableStateFlow] of the full entity
 * list so observers re-emit on every [setEntities] call, mirroring the Room-backed
 * implementation's reactive read model.
 *
 * Tests control ordering directly via [setEntities] — the fake does not itself sort, since the
 * real repository's `observeEntitiesForSeries`/`observeEntitiesForBook` already order by name at
 * the DAO layer.
 */
class FakeEntityEditRepository(
    initialEntities: List<Entity> = emptyList(),
) : EntityEditRepository {
    private val entities = MutableStateFlow(initialEntities)

    /** Test-injectable override for the next [deleteEntity] result; null (the default) succeeds. */
    var deleteEntityResult: AppResult<Unit>? = null

    override fun observeEntitiesForSeries(seriesId: String): Flow<List<Entity>> =
        entities.asStateFlow().map { list -> list.filter { it.homeSeriesId == seriesId } }

    override fun observeEntitiesForBook(bookId: String): Flow<List<Entity>> =
        entities.asStateFlow().map { list -> list.filter { it.homeBookId == bookId } }

    override fun observeEntity(id: String): Flow<Entity?> =
        entities.asStateFlow().map { list -> list.find { it.id == id } }

    override suspend fun createEntity(
        kind: EntityKind,
        name: String,
        homeSeriesId: String?,
        homeBookId: String?,
    ): AppResult<String> {
        val id = "fake-entity-${entities.value.size}"
        val newEntity = Entity(id = id, kind = kind, name = name, homeSeriesId = homeSeriesId, homeBookId = homeBookId)
        entities.value = entities.value + newEntity
        return AppResult.Success(id)
    }

    override suspend fun updateCore(
        id: String,
        name: String,
        imageRef: String?,
    ): AppResult<Unit> {
        entities.value =
            entities.value.map { if (it.id == id) it.copy(name = name, imageRef = imageRef) else it }
        return AppResult.Success(Unit)
    }

    override suspend fun deleteEntity(id: String): AppResult<Unit> {
        deleteEntityResult?.let { return it }
        entities.value = entities.value.filterNot { it.id == id }
        return AppResult.Success(Unit)
    }

    /** Test helper: replace the entity list, emitting to all observers. */
    fun setEntities(list: List<Entity>) {
        entities.value = list
    }
}
