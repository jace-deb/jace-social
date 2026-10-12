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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import dev.jacedeb.jacesocial.data.Store

/** Your servers; tap one to open it. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServersScreen(store: Store, open: (id: String, name: String) -> Unit) {
    val servers by store.servers.collectAsState()
    Column(Modifier.fillMaxSize()) {
        TopAppBar(title = { Text("Servers", fontWeight = FontWeight.Bold) })
        LazyColumn {
            if (servers.isEmpty()) item { Text("You're not in any servers yet", color = JaceColors.muted, modifier = Modifier.padding(16.dp)) }
            items(servers, key = { it.id }) { s ->
                Row(Modifier.fillMaxWidth().clickable { open(s.id, s.name) }.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(48.dp).clip(RoundedCornerShape(16.dp)).background(JaceColors.panel), contentAlignment = Alignment.Center) {
                        if (s.icon_url != null) AsyncImage(s.icon_url, s.name, Modifier.fillMaxSize())
                        else Text(s.name.split(" ").mapNotNull { it.firstOrNull() }.take(2).joinToString(""), fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.width(12.dp))
                    Text(s.name, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    if (s.unread > 0) UnreadBadge(s.unread)
                }
            }
        }
    }
}
