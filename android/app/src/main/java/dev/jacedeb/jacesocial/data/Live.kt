package dev.jacedeb.jacesocial.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/** A live event from Jace Social: call, channel, command, dm_update, friends, groups, message,
 *  presence, servers, typing, voice, voice_signal (see docs/native-apps.md). */
data class LiveEvent(val event: String, val payload: JsonObject) {
    fun str(key: String): String? = (payload[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
}

/** Supabase Realtime over a websocket (the Phoenix protocol, like Jace Launcher's social_live.py):
 *  join our private channel and pass its broadcasts on. Reconnects by itself. */
class Live {
    private val http = OkHttpClient.Builder().pingInterval(20, TimeUnit.SECONDS).build()
    private val json = Json { ignoreUnknownKeys = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _events = MutableSharedFlow<LiveEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<LiveEvent> = _events
    private var socket: WebSocket? = null
    private var job: Job? = null
    private var ref = 0

    fun start(realtime: Realtime, inbox: String) {
        stop()
        if (realtime.url.isBlank() || realtime.key.isBlank() || inbox.isBlank()) return
        val ws = realtime.url.replace("https://", "wss://").replace("http://", "ws://").trimEnd('/') +
            "/realtime/v1/websocket?apikey=${URLEncoder.encode(realtime.key, "UTF-8")}&vsn=1.0.0"
        val topic = "realtime:$inbox"
        job = scope.launch {
            while (isActive) {
                val closed = kotlinx.coroutines.CompletableDeferred<Unit>()
                socket = http.newWebSocket(Request.Builder().url(ws).build(), object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        send(webSocket, topic, "phx_join", buildJsonObject {
                            put("config", buildJsonObject {
                                put("broadcast", buildJsonObject { put("ack", false); put("self", false) })
                                put("presence", buildJsonObject { put("key", "") })
                                put("private", false)
                            })
                        })
                    }

                    override fun onMessage(webSocket: WebSocket, text: String) {
                        val msg = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return
                        if (msg["event"]?.jsonPrimitive?.content != "broadcast") return
                        val inner = msg["payload"] as? JsonObject ?: return
                        val event = inner["event"]?.jsonPrimitive?.content ?: return
                        _events.tryEmit(LiveEvent(event, inner["payload"] as? JsonObject ?: JsonObject(emptyMap())))
                    }

                    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { closed.complete(Unit) }
                    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { closed.complete(Unit) }
                })
                // Phoenix wants a heartbeat every so often, or it closes the socket
                val beat = launch {
                    while (isActive) { delay(25_000); socket?.let { send(it, "phoenix", "heartbeat", JsonObject(emptyMap())) } }
                }
                closed.await()
                beat.cancel()
                delay(5_000)                          // then reconnect
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        socket?.close(1000, null)
        socket = null
    }

    private fun send(ws: WebSocket, topic: String, event: String, payload: JsonObject) {
        ref += 1
        ws.send(buildJsonObject {
            put("topic", topic); put("event", event); put("payload", payload); put("ref", ref.toString()); put("join_ref", "1")
        }.toString())
    }
}
