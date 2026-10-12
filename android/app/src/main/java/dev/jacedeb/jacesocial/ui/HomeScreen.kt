package dev.jacedeb.jacesocial.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import dev.jacedeb.jacesocial.data.ApiError
import dev.jacedeb.jacesocial.data.FriendsData
import dev.jacedeb.jacesocial.data.Person
import dev.jacedeb.jacesocial.data.Session
import kotlinx.coroutines.launch

/** For now: your friends with what they're doing. (Chats, servers and the rest come next.) */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(session: Session) {
    val scope = rememberCoroutineScope()
    var data by remember { mutableStateOf<FriendsData?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var refreshing by remember { mutableStateOf(false) }

    suspend fun load() {
        try {
            data = session.api.friends()
            error = null
        } catch (e: ApiError) {
            if (e.status == 401) session.expired() else error = e.message
        }
    }
    LaunchedEffect(Unit) { load() }

    Scaffold(topBar = {
        TopAppBar(title = { Text("Friends", fontWeight = FontWeight.Bold) }, actions = {
            IconButton(onClick = { scope.launch { session.signOut() } }) {
                Icon(Icons.AutoMirrored.Filled.Logout, contentDescription = "Sign out")
            }
        })
    }) { pad ->
        PullToRefreshBox(isRefreshing = refreshing, onRefresh = { scope.launch { refreshing = true; load(); refreshing = false } },
            modifier = Modifier.padding(pad).fillMaxSize()) {
            val d = data
            when {
                error != null -> Text(error!!, color = JaceColors.red, modifier = Modifier.padding(24.dp))
                d == null -> Text("Loading…", color = JaceColors.muted, modifier = Modifier.padding(24.dp))
                else -> LazyColumn {
                    if (d.incoming.isNotEmpty()) {
                        item { SectionTitle("Friend requests — ${d.incoming.size}") }
                        items(d.incoming, key = { "in" + it.uuid }) { PersonRow(it, "Wants to be friends") }
                    }
                    val online = d.friends.filter { it.online }
                    val offline = d.friends.filter { !it.online }
                    item { SectionTitle("Online — ${online.size}") }
                    items(online, key = { it.uuid }) { PersonRow(it, describe(it)) }
                    item { SectionTitle("Offline — ${offline.size}") }
                    items(offline, key = { it.uuid }) { PersonRow(it, "Offline") }
                }
            }
        }
    }
}

@Composable
fun SectionTitle(text: String) {
    Text(text.uppercase(), color = JaceColors.muted, fontWeight = FontWeight.Bold,
        style = androidx.compose.material3.MaterialTheme.typography.labelMedium,
        modifier = Modifier.padding(start = 16.dp, top = 18.dp, bottom = 6.dp))
}

@Composable
fun PersonRow(p: Person, line: String) {
    Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Avatar(p, 44)
        Spacer(Modifier.width(12.dp))
        Column {
            Text(p.shownName, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(line, color = JaceColors.muted, maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = androidx.compose.material3.MaterialTheme.typography.bodySmall)
        }
        if (p.unread > 0) {
            Spacer(Modifier.weight(1f))
            Box(Modifier.clip(CircleShape).background(JaceColors.red).padding(horizontal = 7.dp, vertical = 2.dp)) {
                Text("${p.unread}", style = androidx.compose.material3.MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
fun Avatar(p: Person, size: Int) {
    Box {
        if (p.avatar_url != null) {
            AsyncImage(p.avatar_url, contentDescription = p.name, modifier = Modifier.size(size.dp).clip(CircleShape))
        } else {
            Box(Modifier.size(size.dp).clip(CircleShape).background(JaceColors.panel), contentAlignment = Alignment.Center) {
                Text(p.name.take(1).uppercase(), fontWeight = FontWeight.Bold)
            }
        }
        Box(Modifier.align(Alignment.BottomEnd).size((size / 3.5).dp).clip(CircleShape).background(JaceColors.bg).padding(2.dp)) {
            Box(Modifier.fillMaxSize().clip(CircleShape).background(statusColor(if (p.online) p.status else "offline")))
        }
    }
}

/** "Playing Minecraft 1.21.1", "building", or "Online" (like the website's Social.describe) */
fun describe(p: Person): String {
    val a = p.activity
    if (a != null && a.type == "playing") return "Playing Minecraft ${a.version.orEmpty()}".trim()
    if (a != null && a.type == "hosting") return "Hosting a world"
    p.custom_status?.takeIf { it.isNotBlank() }?.let { return listOfNotNull(p.status_emoji, it).joinToString(" ") }
    return when (p.status) { "idle" -> "Idle"; "dnd" -> "Do not disturb"; else -> "Online" }
}
