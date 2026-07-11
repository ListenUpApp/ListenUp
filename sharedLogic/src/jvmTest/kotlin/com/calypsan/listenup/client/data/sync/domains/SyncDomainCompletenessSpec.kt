package com.calypsan.listenup.client.data.sync.domains

import com.calypsan.listenup.api.contractJson
import com.calypsan.listenup.api.dto.auth.AuthSession
import com.calypsan.listenup.api.dto.auth.RegisterRequest
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.DomainList
import com.calypsan.listenup.api.sync.SyncControl
import com.calypsan.listenup.api.sync.SyncDomains
import com.calypsan.listenup.client.data.local.db.BookEntityMapper
import com.calypsan.listenup.client.data.sync.testing.StubAvatarDownloadRepository
import com.calypsan.listenup.client.test.db.createInMemoryTestDatabase
import com.calypsan.listenup.client.test.fake.FakeAuthSession
import com.calypsan.listenup.client.test.stubImageStorage
import com.calypsan.listenup.server.module
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.collections.shouldNotContainAnyOf
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.testing.testApplication
import java.nio.file.Files

/**
 * The Phase-2 closing invariant: the contract ([SyncDomains.all]), the client
 * descriptor catalog ([syncDomainCatalog]), and the server's registered
 * repositories list exactly the same 21 mirrored domains — 1:1:1. A domain
 * declared on one side and not the others fails this spec before any runtime
 * symptom (a missing SSE stream, an un-bootstrapped table) can appear.
 *
 * The server leg boots the **real** production [module] in a Ktor
 * `testApplication`, mints a ROOT token via `/api/v1/auth/setup`, and reads
 * `GET /api/v1/sync/domains`. That route responds with
 * `DomainList(domains = registry.knownDomains())`, where the registry is
 * populated by every `SqlSyncableRepository` self-registering at bootstrap —
 * so the assertion is against the full production DI graph, not a test
 * re-listing that could drift from it.
 */
class SyncDomainCompletenessSpec :
    FunSpec({

        test("client catalog mirrors SyncDomains.all exactly (1:1, both directions)") {
            val db = createInMemoryTestDatabase()
            try {
                val catalog =
                    syncDomainCatalog(
                        database = db,
                        mapper = BookEntityMapper(),
                        imageStorage = stubImageStorage(),
                        authSession = FakeAuthSession(userId = "spec-user"),
                        avatarDownloadRepository = StubAvatarDownloadRepository(),
                        pingPresence = {},
                        refetchServerInfo = {},
                        refetchPreferences = {},
                    )
                val catalogNames = catalog.mirrored.map { it.key.name }

                catalogNames.toSet() shouldHaveSize catalogNames.size
                catalogNames.toSet() shouldBe SyncDomains.all.map { it.name }.toSet()
            } finally {
                db.close()
            }
        }

        test("outbox channels are the complete client-write rulebook, consistent with the mirrored write tiers") {
            val db = createInMemoryTestDatabase()
            try {
                val catalog =
                    syncDomainCatalog(
                        database = db,
                        mapper = BookEntityMapper(),
                        imageStorage = stubImageStorage(),
                        authSession = FakeAuthSession(userId = "spec-user"),
                        avatarDownloadRepository = StubAvatarDownloadRepository(),
                        pingPresence = {},
                        refetchServerInfo = {},
                        refetchPreferences = {},
                    )
                val channels = OutboxChannels.all

                // Distinct names — the sender map and queue key on them.
                channels.map { it.name }.toSet() shouldHaveSize channels.size

                // Every Outbox-declaring mirrored domain references the channel named for its own key.
                val outboxDomains = catalog.mirrored.filter { it.writes is WriteTier.Outbox }
                outboxDomains.forEach { domain ->
                    (domain.writes as WriteTier.Outbox).channel.name shouldBe domain.key.name
                }

                // A channel that shadows a mirrored domain must be that domain's declared tier.
                val mirroredByName = catalog.mirrored.associateBy { it.key.name }
                channels.forEach { channel ->
                    val domain = mirroredByName[channel.name] ?: return@forEach
                    domain.writes
                        .shouldBeInstanceOf<WriteTier.Outbox>()
                        .channel.name shouldBe channel.name
                }

                // The only channels without a mirrored descriptor are the two RPC-edit surfaces.
                channels.map { it.name }.filter { it !in mirroredByName }.toSet() shouldBe
                    setOf("profile", "preferences")

                // Frozen posture: exactly these eight mirrored domains write through the outbox
                // (the reading-order trio joined per Integration Foundations §5.3/§5.4).
                outboxDomains.map { it.key.name }.toSet() shouldBe
                    setOf(
                        "books",
                        "series",
                        "contributors",
                        "playback_positions",
                        "listening_events",
                        "reading_orders",
                        "reading_order_books",
                        "reading_order_follows",
                    )
            } finally {
                db.close()
            }
        }

        test(
            "refreshed tier claims exactly the three fold-candidate controls, distinct and disjoint from engine controls",
        ) {
            val db = createInMemoryTestDatabase()
            try {
                val catalog =
                    syncDomainCatalog(
                        database = db,
                        mapper = BookEntityMapper(),
                        imageStorage = stubImageStorage(),
                        authSession = FakeAuthSession(userId = "spec-user"),
                        avatarDownloadRepository = StubAvatarDownloadRepository(),
                        pingPresence = {},
                        refetchServerInfo = {},
                        refetchPreferences = {},
                    )

                val triggers = catalog.refreshed.map { it.trigger }

                // Distinct — no two refreshed domains claim the same control (else the
                // router's KClass map would silently drop one).
                triggers.toSet() shouldHaveSize triggers.size

                // Exactly the three fold-candidate refresh triggers. ActivityChanged was retired when
                // `activities` was promoted to a Room-mirrored data domain (catch-up + live tail),
                // so it is no longer a refreshed trigger.
                triggers.toSet() shouldBe
                    setOf(
                        SyncControl.ActiveSessionsChanged::class,
                        SyncControl.ServerInfoChanged::class,
                        SyncControl.PreferencesChanged::class,
                    )

                // Disjoint from the engine/lifecycle controls the dispatcher owns.
                triggers shouldNotContainAnyOf
                    listOf(
                        SyncControl.CursorStale::class,
                        SyncControl.StreamError::class,
                        SyncControl.AccessChanged::class,
                        SyncControl.UserDeleted::class,
                        SyncControl.LibraryDataChanged::class,
                    )
            } finally {
                db.close()
            }
        }

        test("every SyncControl subtype is owned: an engine control, or exactly one RefreshedDomain trigger") {
            val db = createInMemoryTestDatabase()
            try {
                val catalog =
                    syncDomainCatalog(
                        database = db,
                        mapper = BookEntityMapper(),
                        imageStorage = stubImageStorage(),
                        authSession = FakeAuthSession(userId = "spec-user"),
                        avatarDownloadRepository = StubAvatarDownloadRepository(),
                        pingPresence = {},
                        refetchServerInfo = {},
                        refetchPreferences = {},
                    )

                // The frozen engine/lifecycle controls the dispatcher owns directly (no RefreshedDomain).
                val engineControls =
                    setOf(
                        SyncControl.CursorStale::class,
                        SyncControl.StreamError::class,
                        SyncControl.AccessChanged::class,
                        SyncControl.UserDeleted::class,
                        SyncControl.LibraryDataChanged::class,
                        // Legacy: the dispatcher drops ActivityChanged as a no-op (SyncEventDispatcher).
                        // `activities` is now a cursored Room mirror (#1028) — catch-up + live tail keep
                        // it current, so no RefreshedDomain claims this control. It stays a sealed
                        // subtype for wire compatibility; owning it here keeps completeness exhaustive.
                        SyncControl.ActivityChanged::class,
                    )
                val refreshedTriggers = catalog.refreshed.map { it.trigger }

                // Distinct owners — no control claimed twice, no engine/refreshed overlap.
                refreshedTriggers.toSet() shouldHaveSize refreshedTriggers.size
                (engineControls intersect refreshedTriggers.toSet()).shouldBeEmpty()

                // Completeness: every sealed SyncControl subtype is owned by exactly one side. A new
                // control frame with no engine handler and no RefreshedDomain trigger fails HERE — the
                // regression that today only warn-logs at runtime (SyncEventDispatcher).
                //
                // Recovery of a dropped refresh trigger is now derived, not declared: the lifecycle-reconcile
                // edge re-runs every refreshed domain's refresh through RefreshedDomainRouter.refreshAll,
                // so a new refreshed domain self-heals by construction — no per-trigger engine arm to
                // parse for, no guard that can rot.
                val allControls = SyncControl::class.sealedSubclasses.toSet()
                allControls shouldBe engineControls + refreshedTriggers.toSet()
            } finally {
                db.close()
            }
        }

        test("presence is the only access-sensitive refreshed domain (refreshOnAccessChanged frozen to presence)") {
            val db = createInMemoryTestDatabase()
            try {
                val catalog =
                    syncDomainCatalog(
                        database = db,
                        mapper = BookEntityMapper(),
                        imageStorage = stubImageStorage(),
                        authSession = FakeAuthSession(userId = "spec-user"),
                        avatarDownloadRepository = StubAvatarDownloadRepository(),
                        pingPresence = {},
                        refetchServerInfo = {},
                        refetchPreferences = {},
                    )

                // The presence RPCs (currently-listening / book-readers) are ACL-filtered at read time,
                // so an access change is a presence-visibility change — presence is the one refreshed
                // domain the engine re-fires at the end of an AccessChanged reconcile. Server-info and
                // preferences are access-insensitive. Adding/removing an access-sensitive refresh domain
                // must be a conscious edit here, not a silent side effect.
                catalog.refreshed
                    .filter { it.refreshOnAccessChanged }
                    .map { it.trigger }
                    .toSet() shouldBe setOf(SyncControl.ActiveSessionsChanged::class)
            } finally {
                db.close()
            }
        }

        test("declared delete and digest postures match the frozen rulebook") {
            val db = createInMemoryTestDatabase()
            try {
                val catalog =
                    syncDomainCatalog(
                        database = db,
                        mapper = BookEntityMapper(),
                        imageStorage = stubImageStorage(),
                        authSession = FakeAuthSession(userId = "spec-user"),
                        avatarDownloadRepository = StubAvatarDownloadRepository(),
                        pingPresence = {},
                        refetchServerInfo = {},
                        refetchPreferences = {},
                    )

                // Changing any of these is a product decision, not a side effect:
                // update this spec consciously alongside the descriptor.
                catalog.mirrored
                    .filter { it.deletes is DeleteSemantics.CatchUpOnly }
                    .map { it.key.name }
                    .toSet() shouldBe setOf("playback_positions", "listening_events", "activities", "user_stats")

                catalog.mirrored
                    .filter { it.digest is DigestParticipation.OptOut }
                    .map { it.key.name }
                    .toSet() shouldBe setOf("playback_positions")
            } finally {
                db.close()
            }
        }

        test(
            "access-gated delta participation is frozen: Targeted domains ordered, only collection_shares is LiveTailOnly",
        ) {
            val db = createInMemoryTestDatabase()
            try {
                val catalog =
                    syncDomainCatalog(
                        database = db,
                        mapper = BookEntityMapper(),
                        imageStorage = stubImageStorage(),
                        authSession = FakeAuthSession(userId = "spec-user"),
                        avatarDownloadRepository = StubAvatarDownloadRepository(),
                        pingPresence = {},
                        refetchServerInfo = {},
                        refetchPreferences = {},
                    )
                val gated = catalog.mirrored.filter { it.accessGate != null }

                // The five access-gated domains — the same set AccessGateParitySpec pins to the server.
                gated.map { it.key.name }.toSet() shouldBe
                    setOf("books", "activities", "collections", "collection_books", "collection_shares")

                // The Targeted domains, in their declared dependency order. Changing an order or moving a
                // domain between Targeted and LiveTailOnly is a product decision — update this spec
                // consciously alongside the gate. (The compile-time no-default on AccessGate.delta is the
                // stronger lock; this freezes the resulting shape.)
                gated
                    .mapNotNull { domain ->
                        (domain.accessGate!!.delta as? AccessDeltaPolicy.Targeted)?.let { domain.key.name to it.order }
                    }.sortedBy { it.second }
                    .map { it.first } shouldBe
                    listOf("collections", "collection_books", "books", "activities")

                // The one LiveTailOnly domain — deliberately NOT fetched in the delta.
                gated
                    .filter { it.accessGate!!.delta is AccessDeltaPolicy.LiveTailOnly }
                    .map { it.key.name } shouldBe listOf("collection_shares")
            } finally {
                db.close()
            }
        }

        test("server registrations mirror SyncDomains.all exactly (1:1, both directions)") {
            val homeDir = Files.createTempDirectory("listenup-completeness-home-")
            val tmpDb =
                Files
                    .createTempFile("listenup-completeness-", ".db")
                    .toFile()
                    .apply { deleteOnExit() }
            try {
                testApplication {
                    environment {
                        config =
                            MapApplicationConfig(
                                "database.jdbcUrl" to "jdbc:sqlite:${tmpDb.absolutePath}",
                                "auth.refreshPepper" to "x".repeat(REFRESH_PEPPER_LENGTH),
                                "jwt.secret" to "x".repeat(JWT_SECRET_LENGTH),
                                "jwt.issuer" to "listenup",
                                "jwt.audience" to "listenup-client",
                                "registration.policy" to "OPEN",
                                "mdns.enabled" to "false",
                                "listenup.home" to homeDir.toString(),
                            )
                    }
                    application { module() }

                    val client =
                        createClient {
                            install(ContentNegotiation) { json(contractJson) }
                        }
                    // The sync/domains route is mounted inside authenticate(JWT_PROVIDER),
                    // so it needs a real bearer token — mint the first user as ROOT.
                    val accessToken = client.setupRoot()

                    val domains =
                        client
                            .get("/api/v1/sync/domains") { bearerAuth(accessToken) }
                            .body<DomainList>()
                            .domains

                    domains.toSet() shouldBe SyncDomains.all.map { it.name }.toSet()
                }
            } finally {
                homeDir.toFile().deleteRecursively()
            }
        }
    })

/** Registers the first user as ROOT via `/api/v1/auth/setup` and returns the access token. */
private suspend fun HttpClient.setupRoot(): String {
    val result =
        post("/api/v1/auth/setup") {
            contentType(ContentType.Application.Json)
            setBody(
                RegisterRequest(
                    email = "root@completeness.test",
                    password = "password1234",
                    displayName = "Root",
                ),
            )
        }.body<AppResult<AuthSession>>()
    return (result as AppResult.Success<AuthSession>).data.accessToken.value
}

private const val JWT_SECRET_LENGTH = 32
private const val REFRESH_PEPPER_LENGTH = 32
