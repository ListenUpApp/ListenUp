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
 * A campfire's lifecycle phase — the co-listening lobby amendment (2026-07-11). Every room is
 * born in [LOBBY] (its anchor paused at the creator's own position) and transitions to [LIVE]
 * exactly once, via [com.calypsan.listenup.api.CampfireService.startSession] (host-only,
 * lobby-only). Chat and reactions work in both phases — people talk while gathering — but
 * playback commands are rejected in [LOBBY] with
 * [com.calypsan.listenup.api.error.CampfireError.NotStarted].
 */
@Serializable
enum class CampfirePhase {
    /** Members are gathering; the shared anchor hasn't started. */
    @SerialName("lobby")
    LOBBY,

    /** The host started the room — the shared anchor plays (or pauses, seeks, etc.) as normal. */
    @SerialName("live")
    LIVE,
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
 * @property invited Always `false` for an actual member — everyone in [CampfireSnapshot.members]
 * has, by definition, already joined. A user named in [CampfireSettings.invitedUserIds] who has
 * NOT yet joined is not a member and never appears here; a client renders the "invited — pending"
 * list by diffing `settings.invitedUserIds` against `members.map { it.userId }`. This field is
 * reserved for a future shape where pending invitees appear inline in the roster.
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
 * afterward via [com.calypsan.listenup.api.CampfireService.setControlMode] and
 * [com.calypsan.listenup.api.CampfireService.updateSettings] (the latter LOBBY-only).
 *
 * @property name The campfire's display name. The client constructs a sensible default
 * in code (e.g. "{Host}'s Campfire") — never via a localized string template, since
 * apostrophes in a display name break the string-escaping pipeline (#1079). The server
 * validates this non-blank and at most 100 characters at the `createSession`/`updateSettings`
 * boundary.
 * @property controlMode Who may send playback commands.
 * @property inviteOnly Invite-only campfires never appear in [OpenCampfireSummary] discovery.
 * @property invitedUserIds Users explicitly invited to an invite-only campfire.
 */
@Serializable
@SerialName("CampfireSettings")
data class CampfireSettings(
    val name: String,
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
    /**
     * The host started the campfire: [CampfirePhase.LOBBY] -> [CampfirePhase.LIVE]. The shared
     * moment every member's playback begins from — distinct from [AnchorChanged] because it also
     * carries the phase transition, not just a new anchor.
     */
    @Serializable
    @SerialName("CampfireFrame.CampfireStarted")
    data class CampfireStarted(
        val anchor: CampfireAnchor,
        val byUserId: String,
    ) : CampfireFrame

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
 * @property phase The room's lifecycle phase — [CampfirePhase.LOBBY] until the host calls
 * [com.calypsan.listenup.api.CampfireService.startSession].
 * @property anchor The room's current shared playback state.
 * @property members Current members (including away members, excluded once evicted).
 * @property hostUserId The current host's user id.
 * @property recentChat The room's chat ring buffer (last ~50 messages) for late-joiner context.
 * @property yourPositionMs The caller's own furthest playback position in this book, if any —
 * the basis for the client-side spoiler-ahead comparison.
 * @property spoilerAhead Whether the room's position is far enough ahead of [yourPositionMs]
 * that joining would move the caller's progress forward materially.
 * @property startedAtEpochMs Server epoch-ms when the host called
 * [com.calypsan.listenup.api.CampfireService.startSession], or `null` while still in
 * [CampfirePhase.LOBBY].
 * @property invitedPending Invited-but-not-yet-joined users, enriched with display names — the
 * lobby roster's "invited" rows (see [CampfireInvitableUser]).
 */
@Serializable
@SerialName("CampfireSnapshot")
data class CampfireSnapshot(
    val id: CampfireId,
    val bookId: String,
    val settings: CampfireSettings,
    val phase: CampfirePhase,
    val anchor: CampfireAnchor,
    val members: List<CampfireMember>,
    val hostUserId: String,
    val recentChat: List<ChatMessage>,
    val yourPositionMs: Long?,
    val spoilerAhead: Boolean,
    val startedAtEpochMs: Long? = null,
    val invitedPending: List<CampfireInvitableUser> = emptyList(),
)

/**
 * A discovery-surface summary of one open (non-invite-only) campfire, returned by
 * [com.calypsan.listenup.api.CampfireService.listOpenSessions] for the live badge on a
 * book's detail page and the "Live now" Discover row.
 *
 * @property id The campfire's id.
 * @property bookId The book being listened to together.
 * @property phase The room's lifecycle phase — discovery renders "gathering" ([CampfirePhase.LOBBY])
 * vs. "live" ([CampfirePhase.LIVE]).
 * @property name The campfire's display name.
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
    val phase: CampfirePhase,
    val name: String,
    val hostUserId: String,
    val memberCount: Int,
    val controlMode: CampfireControlMode,
    val inviteOnly: Boolean,
)

/**
 * One candidate for the create/invite sheet's user picker, returned by
 * [com.calypsan.listenup.api.CampfireService.listInvitableUsers]. Already filtered server-side to
 * users who can access the campfire's book — the design spec §7 "no dead-end invites" rule — so
 * the client never has to (and never sees) the full user roster just to build this list.
 *
 * @property userId The candidate's user id.
 * @property displayName The candidate's public display name.
 */
@Serializable
@SerialName("CampfireInvitableUser")
data class CampfireInvitableUser(
    val userId: String,
    val displayName: String,
)
