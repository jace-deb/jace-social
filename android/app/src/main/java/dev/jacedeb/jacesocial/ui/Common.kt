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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import dev.jacedeb.jacesocial.data.Person

@Composable
fun SectionTitle(text: String) {
    Text(text.uppercase(), color = JaceColors.muted, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium,
        modifier = Modifier.padding(start = 16.dp, top = 18.dp, bottom = 6.dp))
}

@Composable
fun PersonRow(p: Person, line: String, actions: (@Composable () -> Unit)? = null) {
    Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Avatar(p, 44)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(p.shownName, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(line, color = JaceColors.muted, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
        }
        if (p.unread > 0) UnreadBadge(p.unread)
        actions?.invoke()
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

/** "Playing Minecraft 1.21.1", a custom status, or Online / Idle / Do not disturb (like the website) */
fun describe(p: Person): String {
    val a = p.activity
    if (a != null && a.type == "playing") return "Playing Minecraft ${a.version.orEmpty()}".trim()
    if (a != null && a.type == "hosting") return "Hosting a world"
    p.custom_status?.takeIf { it.isNotBlank() }?.let { return listOfNotNull(p.status_emoji, it).joinToString(" ") }
    return when (p.status) { "idle" -> "Idle"; "dnd" -> "Do not disturb"; else -> "Online" }
}
