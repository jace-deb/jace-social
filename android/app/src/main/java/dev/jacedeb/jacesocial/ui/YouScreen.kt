package dev.jacedeb.jacesocial.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import androidx.compose.ui.layout.ContentScale
import dev.jacedeb.jacesocial.BuildConfig
import dev.jacedeb.jacesocial.data.Session
import dev.jacedeb.jacesocial.data.Store
import kotlinx.coroutines.launch

/** You: your profile card, status, settings, accounts, bots, updates and signing out. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun YouScreen(session: Session, store: Store) {
    val me by store.me.collectAsState()
    val update by store.update.collectAsState()
    val nav = LocalNav.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var checking by remember { mutableStateOf(false) }
    var signOut by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        TopAppBar(title = { Text("You", fontWeight = FontWeight.Bold) })
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            me?.let { m ->
                Box(Modifier.fillMaxWidth().height(110.dp).background(hexColor(m.accent_color, JaceColors.panel))) {
                    m.banner_url?.let { AsyncImage(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
                }
                Column(Modifier.padding(horizontal = 16.dp).offset(y = (-40).dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Avatar(m.asPerson(), 84)
                    Text(m.shownName, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Muted(listOfNotNull(m.jace_name?.let { "@$it" }, m.minecraft_name?.let { "⛏ $it" }, m.pronouns).joinToString(" · "))
                    listOfNotNull(m.status_emoji, m.custom_status).joinToString(" ").takeIf { it.isNotBlank() }?.let { Text(it) }
                }
            }
            Column(Modifier.padding(horizontal = 16.dp).offset(y = (-24).dp)) {
                NavRow("My profile", "Name, picture, banner, about me, links", icon = "👤") { nav.go("settings/profile") }
                NavRow("Status", statusLabel(me?.status ?: "online"), icon = "🟢") { nav.go("settings/status") }
                HorizontalDivider()
                NavRow("Appearance", "Theme, accent color, text size", icon = "🎨") { nav.go("settings/appearance") }
                NavRow("Notifications", icon = "🔔") { nav.go("settings/notifications") }
                NavRow("Links & privacy", icon = "🔗") { nav.go("settings/links") }
                NavRow("Advanced", icon = "⚙️") { nav.go("settings/advanced") }
                HorizontalDivider()
                if (me?.is_bot != true) {
                    NavRow("Accounts", "Switch accounts, link Jace or Minecraft", icon = "🔁") { nav.go("settings/accounts") }
                    NavRow("My bots", "Make bots, write their code, see what they do", icon = "🤖") { nav.go("bots") }
                    NavRow("Welcome tour", icon = "👋") { nav.go("welcome") }
                } else NavRow("Switch accounts", icon = "🔁") { nav.go("settings/accounts") }
                HorizontalDivider()
                Column(Modifier.padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Muted("Jace Social ${BuildConfig.VERSION_NAME}")
                    val u = update
                    if (u != null) androidx.compose.material3.Button(onClick = { openUpdate(context, u) }) { Text("Update to ${u.version}") }
                    else androidx.compose.material3.OutlinedButton(enabled = !checking, onClick = {
                        checking = true
                        scope.launch { store.checkUpdate()?.let { store.toast(it) }; checking = false }
                    }) { Text(if (checking) "Checking…" else "Check for updates") }
                    var auto by remember { mutableStateOf(store.autoUpdate) }
                    ToggleRow("Check for updates on startup", checked = auto) { auto = it; store.autoUpdate = it }
                }
                NavRow("Sign out", color = JaceColors.red) { signOut = true }
            }
        }
    }
    if (signOut) Confirm("Sign out?", "You can sign back in with Jace or Minecraft.", yes = "Sign out", danger = true,
        onDismiss = { signOut = false }) { scope.launch { store.stop(); session.signOut() } }
}

fun statusLabel(s: String) = when (s) { "idle" -> "Idle"; "dnd" -> "Do Not Disturb"; "invisible" -> "Invisible"; "offline" -> "Offline"; else -> "Online" }
