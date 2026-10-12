package dev.jacedeb.jacesocial.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.jacedeb.jacesocial.data.ChatTarget
import dev.jacedeb.jacesocial.data.InvitePreview
import dev.jacedeb.jacesocial.data.ServerDetail
import dev.jacedeb.jacesocial.data.jo
import kotlinx.coroutines.launch

/** The code from an invite link, or the code itself ("abc123", ".../abc123", ".../invite/abc123"). */
fun inviteCode(text: String) = text.trim().replace(Regex("[?#].*$"), "").trimEnd('/').substringAfterLast('/')

/** Create a server, or join one with an invite code or link. */
@Composable
fun AddServerScreen(initialCode: String) {
    val store = LocalStore.current
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf("") }
    var code by remember { mutableStateOf(initialCode) }
    Page("Add a server") {
        Heading("Create a server")
        Muted("A place for you and your friends, with text channels, voice rooms, roles and bots.")
        Field("Server name", name, { name = it }, max = 48)
        Button(enabled = name.isNotBlank(), onClick = {
            scope.launch {
                store.act { val id = it.createServer(name.trim()); store.refreshServers(); nav.back(); nav.go("server/$id?title=${enc(name.trim())}") }
            }
        }) { Text("Create") }
        Spacer(Modifier.height(8.dp))
        Heading("Join a server")
        Field("Invite code or link", code, { code = it }, max = 200)
        Button(enabled = code.isNotBlank(), onClick = { nav.back(); nav.go("invite/${enc(inviteCode(code))}") }) { Text("Join") }
    }
}

/** An invite: which server it's for, and Join. */
@Composable
fun InviteScreen(code: String) {
    val store = LocalStore.current
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    var preview by remember { mutableStateOf<InvitePreview?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(code) { runCatching { store.api.invite(code) }.onSuccess { preview = it }.onFailure { error = it.message } }
    Page("Invite") {
        error?.let { Text(it, color = JaceColors.red) }
        val p = preview ?: return@Page
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconPic(p.server.name, p.server.icon_url, 64)
            Spacer(Modifier.width(14.dp))
            Column {
                Muted("You've been invited to join")
                Text(p.server.name, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Muted("${p.server.online} online · ${p.server.members} members")
            }
        }
        p.server.description?.let { Text(it) }
        Button(onClick = {
            scope.launch {
                store.act { val r = it.joinInvite(code); store.refreshServers(); nav.back(); nav.go("server/${r.server.id}?title=${enc(r.server.name)}") }
            }
        }) { Text(if (p.joined) "Open server" else "Join ${p.server.name}") }
    }
}

/** A server's onboarding when you join it: rules to agree to, questions that give roles, then the welcome screen. */
@Composable
fun ServerOnboarding(d: ServerDetail, onDone: () -> Unit, open: (ChatTarget, String) -> Unit) {
    val store = LocalStore.current
    val scope = rememberCoroutineScope()
    val s = d.server
    val questions = s.onboarding
    var agreed by remember { mutableStateOf(false) }
    val answers = remember { mutableStateListOf<List<Int>>().apply { repeat(questions.size) { add(emptyList()) } } }
    var step by remember { mutableStateOf(if (!s.rules.isNullOrBlank()) "rules" else if (questions.isNotEmpty()) "questions" else "welcome") }
    fun submit() = scope.launch {
        if (store.act { it.finishOnboarding(s.id, answers.toList(), agreed || s.rules.isNullOrBlank()) }) step = "welcome"
    }
    Page("Welcome to ${s.name}") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconPic(s.name, s.icon_url, 48)
            Spacer(Modifier.width(12.dp))
            Column { Heading(s.name); s.description?.let { Muted(it) } }
        }
        when (step) {
            "rules" -> {
                Heading("Server rules")
                Card { Text(rich(s.rules ?: "", { null })) }
                Row(Modifier.clickable { agreed = !agreed }, verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(agreed, { agreed = it }); Text("I've read and agree to the rules")
                }
                Button(enabled = agreed, onClick = { if (questions.isNotEmpty()) step = "questions" else submit() }) { Text("Continue") }
            }
            "questions" -> {
                questions.forEachIndexed { i, q ->
                    Text(q.question, fontWeight = FontWeight.Bold)
                    if (q.multiple) Muted("Pick any that fit")
                    q.options.forEachIndexed { k, o ->
                        val on = answers[i].contains(k)
                        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                            .border(1.dp, if (on) JaceColors.green else JaceColors.panel, RoundedCornerShape(10.dp))
                            .background(if (on) JaceColors.green.copy(alpha = 0.15f) else Color.Transparent)
                            .clickable { answers[i] = if (q.multiple) (if (on) answers[i] - k else answers[i] + k) else listOf(k) }
                            .padding(12.dp)) { Text("${o.emoji ?: ""} ${o.label}".trim()) }
                    }
                }
                Button(onClick = { submit() }) { Text("Finish") }
            }
            else -> {
                s.welcome?.message?.takeIf { it.isNotBlank() }?.let { Text(it) }
                for (w in s.welcome?.channels ?: emptyList()) {
                    val c = d.channels.firstOrNull { it.id == w.id } ?: continue
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(JaceColors.panel)
                        .clickable { onDone(); open(ChatTarget.Channel(c.id), "#${c.name}") }.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(w.emoji?.ifBlank { null } ?: "#", modifier = Modifier.width(32.dp))
                        Column(Modifier.weight(1f)) { Text("#${c.name}", fontWeight = FontWeight.Bold); w.description?.let { Muted(it) } }
                        Text("›")
                    }
                }
                Button(onClick = onDone) { Text("Let's go") }
            }
        }
    }
}

/** The first-time walkthrough for new accounts: your name and picture, the look, friends, servers. */
@Composable
fun WelcomeScreen() {
    val store = LocalStore.current
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    val me = store.me.collectAsState().value ?: return
    var step by remember { mutableStateOf(0) }
    var name by remember { mutableStateOf(me.display_name ?: "") }
    var friend by remember { mutableStateOf("") }
    val sent = remember { mutableStateListOf<String>() }
    val steps = listOf("You", "Look", "Friends", "Servers")
    val pickAvatar = rememberPhotoPicker { p -> scope.launch { if (store.act { it.setAvatar(p.bytes, p.type) }) store.refreshMe() } }
    fun finish() { store.updateMe(jo("onboarded" to true)); nav.back() }
    Page("Welcome") {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            steps.forEachIndexed { i, s -> Text(s, color = if (i == step) JaceColors.green else JaceColors.muted, fontWeight = if (i == step) FontWeight.Bold else FontWeight.Normal) }
        }
        when (step) {
            0 -> {
                Heading("Welcome to Jace Social, ${me.name}!")
                Muted("Friends, chat, servers and voice, with Minecraft built in. Let's set you up. It takes a minute.", small = false)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Avatar(me.asPerson(), 72)
                    OutlinedButton(onClick = pickAvatar) { Text("Choose a picture") }
                }
                Field("Display name (what friends see)", name, { name = it }, max = 32, placeholder = me.name)
            }
            1 -> {
                Heading("Make it yours")
                for ((id, label) in THEMES) Row(Modifier.fillMaxWidth().clickable { store.saveSettings(jo("theme" to id)) }, verticalAlignment = Alignment.CenterVertically) {
                    RadioButton((me.settings.theme ?: "dark") == id, { store.saveSettings(jo("theme" to id)) }); Text(label)
                }
                Heading("Accent color")
                Swatches(me.settings.accent ?: "#3ddc84") { store.saveSettings(jo("accent" to it)) }
                Muted("You can change all of this later in You → Appearance.")
            }
            2 -> {
                Heading("Find your friends")
                Muted("Type a friend's Minecraft or Jace username to send them a request. It works even if they haven't joined yet.", small = false)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(friend, { friend = it.take(33) }, placeholder = { Text("Username") }, singleLine = true, modifier = Modifier.weight(1f))
                    Button(enabled = friend.isNotBlank(), onClick = {
                        val n = friend.trim()
                        scope.launch { if (store.act { it.addFriend(n) }) { sent.add(n); friend = ""; store.refreshFriends() } }
                    }) { Text("Send") }
                }
                for (s in sent) Text("✓ Request sent to $s", color = JaceColors.green)
            }
            else -> {
                Heading("Join a server")
                Muted("Servers are places for groups: text channels, voice rooms, roles and bots. Make one for your friends, or join one with an invite link.", small = false)
                Button(onClick = { finish(); nav.go("add-server") }) { Text("Create or join a server") }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (step > 0) OutlinedButton(onClick = { step-- }) { Text("Back") }
            TextButton(onClick = { finish() }) { Text("Skip") }
            Button(onClick = {
                if (step == 0 && name.trim() != (me.display_name ?: "")) store.updateMe(jo("display_name" to name.trim()))
                if (step == steps.size - 1) finish() else step++
            }) { Text(if (step == steps.size - 1) "Finish" else "Next") }
        }
    }
}
