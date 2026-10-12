package dev.jacedeb.jacesocial.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.jacedeb.jacesocial.data.ChatTarget
import kotlinx.coroutines.launch

/** Start a group chat with up to 9 friends. */
@Composable
fun NewGroupScreen() {
    val store = LocalStore.current
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    val friends = store.friends.collectAsState().value?.friends ?: emptyList()
    var name by remember { mutableStateOf("") }
    val picked = remember { mutableStateListOf<String>() }
    Page("New group chat") {
        Muted("Pick up to 9 friends.")
        Field("Group name (optional)", name, { name = it }, max = 48)
        if (friends.isEmpty()) Muted("Add some friends first.", small = false)
        for (p in friends.sortedBy { it.shownName.lowercase() }) Row(Modifier.fillMaxWidth().clickable {
            if (picked.contains(p.uuid)) picked.remove(p.uuid) else if (picked.size < 9) picked.add(p.uuid)
        }, verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) { PersonRow(p.copy(unread = 0), if (p.online) describe(p) else "Offline") }
            Checkbox(picked.contains(p.uuid), null)
        }
        Button(enabled = picked.isNotEmpty(), onClick = {
            scope.launch {
                store.act {
                    val id = it.createGroup(name.trim(), picked.toList())
                    store.refreshGroups()
                    nav.back()
                    nav.chat(ChatTarget.Channel(id), name.trim().ifBlank { "Group chat" })
                }
            }
        }) { Text("Create group") }
    }
}

/** A group chat's settings: rename, picture, members (add friends, remove people as the owner), leave. */
@Composable
fun GroupSettingsScreen(id: String) {
    val store = LocalStore.current
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    val groups by store.groups.collectAsState()
    val me by store.me.collectAsState()
    val friends = store.friends.collectAsState().value?.friends ?: emptyList()
    val g = groups.firstOrNull { it.id == id }
    var dialog by remember { mutableStateOf<String?>(null) }
    val pickIcon = rememberPhotoPicker { p -> scope.launch { if (store.act { it.setGroupIcon(id, p.bytes, p.type) }) store.refreshGroups() } }
    Page(g?.name ?: "Group chat") {
        if (g == null) { Muted("Loading…", small = false); return@Page }
        val muted = me?.settings?.muted ?: emptyList()
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            IconPic(g.name, g.icon_url, 64, androidx.compose.foundation.shape.CircleShape)
            Column2 {
                OutlinedButton(onClick = pickIcon) { Text("Change picture") }
                OutlinedButton(onClick = { dialog = "rename" }) { Text("Rename") }
            }
        }
        ToggleRow("Mute this group", "No notifications for it (mentions still come through)", muted.contains(id)) {
            store.saveSettings(dev.jacedeb.jacesocial.data.jo("muted" to if (it) muted + id else muted - id))
        }
        OutlinedButton(onClick = { nav.withMic { store.voice.join(me?.uuid ?: "", id, null, g.name); nav.go("voice") } }) { Text("📞 Start or join the group call") }
        Heading("Members — ${g.members.size}")
        for (m in g.members) Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable { nav.profile(m.uuid) }) {
            Box(Modifier.weight(1f)) { PersonRow(m.copy(unread = 0), if (m.uuid == g.owner) "👑 Owner" else if (m.online) describe(m) else "Offline") }
            if (me?.uuid == g.owner && m.uuid != me?.uuid) TextButton(onClick = { dialog = "remove:${m.uuid}" }) { Text("Remove", color = JaceColors.red) }
        }
        val addable = friends.filter { f -> g.members.none { it.uuid == f.uuid } }
        if (addable.isNotEmpty() && g.members.size < 10) Button(onClick = { dialog = "add" }) { Text("＋ Add a friend") }
        Spacer(Modifier.width(1.dp))
        TextButton(onClick = { dialog = "leave" }) { Text("Leave group", color = JaceColors.red) }
        when (val dlg = dialog) {
            "rename" -> Prompt("Rename the group", g.name, yes = "Rename", onDismiss = { dialog = null }) { n ->
                scope.launch { if (store.act { it.renameGroup(id, n) }) store.refreshGroups() }
            }
            "add" -> Choose("Add a friend", addable.map { it.uuid to it.shownName }, onDismiss = { dialog = null }) { u ->
                scope.launch { if (store.act { it.addToGroup(id, u) }) store.refreshGroups() }
            }
            "leave" -> Confirm("Leave ${g.name}?", yes = "Leave", danger = true, onDismiss = { dialog = null }) {
                scope.launch { if (store.act { it.removeFromGroup(id, me?.uuid ?: "") }) { store.refreshGroups(); nav.go("chats") } }
            }
            else -> if (dlg != null && dlg.startsWith("remove:")) {
                val u = dlg.removePrefix("remove:")
                Confirm("Remove ${g.members.firstOrNull { it.uuid == u }?.shownName}?", yes = "Remove", danger = true, onDismiss = { dialog = null }) {
                    scope.launch { if (store.act { it.removeFromGroup(id, u) }) store.refreshGroups() }
                }
            }
        }
    }
}

@Composable
private fun Column2(content: @Composable () -> Unit) =
    androidx.compose.foundation.layout.Column(verticalArrangement = Arrangement.spacedBy(6.dp)) { content() }
