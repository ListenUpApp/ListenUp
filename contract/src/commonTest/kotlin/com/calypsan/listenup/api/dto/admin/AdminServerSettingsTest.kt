package com.calypsan.listenup.api.dto.admin

import com.calypsan.listenup.api.contractJson
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.encodeToString

class AdminServerSettingsTest :
    FunSpec({
        test("AdminServerSettings round-trips (incl. null remoteUrl)") {
            val v =
                AdminServerSettings(
                    serverName = "My Library",
                    remoteUrl = null,
                    holdNewBooksForReview = false,
                    pushNotificationsEnabled = true,
                )
            contractJson.decodeFromString<AdminServerSettings>(contractJson.encodeToString(v)) shouldBe v
        }
        test("AdminServerSettingsPatch round-trips (defaults null = unchanged)") {
            val v = AdminServerSettingsPatch(serverName = "X", remoteUrl = "", pushNotificationsEnabled = false)
            contractJson.decodeFromString<AdminServerSettingsPatch>(contractJson.encodeToString(v)) shouldBe v
        }

        test("the store region round-trips, and an older server's settings default to the United States") {
            val v = AdminServerSettings("Lib", null, metadataRegion = "uk")
            contractJson.decodeFromString<AdminServerSettings>(contractJson.encodeToString(v)) shouldBe v
            contractJson
                .decodeFromString<AdminServerSettings>("""{"serverName":"Lib","remoteUrl":null}""")
                .metadataRegion shouldBe "us"
        }

        test("a patch leaves the store region alone unless it names one") {
            AdminServerSettingsPatch().metadataRegion shouldBe null
            val v = AdminServerSettingsPatch(metadataRegion = "au")
            contractJson.decodeFromString<AdminServerSettingsPatch>(contractJson.encodeToString(v)) shouldBe v
        }

        test("a library payload carries its store region, and an older one decodes without it") {
            val v =
                com.calypsan.listenup.api.sync.LibrarySyncPayload(
                    id = "lib",
                    name = "Library",
                    metadataPrecedence = "embedded,abs,sidecar",
                    accessMode = "shared",
                    createdByUserId = null,
                    revision = 3L,
                    updatedAt = 0L,
                    createdAt = 0L,
                    deletedAt = null,
                    metadataRegion = "de",
                )
            contractJson.decodeFromString<com.calypsan.listenup.api.sync.LibrarySyncPayload>(contractJson.encodeToString(v)) shouldBe v
            contractJson
                .decodeFromString<com.calypsan.listenup.api.sync.LibrarySyncPayload>(
                    """{"id":"lib","name":"L","metadataPrecedence":"e","accessMode":"shared","createdByUserId":null,""" +
                        """"revision":1,"updatedAt":0,"createdAt":0,"deletedAt":null}""",
                ).metadataRegion shouldBe null
        }
    })
