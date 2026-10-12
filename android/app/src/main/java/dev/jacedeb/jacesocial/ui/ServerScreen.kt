package dev.jacedeb.jacesocial.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import dev.jacedeb.jacesocial.data.Channel
import dev.jacedeb.jacesocial.data.ChatTarget
import dev.jacedeb.jacesocial.data.P
import dev.jacedeb.jacesocial.data.ServerDetail
import dev.jacedeb.jacesocial.data.Session
import dev.jacedeb.jacesocial.data.Store
import dev.jacedeb.jacesocial.data.jo
import kotlinx.coroutines.launch

/** Channels in the order the website shows them: ones outside a category first, then each category with its own. */
fun channelLayout(channels: List<Channel>): List<Pair<Channel?, List<Channel>>> {
    val sorted = channels.sortedBy { it.position }
    val categories = sorted.filter { it.kind == "category" }
    val loose = sorted.filter { it.kind != "category" && (it.parent_id == null || categories.none { c -> c.id == it.parent_id }) }
    val out = mutableListOf<Pair<Channel?, List<Channel>>>()
    if (loose.isNotEmpty()) out.add(null to loose)
    for (c in categories) out.add(c to sorted.filter { it.parent_id == c.id && it.kind != "category" })
    return out
}

fun channelIcon(kind: String) = when (kind) { "voice" -> "🔊"; "announcement" -> "📣"; else -> "#" }

fun inviteLink(code: String) = "https://jace-social.vercel.app/$code"

/** A server: its channels by category, who's in voice, and its members; settings, invites and more in the menu. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ServerScreen(session: Session, store: Store, id: String, title: String, open: (ChatTarget, String) -> Unit, onBack: () -> Unit) {
    val nav = LocalNav.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val me by store.me.collectAsState()
    val room by store.voice.state.collectAsState()
    var detail by remember { mutableStateOf<ServerDetail?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var menu by remember { mutableStateOf(false) }
    var dialog by remember { mutableStateOf<String?>(null) }
    var channelMenu by remember { mutableStateOf<String?>(null) }
    var onboarding by remember { mutableStateOf(false) }

    suspend fun load() {
        runCatching { session.api.server(id) }.onSuccess { detail = it; error = null; if (!it.onboarded) onboarding = true }.onFailure { error = it.message }
    }
    LaunchedEffect(id) { load() }
    // live: channels, voice and members change; removed or deleted: leave the screen
    LaunchedEffect(id) {
        store.live.events.collect { e ->
            if (e.event == "servers" && e.str("server_id") == id && (e.str("kind") == "deleted" || e.str("kind") == "removed")) onBack()
            else if (e.event in setOf("servers", "voice") || (e.event == "channel" && e.str("server_id") == id)) load()
        }
    }
    val d = detail
    if (onboarding && d != null) { ServerOnboarding(d, onDone = { onboarding = false; scope.launch { load() } }, open = open); return }
    val muted = me?.settings?.muted ?: emptyList()

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            title = { Text(d?.server?.name ?: title, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            actions = {
                if (d != null) Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "Server menu") }
                    DropdownMenu(menu, { menu = false }) {
                        val code = d.server.vanity ?: d.server.invite_code
                        if (code != null) DropdownMenuItem(text = { Text("Invite people") }, onClick = { menu = false; share(context, "Join ${d.server.name} on Jace Social: ${inviteLink(code)}") })
                        if (P.has(d.perms, P.MANAGE_CHANNELS)) {
                            DropdownMenuItem(text = { Text("Create channel") }, onClick = { menu = false; dialog = "channel" })
                            DropdownMenuItem(text = { Text("Create category") }, onClick = { menu = false; dialog = "category" })
                        }
                        DropdownMenuItem(text = { Text("Server settings") }, onClick = { menu = false; nav.go("server-settings/$id") })
                        DropdownMenuItem(text = { Text(if (muted.contains(id)) "Unmute server" else "Mute server") },
                            onClick = { menu = false; store.saveSettings(jo("muted" to if (muted.contains(id)) muted - id else muted + id)) })
                        if (me?.settings?.developer == true) DropdownMenuItem(text = { Text("Copy server id") }, onClick = { menu = false; copyText(context, id) })
                        if (!d.owner) DropdownMenuItem(text = { Text("Leave server", color = JaceColors.red) }, onClick = { menu = false; dialog = "leave" })
                    }
                }
            })
        LazyColumn(Modifier.fillMaxSize()) {
            error?.let { item { Text(it, color = JaceColors.red, modifier = Modifier.padding(16.dp)) } }
            if (d == null) { if (error == null) item { Muted("Loading…", small = false) }; return@LazyColumn }
            d.server.banner_url?.let { item { AsyncImage(it, null, Modifier.fillMaxWidth().height(120.dp), contentScale = ContentScale.Crop) } }
            d.server.description?.takeIf { it.isNotBlank() }?.let {
                item { Text(it, color = JaceColors.muted, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) }
            }
            for ((category, list) in channelLayout(d.channels)) {
                category?.let { c ->
                    item(key = c.id) {
                        Box(Modifier.fillMaxWidth().combinedClickable(onLongClick = { if (P.has(d.perms, P.MANAGE_CHANNELS)) nav.go("channel-settings/$id/${c.id}") }) {}) {
                            SectionTitle(c.name)
                        }
                    }
                }
                items(list, key = { it.id }) { c ->
                    val inVoice = d.voice.filter { it.channel_id == c.id }.mapNotNull { v -> d.members.firstOrNull { it.uuid == v.uuid } }
                    val here = room.channelId == c.id
                    Box {
                        Column(Modifier.fillMaxWidth()
                            .combinedClickable(onLongClick = { channelMenu = c.id }) {
                                if (c.kind == "voice") {
                                    if (here) nav.go("voice") else nav.withMic { store.voice.join(me?.uuid ?: "", c.id, id, c.name); nav.go("voice") }
                                } else open(ChatTarget.Channel(c.id), "#${c.name}")
                            }
                            .padding(horizontal = 16.dp, vertical = 10.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(channelIcon(c.kind), color = JaceColors.muted, modifier = Modifier.width(26.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(c.name, fontWeight = if (c.unread > 0 || here) FontWeight.Bold else FontWeight.Normal,
                                        color = if (here) JaceColors.green else if (c.unread > 0 || c.kind == "voice") MaterialTheme.colorScheme.onBackground else JaceColors.muted)
                                    c.topic?.takeIf { it.isNotBlank() }?.let {
                                        Text(it, color = JaceColors.muted, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                }
                                if (muted.contains(c.id)) Text("🔕 ")
                                if (c.unread > 0) UnreadBadge(c.unread)
                            }
                            for (p in inVoice) Row(Modifier.padding(start = 26.dp, top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                                Avatar(p, 24)
                                Spacer(Modifier.width(8.dp))
                                Text(p.shownName, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                        DropdownMenu(channelMenu == c.id, { channelMenu = null }) {
                            DropdownMenuItem(text = { Text(if (muted.contains(c.id)) "Unmute channel" else "Mute channel") }, onClick = {
                                channelMenu = null
                                store.saveSettings(jo("muted" to if (muted.contains(c.id)) muted - c.id else muted + c.id))
                            })
                            if (P.has(d.perms, P.MANAGE_CHANNELS)) DropdownMenuItem(text = { Text("Channel settings") }, onClick = { channelMenu = null; nav.go("channel-settings/$id/${c.id}") })
                            if (me?.settings?.developer == true) DropdownMenuItem(text = { Text("Copy channel id") }, onClick = { channelMenu = null; copyText(context, c.id) })
                        }
                    }
                }
            }
            val members = d.members.map { it.copy(unread = 0) }.sortedWith(compareBy({ !it.online }, { it.shownName.lowercase() }))
            item { SectionTitle("Members — ${members.size}") }
            items(members, key = { "m" + it.uuid }) { p ->
                Box(Modifier.fillMaxWidth().combinedClickable(onClick = { nav.profile(p.uuid, id) })) {
                    PersonRow(p, if (p.online) describe(p) else "Offline")
                }
            }
        }
    }
    when (dialog) {
        "channel" -> Choose("What kind of channel?", listOf("text" to "# Text", "voice" to "🔊 Voice", "announcement" to "📣 Announcements"),
            onDismiss = { dialog = null }) { kind -> dialog = "name:$kind" }
        "category" -> Prompt("New category", label = "Name", yes = "Create", onDismiss = { dialog = null }) { n ->
            scope.launch { if (store.act { it.createChannel(id, n, "category", null, false) }) load() }
        }
        "leave" -> Confirm("Leave ${d?.server?.name}?", yes = "Leave", danger = true, onDismiss = { dialog = null }) {
            scope.launch { if (store.act { it.removeMember(id, me?.uuid ?: "") }) { store.refreshServers(); onBack() } }
        }
        else -> dialog?.takeIf { it.startsWith("name:") }?.let { k ->
            val kind = k.removePrefix("name:")
            Prompt("New ${if (kind == "voice") "voice" else if (kind == "announcement") "announcement" else "text"} channel", label = "Name", yes = "Create",
                onDismiss = { dialog = null }) { n ->
                scope.launch { if (store.act { it.createChannel(id, n, kind, null, false) }) load() }
            }
        }
    }
}
