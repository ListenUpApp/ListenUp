@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.calypsan.listenup.server.push

import com.calypsan.listenup.api.push.PushPlatform
import com.calypsan.listenup.server.testing.MutableClock
import com.calypsan.listenup.server.testing.withSqlDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

/** [PushWatchTokenStore.evict] hands the decision push its audience: the live watchers it removed. */
class PushWatchTokenStoreTest :
    FunSpec({
        test("evict answers the live watchers of one decision and removes every row it watched") {
            withSqlDatabase {
                val clock = MutableClock(Instant.fromEpochMilliseconds(1_730_000_000_000L))
                val store = PushWatchTokenStore(sql, clock)
                runTest {
                    store.register(PushWatchKind.REGISTRATION, "pending-1", "expired", PushPlatform.ANDROID)
                    clock.instant += PushWatchTokenStore.WATCH_TTL + 1.days
                    store.register(PushWatchKind.REGISTRATION, "pending-1", "watch-android", PushPlatform.ANDROID)
                    store.register(PushWatchKind.REGISTRATION, "pending-1", "watch-ios", PushPlatform.IOS)
                    store.register(PushWatchKind.REGISTRATION, "pending-2", "someone-else", PushPlatform.IOS)

                    store.evict(PushWatchKind.REGISTRATION, "pending-1") shouldContainExactlyInAnyOrder
                        listOf(PushWatcher("watch-android", "ANDROID"), PushWatcher("watch-ios", "IOS"))

                    sql.pushWatchTokensQueries.countAll().executeAsOne() shouldBe 1L
                    store.evict(PushWatchKind.REGISTRATION, "pending-1") shouldBe emptyList()
                }
            }
        }
    })
