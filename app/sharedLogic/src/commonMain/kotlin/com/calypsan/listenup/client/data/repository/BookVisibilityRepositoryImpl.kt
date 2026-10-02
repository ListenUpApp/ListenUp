package com.calypsan.listenup.client.data.repository

import com.calypsan.listenup.client.data.local.db.AdminUserRosterDao
import com.calypsan.listenup.client.data.local.db.CollectionBookDao
import com.calypsan.listenup.client.data.local.db.CollectionDao
import com.calypsan.listenup.client.data.local.db.CollectionShareDao
import com.calypsan.listenup.client.domain.model.BookVisibility
import com.calypsan.listenup.client.domain.repository.BookVisibilityRepository
import com.calypsan.listenup.client.domain.repository.UserRepository
import com.calypsan.listenup.core.BookId
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/**
 * Room-backed [BookVisibilityRepository]. Every read is behind [UserRepository.observeIsAdmin]:
 * a member's device gets the empty answer without a single collection query, and a role change
 * switches the reads on or off live. The per-book answer is [classifyBookVisibility] over three
 * Room flows, so a membership, hold, collection, share or roster write re-classifies with no refresh.
 *
 * The book's memberships and its hold are one statement ([CollectionDao.observeHoldingCollections]),
 * and the other two flows read tables a membership write never touches — so a release from the inbox
 * into a collection is one emission, straight from Held to Restricted, never a frame of Public.
 *
 * @property collectionDao A book's live collections, with its hold, in one read.
 * @property collectionBookDao The restricted-id set.
 * @property collectionShareDao Every live share.
 * @property adminUserRosterDao The admin-only roster: who the members are, and their roles.
 * @property userRepository Source of the admin gate.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class BookVisibilityRepositoryImpl(
    private val collectionDao: CollectionDao,
    private val collectionBookDao: CollectionBookDao,
    private val collectionShareDao: CollectionShareDao,
    private val adminUserRosterDao: AdminUserRosterDao,
    private val userRepository: UserRepository,
) : BookVisibilityRepository {
    override fun observeRestrictedBookIds(): Flow<Set<BookId>> =
        whenAdmin(otherwise = emptySet()) {
            collectionBookDao.observeRestrictedBookIds().map { ids -> ids.mapTo(mutableSetOf()) { BookId(it) } }
        }

    override fun observeBookVisibility(bookId: BookId): Flow<BookVisibility?> =
        whenAdmin(otherwise = null) {
            combine(
                collectionDao.observeHoldingCollections(bookId.value),
                collectionShareDao.observeLive(),
                adminUserRosterDao.observeAll(),
            ) { holding, shares, roster ->
                classifyBookVisibility(
                    isHeld = holding.any { it.isBookHeld },
                    holding = holding.map { it.collection },
                    shares = shares,
                    roster = roster,
                )
            }
        }

    private fun <T> whenAdmin(
        otherwise: T,
        adminFlow: () -> Flow<T>,
    ): Flow<T> =
        userRepository
            .observeIsAdmin()
            .distinctUntilChanged()
            .flatMapLatest { isAdmin -> if (isAdmin) adminFlow() else flowOf(otherwise) }
            .distinctUntilChanged()
}
