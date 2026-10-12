package dev.jacedeb.jacesocial.data

import android.content.Context
import dev.jacedeb.jacesocial.BuildConfig
import dev.jacedeb.jacesocial.rtc.CallService
import dev.jacedeb.jacesocial.rtc.Calls
import dev.jacedeb.jacesocial.rtc.Voice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject

/** What the app shows everywhere: you, your friends, group chats and servers, calls and voice.
 *  Loaded from the API and kept current by live events. */
class Store(private val session: Session, private val context: Context) {
    val api get() = session.api
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    val live = Live()
    val me = MutableStateFlow<Me?>(null)
    val friends = MutableStateFlow<FriendsData?>(null)
    val groups = MutableStateFlow<List<Group>>(emptyList())
    val servers = MutableStateFlow<List<ServerItem>>(emptyList())
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error
    /** Short messages shown at the bottom ("Saved", errors from actions, a call ending). */
    private val _toasts = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val toasts: SharedFlow<String> = _toasts
    /** Who's typing where: chat key -> names */
    val typing = MutableStateFlow<Map<String, Set<String>>>(emptyMap())
    /** A link waiting for "Open it?" (the link warning). */
    val pendingLink = MutableStateFlow<String?>(null)
    /** A newer version of the app, when there is one. */
    val update = MutableStateFlow<Release?>(null)
    /** Ask "Update now?" about this version (once per version). */
    val askUpdate = MutableStateFlow<Release?>(null)
    private val prefs = context.getSharedPreferences("jace_prefs", Context.MODE_PRIVATE)
    private var heartbeat: Job? = null

    val calls = Calls(context, session.api,
        onEnded = { toast(it) },
        onIncoming = { name -> Notify.show(me.value, "$name is calling", "Tap to answer", important = true, call = true) },
        onActive = { on -> activeChanged() ; if (!on) Notify.cancelCall() })
    val voice = Voice(session.api, onError = { toast(it) }, onActive = { activeChanged() })

    fun toast(text: String) { _toasts.tryEmit(text) }

    fun start() {
        scope.launch {
            guard { me.value = session.api.me() }
            me.value?.let { live.start(it.realtime, it.inbox); session.remember(it) }
            refreshAll()
        }
        heartbeat = scope.launch { while (isActive) { api.presence(); delay(120_000) } }
        scope.launch { delay(3_000); checkUpdate(auto = true) }
        scope.launch { while (isActive) { delay(3 * 3600_000L); checkUpdate(auto = true) } }
        scope.launch { live.events.collect { handle(it) } }
    }

    fun stop() {
        calls.hangUp()
        voice.leave()
        heartbeat?.cancel()
        CoroutineScope(Dispatchers.IO).launch { api.offline() }
        live.stop()
        scope.cancel()
    }

    private fun handle(e: LiveEvent) {
        val p = e.payload
        fun bool(k: String) = (p[k] as? kotlinx.serialization.json.JsonPrimitive)?.content == "true"
        when (e.event) {
            "friends" -> {
                refreshFriends()
                if (e.str("kind") == "request") Notify.show(me.value, "Friend request", "${e.str("name") ?: "Someone"} wants to be friends", important = true)
            }
            "presence" -> refreshFriendsSoon()
            "message" -> {
                refreshFriends()
                Notify.show(me.value, e.str("name") ?: "A friend", "Sent you a message", important = true,
                    muteKeys = listOfNotNull(e.str("from")), open = e.str("from")?.let { "chat/dm/$it?title=${java.net.URLEncoder.encode(e.str("name") ?: "", "UTF-8")}" })
                clearTyping(e.str("from")?.let { "dm:$it" }, e.str("name"))
            }
            "dm_update" -> refreshFriends()
            "groups" -> refreshGroups()
            "servers" -> refreshServers()
            "channel" -> {
                val server = e.str("server_id")
                if (server == null) refreshGroups() else refreshServers()
                if (!bool("edited") && !bool("deleted") && !bool("reacted") && e.str("name") != null && e.str("from") != me.value?.uuid) {
                    val mentioned = bool("mentioned")
                    Notify.show(me.value, e.str("name")!!, if (mentioned) "Mentioned you" else if (server != null) "New message in a server" else "New message in a group",
                        important = mentioned || server == null, muteKeys = listOfNotNull(e.str("channel_id"), server),
                        open = e.str("channel_id")?.let { "chat/ch/$it?title=" })
                }
                clearTyping(e.str("channel_id")?.let { "ch:$it" }, e.str("name"))
            }
            "typing" -> noteTyping(e)
            "voice" -> voice.refresh()
            "voice_signal" -> voice.onSignal(e)
            "call" -> calls.onSignal(e)
        }
    }

    /** A call or voice room started or ended: keep the call service running while either is on. */
    private fun activeChanged() {
        val c = calls.info.value
        val v = voice.state.value
        when {
            c.state != "idle" -> CallService.start(context, if (c.state == "ringing") "${c.peerName} is calling" else "Call with ${c.peerName}", c.sharing)
            v.channelId != null -> CallService.start(context, "In ${v.channelName}", v.sharing)
            else -> CallService.stop(context)
        }
    }
    /** Share your screen in the call or voice room, after the system's consent. Android wants the
     *  call service running as a screen capture service first. */
    fun startScreenShare(consent: android.content.Intent, inRoom: Boolean) = scope.launch {
        val title = if (inRoom) "In ${voice.state.value.channelName}" else "Call with ${calls.info.value.peerName}"
        CallService.start(context, title, screen = true)
        delay(700)
        try {
            if (inRoom) voice.toggleScreen(consent).join() else calls.toggleScreen(consent)
        } catch (e: Exception) { toast(e.message ?: "Couldn't share your screen"); activeChanged() }
    }
    fun stopScreenShare(inRoom: Boolean) = scope.launch {
        if (inRoom) voice.toggleScreen(null).join() else calls.toggleScreen(null)
        activeChanged()
    }

    fun refreshAll() { refreshMe(); refreshFriends(); refreshGroups(); refreshServers() }
    fun refreshMe() = scope.launch { guard { me.value = session.api.me() } }
    fun refreshFriends() = scope.launch { guard { friends.value = session.api.friends() } }
    fun refreshGroups() = scope.launch { guard { groups.value = session.api.groups().groups } }
    fun refreshServers() = scope.launch { guard { servers.value = session.api.servers().servers } }
    private var friendsSoon: Job? = null
    private fun refreshFriendsSoon() {
        if (friendsSoon?.isActive == true) return
        friendsSoon = scope.launch { delay(1500); refreshFriends() }
    }

    fun person(uuid: String?): Person? {
        if (uuid == null) return null
        val f = friends.value
        return f?.friends?.firstOrNull { it.uuid == uuid }
            ?: f?.incoming?.firstOrNull { it.uuid == uuid } ?: f?.outgoing?.firstOrNull { it.uuid == uuid }
            ?: groups.value.firstNotNullOfOrNull { g -> g.members.firstOrNull { it.uuid == uuid } }
            ?: me.value?.takeIf { it.uuid == uuid }?.asPerson()
    }

    private fun noteTyping(e: LiveEvent) {
        val key = e.str("channel_id")?.let { "ch:$it" } ?: e.str("from")?.let { "dm:$it" } ?: return
        val name = e.str("name") ?: return
        typing.value = typing.value + (key to ((typing.value[key] ?: emptySet()) + name))
        scope.launch {
            delay(8000)
            clearTyping(key, name)
        }
    }
    private fun clearTyping(key: String?, name: String?) {
        if (key == null || name == null) return
        typing.value = typing.value + (key to ((typing.value[key] ?: emptySet()) - name))
    }

    /** Run something that changes data: errors show as a toast. -> did it work */
    suspend fun act(work: suspend (Api) -> Unit): Boolean = try {
        work(session.api); true
    } catch (e: ApiError) {
        if (e.status == 401) session.expired() else toast(e.message ?: "Something went wrong")
        false
    } catch (e: Exception) {
        toast(e.message ?: "Something went wrong"); false
    }
    fun fire(work: suspend (Api) -> Unit) = scope.launch { act(work) }

    /** Accept, decline, cancel or remove, then reload the friends list. */
    fun friendAction(work: suspend (Api) -> Unit) = scope.launch {
        if (act(work)) friends.value = runCatching { session.api.friends() }.getOrNull() ?: friends.value
    }

    /** Change your profile, status or settings (PATCH /me) and show it right away. */
    fun updateMe(fields: JsonObject, saved: String? = null) = scope.launch {
        act { me.value = it.updateMe(fields); saved?.let { s -> toast(s) } }
    }
    /** Settings that follow you (only the ones in `patch` change). */
    fun saveSettings(patch: JsonObject) {
        me.value?.let { m ->
            // show it at once; the server's answer replaces it
            val merged = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }.let { j ->
                val now = j.encodeToJsonElement(Settings.serializer(), m.settings) as JsonObject
                j.decodeFromJsonElement(Settings.serializer(), JsonObject(now + patch))
            }
            me.value = m.copy(settings = merged)
        }
        updateMe(jo("settings" to patch))
    }

    // --- updates (the phone app's own version)
    var autoUpdate: Boolean
        get() = prefs.getBoolean("auto_update", true)
        set(v) { prefs.edit().putBoolean("auto_update", v).apply() }

    /** -> a message for "Check for updates" (null when there's a new version to offer) */
    suspend fun checkUpdate(auto: Boolean = false): String? {
        if (auto && !autoUpdate) return null
        val latest = try { api.latestRelease() } catch (e: Exception) { return "Couldn't check for updates - try again later" }
        if (!newer(latest.version, BuildConfig.VERSION_NAME)) { update.value = null; return "You're up to date" }
        update.value = latest
        if (!auto || prefs.getString("asked_about", "") != latest.version) {
            prefs.edit().putString("asked_about", latest.version).apply()
            askUpdate.value = latest
        }
        return null
    }

    private suspend fun guard(work: suspend () -> Unit) {
        try {
            work()
            _error.value = null
        } catch (e: ApiError) {
            if (e.status == 401) session.expired() else _error.value = e.message
        }
    }

    companion object {
        fun newer(latest: String, current: String): Boolean {
            val a = latest.split(".").map { it.toIntOrNull() ?: 0 }
            val b = current.split(".").map { it.toIntOrNull() ?: 0 }
            for (i in 0 until 3) if (a.getOrElse(i) { 0 } != b.getOrElse(i) { 0 }) return a.getOrElse(i) { 0 } > b.getOrElse(i) { 0 }
            return false
        }
    }
}
