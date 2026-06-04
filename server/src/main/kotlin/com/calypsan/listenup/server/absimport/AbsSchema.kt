package com.calypsan.listenup.server.absimport

/**
 * The Audiobookshelf (ABS) SQLite schema — every table and column name the import reader touches,
 * pinned in one place so a schema shift is a single-file change.
 *
 * ## Provenance
 * Derived from the Audiobookshelf **Sequelize models** (the source of truth for the on-disk SQLite
 * schema), `github.com/advplyr/audiobookshelf`, `server/models/`, reflecting ABS **v2.x**
 * (verified against `master` at the v2.35.x line — current stable as of 2026-06; the migration
 * changelog tops out at schema **v2.20.0** and the tables read here have been structurally stable
 * since). No real backup sample was available, so this is derived from source — the column names
 * below are taken verbatim from the model `init()` definitions.
 *
 * Sequelize pluralizes `modelName` into the SQL table name unless `tableName`/`freezeTableName`
 * is set; none of the models below override it, so every table name is the plural form.
 *
 * ### `users` — `server/models/User.js` (`modelName: 'user'` → table `users`)
 * - `id` (UUID, PK), `username` (STRING), `email` (STRING, nullable), `type` (STRING).
 * - `type` is one of `root | admin | user | guest`. Guests (`type = 'guest'`) are excluded —
 *   they hold no real listening identity to migrate.
 *
 * ### `libraryItems` — `server/models/LibraryItem.js` (`modelName: 'libraryItem'` → `libraryItems`)
 * - `id` (UUID, PK), `libraryId` (UUID FK, from `library.hasMany(LibraryItem)`),
 *   `mediaId` (UUID — polymorphic FK to `books.id` when `mediaType = 'book'`),
 *   `mediaType` (STRING — `book | podcast`), `path` (STRING), `relPath` (STRING).
 * - Podcasts (`mediaType = 'podcast'`) are excluded — ListenUp is audiobooks-only.
 *
 * ### `books` — `server/models/Book.js` (`modelName: 'book'` → `books`)
 * - `id` (UUID, PK), `title` (STRING), `subtitle` (STRING), `asin` (STRING), `isbn` (STRING).
 * - Joined to a library item via `libraryItems.mediaId = books.id`.
 * - Author names come through the `bookAuthors` join → `authors` (below); ABS also denormalizes
 *   `authorNamesFirstLast` onto `libraryItems` (added v2.20.0), but the join is the stable,
 *   version-independent source, so we read it from `authors.name`.
 *
 * ### `bookAuthors` — `server/models/BookAuthor.js` (`modelName: 'bookAuthor'` → `bookAuthors`)
 * - `id` (UUID, PK), `bookId` (UUID FK), `authorId` (UUID FK). Join table only.
 *
 * ### `authors` — `server/models/Author.js` (`modelName: 'author'` → `authors`)
 * - `id` (UUID, PK), `name` (STRING).
 *
 * ### `mediaProgresses` — `server/models/MediaProgress.js` (`modelName: 'mediaProgress'` → `mediaProgresses`)
 * - `id` (UUID, PK), `userId` (UUID FK, from `user.hasMany(MediaProgress)`),
 *   `mediaItemId` (UUID), `mediaItemType` (STRING), `currentTime` (FLOAT, **seconds**),
 *   `duration` (FLOAT, seconds), `isFinished` (BOOLEAN), `finishedAt` (DATE),
 *   `updatedAt` (DATE, the last-touched timestamp).
 * - **Item FK:** progress keys the item via `mediaItemId` + `mediaItemType`. For audiobooks
 *   `mediaItemType = 'book'` and `mediaItemId` is the **`books.id`** (NOT the library-item id —
 *   the library-item id only lives inside the `extraData` JSON blob, which we do not parse).
 *   So `bookItems()` and `progress()` both correlate on `books.id` (see [AbsModels]).
 * - **`progress` is NOT a stored column** — it is a Sequelize computed getter
 *   (`currentTime / duration`). We compute it in the reader rather than selecting it.
 * - **Timestamp unit:** `updatedAt` is `DataTypes.DATE`, which Sequelize stores in SQLite as an
 *   **ISO-8601 text string** (e.g. `2022-01-17T04:33:12.000Z`), NOT epoch millis. The reader
 *   parses it to epoch millis for [AbsModels.AbsProgress.lastUpdateMs].
 *
 * ### `playbackSessions` — `server/models/PlaybackSession.js` (`modelName: 'playbackSession'` → `playbackSessions`)
 * - Defined columns (from the model `init()`): `id` (UUID, PK), `mediaItemId` (UUID, nullable),
 *   `mediaItemType` (STRING — `book | podcastEpisode`), `displayTitle`, `displayAuthor`,
 *   `duration` (FLOAT, seconds), `playMethod` (INTEGER), `mediaPlayer` (STRING — the player/device
 *   label, e.g. `unknown`/an app id), `startTime` (FLOAT, seconds — position at session start),
 *   `currentTime` (FLOAT, seconds — position at session end), `serverVersion`, `coverPath`,
 *   `timeListening` (INTEGER, **seconds actually listened**), `mediaMetadata` (JSON), `date`,
 *   `dayOfWeek`, `extraData` (JSON — holds `libraryItemId`).
 * - **Implicit columns:** the model leaves Sequelize `timestamps` on (no override), so SQLite also
 *   has `createdAt` and `updatedAt` (`DataTypes.DATE` → ISO-8601 text). ABS maps the legacy
 *   `startedAt` onto `createdAt` (`getFromOld(): createdAt = oldPlaybackSession.startedAt`), so the
 *   reader uses **`createdAt`** as the session start timestamp.
 * - **Associated columns:** `userId` and `deviceId` are added by `belongsTo` associations
 *   (`user.hasMany(PlaybackSession)`, `device.hasMany(PlaybackSession)`), not the `init()` block —
 *   they are real FK columns on the table. We read `userId`.
 * - **Item FK:** like `mediaProgresses`, a `mediaItemType = 'book'` session keys the item via
 *   `mediaItemId = books.id`, so [AbsModels.AbsSession.itemId] correlates against the same key the
 *   Phase-2 matcher resolves (`books.id`). Podcast sessions (`mediaItemType = 'podcastEpisode'`) are
 *   excluded by the `WHERE mediaItemType = 'book'` filter.
 * - **ASSUMED — no playback-rate column:** the model defines NO `playbackRate`/`playbackSpeed`
 *   column, so the reader cannot recover the speed; it defaults to `1.0`. Flagged for verification
 *   against a real backup — if a future ABS version adds one, thread it here.
 * - **Device label:** `mediaPlayer` is the only stored device/player column. `deviceInfo` is NOT a
 *   column — it is reconstructed at runtime from the `device` association — so the reader reads
 *   `mediaPlayer` for the device label (nullable).
 *
 * **Verify against a real backup:** all session column names are source-derived (no real sample was
 * available); the ASSUMED no-playback-rate caveat in particular should be re-checked.
 */
internal object AbsSchema {
    /** The SQLite database file packed inside a `.audiobookshelf` backup zip. */
    const val DB_FILENAME = "absdatabase.sqlite"

    // ── Tables ──────────────────────────────────────────────────────────────
    const val USERS = "users"
    const val LIBRARY_ITEMS = "libraryItems"
    const val BOOKS = "books"
    const val BOOK_AUTHORS = "bookAuthors"
    const val AUTHORS = "authors"
    const val MEDIA_PROGRESSES = "mediaProgresses"
    const val PLAYBACK_SESSIONS = "playbackSessions"

    // ── users ───────────────────────────────────────────────────────────────
    const val USER_ID = "id"
    const val USER_USERNAME = "username"
    const val USER_EMAIL = "email"
    const val USER_TYPE = "type"

    /** ABS user `type` value for transient guest accounts, excluded from import. */
    const val USER_TYPE_GUEST = "guest"

    // ── libraryItems ──────────────────────────────────────────────────────────
    const val LIBRARY_ITEM_ID = "id"
    const val LIBRARY_ITEM_LIBRARY_ID = "libraryId"
    const val LIBRARY_ITEM_MEDIA_ID = "mediaId"
    const val LIBRARY_ITEM_MEDIA_TYPE = "mediaType"
    const val LIBRARY_ITEM_PATH = "path"
    const val LIBRARY_ITEM_REL_PATH = "relPath"

    /** ABS `mediaType` value for audiobooks — the only kind we import. */
    const val MEDIA_TYPE_BOOK = "book"

    // ── books ─────────────────────────────────────────────────────────────────
    const val BOOK_ID = "id"
    const val BOOK_TITLE = "title"
    const val BOOK_SUBTITLE = "subtitle"
    const val BOOK_ASIN = "asin"
    const val BOOK_ISBN = "isbn"

    // ── bookAuthors ───────────────────────────────────────────────────────────
    const val BOOK_AUTHOR_BOOK_ID = "bookId"
    const val BOOK_AUTHOR_AUTHOR_ID = "authorId"

    // ── authors ───────────────────────────────────────────────────────────────
    const val AUTHOR_ID = "id"
    const val AUTHOR_NAME = "name"

    // ── mediaProgresses ───────────────────────────────────────────────────────
    const val PROGRESS_USER_ID = "userId"
    const val PROGRESS_MEDIA_ITEM_ID = "mediaItemId"
    const val PROGRESS_MEDIA_ITEM_TYPE = "mediaItemType"
    const val PROGRESS_CURRENT_TIME = "currentTime"
    const val PROGRESS_DURATION = "duration"
    const val PROGRESS_IS_FINISHED = "isFinished"
    const val PROGRESS_UPDATED_AT = "updatedAt"

    // ── playbackSessions ──────────────────────────────────────────────────────
    const val SESSION_ID = "id"
    const val SESSION_USER_ID = "userId"
    const val SESSION_MEDIA_ITEM_ID = "mediaItemId"
    const val SESSION_MEDIA_ITEM_TYPE = "mediaItemType"
    const val SESSION_CURRENT_TIME = "currentTime"
    const val SESSION_START_TIME = "startTime"
    const val SESSION_TIME_LISTENING = "timeListening"

    /** Session start timestamp: ABS maps the legacy `startedAt` onto Sequelize's `createdAt`. */
    const val SESSION_STARTED_AT = "createdAt"

    /** The player/device label column (`deviceInfo` is reconstructed at runtime, not stored). */
    const val SESSION_DEVICE = "mediaPlayer"
}
