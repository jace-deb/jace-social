package dev.jacedeb.jacesocial.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.jacedeb.jacesocial.data.Channel
import dev.jacedeb.jacesocial.data.ChatTarget
import dev.jacedeb.jacesocial.data.ServerDetail
import dev.jacedeb.jacesocial.data.Session
import dev.jacedeb.jacesocial.data.Store

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

/** A server: its channels by category, who's in voice, and its members. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServerScreen(session: Session, store: Store, id: String, title: String, open: (ChatTarget, String) -> Unit, onBack: () -> Unit) {
    var detail by remember { mutableStateOf<ServerDetail?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    suspend fun load() {
        runCatching { session.api.server(id) }.onSuccess { detail = it; error = null }.onFailure { error = it.message }
    }
    LaunchedEffect(id) { load() }
    // live: channels, voice and members change
    LaunchedEffect(id) {
        store.live.events.collect { e ->
            if (e.event in setOf("servers", "voice") || (e.event == "channel" && e.str("server_id") == id)) load()
        }
    }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            title = { Text(detail?.server?.name ?: title, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis) })
        val d = detail
        LazyColumn(Modifier.fillMaxSize()) {
            error?.let { item { Text(it, color = JaceColors.red, modifier = Modifier.padding(16.dp)) } }
            if (d == null) { if (error == null) item { Text("Loading…", color = JaceColors.muted, modifier = Modifier.padding(16.dp)) }; return@LazyColumn }
            d.server.description?.takeIf { it.isNotBlank() }?.let {
                item { Text(it, color = JaceColors.muted, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) }
            }
            for ((category, list) in channelLayout(d.channels)) {
                category?.let { item(key = it.id) { SectionTitle(it.name) } }
                items(list, key = { it.id }) { c ->
                    val inVoice = d.voice.filter { it.channel_id == c.id }.mapNotNull { v -> d.members.firstOrNull { it.uuid == v.uuid } }
                    Column(Modifier.fillMaxWidth()
                        .clickable(enabled = c.kind != "voice") { open(ChatTarget.Channel(c.id), "#${c.name}") }
                        .padding(horizontal = 16.dp, vertical = 10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(channelIcon(c.kind), color = JaceColors.muted, modifier = Modifier.width(26.dp))
                            Column(Modifier.weight(1f)) {
                                Text(c.name, fontWeight = if (c.unread > 0) FontWeight.Bold else FontWeight.Normal,
                                    color = if (c.unread > 0 || c.kind == "voice") MaterialTheme.colorScheme.onBackground else JaceColors.muted)
                                c.topic?.takeIf { it.isNotBlank() }?.let {
                                    Text(it, color = JaceColors.muted, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                            if (c.unread > 0) UnreadBadge(c.unread)
                        }
                        for (p in inVoice) Row(Modifier.padding(start = 26.dp, top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Avatar(p, 24)
                            Spacer(Modifier.width(8.dp))
                            Text(p.shownName, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
            // DM unread counts don't belong in a member list
            val members = d.members.map { it.copy(unread = 0) }.sortedWith(compareBy({ !it.online }, { it.shownName.lowercase() }))
            item { SectionTitle("Members — ${members.size}") }
            items(members, key = { "m" + it.uuid }) { p -> PersonRow(p, if (p.online) describe(p) else "Offline") }
        }
    }
}
