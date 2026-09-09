package com.calebms.openflix.desktop

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.*
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import uk.co.caprica.vlcj.player.component.EmbeddedMediaPlayerComponent
import java.awt.BorderLayout
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.MouseMotionAdapter
import javax.swing.JPanel
import com.sun.jna.NativeLibrary
import uk.co.caprica.vlcj.binding.support.runtime.RuntimeUtil
import uk.co.caprica.vlcj.factory.discovery.NativeDiscovery
import java.io.File

fun initializeVlc() {
    // Determine the application root directory when packaged
    val appDir = System.getProperty("compose.application.resources.dir")?.let { File(it) }
        ?: File(System.getProperty("user.dir"))

    val bundledVlcDir = File(appDir, "vlc")

    if (bundledVlcDir.exists()) {
        println("Loading bundled VLC from: ${bundledVlcDir.absolutePath}")
        NativeLibrary.addSearchPath(RuntimeUtil.getLibVlcCoreLibraryName(), bundledVlcDir.absolutePath)
        NativeLibrary.addSearchPath(RuntimeUtil.getLibVlcLibraryName(), bundledVlcDir.absolutePath)

        // Point VLC to its codec plugins folder
        val pluginsDir = File(bundledVlcDir, "plugins")
        if (pluginsDir.exists()) {
            System.setProperty("VLC_PLUGIN_PATH", pluginsDir.absolutePath)
        }
    } else {
        println("Bundled VLC not found. Falling back to system discovery...")
        NativeDiscovery().discover()
    }
}

fun main() {
    initializeVlc()
    application {
        val client = remember { DesktopRemoteClient() }
        val discovery = remember { DeviceDiscovery() }
        val isConnected by client.isConnected.collectAsState()

        val windowState = rememberWindowState(placement = WindowPlacement.Floating)
        val coroutineScope = rememberCoroutineScope()

        var currentTitle by remember { mutableStateOf("OpenFlix Idle") }
        var currentOverview by remember { mutableStateOf<String?>(null) }
        var hasNextEpisode by remember { mutableStateOf(false) }
        var activeMediaId by remember { mutableStateOf<String?>(null) }
        var activeEpisodeId by remember { mutableStateOf<String?>(null) }

        var isPlaying by remember { mutableStateOf(false) }
        var currentPositionMs by remember { mutableLongStateOf(0L) }
        var totalDurationMs by remember { mutableLongStateOf(0L) }

        var isControlsVisible by remember { mutableStateOf(true) }
        var hideControlsJob by remember { mutableStateOf<Job?>(null) }
        var dismissedCredits by remember { mutableStateOf(false) }

        var isSeeking by remember { mutableStateOf(false) }
        var scrubPosition by remember { mutableLongStateOf(0L) }

        val mediaPlayerComponent = remember {
            EmbeddedMediaPlayerComponent(
                "--no-video-title-show",
                "--vout=x11,any",
                "--codec=avcodec,all",
                "--network-caching=1500",
                "--clock-jitter=0",
                "--clock-synchro=0"
            )
        }

        fun scheduleHideControls() {
            hideControlsJob?.cancel()
            hideControlsJob = coroutineScope.launch {
                delay(3000)
                if (isPlaying && !isSeeking) {
                    isControlsVisible = false
                }
            }
        }

        fun showControls() {
            isControlsVisible = true
            scheduleHideControls()
        }

        fun triggerNextEpisode() {
            dismissedCredits = false
            client.sendCommand(
                RemoteMessage(
                    action = CommandAction.NEXT_EPISODE,
                    mediaId = activeMediaId,
                    episodeId = activeEpisodeId
                )
            )
        }

        fun togglePlayPause() {
            val player = mediaPlayerComponent.mediaPlayer()
            if (player.status().isPlaying) {
                player.controls().pause()
                isPlaying = false
                isControlsVisible = true
                hideControlsJob?.cancel()
            } else {
                player.controls().play()
                isPlaying = true
                scheduleHideControls()
            }
            client.sendProgressTick(activeMediaId, activeEpisodeId, player.status().time(), player.status().length(), isPlaying)
        }

        fun seekBy(offsetMs: Long) {
            val player = mediaPlayerComponent.mediaPlayer()
            val newTime = (player.status().time() + offsetMs).coerceIn(0L, totalDurationMs.coerceAtLeast(1L))
            player.controls().setTime(newTime)
            currentPositionMs = newTime
            showControls()
            client.sendProgressTick(activeMediaId, activeEpisodeId, newTime, totalDurationMs, isPlaying)
        }

        fun toggleFullscreen() {
            windowState.placement = if (windowState.placement == WindowPlacement.Fullscreen || windowState.placement == WindowPlacement.Maximized) {
                WindowPlacement.Floating
            } else {
                WindowPlacement.Fullscreen
            }
        }

        LaunchedEffect(Unit) {
            discovery.discoverPhone { host, port ->
                if (!client.isConnected.value) {
                    client.connect(host, port)
                }
            }
        }

        LaunchedEffect(Unit) {
            client.onCommandReceived = { msg ->
                when (msg.action) {
                    CommandAction.LOAD -> {
                        activeMediaId = msg.mediaId
                        activeEpisodeId = msg.episodeId
                        currentTitle = msg.title ?: "Streaming"
                        currentOverview = msg.overview
                        hasNextEpisode = msg.hasNextEpisode
                        dismissedCredits = false
                        windowState.placement = WindowPlacement.Maximized

                        val streamUrl = msg.streamUrl
                        if (!streamUrl.isNullOrBlank()) {
                            val startSec = msg.positionMs / 1000.0
                            if (startSec > 1.0) {
                                mediaPlayerComponent.mediaPlayer().media().play(streamUrl, ":start-time=$startSec")
                            } else {
                                mediaPlayerComponent.mediaPlayer().media().play(streamUrl)
                            }

                            msg.subtitleUrl?.let { subUrl ->
                                if (subUrl.startsWith("http://") || subUrl.startsWith("https://")) {
                                    mediaPlayerComponent.mediaPlayer().subpictures().setSubTitleUri(subUrl)
                                } else {
                                    mediaPlayerComponent.mediaPlayer().subpictures().setSubTitleFile(subUrl)
                                }
                            }
                            isPlaying = true
                            scheduleHideControls()
                        }
                    }
                    CommandAction.PLAY -> {
                        mediaPlayerComponent.mediaPlayer().controls().play()
                        isPlaying = true
                        scheduleHideControls()
                    }
                    CommandAction.PAUSE -> {
                        mediaPlayerComponent.mediaPlayer().controls().pause()
                        isPlaying = false
                        isControlsVisible = true
                        hideControlsJob?.cancel()
                    }
                    CommandAction.SEEK -> {
                        mediaPlayerComponent.mediaPlayer().controls().setTime(msg.positionMs)
                        currentPositionMs = msg.positionMs
                        showControls()
                    }
                    CommandAction.DISCONNECT -> {
                        mediaPlayerComponent.mediaPlayer().controls().stop()
                        isPlaying = false
                        currentTitle = "OpenFlix Idle"
                        currentOverview = null
                        isControlsVisible = true
                    }
                    else -> Unit
                }
            }
        }

        LaunchedEffect(isConnected) {
            while (isActive && isConnected) {
                delay(1000)
                val player = mediaPlayerComponent.mediaPlayer()
                if (player.status().isPlaying) {
                    isPlaying = true
                    currentPositionMs = player.status().time()
                    totalDurationMs = player.status().length()
                    client.sendProgressTick(activeMediaId, activeEpisodeId, currentPositionMs, totalDurationMs, true)
                }
            }
        }

        DisposableEffect(Unit) {
            onDispose { mediaPlayerComponent.release() }
        }

        Tray(
            icon = rememberVectorPainter(Icons.Default.LiveTv),
            tooltip = "OpenFlix Desktop",
            menu = {
                Item("Quit", onClick = {
                    discovery.stop()
                    client.disconnect()
                    mediaPlayerComponent.mediaPlayer().controls().stop()
                    mediaPlayerComponent.release()
                    exitApplication()
                })
            }
        )

        Window(
            onCloseRequest = {
                mediaPlayerComponent.mediaPlayer().controls().stop()
                client.sendProgressTick(activeMediaId, activeEpisodeId, currentPositionMs, totalDurationMs, false)
                client.disconnect()
                discovery.stop()
                mediaPlayerComponent.release()
                exitApplication()
            },
            state = windowState,
            title = "OpenFlix Player - $currentTitle"
        ) {
            Column(modifier = Modifier.fillMaxSize().background(Color.Black)) {
                // Top Bar: Title, Connection status, and Overview when paused
                AnimatedVisibility(
                    visible = isControlsVisible,
                    enter = expandVertically() + fadeIn(),
                    exit = shrinkVertically() + fadeOut()
                ) {
                    Surface(color = Color(0xFF181818), modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(text = currentTitle, color = Color.White, fontSize = 16.sp)
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

                            // Show synopsis when paused
                            if (!isPlaying && !currentOverview.isNullOrBlank()) {
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    text = currentOverview ?: "",
                                    color = Color.LightGray,
                                    fontSize = 12.sp,
                                    maxLines = 3,
                                    lineHeight = 16.sp
                                )
                            }
                        }
                    }
                }

                // Video Surface
                Box(modifier = Modifier.fillMaxWidth().weight(1f).background(Color.Black)) {
                    SwingPanel(
                        modifier = Modifier.fillMaxSize(),
                        factory = {
                            mediaPlayerComponent.mediaPlayer().input().enableMouseInputHandling(false)
                            mediaPlayerComponent.mediaPlayer().input().enableKeyInputHandling(false)

                            val videoSurface = mediaPlayerComponent.videoSurfaceComponent()
                            videoSurface.isFocusable = true

                            videoSurface.addKeyListener(object : KeyAdapter() {
                                override fun keyPressed(e: KeyEvent) {
                                    when (e.keyCode) {
                                        KeyEvent.VK_SPACE -> togglePlayPause()
                                        KeyEvent.VK_F, KeyEvent.VK_F11 -> toggleFullscreen()
                                        KeyEvent.VK_ESCAPE -> {
                                            if (windowState.placement == WindowPlacement.Fullscreen) {
                                                windowState.placement = WindowPlacement.Floating
                                            }
                                        }
                                        KeyEvent.VK_LEFT -> seekBy(-10000L)
                                        KeyEvent.VK_RIGHT -> seekBy(10000L)
                                        KeyEvent.VK_N -> if (hasNextEpisode) triggerNextEpisode()
                                    }
                                }
                            })

                            videoSurface.addMouseListener(object : MouseAdapter() {
                                override fun mouseClicked(e: MouseEvent) {
                                    videoSurface.requestFocusInWindow()
                                    togglePlayPause()
                                }
                            })

                            videoSurface.addMouseMotionListener(object : MouseMotionAdapter() {
                                override fun mouseMoved(e: MouseEvent) {
                                    showControls()
                                }
                            })

                            JPanel(BorderLayout()).apply {
                                background = java.awt.Color.BLACK
                                add(mediaPlayerComponent, BorderLayout.CENTER)
                            }
                        }
                    )
                }

                // Bottom Control Bar
                val isNearEnd = totalDurationMs > 30000L && currentPositionMs >= (totalDurationMs * 0.95)
                val shouldShowBottomBar = isControlsVisible || (isNearEnd && hasNextEpisode && !dismissedCredits)

                AnimatedVisibility(
                    visible = shouldShowBottomBar,
                    enter = expandVertically() + fadeIn(),
                    exit = shrinkVertically() + fadeOut()
                ) {
                    Surface(color = Color(0xFF181818), modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
                            // End-of-Episode Banner
                            if (isNearEnd && hasNextEpisode && !dismissedCredits) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(bottom = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.End
                                ) {
                                    OutlinedButton(
                                        onClick = { dismissedCredits = true },
                                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)
                                    ) {
                                        Text("Watch Credits", fontSize = 12.sp)
                                    }
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Button(
                                        onClick = { triggerNextEpisode() },
                                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE50914))
                                    ) {
                                        Icon(Icons.Default.SkipNext, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text("Next Episode Now", fontSize = 12.sp)
                                    }
                                }
                            }

                            val displayPosition = if (isSeeking) scrubPosition else currentPositionMs

                            Slider(
                                value = displayPosition.toFloat(),
                                onValueChange = {
                                    isSeeking = true
                                    scrubPosition = it.toLong()
                                },
                                onValueChangeFinished = {
                                    isSeeking = false
                                    mediaPlayerComponent.mediaPlayer().controls().setTime(scrubPosition)
                                    currentPositionMs = scrubPosition
                                    client.sendProgressTick(activeMediaId, activeEpisodeId, scrubPosition, totalDurationMs, isPlaying)
                                    scheduleHideControls()
                                },
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
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    IconButton(onClick = { togglePlayPause() }) {
                                        Icon(
                                            imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                            contentDescription = if (isPlaying) "Pause" else "Play",
                                            tint = Color.White
                                        )
                                    }

                                    IconButton(onClick = { seekBy(-10000L) }) {
                                        Icon(Icons.Default.Replay10, contentDescription = "Rewind 10s", tint = Color.White)
                                    }

                                    IconButton(onClick = { seekBy(10000L) }) {
                                        Icon(Icons.Default.Forward10, contentDescription = "Forward 10s", tint = Color.White)
                                    }

                                    if (hasNextEpisode) {
                                        IconButton(onClick = { triggerNextEpisode() }) {
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

                                IconButton(onClick = { toggleFullscreen() }) {
                                    Icon(
                                        imageVector = if (windowState.placement == WindowPlacement.Fullscreen) Icons.Default.FullscreenExit else Icons.Default.Fullscreen,
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