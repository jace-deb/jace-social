package dev.jacedeb.jacesocial.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import dev.jacedeb.jacesocial.data.DeviceCode
import dev.jacedeb.jacesocial.data.Link
import dev.jacedeb.jacesocial.data.Person
import dev.jacedeb.jacesocial.data.Session
import dev.jacedeb.jacesocial.data.jo
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Settings -> My profile, Status, Appearance, Notifications, Links & privacy, Advanced, Accounts. */
@Composable
fun SettingsScreen(session: Session, tab: String) {
    when (tab) {
        "profile" -> ProfileSettings()
        "status" -> StatusSettings()
        "appearance" -> AppearanceSettings()
        "notifications" -> NotificationSettings()
        "links" -> LinkSettings()
        "advanced" -> AdvancedSettings()
        "accounts" -> AccountSettings(session)
    }
}

@Composable
private fun ProfileSettings() {
    val store = LocalStore.current
    val me = store.me.collectAsState().value ?: return
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf(me.display_name ?: "") }
    var handle by remember { mutableStateOf(me.handle ?: "") }
    var pronouns by remember { mutableStateOf(me.pronouns ?: "") }
    var bio by remember { mutableStateOf(me.bio ?: "") }
    var accent by remember { mutableStateOf(me.accent_color ?: "#3ddc84") }
    val links = remember { mutableStateListOf<Link>().apply { addAll(me.links.ifEmpty { listOf(Link()) }) } }
    var busy by remember { mutableStateOf(false) }
    val pickAvatar = rememberPhotoPicker { p -> scope.launch { if (store.act { it.setAvatar(p.bytes, p.type) }) store.refreshMe() } }
    val pickBanner = rememberPhotoPicker { p -> scope.launch { if (store.act { it.setBanner(p.bytes, p.type) }) store.refreshMe() } }
    Page("My profile") {
        Box(Modifier.fillMaxWidth().height(100.dp).clip(RoundedCornerShape(12.dp)).background(hexColor(accent))) {
            me.banner_url?.let { AsyncImage(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
            Row(Modifier.align(Alignment.BottomEnd).padding(8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Button(onClick = pickBanner) { Text("Change banner") }
                if (me.banner_url != null) OutlinedButton(onClick = { scope.launch { if (store.act { it.removeBanner() }) store.refreshMe() } }) { Text("Remove") }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Avatar(me.asPerson(), 72)
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedButton(onClick = pickAvatar) { Text("Change picture") }
                if (me.avatar_url != null && !me.avatar_url.contains("mc-heads.net"))
                    TextButton(onClick = { scope.launch { if (store.act { it.removeAvatar() }) store.refreshMe() } }) { Text("Remove picture") }
            }
        }
        Field("Display name", name, { name = it }, max = 32, placeholder = me.minecraft_name ?: me.jace_name ?: "")
        OutlinedTextField(handle, { handle = it.lowercase().filter { c -> c.isLetterOrDigit() || c == '_' || c == '.' }.take(20) },
            label = { Text("Handle") }, prefix = { Text("@") }, singleLine = true, enabled = me.handle_change_at == null,
            modifier = Modifier.fillMaxWidth())
        Muted(me.handle_change_at?.let { "Your unique name. You can change it again on ${it.take(10)}." }
            ?: "Your unique name: people can add you as a friend with it. 3-20 letters, numbers, _ and . - you can change it once a week.")
        Field("Pronouns", pronouns, { pronouns = it }, max = 24)
        Field("About me", bio, { bio = it }, max = 300, lines = 4)
        Text("Profile color", fontWeight = FontWeight.SemiBold)
        Swatches(accent) { accent = it ?: "#3ddc84" }
        Text("Links (up to 5)", fontWeight = FontWeight.SemiBold)
        links.forEachIndexed { i, l ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(l.label, { links[i] = l.copy(label = it.take(32)) }, placeholder = { Text("Name") }, singleLine = true, modifier = Modifier.weight(1f))
                OutlinedTextField(l.url, { links[i] = l.copy(url = it.take(300)) }, placeholder = { Text("https://…") }, singleLine = true, modifier = Modifier.weight(2f))
                IconButton(onClick = { links.removeAt(i) }) { Text("✕") }
            }
        }
        if (links.size < 5) TextButton(onClick = { links.add(Link()) }) { Text("+ Add link") }
        Button(enabled = !busy, onClick = {
            busy = true
            scope.launch {
                store.act {
                    if (handle != (me.handle ?: "")) store.me.value = it.updateMe(jo("handle" to handle))
                    store.me.value = it.updateMe(jo("display_name" to name, "pronouns" to pronouns, "bio" to bio, "accent_color" to accent,
                        "links" to links.filter { l -> l.url.isNotBlank() }.map { l -> mapOf("label" to l.label, "url" to l.url) }))
                    store.toast("Saved!")
                }
                busy = false
            }
        }) { Text(if (busy) "Saving…" else "Save profile") }
    }
}

@Composable
private fun StatusSettings() {
    val store = LocalStore.current
    val me = store.me.collectAsState().value ?: return
    var custom by remember { mutableStateOf(me.custom_status ?: "") }
    var emoji by remember { mutableStateOf(me.status_emoji ?: "") }
    Page("Status") {
        for ((s, sub) in listOf("online" to "Show that you're around", "idle" to "Show that you're away",
                                "dnd" to "No notification pop-ups", "invisible" to "You'll look offline, but can still use everything")) {
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(if (me.status == s) JaceColors.panel else Color.Transparent)
                .clickable { store.updateMe(jo("status" to s)) }.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(14.dp).clip(CircleShape).background(statusColor(if (s == "invisible") "offline" else s)))
                Spacer(Modifier.width(12.dp))
                Column { Text(statusLabel(s), fontWeight = FontWeight.SemiBold); Muted(sub) }
            }
        }
        Heading("Custom status")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(emoji, { emoji = it.take(16) }, placeholder = { Text("😀") }, singleLine = true, modifier = Modifier.width(80.dp))
            OutlinedTextField(custom, { custom = it.take(80) }, placeholder = { Text("What's up?") }, singleLine = true, modifier = Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { store.updateMe(jo("custom_status" to custom, "status_emoji" to emoji), "Status set") }) { Text("Set") }
            OutlinedButton(onClick = { custom = ""; emoji = ""; store.updateMe(jo("custom_status" to null, "status_emoji" to null)) }) { Text("Clear") }
        }
    }
}

@Composable
private fun AppearanceSettings() {
    val store = LocalStore.current
    val s = store.me.collectAsState().value?.settings ?: return
    Page("Appearance") {
        Heading("Theme")
        for ((id, label) in THEMES) Row(Modifier.fillMaxWidth().clickable { store.saveSettings(jo("theme" to id)) }, verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = (s.theme ?: "dark") == id, onClick = { store.saveSettings(jo("theme" to id)) })
            Text(label)
        }
        Heading("Accent color")
        Swatches(s.accent ?: "#3ddc84") { store.saveSettings(jo("accent" to it)) }
        var scale by remember { mutableStateOf((s.font_scale ?: 1.0).toFloat()) }
        Heading("Text size: ${(scale * 100).toInt()}%")
        Slider(scale, { scale = it }, valueRange = 0.8f..1.4f, steps = 11, onValueChangeFinished = { store.saveSettings(jo("font_scale" to scale.toDouble())) })
        ToggleRow("Compact messages", "Fit more messages on screen", s.compact == true) { store.saveSettings(jo("compact" to it)) }
        ToggleRow("Show pictures next to messages", checked = s.show_avatars != false) { store.saveSettings(jo("show_avatars" to it)) }
        ToggleRow("Show link previews", checked = s.show_embeds != false) { store.saveSettings(jo("show_embeds" to it)) }
        ToggleRow("Less motion", "Turn off animations", s.reduce_motion == true) { store.saveSettings(jo("reduce_motion" to it)) }
        ToggleRow("24-hour clock", checked = s.time_format == "24h") { store.saveSettings(jo("time_format" to if (it) "24h" else "12h")) }
        ToggleRow("Enter sends the message", "Off: Enter makes a new line, and you tap Send", s.send_on_enter != false) { store.saveSettings(jo("send_on_enter" to it)) }
    }
}

@Composable
private fun NotificationSettings() {
    val store = LocalStore.current
    val s = store.me.collectAsState().value?.settings ?: return
    Page("Notifications") {
        Heading("Notify me about")
        for ((v, label) in listOf("all" to "All messages", "mentions" to "Only direct messages, @mentions and replies", "none" to "Nothing")) {
            Row(Modifier.fillMaxWidth().clickable { store.saveSettings(jo("notify" to v)) }, verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = (s.notify ?: "all") == v, onClick = { store.saveSettings(jo("notify" to v)) })
                Text(label)
            }
        }
        ToggleRow("Pop-up notifications", "When Jace Social isn't the app you're using", s.desktop_notifications != false) { store.saveSettings(jo("desktop_notifications" to it)) }
        ToggleRow("Sounds", "For messages", s.sounds != false) { store.saveSettings(jo("sounds" to it)) }
        Muted("Do Not Disturb (in your status) turns notifications off. Long-press a server or chat to mute just that one.")
    }
}

@Composable
private fun LinkSettings() {
    val store = LocalStore.current
    val s = store.me.collectAsState().value?.settings ?: return
    var domain by remember { mutableStateOf("") }
    val trusted = s.trusted_domains ?: emptyList()
    Page("Links & privacy") {
        ToggleRow("Warn me before opening links", "Shows where a link really goes before you leave Jace Social", s.link_warning != false) {
            store.saveSettings(jo("link_warning" to it))
        }
        Heading("Trusted sites (no warning)")
        if (trusted.isEmpty()) Muted("None yet. You can trust a site from the warning, too.")
        for (d in trusted) Row(verticalAlignment = Alignment.CenterVertically) {
            Text(d, Modifier.weight(1f))
            IconButton(onClick = { store.saveSettings(jo("trusted_domains" to trusted - d)) }) { Text("✕") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(domain, { domain = it }, placeholder = { Text("example.com") }, singleLine = true, modifier = Modifier.weight(1f))
            Button(enabled = domain.isNotBlank(), onClick = {
                val d = domain.trim().lowercase().removePrefix("https://").removePrefix("http://").substringBefore("/")
                if (d.isNotEmpty()) store.saveSettings(jo("trusted_domains" to (trusted + d).distinct()))
                domain = ""
            }) { Text("Trust") }
        }
        Muted("Link previews are fetched by the Jace Social server, so websites you see previews of don't get your IP address.")
    }
}

@Composable
private fun AdvancedSettings() {
    val store = LocalStore.current
    val me = store.me.collectAsState().value ?: return
    val context = LocalContext.current
    Page("Advanced") {
        ToggleRow("Developer mode", "Show buttons to copy ids of messages, people, channels and servers (useful for bots)", me.settings.developer == true) {
            store.saveSettings(jo("developer" to it))
        }
        if (me.settings.developer == true) Card {
            Text("Your id: ${me.uuid}")
            TextButton(onClick = { copyText(context, me.uuid); store.toast("Copied") }) { Text("Copy") }
        }
    }
}

@Composable
private fun AccountSettings(session: Session) {
    val store = LocalStore.current
    val me = store.me.collectAsState().value ?: return
    val accounts by session.accounts.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var note by remember { mutableStateOf("") }
    var device by remember { mutableStateOf<DeviceCode?>(null) }
    var unlink by remember { mutableStateOf(false) }
    Page("Accounts") {
        Heading("Switch accounts")
        Muted("Accounts you've signed in with on this phone, and bots you switched to.")
        for (a in accounts) Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
            .background(if (a.uuid == me.uuid) JaceColors.panel else Color.Transparent)
            .clickable(enabled = a.uuid != me.uuid) { scope.launch { store.stop(); session.switchTo(a.uuid) } }.padding(10.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Avatar(Person(a.uuid, a.name, avatar_url = a.avatar_url, online = true, status = "online", is_bot = a.is_bot), 36)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(a.name + if (a.is_bot) "  BOT" else "", fontWeight = FontWeight.SemiBold)
                Muted(if (a.uuid == me.uuid) "Signed in now" else "Tap to switch")
            }
            if (a.uuid != me.uuid) IconButton(onClick = { session.forget(a.uuid) }) { Text("✕") }
        }
        OutlinedButton(onClick = { scope.launch { store.stop(); session.addAnother() } }) { Text("+ Add another account") }
        if (!me.is_bot) {
            Heading("Linked accounts")
            Muted("Link both to sign in either way. Your friends, chats and servers stay with you.")
            Card {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) { Text("Jace", fontWeight = FontWeight.Bold); Muted(if (me.jace_linked) "@${me.jace_name}" else "Not linked") }
                    if (me.jace_linked) { if (me.minecraft_linked) TextButton(onClick = { unlink = true }) { Text("Unlink") } }
                    else Button(onClick = {
                        scope.launch {
                            try {
                                val start = store.api.linkJace()
                                openUrl(context, start.url)
                                note = "Finish in the browser…"
                                repeat(150) {
                                    delay(2000)
                                    if (store.api.pollLinkJace(start)) { store.refreshMe(); note = "Jace linked!"; return@launch }
                                }
                                note = "Linking timed out - try again"
                            } catch (e: Exception) { note = ""; store.toast(e.message ?: "Couldn't link") }
                        }
                    }) { Text("Link Jace") }
                }
            }
            Card {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) { Text("Minecraft", fontWeight = FontWeight.Bold); Muted(if (me.minecraft_linked) me.minecraft_name ?: "" else "Not linked") }
                    if (!me.minecraft_linked && device == null) Button(onClick = {
                        scope.launch {
                            try {
                                val d = store.api.startLinkMinecraft()
                                device = d
                                while (device == d) {
                                    delay(d.interval * 1000L)
                                    if (store.api.pollLinkMinecraft(d.ticket)) { device = null; store.refreshMe(); note = "Minecraft linked!" }
                                }
                            } catch (e: Exception) { device = null; store.toast(e.message ?: "Couldn't link") }
                        }
                    }) { Text("Link Minecraft") }
                }
                device?.let { d ->
                    Text("Go to ${d.verification_uri} and enter this code:")
                    Text(d.user_code, style = androidx.compose.material3.MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold,
                        modifier = Modifier.border(1.dp, JaceColors.muted, RoundedCornerShape(8.dp)).padding(horizontal = 12.dp, vertical = 6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { copyText(context, d.user_code); openUrl(context, d.verification_uri) }) { Text("Copy code and open") }
                        TextButton(onClick = { device = null }) { Text("Cancel") }
                    }
                }
            }
            if (note.isNotBlank()) Muted(note)
        }
    }
    if (unlink) Confirm("Unlink your Jace account?", "You'll sign in with Minecraft only.", yes = "Unlink", danger = true, onDismiss = { unlink = false }) {
        scope.launch { if (store.act { it.unlinkJace() }) store.refreshMe() }
    }
}
