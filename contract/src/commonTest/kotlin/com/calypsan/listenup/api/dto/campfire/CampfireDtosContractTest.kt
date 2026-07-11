package com.calypsan.listenup.api.dto.campfire

import com.calypsan.listenup.api.contractJson
import com.calypsan.listenup.api.error.AppError
import com.calypsan.listenup.api.error.CampfireError
import com.calypsan.listenup.api.result.AppResult
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.encodeToString

class CampfireDtosContractTest :
    FunSpec({
        test("CampfireId round-trips") {
            val id = CampfireId("cf-1")
            contractJson.decodeFromString<CampfireId>(contractJson.encodeToString(id)) shouldBe id
        }

        test("CampfireAnchor round-trips") {
            val anchor =
                CampfireAnchor(
                    positionMs = 120_000,
                    capturedAtEpochMs = 1_752_105_600_000,
                    speed = 1.25f,
                    isPlaying = true,
                    stateVersion = 7,
                )
            contractJson.decodeFromString<CampfireAnchor>(contractJson.encodeToString(anchor)) shouldBe anchor
        }

        test("CampfireMember round-trips") {
            val member =
                CampfireMember(
                    userId = "user-1",
                    displayName = "Simon",
                    joinedAtEpochMs = 1_752_105_600_000,
                    isAway = false,
                    invited = false,
                )
            contractJson.decodeFromString<CampfireMember>(contractJson.encodeToString(member)) shouldBe member
        }

        test("CampfireSettings round-trips") {
            val settings =
                CampfireSettings(
                    controlMode = CampfireControlMode.HOST_ONLY,
                    inviteOnly = true,
                    invitedUserIds = listOf("user-2", "user-3"),
                )
            contractJson.decodeFromString<CampfireSettings>(contractJson.encodeToString(settings)) shouldBe settings
        }

        test("CampfireControlMode serial names are wire-stable") {
            contractJson.encodeToString(CampfireControlMode.HOST_ONLY) shouldBe "\"host_only\""
            contractJson.encodeToString(CampfireControlMode.EVERYONE) shouldBe "\"everyone\""
        }

        test("ChatMessage round-trips") {
            val message =
                ChatMessage(
                    senderId = "user-1",
                    sentAtEpochMs = 1_752_105_600_000,
                    positionMs = 272_000,
                    text = "This chapter is wild",
                )
            contractJson.decodeFromString<ChatMessage>(contractJson.encodeToString(message)) shouldBe message
        }

        test("CampfireSnapshot round-trips") {
            val snapshot =
                CampfireSnapshot(
                    id = CampfireId("cf-1"),
                    bookId = "book-1",
                    settings = CampfireSettings(CampfireControlMode.EVERYONE, inviteOnly = false),
                    anchor = CampfireAnchor(120_000, 1_752_105_600_000, 1.0f, true, 3),
                    members =
                        listOf(
                            CampfireMember("user-1", "Simon", 1_752_105_600_000, isAway = false, invited = false),
                            CampfireMember("user-2", null, 1_752_105_650_000, isAway = true, invited = false),
                        ),
                    hostUserId = "user-1",
                    recentChat =
                        listOf(
                            ChatMessage("user-1", 1_752_105_650_000, 100_000, "hi"),
                        ),
                    yourPositionMs = 60_000,
                    spoilerAhead = false,
                )
            contractJson.decodeFromString<CampfireSnapshot>(contractJson.encodeToString(snapshot)) shouldBe snapshot
        }

        test("OpenCampfireSummary round-trips") {
            val summary =
                OpenCampfireSummary(
                    id = CampfireId("cf-1"),
                    bookId = "book-1",
                    hostUserId = "user-1",
                    memberCount = 3,
                    controlMode = CampfireControlMode.HOST_ONLY,
                    inviteOnly = false,
                )
            contractJson.decodeFromString<OpenCampfireSummary>(contractJson.encodeToString(summary)) shouldBe summary
        }

        test("CampfireInvitableUser round-trips") {
            val user = CampfireInvitableUser(userId = "user-2", displayName = "Anna")
            contractJson.decodeFromString<CampfireInvitableUser>(contractJson.encodeToString(user)) shouldBe user
        }

        test("PlaybackCommand.Play round-trips polymorphically") {
            val command: PlaybackCommand = PlaybackCommand.Play(commandId = "cmd-1", expectedStateVersion = 3)
            val json = contractJson.encodeToString(PlaybackCommand.serializer(), command)
            contractJson.decodeFromString(PlaybackCommand.serializer(), json) shouldBe command
        }

        test("PlaybackCommand.Pause round-trips polymorphically") {
            val command: PlaybackCommand = PlaybackCommand.Pause(commandId = "cmd-2", expectedStateVersion = null)
            val json = contractJson.encodeToString(PlaybackCommand.serializer(), command)
            contractJson.decodeFromString(PlaybackCommand.serializer(), json) shouldBe command
        }

        test("PlaybackCommand.SeekTo round-trips polymorphically") {
            val command: PlaybackCommand =
                PlaybackCommand.SeekTo(positionMs = 45_000, commandId = "cmd-3", expectedStateVersion = 5)
            val json = contractJson.encodeToString(PlaybackCommand.serializer(), command)
            contractJson.decodeFromString(PlaybackCommand.serializer(), json) shouldBe command
        }

        test("PlaybackCommand.SetSpeed round-trips polymorphically") {
            val command: PlaybackCommand =
                PlaybackCommand.SetSpeed(speed = 1.5f, commandId = "cmd-4", expectedStateVersion = 6)
            val json = contractJson.encodeToString(PlaybackCommand.serializer(), command)
            contractJson.decodeFromString(PlaybackCommand.serializer(), json) shouldBe command
        }

        test("CampfireFrame.AnchorChanged round-trips polymorphically") {
            val frame: CampfireFrame =
                CampfireFrame.AnchorChanged(
                    anchor = CampfireAnchor(30_000, 1_752_105_600_000, 1.0f, false, 2),
                    byUserId = "user-1",
                    commandId = "cmd-1",
                )
            val json = contractJson.encodeToString(CampfireFrame.serializer(), frame)
            contractJson.decodeFromString(CampfireFrame.serializer(), json) shouldBe frame
        }

        test("CampfireFrame.MemberJoined round-trips polymorphically") {
            val frame: CampfireFrame =
                CampfireFrame.MemberJoined(
                    CampfireMember("user-2", "Anna", 1_752_105_600_000, isAway = false, invited = false),
                )
            val json = contractJson.encodeToString(CampfireFrame.serializer(), frame)
            contractJson.decodeFromString(CampfireFrame.serializer(), json) shouldBe frame
        }

        test("CampfireFrame.MemberLeft round-trips polymorphically") {
            val frame: CampfireFrame =
                CampfireFrame.MemberLeft(
                    CampfireMember("user-2", "Anna", 1_752_105_600_000, isAway = false, invited = false),
                )
            val json = contractJson.encodeToString(CampfireFrame.serializer(), frame)
            contractJson.decodeFromString(CampfireFrame.serializer(), json) shouldBe frame
        }

        test("CampfireFrame.MemberAway round-trips polymorphically") {
            val frame: CampfireFrame =
                CampfireFrame.MemberAway(
                    CampfireMember("user-2", "Anna", 1_752_105_600_000, isAway = true, invited = false),
                )
            val json = contractJson.encodeToString(CampfireFrame.serializer(), frame)
            contractJson.decodeFromString(CampfireFrame.serializer(), json) shouldBe frame
        }

        test("CampfireFrame.HostChanged round-trips polymorphically") {
            val frame: CampfireFrame = CampfireFrame.HostChanged(userId = "user-2")
            val json = contractJson.encodeToString(CampfireFrame.serializer(), frame)
            contractJson.decodeFromString(CampfireFrame.serializer(), json) shouldBe frame
        }

        test("CampfireFrame.ControlModeChanged round-trips polymorphically") {
            val frame: CampfireFrame = CampfireFrame.ControlModeChanged(mode = CampfireControlMode.EVERYONE)
            val json = contractJson.encodeToString(CampfireFrame.serializer(), frame)
            contractJson.decodeFromString(CampfireFrame.serializer(), json) shouldBe frame
        }

        test("CampfireFrame.Chat round-trips polymorphically") {
            val frame: CampfireFrame =
                CampfireFrame.Chat(ChatMessage("user-1", 1_752_105_600_000, 100_000, "hi"))
            val json = contractJson.encodeToString(CampfireFrame.serializer(), frame)
            contractJson.decodeFromString(CampfireFrame.serializer(), json) shouldBe frame
        }

        test("CampfireFrame.Reaction round-trips polymorphically") {
            val frame: CampfireFrame = CampfireFrame.Reaction(userId = "user-1", emoji = "🔥")
            val json = contractJson.encodeToString(CampfireFrame.serializer(), frame)
            contractJson.decodeFromString(CampfireFrame.serializer(), json) shouldBe frame
        }

        test("CampfireFrame.CampfireEnded round-trips polymorphically") {
            val frame: CampfireFrame = CampfireFrame.CampfireEnded(reason = "Host ended the campfire")
            val json = contractJson.encodeToString(CampfireFrame.serializer(), frame)
            contractJson.decodeFromString(CampfireFrame.serializer(), json) shouldBe frame
        }

        test("CampfireError.CampfireNotFound round-trips through AppError polymorphism") {
            val error: AppError = CampfireError.CampfireNotFound(correlationId = "corr-1")
            val json = contractJson.encodeToString(error)
            val decoded = contractJson.decodeFromString<AppError>(json)
            decoded shouldBe error
        }

        test("CampfireError.CampfireFull round-trips as an AppResult.Failure") {
            val result: AppResult<Unit> = AppResult.Failure(CampfireError.CampfireFull())
            contractJson.decodeFromString<AppResult<Unit>>(contractJson.encodeToString(result)) shouldBe result
        }

        test("CampfireError.NotAMember round-trips as an AppResult.Failure") {
            val result: AppResult<Unit> = AppResult.Failure(CampfireError.NotAMember())
            contractJson.decodeFromString<AppResult<Unit>>(contractJson.encodeToString(result)) shouldBe result
        }

        test("CampfireError.NotController round-trips as an AppResult.Failure") {
            val result: AppResult<Unit> = AppResult.Failure(CampfireError.NotController())
            contractJson.decodeFromString<AppResult<Unit>>(contractJson.encodeToString(result)) shouldBe result
        }

        test("CampfireError.BookAccessDenied round-trips as an AppResult.Failure") {
            val result: AppResult<Unit> = AppResult.Failure(CampfireError.BookAccessDenied())
            contractJson.decodeFromString<AppResult<Unit>>(contractJson.encodeToString(result)) shouldBe result
        }

        test("CampfireError discriminators are wire-stable") {
            contractJson.encodeToString<AppError>(CampfireError.CampfireNotFound()) shouldBe
                """{"type":"CampfireError.CampfireNotFound"}"""
            contractJson.encodeToString<AppError>(CampfireError.CampfireFull()) shouldBe
                """{"type":"CampfireError.CampfireFull"}"""
            contractJson.encodeToString<AppError>(CampfireError.NotAMember()) shouldBe
                """{"type":"CampfireError.NotAMember"}"""
            contractJson.encodeToString<AppError>(CampfireError.NotController()) shouldBe
                """{"type":"CampfireError.NotController"}"""
            contractJson.encodeToString<AppError>(CampfireError.BookAccessDenied()) shouldBe
                """{"type":"CampfireError.BookAccessDenied"}"""
        }
    })
