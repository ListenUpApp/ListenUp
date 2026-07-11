package com.calypsan.listenup.api.dto.campfire

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.jvm.JvmInline

/**
 * Type-safe wrapper for Campfire (co-listening session) IDs.
 *
 * Wrapping the id prevents accidentally passing a book, user, or any other string id
 * where a campfire id is expected at [com.calypsan.listenup.api.CampfireService] call
 * sites that thread book, user, and session identifiers together. Compiles to a
 * primitive String on the wire — wire-compatible with the plain `campfireId: String`
 * carried by [com.calypsan.listenup.api.push.PushPayload.CampfireInvite].
 */
@Serializable
@JvmInline
value class CampfireId(
    val value: String,
) {
    init {
        require(value.isNotBlank()) { "Campfire ID cannot be blank" }
    }

    override fun toString(): String = value
}

/**
 * The room's shared playback state — never a position tick. Anyone reconstructs "where the
 * room is now" as `positionMs + (now - capturedAtEpochMs) * speed` while [isPlaying]; paused
 * rooms are a fixed point at [positionMs]. Broadcast only on state *change* (see
 * [CampfireFrame.AnchorChanged]), not on a schedule.
 *
 * @property positionMs Playback position, in milliseconds, at the moment the anchor was captured.
 * @property capturedAtEpochMs Server epoch-ms when this anchor was captured.
 * @property speed Playback speed multiplier (1.0 = normal speed), shared room-wide.
 * @property isPlaying Whether the room is playing (vs. paused) as of this anchor.
 * @property stateVersion Monotonically increasing version, bumped by every applied command —
 * the basis for `expectedStateVersion` optimistic-concurrency checks on [PlaybackCommand]s.
 */
@Serializable
@SerialName("CampfireAnchor")
data class CampfireAnchor(
    val positionMs: Long,
    val capturedAtEpochMs: Long,
    val speed: Float,
    val isPlaying: Boolean,
    val stateVersion: Long,
)

/**
 * A playback command sent up from a member to the campfire. The server validates
 * (membership, control mode), applies it against the current anchor, bumps
 * [CampfireAnchor.stateVersion], and broadcasts the resulting anchor to every member —
 * including the sender, who applies it idempotently by echo-matching [commandId].
 */
@Serializable
sealed interface PlaybackCommand {
    /** Client-minted id, echoed back on the resulting [CampfireFrame.AnchorChanged] for idempotent apply. */
    val commandId: String

    /** The [CampfireAnchor.stateVersion] the client observed when issuing this command, if known. */
    val expectedStateVersion: Long?

    /** Resumes playback from the room's current computed position. */
    @Serializable
    @SerialName("PlaybackCommand.Play")
    data class Play(
        override val commandId: String,
        override val expectedStateVersion: Long? = null,
    ) : PlaybackCommand

    /** Pauses the room at its current computed position. */
    @Serializable
    @SerialName("PlaybackCommand.Pause")
    data class Pause(
        override val commandId: String,
        override val expectedStateVersion: Long? = null,
    ) : PlaybackCommand

    /** Seeks the room to [positionMs]. */
    @Serializable
    @SerialName("PlaybackCommand.SeekTo")
    data class SeekTo(
        val positionMs: Long,
        override val commandId: String,
        override val expectedStateVersion: Long? = null,
    ) : PlaybackCommand

    /** Changes the room's shared playback [speed]. */
    @Serializable
    @SerialName("PlaybackCommand.SetSpeed")
    data class SetSpeed(
        val speed: Float,
        override val commandId: String,
        override val expectedStateVersion: Long? = null,
    ) : PlaybackCommand
}

/** Who may send [PlaybackCommand]s to a campfire. */
@Serializable
enum class CampfireControlMode {
    /** Only the current host may send playback commands. */
    @SerialName("host_only")
    HOST_ONLY,

    /** Any member may send playback commands. */
    @SerialName("everyone")
    EVERYONE,
}

/**
 * One member of a campfire, as seen in [CampfireSnapshot.members] or a [CampfireFrame]
 * membership event.
 *
 * @property userId The member's user id.
 * @property displayName The member's public display name, if resolvable server-side.
 * @property joinedAtEpochMs Epoch-ms the member joined (used to pick the longest-present
 * member on host handoff).
 * @property isAway Whether the member's [com.calypsan.listenup.api.CampfireService.observeSession]
 * flow is currently disconnected (grace window before eviction).
 * @property invited Whether the member was explicitly invited but hasn't joined yet.
 */
@Serializable
@SerialName("CampfireMember")
data class CampfireMember(
    val userId: String,
    val displayName: String?,
    val joinedAtEpochMs: Long,
    val isAway: Boolean,
    val invited: Boolean,
)

/**
 * Creation/update settings for a campfire, chosen by the host at creation and mutable
 * afterward via [com.calypsan.listenup.api.CampfireService.setControlMode].
 *
 * @property controlMode Who may send playback commands.
 * @property inviteOnly Invite-only campfires never appear in [OpenCampfireSummary] discovery.
 * @property invitedUserIds Users explicitly invited to an invite-only campfire.
 */
@Serializable
@SerialName("CampfireSettings")
data class CampfireSettings(
    val controlMode: CampfireControlMode,
    val inviteOnly: Boolean,
    val invitedUserIds: List<String> = emptyList(),
)

/**
 * One chat message on a campfire's shared flow. Ephemeral — never persisted beyond the
 * room's in-memory ring buffer (see [CampfireSnapshot.recentChat]).
 *
 * @property senderId The sending member's user id.
 * @property sentAtEpochMs Epoch-ms the message was sent.
 * @property positionMs The room's computed playback position at send time — enables
 * "at 4:32:10" context rendering.
 * @property text The message body.
 */
@Serializable
@SerialName("ChatMessage")
data class ChatMessage(
    val senderId: String,
    val sentAtEpochMs: Long,
    val positionMs: Long,
    val text: String,
)

/**
 * One event on a campfire's live [com.calypsan.listenup.api.CampfireService.observeSession]
 * flow. Consumers drive session UI by folding over the sealed hierarchy.
 */
@Serializable
sealed interface CampfireFrame {
    /** The room's shared anchor changed (command applied, or membership/host change re-anchored it). */
    @Serializable
    @SerialName("CampfireFrame.AnchorChanged")
    data class AnchorChanged(
        val anchor: CampfireAnchor,
        val byUserId: String,
        val commandId: String?,
    ) : CampfireFrame

    /** A member joined the campfire. */
    @Serializable
    @SerialName("CampfireFrame.MemberJoined")
    data class MemberJoined(
        val member: CampfireMember,
    ) : CampfireFrame

    /** A member left the campfire (explicit leave, or eviction past the away grace window). */
    @Serializable
    @SerialName("CampfireFrame.MemberLeft")
    data class MemberLeft(
        val member: CampfireMember,
    ) : CampfireFrame

    /** A member's flow disconnected; they're marked away pending the grace-window eviction. */
    @Serializable
    @SerialName("CampfireFrame.MemberAway")
    data class MemberAway(
        val member: CampfireMember,
    ) : CampfireFrame

    /** The host role transferred to [userId] (explicit [com.calypsan.listenup.api.CampfireService.transferHost] or automatic handoff). */
    @Serializable
    @SerialName("CampfireFrame.HostChanged")
    data class HostChanged(
        val userId: String,
    ) : CampfireFrame

    /** The campfire's control mode changed. */
    @Serializable
    @SerialName("CampfireFrame.ControlModeChanged")
    data class ControlModeChanged(
        val mode: CampfireControlMode,
    ) : CampfireFrame

    /** A chat message was sent. */
    @Serializable
    @SerialName("CampfireFrame.Chat")
    data class Chat(
        val message: ChatMessage,
    ) : CampfireFrame

    /** A fire-and-forget emoji reaction was sent; never persisted in the snapshot. */
    @Serializable
    @SerialName("CampfireFrame.Reaction")
    data class Reaction(
        val userId: String,
        val emoji: String,
    ) : CampfireFrame

    /** The campfire ended for everyone (host ended it, or the idle sweeper reaped it). */
    @Serializable
    @SerialName("CampfireFrame.CampfireEnded")
    data class CampfireEnded(
        val reason: String,
    ) : CampfireFrame
}

/**
 * Full state of a campfire, returned by
 * [com.calypsan.listenup.api.CampfireService.createSession] and
 * [com.calypsan.listenup.api.CampfireService.joinSession] so a (re)joining client can
 * render the room without waiting on the live flow.
 *
 * @property id The campfire's id.
 * @property bookId The book being listened to together. Plain `String` — wire-consistent
 * with [com.calypsan.listenup.api.push.PushPayload.CampfireInvite.bookId].
 * @property settings The room's current settings.
 * @property anchor The room's current shared playback state.
 * @property members Current members (including away members, excluded once evicted).
 * @property hostUserId The current host's user id.
 * @property recentChat The room's chat ring buffer (last ~50 messages) for late-joiner context.
 * @property yourPositionMs The caller's own furthest playback position in this book, if any —
 * the basis for the client-side spoiler-ahead comparison.
 * @property spoilerAhead Whether the room's position is far enough ahead of [yourPositionMs]
 * that joining would move the caller's progress forward materially.
 */
@Serializable
@SerialName("CampfireSnapshot")
data class CampfireSnapshot(
    val id: CampfireId,
    val bookId: String,
    val settings: CampfireSettings,
    val anchor: CampfireAnchor,
    val members: List<CampfireMember>,
    val hostUserId: String,
    val recentChat: List<ChatMessage>,
    val yourPositionMs: Long?,
    val spoilerAhead: Boolean,
)

/**
 * A discovery-surface summary of one open (non-invite-only) campfire, returned by
 * [com.calypsan.listenup.api.CampfireService.listOpenSessions] for the live badge on a
 * book's detail page and the "Live now" Discover row.
 *
 * @property id The campfire's id.
 * @property bookId The book being listened to together.
 * @property hostUserId The current host's user id.
 * @property memberCount Current member count (for "Simon + 2 listening now").
 * @property controlMode The room's current control mode.
 * @property inviteOnly Usually `false`: an invite-only room's entry only appears in
 * `listOpenSessions` at all when the caller is already a member or was explicitly invited (see
 * [com.calypsan.listenup.api.CampfireService.listOpenSessions]), in which case this reads `true` —
 * the client already has standing to see it, so the field is informational rather than a filter.
 */
@Serializable
@SerialName("OpenCampfireSummary")
data class OpenCampfireSummary(
    val id: CampfireId,
    val bookId: String,
    val hostUserId: String,
    val memberCount: Int,
    val controlMode: CampfireControlMode,
    val inviteOnly: Boolean,
)
