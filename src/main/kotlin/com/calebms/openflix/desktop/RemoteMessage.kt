package com.calebms.openflix.desktop

import kotlinx.serialization.Serializable

@Serializable
data class TrackOption(
    val id: Int,
    val name: String,
    val isSelected: Boolean = false
)

@Serializable
enum class CommandAction {
    LOAD,
    PLAY,
    PAUSE,
    SEEK,
    NEXT_EPISODE,
    SYNC_TICK,
    TRACKS_INFO,
    SET_AUDIO_TRACK,
    SET_SUBTITLE_TRACK,
    DISCONNECT
}

@Serializable
data class RemoteMessage(
    val action: CommandAction,
    val mediaId: String? = null,
    val episodeId: String? = null,
    val title: String? = null,
    val overview: String? = null,
    val streamUrl: String? = null,
    val subtitleUrl: String? = null,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val hasNextEpisode: Boolean = false,
    val isPlaying: Boolean = false,
    val isFinished: Boolean = false,
    val selectedTrackId: Int? = null,
    val audioTracks: List<TrackOption> = emptyList(),
    val subtitleTracks: List<TrackOption> = emptyList()
)