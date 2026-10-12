package dev.jacedeb.jacesocial.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.jacedeb.jacesocial.data.ChatTarget
import dev.jacedeb.jacesocial.data.Group
import dev.jacedeb.jacesocial.data.Store

/** Your conversations: friends (direct messages) and group chats, unread first. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatsScreen(store: Store, open: (ChatTarget, String) -> Unit) {
    val friends by store.friends.collectAsState()
    val groups by store.groups.collectAsState()
    val error by store.error.collectAsState()
    Column(Modifier.fillMaxSize()) {
        TopAppBar(title = { Text("Chats", fontWeight = FontWeight.Bold) })
        PullToRefreshBox(isRefreshing = false, onRefresh = { store.refreshAll() }, modifier = Modifier.fillMaxSize()) {
            LazyColumn(Modifier.fillMaxSize()) {
                error?.let { item { Text(it, color = JaceColors.red, modifier = Modifier.padding(16.dp)) } }
                val f = friends
                if (f == null) { item { Text("Loading…", color = JaceColors.muted, modifier = Modifier.padding(16.dp)) }; return@LazyColumn }
                if (f.incoming.isNotEmpty()) {
                    item { SectionTitle("Friend requests — ${f.incoming.size}") }
                    items(f.incoming, key = { "in" + it.uuid }) { PersonRow(it, "Wants to be friends") }
                }
                if (groups.isNotEmpty()) {
                    item { SectionTitle("Group chats") }
                    items(groups, key = { "g" + it.id }) { g -> GroupRow(g) { open(ChatTarget.Channel(g.id), g.name) } }
                }
                item { SectionTitle("Direct messages") }
                val sorted = f.friends.sortedWith(compareByDescending<dev.jacedeb.jacesocial.data.Person> { it.unread }
                    .thenByDescending { it.online }.thenBy { it.shownName.lowercase() })
                items(sorted, key = { it.uuid }) { p ->
                    Box(Modifier.fillMaxWidth().clickable { open(ChatTarget.Direct(p.uuid), p.shownName) }) {
                        PersonRow(p, if (p.online) describe(p) else "Offline")
                    }
                }
            }
        }
    }
}

@Composable
fun GroupRow(g: Group, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(44.dp).clip(CircleShape).background(JaceColors.panel), contentAlignment = Alignment.Center) {
            Text(g.name.take(1).uppercase(), fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(g.name, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("${g.members.size} members", color = JaceColors.muted, style = MaterialTheme.typography.bodySmall)
        }
        if (g.unread > 0) UnreadBadge(g.unread)
    }
}

@Composable
fun UnreadBadge(n: Int) {
    Box(Modifier.clip(CircleShape).background(JaceColors.red).padding(horizontal = 7.dp, vertical = 2.dp)) {
        Text("$n", style = MaterialTheme.typography.labelSmall)
    }
}
