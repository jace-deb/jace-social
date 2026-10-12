package dev.jacedeb.jacesocial.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** What the app shows everywhere: you, your friends, group chats and servers. Loaded from the
 *  API and kept current by live events. */
class Store(private val session: Session) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    val live = Live()
    val me = MutableStateFlow<Me?>(null)
    val friends = MutableStateFlow<FriendsData?>(null)
    val groups = MutableStateFlow<List<Group>>(emptyList())
    val servers = MutableStateFlow<List<ServerItem>>(emptyList())
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error
    /** Who's typing where: chat key -> names */
    val typing = MutableStateFlow<Map<String, Set<String>>>(emptyMap())

    fun start() {
        scope.launch {
            guard { me.value = session.api.me() }
            me.value?.let { live.start(it.realtime, it.inbox) }
            refreshAll()
        }
        scope.launch {
            live.events.collect { e ->
                when (e.event) {
                    "friends", "presence" -> refreshFriends()
                    "message", "dm_update" -> refreshFriends()          // unread counts
                    "groups" -> refreshGroups()
                    "servers" -> refreshServers()
                    "channel" -> { if (e.str("server_id") == null) refreshGroups() else refreshServers() }
                    "typing" -> noteTyping(e)
                }
            }
        }
    }

    fun stop() = live.stop()

    fun refreshAll() { refreshFriends(); refreshGroups(); refreshServers() }
    fun refreshFriends() = scope.launch { guard { friends.value = session.api.friends() } }
    fun refreshGroups() = scope.launch { guard { groups.value = session.api.groups().groups } }
    fun refreshServers() = scope.launch { guard { servers.value = session.api.servers().servers } }

    fun person(uuid: String?): Person? {
        if (uuid == null) return null
        val f = friends.value
        return f?.friends?.firstOrNull { it.uuid == uuid }
            ?: groups.value.firstNotNullOfOrNull { g -> g.members.firstOrNull { it.uuid == uuid } }
    }

    private fun noteTyping(e: LiveEvent) {
        val key = e.str("channel_id")?.let { "ch:$it" } ?: e.str("from")?.let { "dm:$it" } ?: return
        val name = e.str("name") ?: return
        typing.value = typing.value + (key to ((typing.value[key] ?: emptySet()) + name))
        scope.launch {
            kotlinx.coroutines.delay(6000)
            typing.value = typing.value + (key to ((typing.value[key] ?: emptySet()) - name))
        }
    }

    private suspend fun guard(work: suspend () -> Unit) {
        try {
            work()
            _error.value = null
        } catch (e: ApiError) {
            if (e.status == 401) session.expired() else _error.value = e.message
        }
    }
}
