package com.calypsan.listenup.client

import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.work.WorkManager
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext
import org.koin.core.context.stopKoin
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import kotlin.time.Duration.Companion.seconds

/**
 * Boots the real [ListenUp] `Application` and asserts `onCreate()` completes.
 *
 * ## Why this test exists
 *
 * The launch crash fixed in #1248 shipped through four green CI gates and human
 * review. `startKoin` eagerly builds every `createdAtStart = true` single, one of
 * which (`PlaybackControllerActivator`) transitively resolved
 * `DownloadRepository` → `DownloadEnqueuer` → `WorkManager.getInstance()`. With
 * `WorkManager.initialize()` still below the `startKoin` call, that threw on every
 * device, every launch. Nothing caught it because nothing booted the Application.
 *
 * The existing graph test (`ClientKoinGraphE2ETest`, `:app:sharedLogic:jvmTest`)
 * could not have: it loads `sharedModules` only — the Android platform modules
 * where `DownloadEnqueuer` lives are absent by construction — and it deliberately
 * swallows every non-cycle `Exception` while sweeping the singleton graph. This
 * test closes both halves of that gap: the real Android module list, in the real
 * `onCreate()` order, with nothing swallowed.
 *
 * ## How the assertion works
 *
 * Robolectric instantiates the `@Config(application = …)` class and runs
 * `onCreate()` during test-environment setup, before the `@Test` body. A throw
 * there fails the test during setup — so the boot *is* the assertion, and the
 * body only confirms both subsystems came up live.
 *
 * The production manifest names `ListenUp`, but `androidHostTest`'s manifest
 * replaces it with the empty `android.app.Application` so unrelated host tests
 * stay isolated. `@Config` opts this one class back in.
 *
 * ## Why exactly one test method
 *
 * Robolectric builds a fresh Application per test method, but `WorkManager`'s
 * delegate is a JVM static that outlives it — so a second method's `onCreate()`
 * dies on "WorkManager is already initialized", an artifact of the harness rather
 * than a defect in the code under test. Resetting it means reaching for
 * `WorkManagerImpl.setDelegate`, which is `@RestrictTo(LIBRARY_GROUP)`. One boot,
 * both assertions, no restricted API.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = ListenUp::class)
class ApplicationBootTest {
    /**
     * `onCreate()` called `startKoin`, which registers a process-global context.
     * A leaked global Koin poisons every test that runs after this class in the
     * same JVM, so tear it down whether the body passed or failed.
     *
     * The scopes go first: `onCreate()` also launches collectors on them that keep
     * reading Room after the test ends, and the database cannot load its native
     * library under Robolectric. That `UnsatisfiedLinkError` would surface in
     * whichever coroutine test runs next as `UncaughtExceptionsBeforeTest`.
     */
    @After
    fun tearDown() {
        cancelEveryScopeTheApplicationStarted()
        stopKoin()
        assertNothingEscapedTheBoot()
    }

    /**
     * Fails here, naming the cause, if any coroutine the boot launched died with an exception no handler
     * caught. Left alone, kotlinx-coroutines-test hands it to whichever `runTest` runs next in this JVM,
     * which then fails as `UncaughtExceptionsBeforeTest` with nothing to say where it came from.
     */
    private fun assertNothingEscapedTheBoot() {
        runTest { }
    }

    /**
     * Cancels every Koin-held [CoroutineScope], waits for its children, and fails if any is still running.
     *
     * The player's scope runs on the main thread (`Dispatchers.Main.immediate`, the session's one-thread
     * rule), and Robolectric's paused main looper only runs what is posted to it when idled. A coroutine
     * already posted there finishes its cancellation only once the looper runs, and `runBlocking` here holds
     * that very thread — so the looper is idled between the cancel and the join.
     */
    private fun cancelEveryScopeTheApplicationStarted() {
        val koin = GlobalContext.getOrNull() ?: return
        val jobs = koin.getAll<CoroutineScope>().map { it.coroutineContext.job }
        jobs.forEach { it.cancel() }
        shadowOf(Looper.getMainLooper()).idle()
        runBlocking {
            withTimeout(5.seconds) { jobs.forEach { it.join() } }
        }
        val stillRunning = jobs.count { job -> job.children.any { it.isActive } }
        check(stillRunning == 0) { "$stillRunning application scope(s) still have running children after teardown" }
    }

    @Test
    fun `the application boots with WorkManager live before the Koin graph`() {
        // Reaching this line already means onCreate() ran to completion: notification
        // channels registered, WorkManager initialized, and the full Android Koin
        // graph built — every createdAtStart single constructed, nothing swallowed.
        // Under the pre-#1248 ordering Robolectric fails this test during setup with
        // "WorkManager is not initialized properly".
        val app = ApplicationProvider.getApplicationContext<ListenUp>()

        // State the ordering invariant directly rather than inferring it from the
        // absence of a crash: both subsystems are live after onCreate(), and #1248
        // proved the eager-singleton path from Koin into WorkManager.getInstance()
        // is genuinely reachable.
        WorkManager.getInstance(app) shouldNotBe null
        GlobalContext.getOrNull() shouldNotBe null

        // Every dependency MainActivity declares must resolve against this real graph. On
        // 2026-09-25 `by inject<PlaybackStateProvider>()` shipped for Continue On: nothing binds
        // that interface (PlaybackManager implements it; iOS binds no PlaybackManager at all), so
        // the app crashed the first time Android asked for handoff data — every time it left the
        // foreground. Forcing each lazy delegate here covers the next such field too, without a
        // list to keep in step with the activity.
        val activity = Robolectric.buildActivity(MainActivity::class.java).get()
        val unresolved =
            MainActivity::class.java.declaredFields
                .filter { Lazy::class.java.isAssignableFrom(it.type) }
                .mapNotNull { field ->
                    field.isAccessible = true
                    runCatching { (field.get(activity) as Lazy<*>).value }
                        .exceptionOrNull()
                        ?.let { "${field.name.removeSuffix("\$delegate")}: ${(it.message ?: it.javaClass.name).take(120)}" }
                }
        unresolved shouldBe emptyList()

        // Every app-lifetime scope the graph binds catches what its fire-and-forget launches throw. The
        // boot's first shortcut refresh runs on the unbound-name scope and, under Robolectric, dies with
        // an Error (Room's native library is absent); with no handler it escaped into the next test.
        val unhandled =
            GlobalContext.get().getAll<CoroutineScope>().count { it.coroutineContext[CoroutineExceptionHandler] == null }
        unhandled shouldBe 0
    }
}
