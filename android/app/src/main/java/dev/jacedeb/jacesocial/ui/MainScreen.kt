package dev.jacedeb.jacesocial.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import dev.jacedeb.jacesocial.BuildConfig
import dev.jacedeb.jacesocial.data.ChatTarget
import dev.jacedeb.jacesocial.data.Session
import dev.jacedeb.jacesocial.data.Store
import kotlinx.coroutines.flow.StateFlow
import java.net.URLEncoder

fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

/** Signed in: tabs for chats, servers and you, with everything else opening on top. */
@Composable
fun MainScreen(session: Session, store: Store, startAt: String? = null, links: StateFlow<String?>? = null) {
    val nav = rememberNavController()
    val context = LocalContext.current
    val entry by nav.currentBackStackEntryAsState()
    val route = entry?.destination?.route ?: "chats"
    val tabs = listOf(Triple("chats", "Chats", Icons.AutoMirrored.Filled.Chat), Triple("servers", "Servers", Icons.Filled.Dns),
                      Triple("you", "You", Icons.Filled.Person))
    var afterMic by remember { mutableStateOf<(() -> Unit)?>(null) }
    var afterCamera by remember { mutableStateOf<(() -> Unit)?>(null) }
    var afterScreen by remember { mutableStateOf<((android.content.Intent) -> Unit)?>(null) }
    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { afterMic?.invoke(); afterMic = null }
    val cameraPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) afterCamera?.invoke() else store.toast("Jace Social needs the camera for video - allow it in Android's settings")
        afterCamera = null
    }
    val screenConsent = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val data = r.data
        if (r.resultCode == android.app.Activity.RESULT_OK && data != null) afterScreen?.invoke(data)
        afterScreen = null
    }
    val appNav = remember(nav) {
        object : AppNav {
            override fun withMic(then: () -> Unit) { afterMic = then; micPermission.launch(Manifest.permission.RECORD_AUDIO) }
            override fun withCamera(then: () -> Unit) { afterCamera = then; cameraPermission.launch(Manifest.permission.CAMERA) }
            override fun screenShare(then: (android.content.Intent) -> Unit) {
                afterScreen = then
                // Android wants the screen capture service running before it hands over the screen
                val pm = context.getSystemService(android.media.projection.MediaProjectionManager::class.java)
                screenConsent.launch(pm.createScreenCaptureIntent())
            }
            override fun back() { nav.popBackStack() }
            override fun go(route: String) { nav.navigate(route) { launchSingleTop = true } }
            override fun chat(t: ChatTarget, title: String) {
                val (kind, id) = when (t) { is ChatTarget.Direct -> "dm" to t.uuid; is ChatTarget.Channel -> "ch" to t.id }
                nav.navigate("chat/$kind/$id?title=${enc(title)}")
            }
            override fun profile(uuid: String, server: String?) { nav.navigate("profile/$uuid" + (server?.let { "?server=$it" } ?: "")) }
        }
    }
    val snack = remember { SnackbarHostState() }
    LaunchedEffect(store) { store.toasts.collect { snack.showSnackbar(it) } }
    val me by store.me.collectAsState()
    val call by store.calls.info.collectAsState()
    val room by store.voice.state.collectAsState()
    val ask by store.askUpdate.collectAsState()
    var callShown by rememberSaveable { mutableStateOf(true) }

    // notifications (Android 13+ asks)
    val notifyPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    LaunchedEffect(Unit) { if (Build.VERSION.SDK_INT >= 33) notifyPermission.launch(Manifest.permission.POST_NOTIFICATIONS) }

    // test builds can ask to start on another screen (see MainActivity.testHooks); links open invites and chats
    LaunchedEffect(startAt) { startAt?.let { nav.navigate(it) } }
    if (links != null) {
        val link by links.collectAsState()
        LaunchedEffect(link) { link?.let { linkRoute(it)?.let { r -> nav.navigate(r) } } }
    }
    // a new account: the welcome tour
    LaunchedEffect(me?.uuid) { if (me?.onboarded == false && me?.is_bot == false) nav.navigate("welcome") }
    LaunchedEffect(call.state) { if (call.state == "ringing" || call.state == "calling") callShown = true }

    CompositionLocalProvider(LocalStore provides store, LocalNav provides appNav) {
        Box(Modifier.fillMaxSize()) {
            Scaffold(snackbarHost = { SnackbarHost(snack) }, bottomBar = {
                Column {
                    if (call.state != "idle" && !callShown) ActiveBar("📞 ${if (call.state == "in-call") "In a call with" else "Calling"} ${call.peerName}") { callShown = true }
                    if (room.channelId != null && route != "voice") ActiveBar("🔊 In ${room.channelName}") { nav.navigate("voice") { launchSingleTop = true } }
                    if (tabs.any { it.first == route }) NavigationBar {
                        for ((r, label, icon) in tabs) NavigationBarItem(
                            selected = route == r, label = { Text(label) }, icon = { Icon(icon, contentDescription = label) },
                            onClick = { nav.navigate(r) { popUpTo("chats"); launchSingleTop = true } })
                    }
                }
            }) { pad ->
                NavHost(nav, startDestination = "chats", modifier = Modifier.padding(pad)) {
                    composable("chats") { ChatsScreen(store, appNav::chat) }
                    composable("servers") { ServersScreen(store) { id, name -> nav.navigate("server/$id?title=${enc(name)}") } }
                    composable("you") { YouScreen(session, store) }
                    composable("chat/{kind}/{id}?title={title}", arguments = listOf(
                        navArgument("kind") { type = NavType.StringType }, navArgument("id") { type = NavType.StringType },
                        navArgument("title") { type = NavType.StringType; defaultValue = "" })) { e ->
                        val a = e.arguments!!
                        val target = if (a.getString("kind") == "dm") ChatTarget.Direct(a.getString("id")!!) else ChatTarget.Channel(a.getString("id")!!)
                        ChatScreen(session, store, target, a.getString("title") ?: "", onBack = { nav.popBackStack() })
                    }
                    composable("server/{id}?title={title}", arguments = listOf(
                        navArgument("id") { type = NavType.StringType },
                        navArgument("title") { type = NavType.StringType; defaultValue = "" })) { e ->
                        ServerScreen(session, store, e.arguments!!.getString("id")!!, e.arguments!!.getString("title") ?: "", appNav::chat,
                            onBack = { nav.popBackStack() })
                    }
                    composable("profile/{uuid}?server={server}", arguments = listOf(
                        navArgument("uuid") { type = NavType.StringType },
                        navArgument("server") { type = NavType.StringType; nullable = true; defaultValue = null })) { e ->
                        ProfileScreen(e.arguments!!.getString("uuid")!!, e.arguments!!.getString("server"))
                    }
                    composable("server-settings/{id}") { e -> ServerSettingsScreen(e.arguments!!.getString("id")!!) }
                    composable("channel-settings/{server}/{channel}") { e ->
                        ChannelSettingsScreen(e.arguments!!.getString("server")!!, e.arguments!!.getString("channel")!!)
                    }
                    composable("group/{id}") { e -> GroupSettingsScreen(e.arguments!!.getString("id")!!) }
                    composable("new-group") { NewGroupScreen() }
                    composable("add-server?code={code}", arguments = listOf(navArgument("code") { type = NavType.StringType; defaultValue = "" })) { e ->
                        AddServerScreen(e.arguments!!.getString("code") ?: "")
                    }
                    composable("invite/{code}") { e -> InviteScreen(e.arguments!!.getString("code")!!) }
                    composable("settings/{tab}") { e -> SettingsScreen(session, e.arguments!!.getString("tab")!!) }
                    composable("bots") { BotsScreen(session) }
                    composable("bot-code/{id}") { e -> BotCodeScreen(e.arguments!!.getString("id")!!) }
                    composable("bot-blocks/{id}") { e -> BotBlocksScreen(session, e.arguments!!.getString("id")!!) }
                    composable("bot-activity/{id}?server={server}", arguments = listOf(
                        navArgument("id") { type = NavType.StringType },
                        navArgument("server") { type = NavType.StringType; nullable = true; defaultValue = null })) { e ->
                        BotActivityScreen(e.arguments!!.getString("id")!!, e.arguments!!.getString("server"))
                    }
                    composable("voice") { VoiceRoomScreen() }
                    composable("welcome") { WelcomeScreen() }
                }
            }
            if (call.state != "idle" && callShown) CallScreen(onMinimize = { callShown = false })
        }
        val pending by store.pendingLink.collectAsState()
        pending?.let { url -> LinkWarning(url) }
        ask?.let { r ->
            AlertDialog(onDismissRequest = { store.askUpdate.value = null },
                title = { Text("Update to Jace Social ${r.version}?") },
                text = {
                    Column {
                        Text("You have ${BuildConfig.VERSION_NAME}. The new version downloads; open it to install it over this one. You stay signed in.")
                        if (r.notes.isNotBlank()) Muted(r.notes.trim().take(1500))
                    }
                },
                confirmButton = { TextButton(onClick = { store.askUpdate.value = null; openUpdate(context, r) }) { Text("Update now") } },
                dismissButton = { TextButton(onClick = { store.askUpdate.value = null }) { Text("Later") } })
        }
    }
}

/** A thin bar above the tabs: an ongoing call or voice room. */
@Composable
private fun ActiveBar(text: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().background(JaceColors.green).clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text(text, color = JaceColors.onGreen, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        Text("Open", color = JaceColors.onGreen)
    }
}

/** Android: the new APK (the system installs it over this one). */
fun openUpdate(context: android.content.Context, r: dev.jacedeb.jacesocial.data.Release) {
    openUrl(context, r.downloads.firstOrNull { it.id == "android" }?.url ?: r.page)
}

/** jacesocial://invite/<code>, https://jace-social.vercel.app/invite/<code>, or a route from a notification. */
fun linkRoute(link: String): String? {
    if (link.startsWith("chat/") || link.startsWith("server/")) return link
    val m = Regex("""(?:jacesocial://invite/|https://jace-social\.vercel\.app/invite/)([A-Za-z0-9_-]+)""").find(link) ?: return null
    return "invite/${m.groupValues[1]}"
}

/** "Leaving Jace Social": where a link really goes, before opening it (Settings -> Links & privacy). */
@Composable
private fun LinkWarning(url: String) {
    val store = LocalStore.current
    val context = LocalContext.current
    val s = store.me.collectAsState().value?.settings
    val host = runCatching { android.net.Uri.parse(url).host?.lowercase()?.removePrefix("www.") }.getOrNull() ?: ""
    val trusted = s?.trusted_domains ?: emptyList()
    val ours = host == "jace-social.vercel.app"
    if (s?.link_warning == false || ours || trusted.any { host == it || host.endsWith(".$it") }) {
        LaunchedEffect(url) { store.pendingLink.value = null; openUrl(context, url) }
        return
    }
    AlertDialog(onDismissRequest = { store.pendingLink.value = null },
        title = { Text("Leaving Jace Social") },
        text = { Column { Text("This link goes to"); Text(host, fontWeight = FontWeight.Bold); Muted(url.take(300)) } },
        confirmButton = { TextButton(onClick = { store.pendingLink.value = null; openUrl(context, url) }) { Text("Open link") } },
        dismissButton = {
            Row {
                TextButton(onClick = {
                    store.pendingLink.value = null
                    store.saveSettings(dev.jacedeb.jacesocial.data.jo("trusted_domains" to (trusted + host).distinct()))
                    openUrl(context, url)
                }) { Text("Trust $host") }
                TextButton(onClick = { store.pendingLink.value = null }) { Text("Cancel") }
            }
        })
}
