package dev.jacedeb.jacesocial.ui

import android.annotation.SuppressLint
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import dev.jacedeb.jacesocial.BuildConfig
import dev.jacedeb.jacesocial.data.Bot
import dev.jacedeb.jacesocial.data.BotLog
import dev.jacedeb.jacesocial.data.SavedAccount
import dev.jacedeb.jacesocial.data.Session
import dev.jacedeb.jacesocial.data.TryResult
import dev.jacedeb.jacesocial.data.jo
import kotlinx.coroutines.launch

/** My bots: make bots, get their token, switch to them, build them with blocks or code, see what they did. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BotsScreen(session: Session) {
    val store = LocalStore.current
    val nav = LocalNav.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var bots by remember { mutableStateOf<List<Bot>?>(null) }
    var name by remember { mutableStateOf("") }
    var token by remember { mutableStateOf<String?>(null) }
    var dialog by remember { mutableStateOf<Pair<String, Bot>?>(null) }
    var avatarFor by remember { mutableStateOf<Bot?>(null) }
    suspend fun load() { store.act { bots = it.bots() } }
    LaunchedEffect(Unit) { load() }
    val pickAvatar = rememberPhotoPicker { p -> val b = avatarFor ?: return@rememberPhotoPicker; scope.launch { if (store.act { it.setBotAvatar(b.uuid, p.bytes, p.type) }) load() } }
    fun patch(b: Bot, vararg f: Pair<String, Any?>) = scope.launch { if (store.act { it.updateBot(b.uuid, jo(*f)) }) load() }
    Page("My bots") {
        Muted("Bots are accounts you own. Build one with blocks (no code), write its code here and Jace Social runs it for you, " +
            "or program it anywhere with its token and the Jace Social API. You can also switch to a bot and chat as it.")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(name, { name = it.take(32) }, placeholder = { Text("New bot's name") }, singleLine = true, modifier = Modifier.weight(1f))
            Button(enabled = name.isNotBlank(), onClick = {
                scope.launch { store.act { val (_, t) = it.createBot(name.trim()); name = ""; token = t }; load() }
            }) { Text("Create") }
        }
        token?.let { t ->
            Card {
                Text("Bot token", fontWeight = FontWeight.Bold)
                Muted("Copy it now - it's only shown once. Anyone with it can act as your bot, so keep it secret.")
                Text(t, fontFamily = FontFamily.Monospace, style = TextStyle(fontSize = 12.sp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { copyText(context, t); store.toast("Copied") }) { Text("Copy") }
                    TextButton(onClick = { token = null }) { Text("Done") }
                }
            }
        }
        val list = bots
        when {
            list == null -> Muted("Loading…", small = false)
            list.isEmpty() -> Muted("No bots yet.", small = false)
            else -> for (b in list) Card {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.clickable { avatarFor = b; pickAvatar() }) { Avatar(b.asPerson(), 48) }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) { Text(b.name, fontWeight = FontWeight.Bold); BotTag() }
                        Muted("${if (b.has_code) "Runs its code on Jace Social" else if (b.has_program) "Made with blocks" else "Uses the API (or nothing yet)"} · " +
                            if (b.bot_public) "Anyone can add it" else "Only you can add it")
                    }
                }
                var bio by remember(b.uuid) { mutableStateOf(b.bio ?: "") }
                OutlinedTextField(bio, { bio = it.take(300) }, placeholder = { Text("What does it do?") }, modifier = Modifier.fillMaxWidth(),
                    trailingIcon = { if (bio != (b.bio ?: "")) TextButton(onClick = { patch(b, "bio" to bio) }) { Text("Save") } })
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedButton(onClick = { nav.go("bot-blocks/${b.uuid}") }) { Text("🧩 Blocks") }
                    OutlinedButton(onClick = { nav.go("bot-code/${b.uuid}") }) { Text("💻 ${if (b.has_code) "Edit code" else "Write code"}") }
                    OutlinedButton(onClick = { nav.go("bot-activity/${b.uuid}") }) { Text("📜 Activity") }
                    OutlinedButton(onClick = { dialog = "switch" to b }) { Text("Switch to it") }
                    OutlinedButton(onClick = { dialog = "token" to b }) { Text("New token") }
                    OutlinedButton(onClick = { dialog = "delete" to b }) { Text("Delete", color = JaceColors.red) }
                }
                ToggleRow("Public", "Anyone can add it to their server", b.bot_public) { patch(b, "bot_public" to it) }
                ToggleRow("On", "Off: it doesn't answer anything", b.bot_enabled) { patch(b, "bot_enabled" to it) }
            }
        }
        Card {
            Text("Programming a bot with the API (run it yourself)", fontWeight = FontWeight.Bold)
            Muted("Send requests to ${BuildConfig.API_BASE}/api/v1 with the header Authorization: Bearer <bot token>. The bot uses the same API " +
                "as the app: read and send messages in /channels/{id}/messages, react with /reactions, and so on. Live events (new messages, and " +
                "\"command\" when someone uses one of its slash commands) arrive on the bot's private Realtime channel: GET /me gives its name and key.")
        }
    }
    dialog?.let { (what, b) ->
        when (what) {
            "switch" -> Confirm("Switch to ${b.name}?", "You can switch back from You → Switch accounts.", yes = "Switch", onDismiss = { dialog = null }) {
                scope.launch {
                    store.act { api ->
                        val s = api.botSession(b.uuid)
                        session.remember(SavedAccount(s.uuid, s.name, s.avatar_url, s.token, is_bot = true))
                        store.stop()
                        session.switchTo(s.uuid)
                    }
                }
            }
            "token" -> Confirm("Make a new token?", "The old one stops working right away.", yes = "New token", onDismiss = { dialog = null }) {
                scope.launch { store.act { token = it.botToken(b.uuid) } }
            }
            "delete" -> Confirm("Delete ${b.name}?", "It leaves every server.", yes = "Delete", danger = true, onDismiss = { dialog = null }) {
                scope.launch { if (store.act { it.deleteBot(b.uuid) }) load() }
            }
            else -> {}
        }
    }
}

private const val STARTER_CODE = """// This runs on Jace Social's server whenever something happens where your bot is.
// It can reply, send messages, react, give roles and remember things (storage).

bot.on("message", (msg) => {
  if (msg.text.toLowerCase() === "hi bot") msg.reply("Hi " + msg.author.name + "! 👋");
});

bot.command("count", "Count up by one", (cmd) => {
  const n = (storage.get("count") || 0) + 1;
  storage.set("count", n);
  cmd.reply("The count is now " + n);
});

bot.on("join", (member) => {
  member.reply("Welcome, " + member.author.name + "!");
});
"""

private const val CODE_HELP = """bot.on("message", (msg) => { ... })   someone wrote in a chat the bot is in
bot.on("join", (member) => { ... })    someone joined the server
bot.command("name", "What it does", [{ name: "option", description: "...", required: true }], (cmd, args) => { ... })

msg / cmd / member have:
  .text  .author.id  .author.name  .channel.id  .channel.name  .server
  .reply(text)  .react("👍")  .delete()  .giveRole("Role")  .takeRole("Role")
bot.send("channel-name", text)
storage.get(key)  storage.set(key, value)  storage.delete(key)  storage.keys()
console.log(...)  shows in the bot's Activity

Limits per event: 300 ms of work, 5 messages, 32 MB of memory. No internet access, files or timers."""

/** Write a bot in JavaScript; Jace Social runs it (in a sandbox) when something happens. Try it first. */
@Composable
fun BotCodeScreen(id: String) {
    val store = LocalStore.current
    val scope = rememberCoroutineScope()
    var code by remember { mutableStateOf<String?>(null) }
    var saved by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("") }
    var tryText by remember { mutableStateOf("hi bot") }
    var result by remember { mutableStateOf<TryResult?>(null) }
    var removing by remember { mutableStateOf(false) }
    LaunchedEffect(id) {
        runCatching { store.api.botCode(id) }.onSuccess { code = it ?: STARTER_CODE; saved = it ?: "" }.onFailure { code = STARTER_CODE; store.toast(it.message ?: "") }
    }
    val c = code
    Page("Bot code", actions = {
        if (c != null) TextButton(enabled = c != saved, onClick = {
            scope.launch {
                try { val cmds = store.api.saveBotCode(id, c); saved = c; status = "Saved ✓" + if (cmds.isNotEmpty()) " Commands: ${cmds.joinToString { "/$it" }}." else "" }
                catch (e: Exception) { status = "⚠ ${e.message}" }
            }
        }) { Text(if (c != saved) "Save" else "Saved") }
    }) {
        if (c == null) { Muted("Loading…", small = false); return@Page }
        if (status.isNotBlank()) Text(status, color = if (status.startsWith("⚠")) JaceColors.red else JaceColors.green)
        OutlinedTextField(c, { code = it }, textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp),
            modifier = Modifier.fillMaxWidth().heightIn(min = 320.dp))
        Card {
            Text("Try it", fontWeight = FontWeight.Bold)
            Muted("Runs the code above for a pretend message (or a /command) without saving or sending anything.")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(tryText, { tryText = it }, placeholder = { Text("A message, or /command") }, singleLine = true, modifier = Modifier.weight(1f))
                Button(onClick = { scope.launch { try { result = store.api.tryBotCode(id, c, tryText) } catch (e: Exception) { status = "⚠ ${e.message}" } } }) { Text("Run") }
            }
            result?.let { r ->
                r.error?.let { Text("⚠ $it", color = JaceColors.red) }
                if (r.actions.isEmpty() && r.error == null) Muted("It wouldn't do anything.")
                for (a in r.actions) Text(when (a.t) {
                    "reply" -> "💬 Reply: ${a.text}"; "send" -> "💬 Send in #${a.channel}: ${a.text}"; "react" -> "😀 React with ${a.emoji}"
                    "give_role" -> "🏷️ Give the role ${a.role}"; "take_role" -> "🏷️ Take the role ${a.role}"; "delete_message" -> "🗑️ Delete the message"
                    else -> a.t
                })
                for ((k, v) in r.storage) Text("💾 " + if (v is kotlinx.serialization.json.JsonNull) "Forget \"$k\"" else "Save \"$k\" = $v")
                for (l in r.logs) Muted("› $l")
            }
        }
        Card {
            Text("What your code can use", fontWeight = FontWeight.Bold)
            Text(CODE_HELP, fontFamily = FontFamily.Monospace, style = TextStyle(fontSize = 11.sp))
        }
        if (saved.isNotEmpty()) TextButton(onClick = { removing = true }) { Text("Remove code", color = JaceColors.red) }
    }
    if (removing) Confirm("Remove this code?", "The bot stops running it.", yes = "Remove", danger = true, onDismiss = { removing = false }) {
        scope.launch { if (store.act { it.saveBotCode(id, null) }) { saved = ""; status = "Code removed" } }
    }
}

/** The block editor: Blockly is JavaScript with no native version, so it's the website's /bot-blocks
 *  page in a web view (the one part of the app that isn't native). */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun BotBlocksScreen(session: Session, id: String) {
    val nav = LocalNav.current
    val token = session.token.value ?: return
    Page("Bot blocks", scroll = false) {
        AndroidView(modifier = Modifier.fillMaxSize(), factory = { ctx ->
            WebView(ctx).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                        if (request.url.scheme == "jacesocial") { nav.back(); return true }
                        return false
                    }
                }
                // the token goes in the #fragment, which is never sent to a server
                loadUrl("${BuildConfig.API_BASE}/bot-blocks?bot=$id#token=$token")
            }
        }, onRelease = { it.destroy() })
    }
}

/** Everything a bot did, newest first. */
@Composable
fun BotActivityScreen(id: String, server: String?) {
    val store = LocalStore.current
    val scope = rememberCoroutineScope()
    var entries by remember { mutableStateOf<List<BotLog>>(emptyList()) }
    var channels by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var servers by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var more by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    suspend fun load(before: Long? = null) {
        try {
            val r = store.api.botActivity(id, server, before)
            entries = if (before != null) entries + r.entries else r.entries
            channels = channels + r.channels; servers = servers + r.servers
            more = r.entries.size == 100
        } catch (e: Exception) { error = e.message }
    }
    LaunchedEffect(id) { load() }
    val icons = mapOf("event" to "📥", "message" to "💬", "react" to "😀", "role" to "🏷️", "delete" to "🗑️", "storage" to "💾", "log" to "›", "error" to "⚠️", "command" to "⌨️")
    Page("📜 What it did") {
        Muted("${if (server != null) "In this server, " else ""}newest first. Kept for 14 days.")
        error?.let { Text(it, color = JaceColors.red) }
        if (error == null && entries.isEmpty()) Muted("Nothing yet.", small = false)
        for (e in entries) Row {
            Text(icons[e.kind] ?: "•", modifier = Modifier.width(28.dp))
            Column {
                Text(e.detail, color = if (e.kind == "error") JaceColors.red else androidx.compose.material3.MaterialTheme.colorScheme.onBackground)
                Muted(listOfNotNull(timeOf(e.at) + " " + e.at.take(10), e.server_id?.takeIf { server == null }?.let { servers[it] },
                    e.channel_id?.let { channels[it] }?.let { "#$it" }).joinToString(" · "))
            }
        }
        if (more && entries.isNotEmpty()) OutlinedButton(onClick = { scope.launch { load(entries.last().id) } }) { Text("Show older") }
    }
}
