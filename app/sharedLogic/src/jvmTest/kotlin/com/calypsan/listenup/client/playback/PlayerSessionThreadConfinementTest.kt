package com.calypsan.listenup.client.playback

import com.calypsan.listenup.core.BookId
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainOnly
import io.kotest.matchers.collections.shouldNotBeEmpty
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.newSingleThreadContext
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * The session's state is touched only on its own scope's thread (Main.immediate in the app). Sign-out calls Close
 * from a background scope; on real threads that call must hop onto the player's thread before it touches anything.
 */
@OptIn(DelicateCoroutinesApi::class, ExperimentalCoroutinesApi::class)
class PlayerSessionThreadConfinementTest :
    FunSpec({
        test("Close from a background thread does its work on the player's thread and still unloads") {
            val playerThread = newSingleThreadContext("player")
            val rig = PlayerSessionRig(TestScope(), playerContext = playerThread)
            try {
                rig.withBook("b1")
                val threadsThatTouchedTheEngine = CopyOnWriteArrayList<String>()
                withContext(playerThread) { rig.session.playBook(BookId("b1"), null) }
                withTimeout(5.seconds) {
                    rig.session.state.first { (it as? PlayerSessionState.Loaded)?.phase == PlayerPhase.Playing }
                }
                rig.engine.onCall = { threadsThatTouchedTheEngine += Thread.currentThread().name.substringBefore(" @") }

                withContext(Dispatchers.Default) { rig.session.closeBook() }

                withTimeout(5.seconds) { rig.session.state.first { it == PlayerSessionState.NothingLoaded } }
                threadsThatTouchedTheEngine.shouldNotBeEmpty()
                threadsThatTouchedTheEngine shouldContainOnly listOf("player")
            } finally {
                rig.close()
                playerThread.close()
            }
        }
    })
