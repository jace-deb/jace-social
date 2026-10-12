package dev.jacedeb.jacesocial.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import dev.jacedeb.jacesocial.data.ChatTarget
import dev.jacedeb.jacesocial.data.Session
import dev.jacedeb.jacesocial.data.Store
import java.net.URLEncoder

/** Signed in: tabs for chats, servers and you; chats open on top. */
@Composable
fun MainScreen(session: Session, store: Store, startAt: String? = null) {
    val nav = rememberNavController()
    val entry by nav.currentBackStackEntryAsState()
    val route = entry?.destination?.route ?: "chats"
    val tabs = listOf(Triple("chats", "Chats", Icons.AutoMirrored.Filled.Chat), Triple("servers", "Servers", Icons.Filled.Dns),
                      Triple("you", "You", Icons.Filled.Person))
    fun openChat(t: ChatTarget, title: String) {
        val (kind, id) = when (t) { is ChatTarget.Direct -> "dm" to t.uuid; is ChatTarget.Channel -> "ch" to t.id }
        nav.navigate("chat/$kind/$id?title=${URLEncoder.encode(title, "UTF-8")}")
    }

    // test builds can ask to start on another screen (see MainActivity.testHooks)
    androidx.compose.runtime.LaunchedEffect(startAt) { startAt?.let { nav.navigate(it) } }
    Scaffold(bottomBar = {
        if (tabs.any { it.first == route }) NavigationBar {
            for ((r, label, icon) in tabs) NavigationBarItem(
                selected = route == r, label = { Text(label) }, icon = { Icon(icon, contentDescription = label) },
                onClick = { nav.navigate(r) { popUpTo("chats"); launchSingleTop = true } })
        }
    }) { pad ->
        NavHost(nav, startDestination = "chats", modifier = Modifier.padding(pad)) {
            composable("chats") { ChatsScreen(store, ::openChat) }
            composable("servers") { ServersScreen(store) { id, name -> nav.navigate("server/$id?title=${URLEncoder.encode(name, "UTF-8")}") } }
            composable("server/{id}?title={title}", arguments = listOf(
                navArgument("id") { type = NavType.StringType },
                navArgument("title") { type = NavType.StringType; defaultValue = "" })) { e ->
                ServerScreen(session, store, e.arguments!!.getString("id")!!, e.arguments!!.getString("title") ?: "", ::openChat,
                    onBack = { nav.popBackStack() })
            }
            composable("you") { YouScreen(session, store) }
            composable("chat/{kind}/{id}?title={title}", arguments = listOf(
                navArgument("kind") { type = NavType.StringType }, navArgument("id") { type = NavType.StringType },
                navArgument("title") { type = NavType.StringType; defaultValue = "" })) { e ->
                val a = e.arguments!!
                val target = if (a.getString("kind") == "dm") ChatTarget.Direct(a.getString("id")!!) else ChatTarget.Channel(a.getString("id")!!)
                ChatScreen(session, store, target, a.getString("title") ?: "", onBack = { nav.popBackStack() })
            }
        }
    }
}
