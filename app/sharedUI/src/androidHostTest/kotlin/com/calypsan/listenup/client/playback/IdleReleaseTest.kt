package com.calypsan.listenup.client.playback

import androidx.media3.common.Player
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.lang.reflect.Proxy

class IdleReleaseTest :
    FunSpec({
        test("an idle release saves the position, then stops and empties the player, then announces it") {
            val events = mutableListOf<String>()
            val player =
                Proxy.newProxyInstance(
                    Player::class.java.classLoader,
                    arrayOf(Player::class.java),
                ) { _, method, _ ->
                    events += method.name
                    null
                } as Player

            releaseIdlePlayer(
                player = player,
                savePosition = { events += "save" },
                announceRelease = { events += "announce" },
            )

            events shouldBe listOf("save", "stop", "clearMediaItems", "announce")
        }
    })
