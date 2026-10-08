package com.calebms.openflix.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.window.*
import com.sun.jna.NativeLibrary
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import uk.co.caprica.vlcj.binding.support.runtime.RuntimeUtil
import uk.co.caprica.vlcj.factory.discovery.NativeDiscovery
import uk.co.caprica.vlcj.player.component.EmbeddedMediaPlayerComponent
import java.awt.BorderLayout
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.MouseMotionAdapter
import java.io.File
import javax.swing.JPanel
import kotlin.system.exitProcess

fun initializeVlc() {
    val appDir = System.getProperty("compose.application.resources.dir")?.let { File(it) }
        ?: File(System.getProperty("user.dir"))

    val bundledVlcDir = File(appDir, "vlc")

    if (bundledVlcDir.exists()) {
        println("Loading bundled VLC from: ${bundledVlcDir.absolutePath}")
        NativeLibrary.addSearchPath(RuntimeUtil.getLibVlcCoreLibraryName(), bundledVlcDir.absolutePath)
        NativeLibrary.addSearchPath(RuntimeUtil.getLibVlcLibraryName(), bundledVlcDir.absolutePath)

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
        val isShuttingDown = remember { java.util.concurrent.atomic.AtomicBoolean(false) }

        var currentTitle by remember { mutableStateOf("OpenFlix Idle") }
        var currentOverview by remember { mutableStateOf<String?>(null) }
        var hasNextEpisode by remember { mutableStateOf(false) }
        var activeMediaId by remember { mutableStateOf<String?>(null) }
        var activeEpisodeId by remember { mutableStateOf<String?>(null) }

        var isPlaying by remember { mutableStateOf(false) }
        var currentPositionMs by remember { mutableLongStateOf(0L) }
        var totalDurationMs by remember { mutableLongStateOf(0L) }

        var volume by remember { mutableFloatStateOf(1.0f) }
        var isMuted by remember { mutableStateOf(false) }

        var isControlsVisible by remember { mutableStateOf(true) }
        var hideControlsJob by remember { mutableStateOf<Job?>(null) }
        var dismissedCredits by remember { mutableStateOf(false) }

        var isSeeking by remember { mutableStateOf(false) }
        var scrubPosition by remember { mutableLongStateOf(0L) }

        var audioTracks by remember { mutableStateOf<List<TrackOption>>(emptyList()) }
        var subtitleTracks by remember { mutableStateOf<List<TrackOption>>(emptyList()) }

        val isWindows = remember { System.getProperty("os.name").lowercase().contains("win") }
        val mediaPlayerComponent = remember {
            val voutParam = if (isWindows) "--vout=direct3d11,any" else "--vout=x11,any"
            EmbeddedMediaPlayerComponent(
                "--no-video-title-show",
                voutParam,
                "--codec=avcodec,all",
                "--network-caching=1500",
                "--clock-jitter=0",
                "--clock-synchro=0"
            )
        }

        fun updateVolume(newVol: Float) {
            val clampedVol = newVol.coerceIn(0f, 1f)
            volume = clampedVol
            isMuted = clampedVol == 0f
            mediaPlayerComponent.mediaPlayer().audio().setVolume((clampedVol * 100).toInt())
        }

        fun toggleMute() {
            isMuted = !isMuted
            mediaPlayerComponent.mediaPlayer().audio().setMute(isMuted)
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

        fun fetchAndSendTracks() {
            val player = mediaPlayerComponent.mediaPlayer()

            // 1. Audio Tracks
            val currentAudio = player.audio().track()
            val audioList = player.audio().trackDescriptions()?.map { desc ->
                TrackOption(
                    id = desc.id(),
                    name = desc.description(),
                    isSelected = desc.id() == currentAudio
                )
            } ?: emptyList()

            // 2. Subtitle / SPU Tracks
            val currentSub = player.subpictures().track()
            val subList = player.subpictures().trackDescriptions()?.map { desc ->
                TrackOption(
                    id = desc.id(),
                    name = if (desc.id() == -1) "Off" else desc.description(),
                    isSelected = desc.id() == currentSub
                )
            } ?: emptyList()

            audioTracks = audioList
            subtitleTracks = subList

            client.sendCommand(
                RemoteMessage(
                    action = CommandAction.TRACKS_INFO,
                    mediaId = activeMediaId,
                    episodeId = activeEpisodeId,
                    audioTracks = audioList,
                    subtitleTracks = subList
                )
            )
        }

        fun safeShutdown() {
            if (!isShuttingDown.compareAndSet(false, true)) return

            kotlin.concurrent.thread(start = true, isDaemon = false, name = "AppShutdownThread") {
                try {
                    client.sendProgressTick(activeMediaId, activeEpisodeId, currentPositionMs, totalDurationMs, false)
                    client.disconnect()
                    client.close()
                    discovery.stop()
                } catch (_: Exception) {}

                try {
                    val player = mediaPlayerComponent.mediaPlayer()
                    if (player.status().isPlaying) {
                        player.controls().stop()
                    }
                    mediaPlayerComponent.release()
                } catch (_: Exception) {}

                exitProcess(0)
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

                        coroutineScope.launch {
                            delay(1500)
                            fetchAndSendTracks()
                        }

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
                        activeMediaId = null
                        activeEpisodeId = null
                        currentTitle = "OpenFlix Idle"
                        currentOverview = null
                        isControlsVisible = true
                    }
                    CommandAction.SET_AUDIO_TRACK -> {
                        msg.selectedTrackId?.let { trackId ->
                            mediaPlayerComponent.mediaPlayer().audio().setTrack(trackId)
                            fetchAndSendTracks()
                        }
                    }
                    CommandAction.SET_SUBTITLE_TRACK -> {
                        msg.selectedTrackId?.let { trackId ->
                            mediaPlayerComponent.mediaPlayer().subpictures().setTrack(trackId)
                            fetchAndSendTracks()
                        }
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

        Tray(
            icon = rememberVectorPainter(Icons.Default.LiveTv),
            tooltip = "OpenFlix Desktop",
            menu = {
                Item("Quit", onClick = { safeShutdown() })
            }
        )

        Window(
            onCloseRequest = { safeShutdown() },
            state = windowState,
            title = "OpenFlix Player - $currentTitle"
        ) {
            val hasActiveMedia = activeMediaId != null || isPlaying

            // Global AWT Key Event Dispatcher to capture keybinds regardless of focus (Compose, Swing, or AWT)
            DisposableEffect(hasActiveMedia, isConnected, isPlaying, volume, isMuted, hasNextEpisode) {
                val dispatcher = java.awt.KeyEventDispatcher { e ->
                    if (e.id == KeyEvent.KEY_PRESSED) {
                        when (e.keyCode) {
                            KeyEvent.VK_SPACE -> {
                                if (hasActiveMedia) togglePlayPause()
                                true
                            }
                            KeyEvent.VK_F, KeyEvent.VK_F11 -> {
                                toggleFullscreen()
                                true
                            }
                            KeyEvent.VK_ESCAPE -> {
                                if (windowState.placement == WindowPlacement.Fullscreen) {
                                    windowState.placement = WindowPlacement.Floating
                                }
                                true
                            }
                            KeyEvent.VK_LEFT -> {
                                if (hasActiveMedia) seekBy(-10000L)
                                true
                            }
                            KeyEvent.VK_RIGHT -> {
                                if (hasActiveMedia) seekBy(10000L)
                                true
                            }
                            KeyEvent.VK_N -> {
                                if (hasActiveMedia && hasNextEpisode) triggerNextEpisode()
                                true
                            }
                            KeyEvent.VK_M -> {
                                toggleMute()
                                true
                            }
                            KeyEvent.VK_UP -> {
                                updateVolume((volume + 0.1f).coerceAtMost(1.0f))
                                true
                            }
                            KeyEvent.VK_DOWN -> {
                                updateVolume((volume - 0.1f).coerceAtLeast(0.0f))
                                true
                            }
                            else -> false
                        }
                    } else false
                }

                val focusManager = java.awt.KeyboardFocusManager.getCurrentKeyboardFocusManager()
                focusManager.addKeyEventDispatcher(dispatcher)

                onDispose {
                    focusManager.removeKeyEventDispatcher(dispatcher)
                }
            }

            if (!hasActiveMedia) {
                IdleScreen(isConnected = isConnected)
            } else {
                Column(modifier = Modifier.fillMaxSize().background(Color.Black)) {
                    PlayerTopBar(
                        title = currentTitle,
                        overview = currentOverview,
                        isConnected = isConnected,
                        isPlaying = isPlaying,
                        isControlsVisible = isControlsVisible
                    )

                    Box(modifier = Modifier.fillMaxWidth().weight(1f).background(Color.Black)) {
                        SwingPanel(
                            modifier = Modifier.fillMaxSize(),
                            factory = {
                                mediaPlayerComponent.mediaPlayer().input().enableMouseInputHandling(false)
                                mediaPlayerComponent.mediaPlayer().input().enableKeyInputHandling(false)

                                    val videoSurface = mediaPlayerComponent.videoSurfaceComponent()

                                videoSurface.addMouseListener(object : MouseAdapter() {
                                    override fun mouseClicked(e: MouseEvent) {
                                        togglePlayPause()
                                    }
                                })

                                videoSurface.addMouseMotionListener(object : MouseMotionAdapter() {
                                    private var lastScreenX = -1
                                    private var lastScreenY = -1

                                    override fun mouseMoved(e: MouseEvent) {
                                        val screenLoc = try { e.locationOnScreen } catch (_: Exception) { null }
                                        if (screenLoc != null) {
                                            if (screenLoc.x != lastScreenX || screenLoc.y != lastScreenY) {
                                                lastScreenX = screenLoc.x
                                                lastScreenY = screenLoc.y
                                                showControls()
                                            }
                                        } else {
                                            showControls()
                                        }
                                    }
                                })

                                JPanel(BorderLayout()).apply {
                                    background = java.awt.Color.BLACK
                                    add(mediaPlayerComponent, BorderLayout.CENTER)
                                }
                            }
                        )
                    }

                    val isNearEnd = totalDurationMs > 30000L && currentPositionMs >= (totalDurationMs * 0.95)

                    PlayerBottomBar(
                        currentPositionMs = currentPositionMs,
                        totalDurationMs = totalDurationMs,
                        isPlaying = isPlaying,
                        isMuted = isMuted,
                        volume = volume,
                        isSeeking = isSeeking,
                        scrubPosition = scrubPosition,
                        hasNextEpisode = hasNextEpisode,
                        isNearEnd = isNearEnd,
                        dismissedCredits = dismissedCredits,
                        isControlsVisible = isControlsVisible,
                        audioTracks = audioTracks,
                        subtitleTracks = subtitleTracks,
                        isFullscreen = windowState.placement == WindowPlacement.Fullscreen,
                        onPlayPauseToggle = { togglePlayPause() },
                        onSeekBy = { seekBy(it) },
                        onScrubChange = {
                            isSeeking = true
                            scrubPosition = it
                        },
                        onScrubFinished = {
                            isSeeking = false
                            mediaPlayerComponent.mediaPlayer().controls().setTime(it)
                            currentPositionMs = it
                            client.sendProgressTick(activeMediaId, activeEpisodeId, it, totalDurationMs, isPlaying)
                            scheduleHideControls()
                        },
                        onVolumeChange = { updateVolume(it) },
                        onMuteToggle = { toggleMute() },
                        onAudioTrackSelect = { track ->
                            mediaPlayerComponent.mediaPlayer().audio().setTrack(track.id)
                            fetchAndSendTracks()
                        },
                        onSubtitleTrackSelect = { track ->
                            mediaPlayerComponent.mediaPlayer().subpictures().setTrack(track.id)
                            fetchAndSendTracks()
                        },
                        onNextEpisode = { triggerNextEpisode() },
                        onDismissCredits = { dismissedCredits = true },
                        onFullscreenToggle = { toggleFullscreen() }
                    )
                }
            }
        }
    }
}