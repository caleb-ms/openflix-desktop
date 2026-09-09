package com.calebms.openflix.desktop

import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class DesktopRemoteClient {

    private val client = HttpClient(CIO) {
        install(WebSockets)
    }

    private var session: DefaultClientWebSocketSession? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val _isConnected = MutableStateFlow(false)
    val isConnected = _isConnected.asStateFlow()

    var onCommandReceived: ((RemoteMessage) -> Unit)? = null

    fun connect(serverHost: String, port: Int = 8080) {
        if (_isConnected.value || session != null) return

        scope.launch {
            try {
                client.webSocket(host = serverHost, port = port, path = "/ws/remote") {
                    session = this
                    _isConnected.value = true
                    println("Connected to OpenFlix Phone Server!")

                    for (frame in incoming) {
                        if (frame is Frame.Text) {
                            val text = frame.readText()
                            try {
                                val msg = Json.decodeFromString<RemoteMessage>(text)
                                withContext(Dispatchers.Main) {
                                    onCommandReceived?.invoke(msg)
                                }
                            } catch (e: Exception) {
                                println("Failed to parse remote command: ${e.message}")
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                println("WebSocket connection failed: ${e.message}")
            } finally {
                _isConnected.value = false
                session = null
            }
        }
    }

    fun sendProgressTick(
        mediaId: String?,
        episodeId: String?,
        positionMs: Long,
        durationMs: Long,
        isPlaying: Boolean
    ) {
        val s = session ?: return
        scope.launch {
            try {
                val tick = RemoteMessage(
                    action = CommandAction.SYNC_TICK,
                    mediaId = mediaId,
                    episodeId = episodeId,
                    positionMs = positionMs,
                    durationMs = durationMs,
                    isPlaying = isPlaying,
                    isFinished = durationMs > 0 && positionMs >= (durationMs * 0.95)
                )
                s.send(Frame.Text(Json.encodeToString(tick)))
            } catch (e: Exception) {
                // Ignore transient frame errors
            }
        }
    }

    fun sendCommand(message: RemoteMessage) {
        val s = session ?: return
        scope.launch {
            try {
                s.send(Frame.Text(Json.encodeToString(message)))
            } catch (e: Exception) {
                println("Failed to send remote command: ${e.message}")
            }
        }
    }

    fun disconnect() {
        scope.launch {
            try {
                session?.close()
            } catch (_: Exception) {}
        }
    }
}