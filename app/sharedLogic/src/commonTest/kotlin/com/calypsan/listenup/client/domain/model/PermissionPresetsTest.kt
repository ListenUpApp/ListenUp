package com.calypsan.listenup.client.domain.model

import com.calypsan.listenup.api.dto.auth.Permission
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class PermissionPresetsTest :
    FunSpec({
        val both = setOf(Permission.EDIT_METADATA, Permission.CURATE_LIBRARY)
        val legacy = setOf(Permission.EDIT_METADATA)

        test("each preset's flags, with both permissions advertised") {
            PermissionPreset.LISTENER.applyTo(UserPermissions(), both) shouldBe
                UserPermissions(canEditMetadata = false, canCurateLibrary = false)
            PermissionPreset.CONTRIBUTOR.applyTo(UserPermissions(canCurateLibrary = true), both) shouldBe
                UserPermissions(canEditMetadata = true, canCurateLibrary = false)
            PermissionPreset.LIBRARIAN.applyTo(UserPermissions(), both) shouldBe
                UserPermissions(canEditMetadata = true, canCurateLibrary = true)
        }

        test("Contributor is exactly the defaults") {
            PermissionPreset.CONTRIBUTOR.applyTo(UserPermissions(canEditMetadata = false), both) shouldBe UserPermissions()
        }

        test("flags map back to their preset, and anything else is Custom") {
            presetFor(UserPermissions(canEditMetadata = false, canCurateLibrary = false), both) shouldBe PermissionPreset.LISTENER
            presetFor(UserPermissions(canEditMetadata = true, canCurateLibrary = false), both) shouldBe PermissionPreset.CONTRIBUTOR
            presetFor(UserPermissions(canEditMetadata = true, canCurateLibrary = true), both) shouldBe PermissionPreset.LIBRARIAN
            presetFor(UserPermissions(canEditMetadata = false, canCurateLibrary = true), both) shouldBe PermissionPreset.CUSTOM
        }

        test("applying a preset then reading it back is the identity, for every pickable preset") {
            PermissionPreset.pickable.forEach { preset ->
                presetFor(preset.applyTo(UserPermissions(), both), both) shouldBe preset
            }
        }

        test("only advertised permissions are set or read") {
            PermissionPreset.LIBRARIAN.applyTo(UserPermissions(canCurateLibrary = false), legacy) shouldBe
                UserPermissions(canEditMetadata = true, canCurateLibrary = false)
            // Against an older server the curate flag is not the server's, so it never makes a set Custom.
            presetFor(UserPermissions(canEditMetadata = true, canCurateLibrary = true), legacy) shouldBe
                PermissionPreset.CONTRIBUTOR
        }

        test("presets are shown only when more than one permission is advertised") {
            presetsApply(both) shouldBe true
            presetsApply(legacy) shouldBe false
        }

        test("allows and granting read and set one permission; UNKNOWN does neither") {
            val flags = UserPermissions(canEditMetadata = true, canCurateLibrary = false)
            flags.allows(Permission.EDIT_METADATA) shouldBe true
            flags.allows(Permission.CURATE_LIBRARY) shouldBe false
            flags.allows(Permission.UNKNOWN) shouldBe false
            flags.granting(Permission.CURATE_LIBRARY, true) shouldBe UserPermissions(canEditMetadata = true, canCurateLibrary = true)
            flags.granting(Permission.UNKNOWN, true) shouldBe flags
        }

        test("the list label is the role for owners and admins, the preset for members, and Member on an older server") {
            fun user(
                role: String,
                isRoot: Boolean = false,
                flags: UserPermissions = UserPermissions(),
            ) = AdminUserInfo(
                id = "u",
                email = "u@x",
                displayName = "U",
                firstName = null,
                lastName = null,
                isRoot = isRoot,
                role = role,
                status = "ACTIVE",
                permissions = flags,
                createdAt = "0",
            )
            accessLabelFor(user("ROOT", isRoot = true), both) shouldBe AccessLabel.OWNER
            accessLabelFor(user("ADMIN"), both) shouldBe AccessLabel.ADMIN
            accessLabelFor(user("admin"), both) shouldBe AccessLabel.ADMIN
            accessLabelFor(user("MEMBER"), both) shouldBe AccessLabel.CONTRIBUTOR
            accessLabelFor(user("MEMBER", flags = UserPermissions(canEditMetadata = false)), both) shouldBe AccessLabel.LISTENER
            accessLabelFor(user("MEMBER", flags = UserPermissions(canEditMetadata = false, canCurateLibrary = true)), both) shouldBe
                AccessLabel.CUSTOM
            accessLabelFor(user("MEMBER"), legacy) shouldBe AccessLabel.MEMBER
        }

        test("with reading orders advertised, Contributor makes them and Listener does not") {
            val all = Permission.known.toSet()
            PermissionPreset.LISTENER.applyTo(UserPermissions(), all) shouldBe
                UserPermissions(canEditMetadata = false, canCurateLibrary = false, canMakeReadingOrders = false)
            PermissionPreset.CONTRIBUTOR.applyTo(UserPermissions(canMakeReadingOrders = false), all) shouldBe
                UserPermissions(canEditMetadata = true, canCurateLibrary = false, canMakeReadingOrders = true)
            presetFor(UserPermissions(), all) shouldBe PermissionPreset.CONTRIBUTOR
            presetFor(UserPermissions(canMakeReadingOrders = false), all) shouldBe PermissionPreset.CUSTOM
        }
    })
