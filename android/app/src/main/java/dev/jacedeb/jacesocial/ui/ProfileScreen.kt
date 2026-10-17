package dev.jacedeb.jacesocial.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import dev.jacedeb.jacesocial.data.ChatTarget
import dev.jacedeb.jacesocial.data.FullProfile
import dev.jacedeb.jacesocial.data.Me
import dev.jacedeb.jacesocial.data.P
import dev.jacedeb.jacesocial.data.Person
import dev.jacedeb.jacesocial.data.ServerDetail
import dev.jacedeb.jacesocial.data.jo
import kotlinx.coroutines.launch

/** Someone's profile; opened from a server, also their roles and what you can do to them there. */
@Composable
fun ProfileScreen(uuid: String, serverId: String?) {
    val store = LocalStore.current
    val nav = LocalNav.current
    val context = LocalContext.current
    val me by store.me.collectAsState()
    val scope = rememberCoroutineScope()
    var p by remember { mutableStateOf<FullProfile?>(null) }
    var detail by remember { mutableStateOf<ServerDetail?>(null) }
    var removing by remember { mutableStateOf(false) }
    suspend fun load() {
        store.act { p = it.profile(uuid) }
        if (serverId != null) store.act { detail = it.server(serverId) }
    }
    LaunchedEffect(uuid) { load() }
    val prof = p
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Box(Modifier.fillMaxWidth().height(130.dp).background(hexColor(prof?.accent_color, androidx.compose.ui.graphics.Color(0xFF2A4A37)))) {
            prof?.banner_url?.let { AsyncImage(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
            IconButton(onClick = { nav.back() }, modifier = Modifier.padding(top = 28.dp)) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
        }
        if (prof == null) { Muted("Loading…", small = false); return@Column }
        val member = detail?.members?.firstOrNull { it.uuid == uuid }
        Column(Modifier.padding(horizontal = 16.dp).offset(y = (-44).dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Avatar(prof.asPerson(), 92)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(member?.nickname?.takeIf { it.isNotBlank() } ?: prof.display_name?.takeIf { it.isNotBlank() } ?: prof.name,
                    style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                if (prof.is_bot) BotTag()
            }
            Muted(listOfNotNull(prof.handle?.let { "@$it" } ?: prof.jace_name?.let { "@$it" }, prof.minecraft_name?.let { "⛏ $it" }, prof.pronouns).joinToString(" · "))
            Text(statusLabel(prof.status) + (prof.custom_status?.let { " · ${prof.status_emoji ?: ""} $it" } ?: "") +
                (if (!prof.online && prof.last_seen != null) " · last seen ${since(prof.last_seen)} ago" else ""))
            ActivityCard(prof.asPerson())
            prof.bio?.takeIf { it.isNotBlank() }?.let { Card { Muted("ABOUT ME"); Text(it) } }
            if (prof.links.isNotEmpty()) Chips(prof.links.map { "🔗 ${it.label.ifBlank { it.url }}" }) { i -> openLink(context, store, prof.links[i].url) }
            val d = detail
            if (d != null && member != null) {
                val roles = d.roles.filter { member.roles.contains(it.id) }.sortedByDescending { it.position }
                if (roles.isNotEmpty()) Chips(roles.map { "● ${it.name}" }, colors = roles.map { hexColor(it.color, JaceColors.gray) })
                me?.let { m -> MemberActions(d, member, m) { scope.launch { load() } } }
            }
            if (prof.mutual_servers.isNotEmpty()) Muted("Servers you share: ${prof.mutual_servers.joinToString { it.name }}")
            if (prof.is_bot && (prof.bot_owner == me?.uuid || (d != null && member != null && P.has(d.perms, P.MANAGE_SERVER))))
                OutlinedButton(onClick = { nav.go("bot-activity/${prof.uuid}" + if (prof.bot_owner == me?.uuid) "" else "?server=${d?.server?.id}") }) { Text("📜 See everything it does") }
            @OptIn(ExperimentalLayoutApi::class)
            if (!prof.is_you && !prof.is_bot) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (prof.is_friend) {
                    Button(onClick = { nav.chat(ChatTarget.Direct(uuid), prof.display_name ?: prof.name) }) { Text("Message") }
                    OutlinedButton(onClick = { store.calls.call(uuid, prof.display_name ?: prof.name); }) { Text("📞 Call") }
                    OutlinedButton(onClick = { removing = true }) { Text("Remove friend", color = JaceColors.red) }
                } else Button(onClick = { scope.launch { if (store.act { it.call("POST", "/friends", jo("uuid" to uuid).toString()) }) { store.refreshFriends(); load(); store.toast("Friend request sent") } } }) { Text("Add friend") }
            }
        }
    }
    if (removing) Confirm("Remove ${prof?.name} from your friends?", yes = "Remove", danger = true, onDismiss = { removing = false }) {
        scope.launch { if (store.act { it.removeFriend(uuid) }) { store.refreshFriends(); load() } }
    }
}

@Composable
fun BotTag() = Text(" BOT ", color = JaceColors.onGreen, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelSmall,
    modifier = Modifier.padding(start = 6.dp).clip(RoundedCornerShape(4.dp)).background(JaceColors.green).padding(horizontal = 2.dp))

/** Rich presence: what they're playing (like the website's activity card). */
@Composable
fun ActivityCard(p: Person) {
    val a = p.activity ?: return
    if (!p.online || a.type == "app") return
    val lines = mutableListOf<String>()
    val title = if (a.type == "launcher") "Jace Launcher" else listOfNotNull("Minecraft", a.version, a.loader?.takeIf { it != "vanilla" }?.let { "($it)" }).joinToString(" ")
    when (a.type) {
        "hosting" -> lines.add(a.world?.let { "Hosting \"$it\"" } ?: "Hosting a world")
        "playing" -> { a.server?.let { lines.add("On $it") } ?: a.world?.let { lines.add("In \"$it\"") }; a.modpack?.let { lines.add(it) } }
        "launcher" -> lines.add("In Jace Launcher")
    }
    a.started_at?.let { lines.add("for ${since(it)}") }
    Card {
        Muted(if (a.type == "launcher") "JACE LAUNCHER" else "PLAYING MINECRAFT")
        Text(title, fontWeight = FontWeight.Bold)
        for (l in lines) Muted(l, small = false)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun Chips(labels: List<String>, colors: List<androidx.compose.ui.graphics.Color>? = null, on: List<Boolean>? = null, onClick: ((Int) -> Unit)? = null) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        labels.forEachIndexed { i, l ->
            val selected = on?.getOrNull(i) == true
            Text(l, color = colors?.getOrNull(i) ?: MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.clip(RoundedCornerShape(14.dp)).background(if (selected) JaceColors.green.copy(alpha = 0.2f) else JaceColors.panel)
                    .border(1.dp, if (selected) JaceColors.green else androidx.compose.ui.graphics.Color.Transparent, RoundedCornerShape(14.dp))
                    .let { m -> if (onClick != null) m.clickable { onClick(i) } else m }.padding(horizontal = 10.dp, vertical = 5.dp))
        }
    }
}

/** Roles, nickname, timeout, kick, ban and handing the server over, for one member. */
@Composable
fun MemberActions(d: ServerDetail, member: Person, me: Me, changed: () -> Unit) {
    val store = LocalStore.current
    val scope = rememberCoroutineScope()
    val sid = d.server.id
    val isOwner = member.uuid == d.server.owner
    val theirTop = if (isOwner) 1e9 else (d.roles.filter { member.roles.contains(it.id) }.maxOfOrNull { it.position } ?: 0).toDouble()
    val above = d.owner || (member.uuid != me.uuid && theirTop < d.top)
    val assignable = d.roles.filter { !it.is_default && (d.owner || it.position < d.top) }.sortedByDescending { it.position }
    val timedOut = member.timeout_until?.let { parseTime(it) }?.let { it.toEpochMilli() > System.currentTimeMillis() } == true
    var dialog by remember { mutableStateOf<String?>(null) }
    fun run(work: suspend (dev.jacedeb.jacesocial.data.Api) -> Unit) = scope.launch { if (store.act(work)) changed() }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (P.has(d.perms, P.MANAGE_ROLES) && (above || member.uuid == me.uuid) && assignable.isNotEmpty()) {
            Muted("ROLES (tap to give or take)")
            Chips(assignable.map { "● ${it.name}" + if (member.roles.contains(it.id)) " ✓" else " +" },
                colors = assignable.map { hexColor(it.color, JaceColors.gray) }, on = assignable.map { member.roles.contains(it.id) }) { i ->
                val r = assignable[i]
                val roles = if (member.roles.contains(r.id)) member.roles - r.id else member.roles + r.id
                run { it.updateMember(sid, jo("uuid" to member.uuid, "roles" to roles)) }
            }
        }
        @OptIn(ExperimentalLayoutApi::class)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (if (member.uuid == me.uuid) P.has(d.perms, P.CHANGE_NICK) else P.has(d.perms, P.MANAGE_NICKS) && above)
                OutlinedButton(onClick = { dialog = "nick" }) { Text("Nickname") }
            if (member.uuid != me.uuid && above && !isOwner) {
                if (P.has(d.perms, P.TIMEOUT)) {
                    if (timedOut) OutlinedButton(onClick = { run { it.updateMember(sid, jo("uuid" to member.uuid, "timeout_until" to null)) } }) { Text("End timeout") }
                    else OutlinedButton(onClick = { dialog = "timeout" }) { Text("Time out…") }
                }
                if (P.has(d.perms, P.KICK)) OutlinedButton(onClick = { dialog = "kick" }) { Text("Kick", color = JaceColors.red) }
                if (P.has(d.perms, P.BAN)) OutlinedButton(onClick = { dialog = "ban" }) { Text("Ban", color = JaceColors.red) }
            }
            if (d.owner && member.uuid != me.uuid && !member.is_bot) OutlinedButton(onClick = { dialog = "owner" }) { Text("Make owner") }
        }
        if (timedOut) Muted("Timed out until ${member.timeout_until?.let { timeOf(it) }}")
    }
    when (dialog) {
        "nick" -> Prompt("Nickname in this server", member.nickname ?: "", note = "Empty removes it", allowEmpty = true, onDismiss = { dialog = null }) { n ->
            run { it.updateMember(sid, jo("uuid" to member.uuid, "nickname" to n)) }
        }
        "timeout" -> Choose("Time out ${member.name} for", listOf("1" to "1 minute", "5" to "5 minutes", "10" to "10 minutes", "60" to "1 hour",
            "1440" to "1 day", "10080" to "1 week"), onDismiss = { dialog = null }) { m ->
            val until = java.time.Instant.now().plusSeconds(m.toLong() * 60).toString()
            run { it.updateMember(sid, jo("uuid" to member.uuid, "timeout_until" to until)) }
        }
        "kick" -> Confirm("Kick ${member.name}?", "They can join again with an invite.", yes = "Kick", danger = true, onDismiss = { dialog = null }) {
            run { it.removeMember(sid, member.uuid) }
        }
        "ban" -> Prompt("Ban ${member.name}?", note = "They won't be able to come back. Their messages from the last 7 days are deleted too. Reason (optional):",
            yes = "Ban", allowEmpty = true, onDismiss = { dialog = null }) { reason ->
            run { it.ban(sid, member.uuid, reason, true) }
        }
        "owner" -> Confirm("Give ${d.server.name} to ${member.name}?", "You'll stop being the owner.", yes = "Make owner", danger = true, onDismiss = { dialog = null }) {
            run { it.updateMember(sid, jo("uuid" to member.uuid, "role" to "owner")) }
        }
    }
}

/** Open a link, first saying where it really goes (Settings -> Links & privacy), like the website. */
fun openLink(context: android.content.Context, store: dev.jacedeb.jacesocial.data.Store, url: String) {
    store.pendingLink.value = url
}
