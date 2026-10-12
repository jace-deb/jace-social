package dev.jacedeb.jacesocial.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import dev.jacedeb.jacesocial.data.Override
import dev.jacedeb.jacesocial.data.P
import dev.jacedeb.jacesocial.data.ServerDetail
import dev.jacedeb.jacesocial.data.jo
import kotlinx.coroutines.launch

private val SLOWMODE = listOf(0 to "Off", 5 to "5 seconds", 10 to "10 seconds", 30 to "30 seconds", 60 to "1 minute",
    300 to "5 minutes", 900 to "15 minutes", 3600 to "1 hour", 21600 to "6 hours")

/** Channel settings: name, topic, slowmode, category, user limit (voice), and permissions for
 *  roles or members (allow / inherit / deny). */
@Composable
fun ChannelSettingsScreen(serverId: String, channelId: String) {
    val store = LocalStore.current
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    var d by remember { mutableStateOf<ServerDetail?>(null) }
    suspend fun load() { store.act { d = it.server(serverId) } }
    LaunchedEffect(serverId) { load() }
    val detail = d
    val channel = detail?.channels?.firstOrNull { it.id == channelId }
    if (detail == null || channel == null) { Page("Channel settings") { Muted("Loading…", small = false) }; return }
    val kind = channel.kind
    var tab by remember { mutableStateOf(0) }
    var name by remember(channel.id) { mutableStateOf(channel.name) }
    var topic by remember(channel.id) { mutableStateOf(channel.topic ?: "") }
    var slowmode by remember(channel.id) { mutableStateOf(channel.slowmode) }
    var limit by remember(channel.id) { mutableStateOf(channel.user_limit.toFloat()) }
    var parent by remember(channel.id) { mutableStateOf(channel.parent_id) }
    var dialog by remember { mutableStateOf<String?>(null) }
    val categories = detail.channels.filter { it.kind == "category" }
    val canRoles = P.has(detail.perms, P.MANAGE_ROLES)
    Page(if (kind == "category") channel.name else "${channelIcon(kind)} ${channel.name}") {
        if (canRoles) ScrollableTabRow(selectedTabIndex = tab, edgePadding = 0.dp) {
            Tab(tab == 0, { tab = 0 }, text = { Text("General") })
            Tab(tab == 1, { tab = 1 }, text = { Text("Permissions") })
        }
        if (tab == 0) {
            Field("Name", name, { name = it }, max = 48)
            if (kind != "category" && kind != "voice") {
                Field("Topic", topic, { topic = it }, max = 200, lines = 2)
                Heading("Slowmode (time between someone's messages)")
                OutlinedButton(onClick = { dialog = "slowmode" }) { Text(SLOWMODE.firstOrNull { it.first == slowmode }?.second ?: "${slowmode}s") }
            }
            if (kind == "voice") {
                Heading("User limit: ${if (limit.toInt() == 0) "none" else limit.toInt()}")
                Slider(limit, { limit = it }, valueRange = 0f..8f, steps = 7)
            }
            if (kind != "category") {
                Heading("Category")
                OutlinedButton(onClick = { dialog = "category" }) { Text(categories.firstOrNull { it.id == parent }?.name ?: "No category") }
            }
            Button(onClick = {
                scope.launch {
                    val fields = jo("name" to name, "topic" to topic, "slowmode" to slowmode, "user_limit" to limit.toInt(),
                        "parent_id" to if (kind == "category") channel.parent_id else parent)
                    if (store.act { it.updateChannel(channel.id, fields) }) { store.toast("Saved"); load() }
                }
            }) { Text("Save") }
            TextButton(onClick = { dialog = "delete" }) { Text("Delete ${if (kind == "category") "category" else "channel"}", color = JaceColors.red) }
        } else ChannelPermissions(detail, channel.id, kind) { scope.launch { load() } }
    }
    when (dialog) {
        "slowmode" -> Choose("Slowmode", SLOWMODE.map { it.first.toString() to it.second }, onDismiss = { dialog = null }) { slowmode = it.toInt() }
        "category" -> Choose("Category", listOf("" to "No category") + categories.map { it.id to it.name }, onDismiss = { dialog = null }) { parent = it.ifEmpty { null } }
        "delete" -> Confirm(if (kind == "category") "Delete the ${channel.name} category?" else "Delete ${channel.name}?",
            if (kind == "category") "Its channels stay." else "All its messages are deleted too.", yes = "Delete", danger = true, onDismiss = { dialog = null }) {
            scope.launch { if (store.act { it.deleteChannel(channel.id) }) nav.back() }
        }
    }
}

@Composable
private fun ChannelPermissions(d: ServerDetail, channelId: String, kind: String, reload: () -> Unit) {
    val store = LocalStore.current
    val scope = rememberCoroutineScope()
    val overrides = d.overrides.filter { it.channel_id == channelId }
    val everyone = d.roles.firstOrNull { it.is_default } ?: return
    data class Target(val type: String, val id: String, val label: String)
    val targets = overrides.map { o ->
        if (o.target_type == "role") Target("role", o.target_id, d.roles.firstOrNull { it.id == o.target_id }?.name ?: "deleted role")
        else Target("member", o.target_id, d.members.firstOrNull { it.uuid == o.target_id }?.shownName ?: "someone")
    }.let { if (it.none { t -> t.type == "role" && t.id == everyone.id }) listOf(Target("role", everyone.id, "@everyone")) + it else it }
    var sel by remember { mutableStateOf(0) }
    val target = targets[sel.coerceAtMost(targets.size - 1)]
    val current = overrides.firstOrNull { it.target_type == target.type && it.target_id == target.id } ?: Override(channelId, target.type, target.id)
    var draft by remember(target, d) { mutableStateOf<Pair<Long, Long>?>(null) }
    val shown = draft ?: (current.allow to current.deny)
    var adding by remember { mutableStateOf(false) }
    val everyoneOverride = overrides.firstOrNull { it.target_type == "role" && it.target_id == everyone.id }
    val isPrivate = everyoneOverride != null && (everyoneOverride.deny and P.VIEW) != 0L
    ToggleRow("Private", "Only the roles and members below can see it", isPrivate) { on ->
        val o = everyoneOverride ?: Override(channelId, "role", everyone.id)
        val deny = if (on) o.deny or P.VIEW else o.deny and P.VIEW.inv()
        scope.launch { if (store.act { it.setOverride(channelId, "role", everyone.id, o.allow and P.VIEW.inv(), deny) }) reload() }
    }
    Chips(targets.map { (if (it.type == "role") "● " else "👤 ") + it.label }, on = targets.indices.map { it == sel }) { sel = it; draft = null }
    TextButton(onClick = { adding = true }) { Text("＋ Add role or member") }
    val only: (P.Info) -> Boolean = when (kind) {
        "voice" -> { i -> i.bit in setOf(P.VIEW, P.CONNECT, P.SPEAK, P.STREAM, P.MANAGE_CHANNELS, P.MANAGE_ROLES) }
        "category" -> { _ -> true }
        else -> { i -> i.bit !in setOf(P.CONNECT, P.SPEAK, P.STREAM) }
    }
    PermissionList(0, if (d.owner) P.ALL else d.perms, {}, only, Triple(shown.first, shown.second) { a, dn -> draft = a to dn })
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(enabled = draft != null, onClick = {
            val (a, dn) = draft ?: return@Button
            scope.launch { if (store.act { it.setOverride(channelId, target.type, target.id, a, dn) }) { draft = null; reload() } }
        }) { Text("Save") }
        if (!(target.type == "role" && target.id == everyone.id) && overrides.any { it.target_id == target.id })
            OutlinedButton(onClick = { scope.launch { if (store.act { it.removeOverride(channelId, target.type, target.id) }) { sel = 0; reload() } } }) {
                Text("Remove", color = JaceColors.red)
            }
    }
    if (adding) Choose("Add a role or member", d.roles.filter { !it.is_default && targets.none { t -> t.id == it.id } }.map { "role:${it.id}" to "● ${it.name}" } +
            d.members.filter { m -> targets.none { t -> t.id == m.uuid } }.map { "member:${it.uuid}" to "👤 ${it.shownName}" },
        onDismiss = { adding = false }) { v ->
        val (type, id) = v.split(":").let { it[0] to it[1] }
        scope.launch { if (store.act { it.setOverride(channelId, type, id, 0, 0) }) { sel = targets.size; reload() } }
    }
}
