package com.calypsan.listenup.client.di.e2e

import com.calypsan.listenup.client.domain.repository.AuthSession
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldNotBe

class ClientKoinGraphE2ETest :
    FunSpec({

        test("fixture boots the server and builds the client graph") {
            val fixture = autoClose(DiWiredClientFixture.start())
            // Resolving a known singleton proves the graph wired, not just that start() returned.
            fixture.koin.koin.get<AuthSession>() shouldNotBe null
        }
    })
