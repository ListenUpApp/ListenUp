package com.calypsan.listenup.client.domain.usecase.auth

import com.calypsan.listenup.core.SecureStorage
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest

/** In-memory [SecureStorage] standing in for the Keychain / Keystore. */
private class MemoryStorage : SecureStorage {
    val data = mutableMapOf<String, String>()

    override suspend fun save(
        key: String,
        value: String,
    ) {
        data[key] = value
    }

    override suspend fun read(key: String): String? = data[key]

    override suspend fun delete(key: String) {
        data.remove(key)
    }

    override suspend fun clear() {
        data.clear()
    }
}

private const val OWNER_KEY = "library_owner_user_id"

/**
 * The local library copy belongs to one person. A lapsed session keeps it so that person can sign
 * back in offline; anyone else signing in must start from a clean slate, or they inherit the first
 * person's positions and sync cursors.
 */
class LibraryOwnerGuardTest :
    FunSpec({
        class Rig(
            owner: String? = null,
            persistedUserId: String? = null,
            storedUserId: String? = null,
            signOutFails: Boolean = false,
        ) {
            val calls = mutableListOf<String>()
            val storage = MemoryStorage().also { s -> owner?.let { s.data[OWNER_KEY] = it } }
            val guard =
                LibraryOwnerGuard(
                    secureStorage = storage,
                    persistedUserId = { persistedUserId },
                    storedUserId = { storedUserId },
                    localSignOut = {
                        if (signOutFails) error("x")
                        calls += "signOut"
                    },
                    deleteDeviceFiles = { calls += "deleteFiles" },
                )
        }

        test("a different person signing in wipes the local copy, then the device files, in that order") {
            runTest {
                val rig = Rig(owner = "alice")

                rig.guard.beforeSignIn("bob")

                rig.calls shouldBe listOf("signOut", "deleteFiles")
            }
        }

        test("the same person signing back in keeps everything") {
            runTest {
                val rig = Rig(owner = "alice")

                rig.guard.beforeSignIn("alice")

                rig.calls.shouldBeEmpty()
            }
        }

        test("a first sign-in on a clean install wipes nothing and records the owner") {
            runTest {
                val rig = Rig()

                rig.guard.beforeSignIn("bob")

                rig.calls.shouldBeEmpty()
                rig.storage.read(OWNER_KEY) shouldBe "bob"
            }
        }

        test("an install from before the owner was recorded falls back to the lapsed session's user id") {
            runTest {
                val rig = Rig(persistedUserId = "alice")

                rig.guard.beforeSignIn("bob")

                rig.calls shouldBe listOf("signOut", "deleteFiles")
            }
        }

        test("with no persisted user id it falls back to the user row still in the database") {
            runTest {
                val rig = Rig(persistedUserId = null, storedUserId = "alice")

                rig.guard.beforeSignIn("bob")

                rig.calls shouldBe listOf("signOut", "deleteFiles")
            }
        }

        test("the recorded owner wins over both fallbacks") {
            runTest {
                val rig = Rig(owner = "bob", persistedUserId = "alice", storedUserId = "alice")

                rig.guard.beforeSignIn("bob")

                rig.calls.shouldBeEmpty()
            }
        }

        test("the owner is recorded after a wipe, so the next same-person sign-in keeps the copy") {
            runTest {
                val rig = Rig(owner = "alice")

                rig.guard.beforeSignIn("bob")
                rig.guard.beforeSignIn("bob")

                rig.calls.count { it == "signOut" } shouldBe 1
            }
        }

        test("a failed wipe does not record the new owner") {
            runTest {
                val rig = Rig(owner = "alice", signOutFails = true)

                shouldThrow<IllegalStateException> { rig.guard.beforeSignIn("bob") }

                rig.storage.read(OWNER_KEY) shouldBe "alice"
            }
        }
    })
