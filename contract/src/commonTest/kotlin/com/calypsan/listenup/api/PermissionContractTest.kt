package com.calypsan.listenup.api

import com.calypsan.listenup.api.dto.ServerInfo
import com.calypsan.listenup.api.dto.advertisedPermissions
import com.calypsan.listenup.api.dto.auth.AdminUserPatch
import com.calypsan.listenup.api.dto.auth.Permission
import com.calypsan.listenup.api.dto.auth.PermissionGroup
import com.calypsan.listenup.api.dto.auth.RegistrationPolicy
import com.calypsan.listenup.api.dto.auth.UserPermissions
import com.calypsan.listenup.api.dto.auth.UserPermissionsPatch
import com.calypsan.listenup.api.dto.auth.UserRole
import com.calypsan.listenup.api.dto.auth.allows
import com.calypsan.listenup.api.dto.auth.granting
import com.calypsan.listenup.api.dto.auth.patchedBy
import com.calypsan.listenup.api.sync.AdminUserRosterSyncPayload
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.serialization.builtins.ListSerializer

class PermissionContractTest :
    FunSpec({
        test("a permission this build has never heard of decodes as UNKNOWN, alone and in a list") {
            contractJson.decodeFromString(Permission.serializer(), "\"MAKE_TIME_MACHINES\"") shouldBe Permission.UNKNOWN
            contractJson.decodeFromString(
                ListSerializer(Permission.serializer()),
                """["EDIT_METADATA","FUTURE_THING"]""",
            ) shouldContainExactly listOf(Permission.EDIT_METADATA, Permission.UNKNOWN)
        }

        test("known permissions round-trip by name") {
            Permission.known.forEach { permission ->
                val json = contractJson.encodeToString(Permission.serializer(), permission)
                contractJson.decodeFromString(Permission.serializer(), json) shouldBe permission
            }
        }

        test("the Library group holds Edit metadata then Curate library, and UNKNOWN is never listed") {
            Permission.known.filter { it.group == PermissionGroup.LIBRARY } shouldContainExactly
                listOf(Permission.EDIT_METADATA, Permission.CURATE_LIBRARY)
            Permission.known shouldNotContain Permission.UNKNOWN
        }

        test("the Story World group holds Contribute then Curate, after the Library group") {
            Permission.known.filter { it.group == PermissionGroup.STORY_WORLD } shouldContainExactly
                listOf(Permission.CONTRIBUTE_STORY_WORLD, Permission.CURATE_STORY_WORLD)
            Permission.known.map { it.group }.distinct() shouldContainExactly
                listOf(PermissionGroup.LIBRARY, PermissionGroup.STORY_WORLD, PermissionGroup.READING_ORDERS)
        }

        test("the Reading orders group holds Make reading orders alone, after the Story World group") {
            Permission.known.filter { it.group == PermissionGroup.READING_ORDERS } shouldContainExactly
                listOf(Permission.MAKE_READING_ORDERS)
            Permission.known shouldContainExactly
                listOf(
                    Permission.EDIT_METADATA,
                    Permission.CURATE_LIBRARY,
                    Permission.CONTRIBUTE_STORY_WORLD,
                    Permission.CURATE_STORY_WORLD,
                    Permission.MAKE_READING_ORDERS,
                )
        }

        test("each permission's wire key is the UserPermissions field it reads, and the default follows the rule") {
            Permission.EDIT_METADATA.wireKey shouldBe "canEdit"
            Permission.CURATE_LIBRARY.wireKey shouldBe "canCurateLibrary"
            Permission.EDIT_METADATA.defaultGranted shouldBe true
            Permission.CURATE_LIBRARY.defaultGranted shouldBe false
            Permission.CONTRIBUTE_STORY_WORLD.wireKey shouldBe "canContributeStoryWorld"
            Permission.CURATE_STORY_WORLD.wireKey shouldBe "canCurateStoryWorld"
            Permission.CONTRIBUTE_STORY_WORLD.defaultGranted shouldBe true
            Permission.CURATE_STORY_WORLD.defaultGranted shouldBe false
            Permission.MAKE_READING_ORDERS.wireKey shouldBe "canMakeReadingOrders"
            Permission.MAKE_READING_ORDERS.defaultGranted shouldBe true
            // Flip each flag away from its default: contractJson skips defaults, so the key that
            // appears is exactly the field's SerialName — and it must be the permission's wire key.
            Permission.known.forEach { permission ->
                val flipped =
                    when (permission) {
                        Permission.EDIT_METADATA -> UserPermissions(canEditMetadata = false)
                        Permission.CURATE_LIBRARY -> UserPermissions(canCurateLibrary = true)
                        Permission.CONTRIBUTE_STORY_WORLD -> UserPermissions(canContributeStoryWorld = false)
                        Permission.CURATE_STORY_WORLD -> UserPermissions(canCurateStoryWorld = true)
                        Permission.MAKE_READING_ORDERS -> UserPermissions(canMakeReadingOrders = false)
                        Permission.UNKNOWN -> error("not listed")
                    }
                contractJson.encodeToString(UserPermissions.serializer(), flipped) shouldContain
                    "\"${permission.wireKey}\""
            }
        }

        test("fromWireKey resolves known keys and nothing else") {
            Permission.fromWireKey("canEdit") shouldBe Permission.EDIT_METADATA
            Permission.fromWireKey("canCurateLibrary") shouldBe Permission.CURATE_LIBRARY
            Permission.fromWireKey("canMakeReadingOrders") shouldBe Permission.MAKE_READING_ORDERS
            Permission.fromWireKey("canDoAnything") shouldBe Permission.UNKNOWN
            Permission.fromWireKey("") shouldBe Permission.UNKNOWN
        }

        test("allows reads the matching flag; UNKNOWN grants nothing") {
            val librarian = UserPermissions(canEditMetadata = true, canCurateLibrary = true)
            librarian.allows(Permission.EDIT_METADATA) shouldBe true
            librarian.allows(Permission.CURATE_LIBRARY) shouldBe true
            librarian.allows(Permission.UNKNOWN) shouldBe false
            UserPermissions().allows(Permission.CONTRIBUTE_STORY_WORLD) shouldBe true
            UserPermissions().allows(Permission.CURATE_STORY_WORLD) shouldBe false
            UserPermissions(canContributeStoryWorld = false).allows(Permission.CONTRIBUTE_STORY_WORLD) shouldBe false
            UserPermissions(canCurateStoryWorld = true).allows(Permission.CURATE_STORY_WORLD) shouldBe true
            UserPermissions(canEditMetadata = false).allows(Permission.EDIT_METADATA) shouldBe false
            UserPermissions().allows(Permission.MAKE_READING_ORDERS) shouldBe true
            UserPermissions(canMakeReadingOrders = false).allows(Permission.MAKE_READING_ORDERS) shouldBe false
        }

        test("a patch changes only the flags it names") {
            val stored = UserPermissions(canEditMetadata = false, canCurateLibrary = true)
            stored.patchedBy(UserPermissionsPatch(canEditMetadata = true)) shouldBe
                UserPermissions(canEditMetadata = true, canCurateLibrary = true)
            stored.patchedBy(UserPermissionsPatch(canCurateLibrary = false)) shouldBe
                UserPermissions(canEditMetadata = false, canCurateLibrary = false)
            stored.patchedBy(UserPermissionsPatch(canCurateStoryWorld = true)) shouldBe
                stored.copy(canCurateStoryWorld = true)
            stored.patchedBy(UserPermissionsPatch()) shouldBe stored
            stored.patchedBy(null) shouldBe stored
        }

        test("granting names one permission in a patch, and UNKNOWN names nothing") {
            UserPermissionsPatch().granting(Permission.CURATE_LIBRARY, true) shouldBe UserPermissionsPatch(canCurateLibrary = true)
            UserPermissionsPatch().granting(Permission.CONTRIBUTE_STORY_WORLD, false) shouldBe
                UserPermissionsPatch(canContributeStoryWorld = false)
            UserPermissionsPatch().granting(Permission.CURATE_STORY_WORLD, true) shouldBe
                UserPermissionsPatch(canCurateStoryWorld = true)
            UserPermissionsPatch(canCurateStoryWorld = false).isEmpty shouldBe false
            UserPermissionsPatch().granting(Permission.MAKE_READING_ORDERS, false) shouldBe
                UserPermissionsPatch(canMakeReadingOrders = false)
            UserPermissionsPatch(canEditMetadata = false).granting(Permission.UNKNOWN, true) shouldBe
                UserPermissionsPatch(canEditMetadata = false)
        }

        test("a one-flag patch puts only that key on the wire, and an absent key decodes as unchanged") {
            contractJson.encodeToString(UserPermissionsPatch.serializer(), UserPermissionsPatch(canCurateLibrary = true)) shouldBe
                """{"canCurateLibrary":true}"""
            contractJson.decodeFromString(UserPermissionsPatch.serializer(), """{"canEdit":false}""") shouldBe
                UserPermissionsPatch(canEditMetadata = false, canCurateLibrary = null)
        }

        test("an admin patch that changes no flag carries no permissions key — an empty object means grant Edit") {
            contractJson.encodeToString(AdminUserPatch.serializer(), AdminUserPatch(role = UserRole.ADMIN)) shouldBe
                """{"role":"ADMIN"}"""
            // What a pre-split app sends for UserPermissions(canEdit = true): defaults are omitted.
            contractJson.decodeFromString(AdminUserPatch.serializer(), """{"permissions":{}}""").permissions shouldBe
                UserPermissionsPatch()
        }

        test("ServerInfo from an older server advertises only canEdit") {
            val legacy =
                """{"name":"L","version":"1","apiVersion":"v1","setupRequired":false,"registrationPolicy":"OPEN","instanceId":"i"}"""
            val info = contractJson.decodeFromString(ServerInfo.serializer(), legacy)
            info.permissionFlags shouldBe setOf("canEdit")
            info.advertisedPermissions() shouldBe setOf(Permission.EDIT_METADATA)
        }

        test("advertisedPermissions drops keys this build does not know") {
            val info =
                ServerInfo(
                    name = "L",
                    version = "1",
                    apiVersion = "v1",
                    setupRequired = false,
                    registrationPolicy = RegistrationPolicy.OPEN,
                    instanceId = "i",
                    permissionFlags = setOf("canEdit", "canCurateLibrary", "canFlyBooks"),
                )
            info.advertisedPermissions() shouldBe setOf(Permission.EDIT_METADATA, Permission.CURATE_LIBRARY)
            contractJson.decodeFromString(ServerInfo.serializer(), contractJson.encodeToString(ServerInfo.serializer(), info)) shouldBe info
        }

        test("the roster payload carries nested permissions, and an older server's payload has none") {
            val payload =
                AdminUserRosterSyncPayload(
                    id = "u1",
                    email = "a@x",
                    displayName = "A",
                    role = "MEMBER",
                    status = "ACTIVE",
                    canEdit = true,
                    permissions = UserPermissions(canEditMetadata = true, canCurateLibrary = true),
                    accountCreatedAt = 1L,
                    revision = 2L,
                    updatedAt = 3L,
                    createdAt = 1L,
                    deletedAt = null,
                )
            val json = contractJson.encodeToString(AdminUserRosterSyncPayload.serializer(), payload)
            json shouldContain """"permissions":{"canCurateLibrary":true}"""
            contractJson.decodeFromString(AdminUserRosterSyncPayload.serializer(), json) shouldBe payload
            val older =
                """{"id":"u1","email":"a@x","displayName":"A","role":"MEMBER","status":"ACTIVE","canShare":true,""" +
                    """"canEdit":false,"accountCreatedAt":1,"revision":2,"updatedAt":3,"createdAt":1,"deletedAt":null}"""
            contractJson.decodeFromString(AdminUserRosterSyncPayload.serializer(), older).permissions shouldBe null
        }
    })
