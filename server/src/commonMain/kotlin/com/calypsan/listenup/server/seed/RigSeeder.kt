package com.calypsan.listenup.server.seed

import com.calypsan.listenup.api.dto.activity.ActivityType
import com.calypsan.listenup.api.dto.auth.RegisterRequest
import com.calypsan.listenup.api.dto.auth.RegistrationPolicy
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.ListeningEventSyncPayload
import com.calypsan.listenup.api.sync.ShelfSyncPayload
import com.calypsan.listenup.server.auth.AuthServiceImpl
import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase
import com.calypsan.listenup.server.db.sqldelight.suspendTransaction
import com.calypsan.listenup.server.logging.loggerFor
import com.calypsan.listenup.server.media.ImageStore
import com.calypsan.listenup.server.services.ActiveSessionRepository
import com.calypsan.listenup.server.services.ActivityRecorder
import com.calypsan.listenup.server.services.ListeningEventRepository
import com.calypsan.listenup.server.services.PlaybackPositionRepository
import com.calypsan.listenup.server.services.PublicProfileMaintainer
import com.calypsan.listenup.server.settings.ServerSettingsRepository
import com.calypsan.listenup.server.sync.ShelfBookRepository
import com.calypsan.listenup.server.sync.ShelfRepository
import kotlinx.coroutines.delay
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readByteArray
import kotlinx.io.readString
import kotlin.time.Clock
import kotlin.uuid.Uuid

private val logger = loggerFor<RigSeeder>()

private const val MIN = 60_000L
private const val HOUR = 60 * MIN
private const val DAY = 24 * HOUR
private const val RIG_HOME = "/home/simonh/.listenup-homedisc-rig"
private const val TZ = "America/Edmonton"

/**
 * LOCAL RIG ONLY — never committed. Seeds the Home/Discover drive rig through the real repositories:
 * seven people, positions, a streak, leaderboard time across periods, shelves, activity, avatars, and two
 * live listeners kept alive for as long as the server runs.
 */
class RigSeeder(
    private val sql: ListenUpDatabase,
    private val auth: AuthServiceImpl,
    private val positions: PlaybackPositionRepository,
    private val events: ListeningEventRepository,
    private val shelves: ShelfRepository,
    private val shelfBooks: ShelfBookRepository,
    private val activity: ActivityRecorder,
    private val sessions: ActiveSessionRepository,
    private val avatars: ImageStore,
    private val profiles: PublicProfileMaintainer,
    private val settings: ServerSettingsRepository,
) : DomainSeeder {
    override val domainName: String = "homedisc-rig"
    override val order: Int = 0

    override suspend fun isAlreadySeeded(): Boolean = false

    private data class Account(val role: String, val name: String, val email: String, val password: String)

    private fun accounts(): List<Account> {
        val text = SystemFileSystem.source(Path("$RIG_HOME/rig-credentials.txt")).buffered().use { it.readString() }
        return text.lines().filter { it.isNotBlank() && !it.startsWith("#") }.map {
            val (role, name, email, password) = it.split("|")
            Account(role, name, email, password)
        }
    }

    private suspend fun userId(email: String): String? =
        suspendTransaction(sql) { sql.usersQueries.selectByEmailNormalized(email_normalized = email).executeAsOneOrNull()?.id }

    override suspend fun seed() {
        val accounts = accounts()
        val books = awaitBooks(expected = 15)
        if (userId(accounts.first().email) == null) {
            seedEverything(accounts, books)
        } else {
            logger.info { "rig: already seeded — keep-alive only" }
        }
        keepAlive(accounts, books)
    }

    private suspend fun awaitBooks(expected: Int): Map<String, String> {
        repeat(180) {
            val rows = suspendTransaction(sql) { sql.booksQueries.selectLiveIdsAndTitles().executeAsList() }
            if (rows.size >= expected) return rows.associate { it.title to it.id }
            delay(2_000)
        }
        val rows = suspendTransaction(sql) { sql.booksQueries.selectLiveIdsAndTitles().executeAsList() }
        logger.warn { "rig: only ${rows.size} books after waiting" }
        return rows.associate { it.title to it.id }
    }

    private fun Map<String, String>.id(prefix: String): String =
        entries.firstOrNull { it.key.startsWith(prefix) }?.value ?: error("rig: no book titled '$prefix…'")

    private suspend fun durationMs(bookId: String): Long =
        suspendTransaction(sql) { sql.booksQueries.durationForBook(bookId).executeAsOneOrNull() } ?: (5 * HOUR)

    @Suppress("LongMethod")
    private suspend fun seedEverything(
        accounts: List<Account>,
        books: Map<String, String>,
    ) {
        val now = Clock.System.now().toEpochMilliseconds()
        settings.setRegistrationPolicy(RegistrationPolicy.OPEN)
        val ids = mutableMapOf<String, String>()
        for (a in accounts) {
            val request = RegisterRequest(email = a.email, password = a.password, displayName = a.name)
            val result = if (a.role == "ROOT") auth.setupRoot(request) else auth.register(request)
            if (result is AppResult.Failure) logger.warn { "rig: ${a.email} not created — ${result.error.code}" }
            val id = userId(a.email) ?: error("rig: ${a.email} missing")
            ids[a.name.substringBefore(' ')] = id
            suspendTransaction(sql) { sql.usersQueries.updateTimezone(timezone = TZ, id = id) }
        }
        settings.setRegistrationPolicy(RegistrationPolicy.APPROVAL_QUEUE)

        // Avatars for everyone but Jonah (who keeps the generated initials).
        for (first in listOf("Rowan", "Priya", "Marcus", "Ines", "Theo", "Hana")) {
            val userId = ids.getValue(first)
            val bytes = SystemFileSystem.source(Path("$RIG_HOME/avatars-src/${first.lowercase()}.png")).buffered().use { it.readByteArray() }
            avatars.store(userId, bytes, "image/png")
            suspendTransaction(sql) {
                sql.usersQueries.updateAvatarTypeAndFrame(
                    avatar_type = "image",
                    avatar_updated_at = now,
                    avatar_frame = null,
                    updated_at = now,
                    id = userId,
                )
            }
            profiles.refresh(userId)
        }

        val lanternKeeper = books.id("The Lantern Keeper")
        val drownedLight = books.id("The Drowned Light")
        val harbour = books.id("A Harbour of Embers")
        val cartographer = books.id("The Cartographer")
        val sabbath = books.id("Sabbath")
        val zen = books.id("Zen Mind")
        val letGo = books.id("Let Go")
        val seer = books.id("The Seer")
        val littlePrince = books.id("The Little Prince")
        val velveteen = books.id("The Velveteen Rabbit")
        val pageant = books.id("The Best Christmas")
        val shadows = books.id("In Praise of Shadows")
        val artOfWar = books.id("The Art of War")
        val presence = books.id("Practice of the Presence")
        val cockroach = books.id("How to Hold a Cockroach")

        suspend fun progress(user: String, book: String, fraction: Double, agoMs: Long, startedAgoMs: Long = agoMs + 3 * DAY) {
            val uid = ids.getValue(user)
            val total = durationMs(book)
            positions.recordPosition(uid, book, (total * 0.01).toLong(), now - startedAgoMs, false, 1.0f, null)
            positions.recordPosition(uid, book, (total * fraction).toLong(), now - agoMs, false, 1.0f, null)
        }

        suspend fun finish(user: String, book: String, finishedAgoMs: Long, startedAgoMs: Long) {
            val uid = ids.getValue(user)
            val total = durationMs(book)
            positions.recordPosition(uid, book, (total * 0.02).toLong(), now - startedAgoMs, false, 1.0f, null)
            positions.recordPosition(
                uid, book, total, now - finishedAgoMs, true, 1.0f, null,
                finishedAt = now - finishedAgoMs, startedAt = now - startedAgoMs,
            )
        }

        suspend fun listen(user: String, book: String, daysAgo: Int, minutes: Int, hourOfDayOffsetMs: Long = 0L) {
            val uid = ids.getValue(user)
            // Today's sessions end an hour before now; earlier days end at the same clock time minus the offset.
            val endedAt = now - daysAgo * DAY - HOUR - hourOfDayOffsetMs
            val startedAt = endedAt - minutes * MIN
            val payload =
                ListeningEventSyncPayload(
                    id = Uuid.random().toString(),
                    bookId = book,
                    startPositionMs = 0L,
                    endPositionMs = minutes * MIN,
                    startedAt = startedAt,
                    endedAt = endedAt,
                    playbackSpeed = 1.0f,
                    tz = TZ,
                    deviceLabel = "Rig seeder",
                    revision = 0L,
                    updatedAt = 0L,
                    createdAt = 0L,
                    deletedAt = null,
                )
            val r = events.upsert(value = payload, clientOpId = null, userId = uid)
            if (r is AppResult.Failure) logger.warn { "rig: event failed for $user — ${r.error.code}" }
        }

        suspend fun shelf(user: String, name: String, private: Boolean, bookIds: List<String>, createdAgoMs: Long) {
            val uid = ids.getValue(user)
            val created = now - createdAgoMs
            val payload =
                ShelfSyncPayload(
                    id = Uuid.random().toString(),
                    name = name,
                    description = "",
                    isPrivate = private,
                    revision = 0L,
                    updatedAt = created,
                    createdAt = created,
                    deletedAt = null,
                )
            when (val r = shelves.upsert(payload, userId = uid)) {
                is AppResult.Success -> {
                    bookIds.forEach { shelfBooks.addBook(r.data.id, it, uid) }
                    if (!private) {
                        activity.record(
                            userId = uid, type = ActivityType.SHELF_CREATED, shelfId = r.data.id, shelfName = name,
                            occurredAt = created,
                        )
                    }
                }
                is AppResult.Failure -> logger.warn { "rig: shelf '$name' failed — ${r.error.code}" }
            }
        }

        // ── Rowan (root, the main user) ──────────────────────────────────────────────────────────
        finish("Rowan", littlePrince, finishedAgoMs = 24 * DAY, startedAgoMs = 27 * DAY)
        finish("Rowan", lanternKeeper, finishedAgoMs = 3 * DAY + 2 * HOUR, startedAgoMs = 16 * DAY)
        progress("Rowan", seer, 0.31, agoMs = DAY + 2 * HOUR, startedAgoMs = 9 * DAY)
        progress("Rowan", letGo, 0.16, agoMs = 20 * HOUR, startedAgoMs = 5 * DAY)
        progress("Rowan", cartographer, 0.58, agoMs = 10 * HOUR, startedAgoMs = 12 * DAY)
        progress("Rowan", zen, 0.2, agoMs = 6 * HOUR, startedAgoMs = 4 * DAY)
        progress("Rowan", sabbath, 0.31, agoMs = 3 * HOUR, startedAgoMs = 8 * DAY)
        progress("Rowan", drownedLight, 0.33, agoMs = 40 * MIN, startedAgoMs = 2 * DAY + 3 * HOUR)
        // A live 5-day streak (today and the four days before), a gap, and a longer 9-day best a few weeks back.
        listOf(0 to 52, 1 to 95, 2 to 38, 3 to 71, 4 to 24).forEach { (d, m) -> listen("Rowan", drownedLight, d, m) }
        (12..20).forEach { d -> listen("Rowan", if (d % 2 == 0) lanternKeeper else sabbath, d, 30 + (d * 7) % 50) }
        listOf(26, 33, 41, 55, 80, 120, 200).forEach { d -> listen("Rowan", littlePrince, d, 45) }
        shelf("Rowan", "Winter evenings", false, listOf(lanternKeeper, drownedLight, harbour, sabbath, velveteen), 20 * DAY)
        shelf("Rowan", "Quiet mornings", false, listOf(zen, letGo, shadows), 9 * DAY)
        shelf("Rowan", "Gifts for Mum", true, listOf(littlePrince, pageant), 6 * DAY)

        // ── Priya: the week's leader, live on the third Lantern book ────────────────────────────
        finish("Priya", lanternKeeper, finishedAgoMs = 6 * DAY, startedAgoMs = 14 * DAY)
        finish("Priya", drownedLight, finishedAgoMs = 2 * DAY, startedAgoMs = 6 * DAY - HOUR)
        progress("Priya", harbour, 0.12, agoMs = 2 * MIN, startedAgoMs = DAY + 4 * HOUR)
        (0..6).forEach { d -> listen("Priya", if (d < 2) harbour else drownedLight, d, 120 + d * 9) }
        (7..40 step 3).forEach { d -> listen("Priya", lanternKeeper, d, 80) }
        shelf("Priya", "Fantasy I keep coming back to", false, listOf(lanternKeeper, drownedLight, harbour, cartographer), 10 * DAY)
        shelf("Priya", "Short and sweet", false, listOf(littlePrince, velveteen, shadows, artOfWar, pageant), 4 * DAY)

        // ── Marcus: live on Sabbath; finished The Art of War yesterday ───────────────────────────
        finish("Marcus", artOfWar, finishedAgoMs = DAY + 5 * HOUR, startedAgoMs = 5 * DAY)
        progress("Marcus", sabbath, 0.44, agoMs = 1 * MIN, startedAgoMs = 3 * DAY)
        listOf(0 to 64, 1 to 88, 2 to 47, 4 to 90, 5 to 33).forEach { (d, m) -> listen("Marcus", sabbath, d, m) }
        (8..90 step 6).forEach { d -> listen("Marcus", presence, d, 55) }
        shelf("Marcus", "Philosophy for the commute", false, listOf(artOfWar, zen, shadows, presence), 15 * DAY)

        // ── Ines: recent, not live; finished The Velveteen Rabbit this week (ties Marcus on books) ─
        finish("Ines", velveteen, finishedAgoMs = 3 * DAY, startedAgoMs = 5 * DAY)
        progress("Ines", cartographer, 0.27, agoMs = 2 * HOUR, startedAgoMs = 2 * DAY)
        listOf(0 to 40, 2 to 75, 3 to 60, 5 to 20).forEach { (d, m) -> listen("Ines", cartographer, d, m) }
        (10..60 step 10).forEach { d -> listen("Ines", velveteen, d, 35) }
        shelf("Ines", "Read aloud", true, listOf(velveteen, littlePrince, pageant), 7 * DAY)

        // ── Theo: played yesterday; mostly a monthly listener ─────────────────────────────────────
        progress("Theo", cockroach, 0.62, agoMs = 26 * HOUR, startedAgoMs = 20 * DAY)
        listOf(1 to 30, 6 to 25).forEach { (d, m) -> listen("Theo", cockroach, d, m) }
        (8..28 step 2).forEach { d -> listen("Theo", cockroach, d, 70) }

        // ── Hana: a yearly listener who started The Seer's Path five days ago ───────────────────
        progress("Hana", seer, 0.08, agoMs = 5 * DAY, startedAgoMs = 5 * DAY + HOUR)
        listen("Hana", seer, 5, 28)
        (35..330 step 15).forEach { d -> listen("Hana", letGo, d, 110) }
        finish("Hana", letGo, finishedAgoMs = 40 * DAY, startedAgoMs = 120 * DAY)

        // ── Jonah: exactly one book in progress, nothing else ───────────────────────────────────
        progress("Jonah", littlePrince, 0.4, agoMs = 3 * HOUR, startedAgoMs = 2 * DAY)
        listOf(0 to 22, 2 to 18).forEach { (d, m) -> listen("Jonah", littlePrince, d, m) }

        // recordPosition opens a presence row for every unfinished write; clear all but the two live listeners.
        for ((first, uid) in ids) {
            if (first == "Priya" || first == "Marcus") continue
            books.values.forEach { sessions.deleteForUserBook(uid, it) }
        }
        ids.values.forEach { profiles.refresh(it) }
        logger.info { "rig: seeded ${ids.size} people over ${books.size} books" }
    }

    private suspend fun keepAlive(
        accounts: List<Account>,
        books: Map<String, String>,
    ) {
        val priya = userId(accounts.first { it.name.startsWith("Priya") }.email) ?: return
        val marcus = userId(accounts.first { it.name.startsWith("Marcus") }.email) ?: return
        val harbour = books.id("A Harbour of Embers")
        val sabbath = books.id("Sabbath")
        while (true) {
            sessions.startOrRefresh(priya, harbour)
            sessions.startOrRefresh(marcus, sabbath)
            delay(60_000)
        }
    }
}
