package com.calypsan.listenup.client.data.sync

import com.calypsan.listenup.api.BookRatingService
import com.calypsan.listenup.api.BookService
import com.calypsan.listenup.api.CollectionService
import com.calypsan.listenup.api.ContributorService
import com.calypsan.listenup.api.EntityService
import com.calypsan.listenup.api.GenreService
import com.calypsan.listenup.api.MoodService
import com.calypsan.listenup.api.NotificationService
import com.calypsan.listenup.api.PlaybackService
import com.calypsan.listenup.api.ProfileService
import com.calypsan.listenup.api.SeriesService
import com.calypsan.listenup.api.ShelfService
import com.calypsan.listenup.api.TagService
import com.calypsan.listenup.api.UserPreferencesService
import com.calypsan.listenup.api.WorldEventService
import com.calypsan.listenup.api.dto.BookMoodMutation
import com.calypsan.listenup.api.dto.BookMutation
import com.calypsan.listenup.api.dto.BookRatingMutation
import com.calypsan.listenup.api.dto.BookTagMutation
import com.calypsan.listenup.api.dto.CollectionBookMutation
import com.calypsan.listenup.api.dto.CollectionMutation
import com.calypsan.listenup.api.dto.ContributorMutation
import com.calypsan.listenup.api.dto.GenreMutation
import com.calypsan.listenup.api.dto.NotificationMutation
import com.calypsan.listenup.api.dto.RateBookRequest
import com.calypsan.listenup.api.dto.SeriesMutation
import com.calypsan.listenup.api.dto.ShelfBookMutation
import com.calypsan.listenup.api.dto.ShelfMutation
import com.calypsan.listenup.api.dto.TagMutation
import com.calypsan.listenup.api.dto.entity.EntityMutation
import com.calypsan.listenup.api.result.map
import com.calypsan.listenup.api.result.onSuccess
import com.calypsan.listenup.client.data.remote.RpcChannel
import com.calypsan.listenup.client.data.sync.domains.OutboxChannel
import com.calypsan.listenup.client.data.sync.domains.OutboxChannels
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.CollectionId
import com.calypsan.listenup.core.ContributorId
import com.calypsan.listenup.core.EntityId
import com.calypsan.listenup.core.GenreId
import com.calypsan.listenup.core.MoodId
import com.calypsan.listenup.core.SeriesId
import com.calypsan.listenup.core.ShelfId
import com.calypsan.listenup.core.TagId
import com.calypsan.listenup.core.currentEpochMilliseconds
import io.github.oshai.kotlinlogging.KotlinLogging

/** Logs position writes the server superseded — see [describeSupersededPositionWrite]. */
private val positionLogger = KotlinLogging.logger("PositionOutbox")

/**
 * Outbox sender bindings for playback telemetry, user preferences, profile, and notifications.
 */
internal fun playbackAndAccountOutboxBindings(
    playbackChannel: RpcChannel<PlaybackService>,
    userPreferencesChannel: RpcChannel<UserPreferencesService>,
    profileChannel: RpcChannel<ProfileService>,
    notificationChannel: RpcChannel<NotificationService>,
): List<Pair<OutboxChannel<*>, PendingOperationSender>> =
    listOf(
        outboxBinding(OutboxChannels.Positions) { _, request ->
            playbackChannel.call { it.recordPosition(request) }.onSuccess { result ->
                describeSupersededPositionWrite(request, result, currentEpochMilliseconds())
                    ?.let { line -> positionLogger.warn { line } }
            }
        },
        outboxBinding(OutboxChannels.ListeningEvents) { _, request ->
            playbackChannel.call { it.recordListeningEvent(request) }
        },
        outboxBinding(OutboxChannels.Preferences) { _, patch ->
            userPreferencesChannel.call { it.updateMyPreferences(patch) }
        },
        outboxBinding(OutboxChannels.Profile) { _, patch ->
            profileChannel.call { it.updateMyProfile(patch) }
        },
        // The op's entityId is the notification id; the mutation carries it too.
        outboxBinding(OutboxChannels.Notifications) { _, mutation ->
            when (mutation) {
                is NotificationMutation.MarkRead -> {
                    notificationChannel.call { it.markRead(mutation.notificationId) }
                }
            }
        },
    )

/**
 * Outbox sender bindings for library catalog entities (books, series, contributors, genres, tags).
 */
internal fun catalogOutboxBindings(
    bookChannel: RpcChannel<BookService>,
    collectionChannel: RpcChannel<CollectionService>,
    seriesChannel: RpcChannel<SeriesService>,
    contributorChannel: RpcChannel<ContributorService>,
    genreChannel: RpcChannel<GenreService>,
    tagChannel: RpcChannel<TagService>,
): List<Pair<OutboxChannel<*>, PendingOperationSender>> =
    listOf(
        outboxBinding(OutboxChannels.Books) { id, mutation ->
            val bookId = BookId(id)
            when (mutation) {
                is BookMutation.Update -> {
                    bookChannel.call { it.updateBook(bookId, mutation.patch) }
                }

                is BookMutation.SetContributors -> {
                    bookChannel.call { it.setBookContributors(bookId, mutation.contributors) }
                }

                is BookMutation.SetSeries -> {
                    bookChannel.call { it.setBookSeries(bookId, mutation.series) }
                }

                is BookMutation.SetGenres -> {
                    bookChannel.call { it.setBookGenres(bookId, mutation.genres) }
                }

                is BookMutation.SetChapters -> {
                    bookChannel.call { it.setBookChapters(bookId, mutation.chapters) }
                }

                is BookMutation.SetTierLabels -> {
                    bookChannel.call {
                        it.setBookTierLabels(bookId, mutation.bookTierLabel, mutation.partTierLabel)
                    }
                }

                // Rides the books channel for FIFO + shield, but dispatches to CollectionService.
                is BookMutation.SetCollections -> {
                    collectionChannel.call {
                        it.setBookCollections(bookId, mutation.collectionIds.map(::CollectionId))
                    }
                }

                is BookMutation.DeleteCover -> {
                    bookChannel.call { it.deleteBookCover(bookId) }
                }
            }
        },
        // The op's entityId is the seriesId; the sender reconstructs the SeriesId from it.
        outboxBinding(OutboxChannels.Series) { id, mutation ->
            when (mutation) {
                is SeriesMutation.Update -> {
                    seriesChannel.call { it.updateSeries(SeriesId(id), mutation.patch) }
                }

                is SeriesMutation.Delete -> {
                    seriesChannel.call { it.deleteSeries(SeriesId(id)) }.orSuccessIfNotFound()
                }
            }
        },
        // The op's entityId is the contributorId; the sender reconstructs the ContributorId from it.
        outboxBinding(OutboxChannels.Contributors) { id, mutation ->
            when (mutation) {
                is ContributorMutation.Update -> {
                    contributorChannel.call { it.updateContributor(ContributorId(id), mutation.patch) }
                }

                is ContributorMutation.Delete -> {
                    contributorChannel
                        .call { service ->
                            service.deleteContributor(
                                ContributorId(id),
                            )
                        }.orSuccessIfNotFound()
                }
            }
        },
        // The op's entityId is the genreId; the sender reconstructs the GenreId from it.
        outboxBinding(OutboxChannels.Genres) { id, mutation ->
            when (mutation) {
                is GenreMutation.Update -> {
                    genreChannel.call { it.updateGenre(GenreId(id), mutation.patch) }
                }

                is GenreMutation.Delete -> {
                    genreChannel.call { it.deleteGenre(GenreId(id)) }.orSuccessIfNotFound()
                }
            }
        },
        // The op's entityId is the tagId; the sender reconstructs the TagId from it.
        outboxBinding(OutboxChannels.Tags) { id, mutation ->
            when (mutation) {
                is TagMutation.Rename -> tagChannel.call { it.renameTag(TagId(id), mutation.newName) }
                is TagMutation.Delete -> tagChannel.call { it.deleteTag(TagId(id)) }.orSuccessIfNotFound()
            }
        },
        // The op's entityId is the "$bookId:$tagId" envelope; the sender reads the ids from the payload.
        // Add re-dispatches find-or-create by name (resolves back to the same existing tag).
        outboxBinding(OutboxChannels.BookTags) { _, mutation ->
            when (mutation) {
                is BookTagMutation.Add -> {
                    tagChannel.call { it.addTagToBook(BookId(mutation.bookId), mutation.name) }
                }

                is BookTagMutation.Remove -> {
                    tagChannel.call { it.removeTagFromBook(BookId(mutation.bookId), TagId(mutation.tagId)) }
                }
            }
        },
    )

/**
 * Outbox sender bindings for reader curation and story-world mutations (moods, ratings, shelves,
 * collections, entities, and world events).
 */
internal fun curationOutboxBindings(
    moodChannel: RpcChannel<MoodService>,
    bookRatingChannel: RpcChannel<BookRatingService>,
    shelfChannel: RpcChannel<ShelfService>,
    collectionChannel: RpcChannel<CollectionService>,
    entityChannel: RpcChannel<EntityService>,
    worldEventChannel: RpcChannel<WorldEventService>,
): List<Pair<OutboxChannel<*>, PendingOperationSender>> =
    listOf(
        outboxBinding(OutboxChannels.BookMoods) { _, mutation ->
            when (mutation) {
                is BookMoodMutation.Add -> {
                    moodChannel.call { it.addMoodToBook(BookId(mutation.bookId), mutation.name) }
                }

                is BookMoodMutation.Remove -> {
                    moodChannel.call { service ->
                        service.removeMoodFromBook(
                            BookId(mutation.bookId),
                            MoodId(mutation.moodId),
                        )
                    }
                }
            }
        },
        outboxBinding(OutboxChannels.BookRatings) { _, mutation ->
            when (mutation) {
                is BookRatingMutation.Set -> {
                    bookRatingChannel.call { service ->
                        service.rate(
                            BookId(mutation.bookId),
                            RateBookRequest(
                                candidateId = mutation.candidateId,
                                halfStars = mutation.halfStars,
                                note = mutation.note,
                            ),
                        )
                    }
                }

                is BookRatingMutation.Clear -> {
                    bookRatingChannel.call { it.clearRating(BookId(mutation.bookId)) }
                }
            }
        },
        // The op's entityId is the shelfId; the sender reconstructs the ShelfId from it.
        outboxBinding(OutboxChannels.Shelves) { id, mutation ->
            when (mutation) {
                is ShelfMutation.Update -> {
                    shelfChannel.call { service ->
                        service.updateShelf(
                            shelfId = ShelfId(id),
                            name = mutation.name,
                            description = mutation.description,
                            isPrivate = mutation.isPrivate,
                        )
                    }
                }

                is ShelfMutation.Delete -> {
                    shelfChannel.call { it.deleteShelf(ShelfId(id)) }.orSuccessIfNotFound()
                }
            }
        },
        // The op's entityId is the "$shelfId:$bookId" envelope; the sender reads the ids from the payload.
        outboxBinding(OutboxChannels.ShelfBooks) { _, mutation ->
            when (mutation) {
                is ShelfBookMutation.Add -> {
                    shelfChannel.call {
                        it.addBookToShelf(ShelfId(mutation.shelfId), BookId(mutation.bookId))
                    }
                }

                is ShelfBookMutation.Remove -> {
                    shelfChannel.call {
                        it.removeBookFromShelf(ShelfId(mutation.shelfId), BookId(mutation.bookId))
                    }
                }

                is ShelfBookMutation.Reorder -> {
                    shelfChannel.call { service ->
                        service.reorderShelfBooks(
                            ShelfId(mutation.shelfId),
                            mutation.orderedBookIds.map(::BookId),
                        )
                    }
                }
            }
        },
        // The op's entityId is the collectionId; the sender reconstructs the CollectionId from it.
        outboxBinding(OutboxChannels.Collections) { id, mutation ->
            when (mutation) {
                is CollectionMutation.Rename -> {
                    collectionChannel.call { it.renameCollection(CollectionId(id), mutation.newName) }
                }

                is CollectionMutation.Delete -> {
                    collectionChannel.call { it.deleteCollection(CollectionId(id)) }.orSuccessIfNotFound()
                }
            }
        },
        // The op's entityId is the "$collectionId:$bookId" envelope; the sender reads the ids from the payload.
        outboxBinding(OutboxChannels.CollectionBooks) { _, mutation ->
            when (mutation) {
                is CollectionBookMutation.Add -> {
                    collectionChannel.call {
                        it.addBookToCollection(CollectionId(mutation.collectionId), BookId(mutation.bookId))
                    }
                }

                is CollectionBookMutation.Remove -> {
                    collectionChannel.call { service ->
                        service.removeBookFromCollection(
                            CollectionId(mutation.collectionId),
                            BookId(mutation.bookId),
                        )
                    }
                }
            }
        },
        // The op's entityId is the entity id. Upsert carries the whole snapshot (create and edit alike).
        // Both branches fold NotFound: a re-fired delete finds the entity gone, and an edit queued
        // behind someone else's delete finds a tombstone — either way the delete has already won.
        outboxBinding(OutboxChannels.Entities) { id, mutation ->
            when (mutation) {
                is EntityMutation.Upsert -> {
                    entityChannel.call { it.upsertEntity(mutation.upsert) }.map { }.orSuccessIfNotFound()
                }

                is EntityMutation.Delete -> {
                    entityChannel.call { it.deleteEntity(EntityId(id)) }.orSuccessIfNotFound()
                }
            }
        },
        // One outbox row is one EventsBatch, applied atomically. A lone edit or delete folds NotFound
        // (a delete has already won); a batch of several stands or falls together.
        outboxBinding(OutboxChannels.WorldEvents) { _, batch ->
            worldEventChannel.call { it.applyBatch(batch) }.orSuccessIfSingleOpNotFound(batch)
        },
    )
