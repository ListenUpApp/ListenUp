package com.calypsan.listenup.web.session

import com.calypsan.listenup.api.dto.auth.AccessToken
import com.calypsan.listenup.api.dto.auth.RefreshToken
import com.calypsan.listenup.api.dto.auth.SessionId
import com.calypsan.listenup.api.dto.auth.UserId
import com.calypsan.listenup.api.dto.auth.UserRole
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

class WebSessionStoreTest :
    FunSpec({
        fun sampleSession() =
            WebSession(
                sessionId = SessionId("s1"),
                userId = UserId("u1"),
                role = UserRole.MEMBER,
                accessToken = AccessToken("at"),
                refreshToken = RefreshToken("rt"),
                accessExpiresAt = 1_000L,
            )

        test("put then get returns the stored session") {
            val store = WebSessionStore()
            val id = store.newCookieId()
            val session = sampleSession()
            store.put(id, session)
            store.get(id) shouldBe session
        }

        test("remove deletes the session") {
            val store = WebSessionStore()
            val id = store.newCookieId()
            store.put(id, sampleSession())
            store.remove(id)
            store.get(id).shouldBeNull()
        }

        test("newCookieId mints distinct, non-trivial opaque ids") {
            val store = WebSessionStore()
            val a = store.newCookieId()
            val b = store.newCookieId()
            a shouldNotBe b
            (a.length >= 32) shouldBe true
        }
    })
