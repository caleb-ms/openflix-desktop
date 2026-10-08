package com.calebms.openflix.desktop

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun PlayerTopBar(
    title: String,
    overview: String?,
    isConnected: Boolean,
    isPlaying: Boolean,
    isControlsVisible: Boolean,
    modifier: Modifier = Modifier
) {
    val alpha by animateFloatAsState(
        targetValue = if (isControlsVisible) 1f else 0f,
        animationSpec = tween(250),
        label = "TopBarAlpha"
    )

    Surface(
        color = Color(0xFF181818),
        modifier = modifier
            .fillMaxWidth()
            .height(if (isControlsVisible) 72.dp else 0.dp)
            .graphicsLayer { this.alpha = alpha }
    ) {
        if (isControlsVisible) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = title,
                        color = Color.White,
                        fontSize = 16.sp,
                        maxLines = 1
                    )

                    Surface(
                        color = if (isConnected) Color(0xFF1E3A24) else Color(0xFF3A1E1E),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = if (isConnected) Icons.Default.CheckCircle else Icons.Default.WifiOff,
                                contentDescription = null,
                                tint = if (isConnected) Color(0xFF46D369) else Color(0xFFE50914),
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = if (isConnected) "Linked to Phone" else "Waiting...",
                                color = if (isConnected) Color(0xFF46D369) else Color(0xFFE50914),
                                fontSize = 11.sp
                            )
                        }
                    }
                }

                if (!isPlaying && !overview.isNullOrBlank()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = overview,
                        color = Color.LightGray,
                        fontSize = 12.sp,
                        maxLines = 1,
                        lineHeight = 16.sp
                    )
                }
            }
        }
    }
}

@Composable
fun PlayerBottomBar(
    currentPositionMs: Long,
    totalDurationMs: Long,
    isPlaying: Boolean,
    isMuted: Boolean,
    volume: Float,
    isSeeking: Boolean,
    scrubPosition: Long,
    hasNextEpisode: Boolean,
    isNearEnd: Boolean,
    dismissedCredits: Boolean,
    isControlsVisible: Boolean,
    audioTracks: List<TrackOption>,
    subtitleTracks: List<TrackOption>,
    isFullscreen: Boolean,
    onPlayPauseToggle: () -> Unit,
    onSeekBy: (Long) -> Unit,
    onScrubChange: (Long) -> Unit,
    onScrubFinished: (Long) -> Unit,
    onVolumeChange: (Float) -> Unit,
    onMuteToggle: () -> Unit,
    onAudioTrackSelect: (TrackOption) -> Unit,
    onSubtitleTrackSelect: (TrackOption) -> Unit,
    onNextEpisode: () -> Unit,
    onDismissCredits: () -> Unit,
    onFullscreenToggle: () -> Unit,
    modifier: Modifier = Modifier
) {
    var showAudioMenu by remember { mutableStateOf(false) }
    var showSubtitleMenu by remember { mutableStateOf(false) }

    val shouldShow = isControlsVisible || (isNearEnd && hasNextEpisode && !dismissedCredits)
    val alpha by animateFloatAsState(
        targetValue = if (shouldShow) 1f else 0f,
        animationSpec = tween(250),
        label = "BottomBarAlpha"
    )

    Surface(
        color = Color(0xFF181818),
        modifier = modifier
            .fillMaxWidth()
            .height(if (shouldShow) Dp.Unspecified else 0.dp)
            .graphicsLayer { this.alpha = alpha }
    ) {
        if (shouldShow) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 8.dp)
            ) {
                // Next Episode Prompt Banner at End of Video
                if (isNearEnd && hasNextEpisode && !dismissedCredits) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.End
                    ) {
                        OutlinedButton(
                            onClick = onDismissCredits,
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)
                        ) {
                            Text("Watch Credits", fontSize = 12.sp)
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Button(
                            onClick = onNextEpisode,
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE50914))
                        ) {
                            Icon(Icons.Default.SkipNext, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Next Episode Now", fontSize = 12.sp)
                        }
                    }
                }

                val displayPosition = if (isSeeking) scrubPosition else currentPositionMs

                // Progress Scrubber Slider
                Slider(
                    value = displayPosition.toFloat(),
                    onValueChange = { onScrubChange(it.toLong()) },
                    onValueChangeFinished = { onScrubFinished(scrubPosition) },
                    valueRange = 0f..(if (totalDurationMs > 0) totalDurationMs.toFloat() else 1f),
                    colors = SliderDefaults.colors(
                        thumbColor = Color(0xFFE50914),
                        activeTrackColor = Color(0xFFE50914),
                        inactiveTrackColor = Color(0xFF333333)
                    )
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    // Left Control Group: Transport & Time
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = onPlayPauseToggle) {
                            Icon(
                                imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                contentDescription = if (isPlaying) "Pause" else "Play",
                                tint = Color.White
                            )
                        }

                        IconButton(onClick = { onSeekBy(-10000L) }) {
                            Icon(Icons.Default.Replay10, contentDescription = "Rewind 10s", tint = Color.White)
                        }

                        IconButton(onClick = { onSeekBy(10000L) }) {
                            Icon(Icons.Default.Forward10, contentDescription = "Forward 10s", tint = Color.White)
                        }

                        if (hasNextEpisode) {
                            IconButton(onClick = onNextEpisode) {
                                Icon(Icons.Default.SkipNext, contentDescription = "Next Episode", tint = Color.White)
                            }
                        }

                        Spacer(modifier = Modifier.width(10.dp))

                        Text(
                            text = "${formatDuration(displayPosition)} / ${formatDuration(totalDurationMs)}",
                            color = Color.LightGray,
                            fontSize = 13.sp
                        )
                    }

                    // Right Control Group: Tracks, Volume, Fullscreen
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // Audio Track Selector
                        Box {
                            IconButton(onClick = { showAudioMenu = !showAudioMenu }) {
                                Icon(
                                    imageVector = Icons.Default.GraphicEq,
                                    contentDescription = "Audio Tracks",
                                    tint = if (audioTracks.isNotEmpty()) Color.White else Color.Gray
                                )
                            }
                            DropdownMenu(
                                expanded = showAudioMenu,
                                onDismissRequest = { showAudioMenu = false },
                                modifier = Modifier.background(Color(0xFF242424))
                            ) {
                                Text(
                                    text = "Audio Track",
                                    color = Color.Gray,
                                    fontSize = 11.sp,
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                                )
                                HorizontalDivider(color = Color(0xFF333333))
                                if (audioTracks.isEmpty()) {
                                    DropdownMenuItem(
                                        text = { Text("No Audio Tracks", color = Color.Gray, fontSize = 12.sp) },
                                        onClick = { showAudioMenu = false }
                                    )
                                } else {
                                    audioTracks.forEach { track ->
                                        DropdownMenuItem(
                                            text = {
                                                Row(verticalAlignment = Alignment.CenterVertically) {
                                                    if (track.isSelected) {
                                                        Icon(
                                                            imageVector = Icons.Default.Check,
                                                            contentDescription = null,
                                                            tint = Color(0xFFE50914),
                                                            modifier = Modifier.size(16.dp)
                                                        )
                                                        Spacer(modifier = Modifier.width(6.dp))
                                                    }
                                                    Text(
                                                        text = track.name,
                                                        color = if (track.isSelected) Color.White else Color.LightGray,
                                                        fontSize = 13.sp
                                                    )
                                                }
                                            },
                                            onClick = {
                                                onAudioTrackSelect(track)
                                                showAudioMenu = false
                                            }
                                        )
                                    }
                                }
                            }
                        }

                        // Subtitle Track Selector
                        Box {
                            IconButton(onClick = { showSubtitleMenu = !showSubtitleMenu }) {
                                Icon(
                                    imageVector = Icons.Default.Subtitles,
                                    contentDescription = "Subtitle Tracks",
                                    tint = if (subtitleTracks.isNotEmpty()) Color.White else Color.Gray
                                )
                            }
                            DropdownMenu(
                                expanded = showSubtitleMenu,
                                onDismissRequest = { showSubtitleMenu = false },
                                modifier = Modifier.background(Color(0xFF242424))
                            ) {
                                Text(
                                    text = "Subtitles",
                                    color = Color.Gray,
                                    fontSize = 11.sp,
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                                )
                                HorizontalDivider(color = Color(0xFF333333))
                                if (subtitleTracks.isEmpty()) {
                                    DropdownMenuItem(
                                        text = { Text("No Subtitle Tracks", color = Color.Gray, fontSize = 12.sp) },
                                        onClick = { showSubtitleMenu = false }
                                    )
                                } else {
                                    subtitleTracks.forEach { track ->
                                        DropdownMenuItem(
                                            text = {
                                                Row(verticalAlignment = Alignment.CenterVertically) {
                                                    if (track.isSelected) {
                                                        Icon(
                                                            imageVector = Icons.Default.Check,
                                                            contentDescription = null,
                                                            tint = Color(0xFFE50914),
                                                            modifier = Modifier.size(16.dp)
                                                        )
                                                        Spacer(modifier = Modifier.width(6.dp))
                                                    }
                                                    Text(
                                                        text = track.name,
                                                        color = if (track.isSelected) Color.White else Color.LightGray,
                                                        fontSize = 13.sp
                                                    )
                                                }
                                            },
                                            onClick = {
                                                onSubtitleTrackSelect(track)
                                                showSubtitleMenu = false
                                            }
                                        )
                                    }
                                }
                            }
                        }

                        Spacer(modifier = Modifier.width(8.dp))

                        // Volume Control Group
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = onMuteToggle) {
                                Icon(
                                    imageVector = when {
                                        isMuted || volume == 0f -> Icons.Default.VolumeOff
                                        volume < 0.5f -> Icons.Default.VolumeDown
                                        else -> Icons.Default.VolumeUp
                                    },
                                    contentDescription = "Mute Toggle",
                                    tint = Color.White
                                )
                            }

                            Slider(
                                value = if (isMuted) 0f else volume,
                                onValueChange = onVolumeChange,
                                valueRange = 0f..1f,
                                modifier = Modifier.width(80.dp),
                                colors = SliderDefaults.colors(
                                    thumbColor = Color.White,
                                    activeTrackColor = Color.White,
                                    inactiveTrackColor = Color(0xFF444444)
                                )
                            )
                        }

                        Spacer(modifier = Modifier.width(8.dp))

                        IconButton(onClick = onFullscreenToggle) {
                            Icon(
                                imageVector = if (isFullscreen) Icons.Default.FullscreenExit else Icons.Default.Fullscreen,
                                contentDescription = "Toggle Fullscreen",
                                tint = Color.White
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun formatDuration(ms: Long): String {
    val totalSeconds = ms / 1000
    val seconds = totalSeconds % 60
    val minutes = (totalSeconds / 60) % 60
    val hours = totalSeconds / 3600
    return if (hours > 0) {
        String.format("%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format("%02d:%02d", minutes, seconds)
    }
}
