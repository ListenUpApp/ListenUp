package com.calypsan.listenup.api

import com.calypsan.listenup.api.dto.auth.Permission
import com.calypsan.listenup.api.dto.auth.PermissionGroup
import com.calypsan.listenup.api.dto.auth.UserPermissions
import com.calypsan.listenup.api.dto.auth.allows
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

        test("each permission's wire key is the UserPermissions field it reads, and the default follows the rule") {
            Permission.EDIT_METADATA.wireKey shouldBe "canEdit"
            Permission.CURATE_LIBRARY.wireKey shouldBe "canCurateLibrary"
            Permission.EDIT_METADATA.defaultGranted shouldBe true
            Permission.CURATE_LIBRARY.defaultGranted shouldBe false
            // Flip each flag away from its default: contractJson skips defaults, so the key that
            // appears is exactly the field's SerialName — and it must be the permission's wire key.
            Permission.known.forEach { permission ->
                val flipped =
                    when (permission) {
                        Permission.EDIT_METADATA -> UserPermissions(canEditMetadata = false)
                        Permission.CURATE_LIBRARY -> UserPermissions(canCurateLibrary = true)
                        Permission.UNKNOWN -> error("not listed")
                    }
                contractJson.encodeToString(UserPermissions.serializer(), flipped) shouldContain
                    "\"${permission.wireKey}\""
            }
        }

        test("fromWireKey resolves known keys and nothing else") {
            Permission.fromWireKey("canEdit") shouldBe Permission.EDIT_METADATA
            Permission.fromWireKey("canCurateLibrary") shouldBe Permission.CURATE_LIBRARY
            Permission.fromWireKey("canDoAnything") shouldBe Permission.UNKNOWN
            Permission.fromWireKey("") shouldBe Permission.UNKNOWN
        }

        test("allows reads the matching flag; UNKNOWN grants nothing") {
            val librarian = UserPermissions(canEditMetadata = true, canCurateLibrary = true)
            librarian.allows(Permission.EDIT_METADATA) shouldBe true
            librarian.allows(Permission.CURATE_LIBRARY) shouldBe true
            librarian.allows(Permission.UNKNOWN) shouldBe false
            UserPermissions(canEditMetadata = false).allows(Permission.EDIT_METADATA) shouldBe false
        }
    })
