package dev.jacedeb.jacesocial.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import dev.jacedeb.jacesocial.data.Attachment
import dev.jacedeb.jacesocial.data.ChatTarget
import dev.jacedeb.jacesocial.data.Command
import dev.jacedeb.jacesocial.data.Message
import dev.jacedeb.jacesocial.data.P
import dev.jacedeb.jacesocial.data.Person
import dev.jacedeb.jacesocial.data.ReplyPreview
import dev.jacedeb.jacesocial.data.Session
import dev.jacedeb.jacesocial.data.Settings
import dev.jacedeb.jacesocial.data.Store
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val QUICK = listOf("👍", "❤️", "😂", "😮", "😢", "🎉")

/** A file being sent: uploading, then its attachment. */
private class Upload(val name: String, val image: Boolean) { var att by mutableStateOf<Attachment?>(null) }

/** One conversation: a friend, a group chat or a server channel. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(session: Session, store: Store, target: ChatTarget, title: String, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val nav = LocalNav.current
    val context = LocalContext.current
    val me by store.me.collectAsState()
    val typing by store.typing.collectAsState()
    // read here so names and avatars fill in when they load after the chat opens
    val friends by store.friends.collectAsState()
    val groups by store.groups.collectAsState()
    val settings = me?.settings ?: Settings()
    val group = (target as? ChatTarget.Channel)?.let { t -> groups.firstOrNull { it.id == t.id } }
    var messages by remember { mutableStateOf<List<Message>>(emptyList()) }
    var replies by remember { mutableStateOf<Map<String, ReplyPreview>>(emptyMap()) }
    var people by remember { mutableStateOf<Map<String, Person>>(emptyMap()) }
    var perms by remember { mutableStateOf<Long?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var loaded by remember { mutableStateOf(false) }
    var olderDone by remember { mutableStateOf(false) }
    var text by remember { mutableStateOf("") }
    var replyTo by remember { mutableStateOf<Message?>(null) }
    var editing by remember { mutableStateOf<Message?>(null) }
    var lastTyping by remember { mutableLongStateOf(0L) }
    var commands by remember { mutableStateOf<List<Command>>(emptyList()) }
    var pins by remember { mutableStateOf<List<Message>?>(null) }
    val uploads = remember { mutableStateListOf<Upload>() }
    val mentionTokens = remember { mutableStateMapOf<String, String>() }      // "@Steve" -> "<@uuid>"
    val list = rememberLazyListState()
    val isServer = target is ChatTarget.Channel && group == null
    fun can(p: Long) = perms?.let { P.has(it, p) } ?: true

    fun who(uuid: String?): Person? = uuid?.let { people[it] ?: friends?.friends?.firstOrNull { p -> p.uuid == it }
        ?: groups.firstNotNullOfOrNull { g -> g.members.firstOrNull { p -> p.uuid == it } } ?: me?.takeIf { m -> m.uuid == uuid }?.asPerson() }

    suspend fun loadNewest() {
        try {
            val page = session.api.messages(target)
            val byId = (messages + page.messages).associateBy { it.id }
            messages = byId.values.sortedBy { it.id }.filter { m -> page.messages.any { it.id == m.id } || m.id < (page.messages.firstOrNull()?.id ?: 0) }
            replies = replies + page.replies
            people = people + page.people
            perms = page.perms
            error = null
            session.api.markRead(target)
        } catch (e: Exception) {
            error = e.message
        }
        loaded = true
    }

    suspend fun loadOlder() {
        val oldest = messages.firstOrNull()?.id ?: return
        val page = runCatching { session.api.messages(target, before = oldest) }.getOrNull() ?: return
        if (page.messages.isEmpty()) { olderDone = true; return }
        messages = (page.messages + messages).distinctBy { it.id }.sortedBy { it.id }
        replies = replies + page.replies
        people = people + page.people
    }

    LaunchedEffect(target.key) {
        loadNewest()
        if (target is ChatTarget.Channel) runCatching { commands = session.api.commands(target.id) }
    }
    // live: new or changed messages here
    LaunchedEffect(target.key) {
        store.live.events.collect { e ->
            val here = when (target) {
                is ChatTarget.Direct -> e.event in setOf("message", "dm_update") && (e.str("from") == target.uuid || e.str("with") == target.uuid)
                is ChatTarget.Channel -> e.event == "channel" && e.str("channel_id") == target.id
            }
            if (here) loadNewest()
        }
    }
    // reaching the top loads older messages
    LaunchedEffect(list) {
        snapshotFlow { list.layoutInfo.visibleItemsInfo.lastOrNull()?.index }.collect { last ->
            if (last != null && last >= messages.size - 3 && messages.isNotEmpty() && !olderDone) loadOlder()
        }
    }

    val pickFiles = rememberFilePicker { files ->
        if (!can(P.ATTACH)) { store.toast("You can't attach files here"); return@rememberFilePicker }
        for (f in files.take(10 - uploads.size)) {
            if (f.bytes.size > 10 * 1024 * 1024) { store.toast("${f.name} is bigger than 10 MB"); continue }
            val u = Upload(f.name, f.type.startsWith("image/"))
            uploads.add(u)
            scope.launch {
                try { u.att = session.api.upload(f.name, f.type, f.bytes, f.width, f.height) }
                catch (e: Exception) { store.toast("Couldn't upload ${f.name}: ${e.message}"); uploads.remove(u) }
            }
        }
    }

    fun runCommand(c: Command, rest: String) = scope.launch {
        val args = c.options.firstOrNull()?.let { mapOf(it.name to rest) } ?: emptyMap()
        c.options.firstOrNull { it.required && (args[it.name] ?: "").isBlank() }?.let { store.toast("Fill in ${it.name}"); return@launch }
        if (store.act { it.runCommand((target as ChatTarget.Channel).id, c, args) }) loadNewest()
    }

    fun send() {
        var body = text.trim()
        for ((label, token) in mentionTokens) body = body.replace(label, token)
        if (uploads.any { it.att == null }) { store.toast("Wait for the files to finish uploading"); return }
        val files = uploads.mapNotNull { it.att }
        if (body.isEmpty() && files.isEmpty()) return
        if (body.startsWith("/") && commands.isNotEmpty() && editing == null) {
            val name = body.drop(1).split(Regex("\\s+"))[0]
            commands.firstOrNull { it.name == name }?.let { c -> text = ""; runCommand(c, body.drop(name.length + 2)); return }
        }
        val edit = editing
        val reply = replyTo
        text = ""; replyTo = null; editing = null; uploads.clear(); mentionTokens.clear()
        scope.launch {
            try {
                if (edit != null) {
                    session.api.edit(target, edit.id, body)
                    messages = messages.map { if (it.id == edit.id) it.copy(body = body, edited_at = "now") else it }
                } else {
                    val m = session.api.sendWith(target, body, reply?.id, files)
                    messages = (messages + m).distinctBy { it.id }.sortedBy { it.id }
                    if (reply != null) replies = replies + (reply.id.toString() to ReplyPreview(reply.id, reply.sender, reply.body))
                }
                list.animateScrollToItem(0)
            } catch (e: Exception) {
                error = e.message
                text = body
            }
        }
    }

    fun react(m: Message, emoji: String) = scope.launch {
        val mine = m.reactions.firstOrNull { it.emoji == emoji }?.me == true
        runCatching { session.api.react(target, m.id, emoji, !mine) }.onFailure { error = it.message }
        messages = messages.map { x ->
            if (x.id != m.id) x else {
                val others = x.reactions.filter { it.emoji != emoji }
                val old = x.reactions.firstOrNull { it.emoji == emoji }
                val count = (old?.count ?: 0) + if (mine) -1 else 1
                x.copy(reactions = if (count <= 0) others else others + dev.jacedeb.jacesocial.data.Reaction(emoji, count, !mine))
            }
        }
    }

    // suggestions while typing: @people, /commands
    val atWord = Regex("""(^|\s)@([\p{L}\p{N}_.-]{0,32})$""").find(text)
    val slash = if (target is ChatTarget.Channel && Regex("^/[a-z0-9_-]*$").matches(text)) text.drop(1) else null
    val everyone = (people.values + (friends?.friends ?: emptyList()) + (group?.members ?: emptyList())).distinctBy { it.uuid }.filter { it.uuid != me?.uuid }
    val suggestions: List<Pair<String, () -> Unit>> = when {
        atWord != null -> {
            val q = atWord.groupValues[2].lowercase()
            everyone.filter { it.shownName.lowercase().contains(q) || it.name.lowercase().contains(q) }.take(6).map { p ->
                "@${p.shownName}" to {
                    val label = "@${p.shownName}"
                    text = text.substring(0, atWord.range.first + atWord.groupValues[1].length) + "$label "
                    mentionTokens[label] = "<@${p.uuid}>"
                }
            }
        }
        slash != null -> commands.filter { it.name.startsWith(slash) }.take(12).map { c ->
            "/${c.name} — ${c.description.ifBlank { c.bot_name }}" to { text = "/${c.name} " }
        }
        else -> emptyList()
    }

    Column(Modifier.fillMaxSize().imePadding()) {
        TopAppBar(
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            title = {
                Column(Modifier.clickable {
                    when { target is ChatTarget.Direct -> nav.profile(target.uuid); group != null -> nav.go("group/${group.id}") }
                }) {
                    Text(group?.name ?: title, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    val names = typing[target.key].orEmpty()
                    if (names.isNotEmpty()) Text("${names.joinToString()} ${if (names.size == 1) "is" else "are"} typing…",
                        style = MaterialTheme.typography.labelSmall, color = JaceColors.muted)
                }
            },
            actions = {
                if (target is ChatTarget.Direct) IconButton(onClick = { nav.withMic { store.calls.call(target.uuid, title) } }) { Icon(Icons.Filled.Call, "Call") }
                if (group != null) IconButton(onClick = { nav.withMic { store.voice.join(me?.uuid ?: "", group.id, null, group.name); nav.go("voice") } }) { Icon(Icons.Filled.Call, "Group call") }
                if (target is ChatTarget.Channel) IconButton(onClick = {
                    scope.launch { runCatching { session.api.pins(target.id) }.onSuccess { p -> people = people + p.people; pins = p.messages }.onFailure { store.toast(it.message ?: "") } }
                }) { Icon(Icons.Filled.PushPin, "Pinned messages") }
                if (group != null) IconButton(onClick = { nav.go("group/${group.id}") }) { Icon(Icons.Filled.Settings, "Group settings") }
            })
        error?.let { Text(it, color = JaceColors.red, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) }
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = list, reverseLayout = true,
            contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 8.dp)) {
            val shown = messages.asReversed()
            items(shown, key = { it.id }) { m ->
                val i = shown.indexOf(m)
                val prev = shown.getOrNull(i + 1)        // the one above it
                val grouped = prev != null && prev.sender == m.sender && prev.kind != "system" && minutesBetween(prev.created_at, m.created_at) < 5
                val mine = m.sender == me?.uuid
                MessageRow(m, who(m.sender), grouped, m.reply_to?.let { replies[it.toString()] }, ::who, mine, settings, me?.uuid,
                    canPin = target is ChatTarget.Channel && (group != null || can(P.MANAGE_MESSAGES)),
                    canDelete = mine || (isServer && can(P.MANAGE_MESSAGES)),
                    onReply = { replyTo = m; editing = null },
                    onEdit = { editing = m; replyTo = null; text = m.body },
                    onDelete = { scope.launch { runCatching { session.api.delete(target, m.id) }.onSuccess { messages = messages.filter { it.id != m.id } }.onFailure { error = it.message } } },
                    onPin = { on -> scope.launch { if (store.act { it.pin(m.id, on) }) { store.toast(if (on) "Pinned" else "Unpinned"); loadNewest() } } },
                    onReact = { e -> react(m, e) })
            }
            if (loaded && messages.isEmpty()) item { Text("No messages yet - say hi!", color = JaceColors.muted, modifier = Modifier.padding(24.dp)) }
        }
        // suggestions, the reply / edit banner, files being sent, then the message box
        if (suggestions.isNotEmpty()) Column(Modifier.fillMaxWidth().background(JaceColors.bg2).heightIn(max = 220.dp).verticalScroll(rememberScrollState())) {
            for ((label, pick) in suggestions) Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth().clickable { pick() }.padding(horizontal = 16.dp, vertical = 10.dp))
        }
        if (slash == "" && commands.isEmpty()) Muted("No commands here yet. Add a bot like Jace: server menu → Server settings → Bots")
        val banner = editing?.let { "Editing your message" } ?: replyTo?.let { "Replying to ${who(it.sender)?.shownName ?: "someone"}" }
        if (banner != null) Row(Modifier.fillMaxWidth().background(JaceColors.bg2).padding(start = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(banner, color = JaceColors.muted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
            IconButton(onClick = { replyTo = null; if (editing != null) { editing = null; text = "" } }) { Icon(Icons.Filled.Close, "Cancel") }
        }
        if (uploads.isNotEmpty()) Chips(uploads.map { (if (it.image) "🖼 " else "📎 ") + it.name + if (it.att == null) " …" else " ✕" }) { i ->
            uploads.getOrNull(i)?.let { if (it.att != null) uploads.remove(it) }
        }
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (editing == null) IconButton(onClick = pickFiles) { Icon(Icons.Filled.AttachFile, "Attach files", tint = JaceColors.muted) }
            val enterSends = settings.send_on_enter != false
            OutlinedTextField(text, onValueChange = {
                text = it
                val now = System.currentTimeMillis()
                if (it.isNotBlank() && now - lastTyping > 4000) { lastTyping = now; scope.launch { session.api.typing(target) } }
            }, placeholder = { Text("Message ${group?.name ?: title}", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                keyboardOptions = KeyboardOptions(imeAction = if (enterSends) ImeAction.Send else ImeAction.Default),
                keyboardActions = KeyboardActions(onSend = { send() }),
                modifier = Modifier.weight(1f).heightIn(max = 160.dp), shape = RoundedCornerShape(22.dp))
            IconButton(onClick = ::send, enabled = text.isNotBlank() || uploads.any { it.att != null }) {
                Icon(Icons.AutoMirrored.Filled.Send, "Send", tint = JaceColors.green)
            }
        }
    }
    pins?.let { list ->
        AlertDialog(onDismissRequest = { pins = null }, title = { Text("📌 Pinned messages") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (list.isEmpty()) Muted("Nothing pinned yet. Long-press a message and choose Pin.")
                    for (m in list) Card {
                        Text(who(m.sender)?.shownName ?: "Someone", fontWeight = FontWeight.Bold)
                        Text(rich(m.body, ::who))
                        Muted(timeOf(m.created_at, settings))
                    }
                }
            },
            confirmButton = { TextButton(onClick = { pins = null }) { Text("Close") } })
    }
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
private fun MessageRow(m: Message, sender: Person?, grouped: Boolean, reply: ReplyPreview?, who: (String?) -> Person?, mine: Boolean,
                       settings: Settings, meId: String?, canPin: Boolean, canDelete: Boolean,
                       onReply: () -> Unit, onEdit: () -> Unit, onDelete: () -> Unit, onPin: (Boolean) -> Unit, onReact: (String) -> Unit) {
    val store = LocalStore.current
    val nav = LocalNav.current
    val context = LocalContext.current
    if (m.kind == "system") {
        Text("→ ${m.body}", color = JaceColors.muted, style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp))
        return
    }
    var menu by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    val compact = settings.compact == true
    val avatars = settings.show_avatars != false
    val pinged = !mine && meId != null && m.mentions.contains(meId)
    Box {
        Row(Modifier.fillMaxWidth().background(if (pinged) JaceColors.yellow.copy(alpha = 0.12f) else androidx.compose.ui.graphics.Color.Transparent)
            .combinedClickable(onClick = {}, onLongClick = { menu = true })
            .padding(start = 12.dp, end = 12.dp, top = if (grouped || compact) 1.dp else 10.dp, bottom = 1.dp)) {
            if (avatars && !compact) {
                if (grouped) Spacer(Modifier.width(40.dp))
                else Box(Modifier.clickable { m.sender?.let { nav.profile(it) } }) { Avatar(sender ?: Person(m.sender ?: "", "?"), 40) }
                Spacer(Modifier.width(10.dp))
            }
            Column(Modifier.weight(1f)) {
                if (reply != null) Text("↪ ${who(reply.sender)?.shownName ?: "Someone"}: ${plain(reply.body).ifBlank { "(attachment)" }}",
                    color = JaceColors.muted, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (!grouped || compact) Row(verticalAlignment = Alignment.Bottom) {
                    if (compact) Text("${timeOf(m.created_at, settings)}  ", color = JaceColors.muted, style = MaterialTheme.typography.labelSmall)
                    Text(sender?.shownName ?: "Someone", fontWeight = FontWeight.SemiBold, modifier = Modifier.clickable { m.sender?.let { nav.profile(it) } })
                    if (sender?.is_bot == true) Text(" BOT", color = JaceColors.green, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                    if (!compact) Text("  ${timeOf(m.created_at, settings)}", color = JaceColors.muted, style = MaterialTheme.typography.labelSmall)
                    if (m.pinned_at != null) Text("  📌", style = MaterialTheme.typography.labelSmall)
                }
                if (m.body.isNotBlank()) Text(rich(m.body, who, meId) { url -> openLink(context, store, url) } +
                    if (m.edited_at != null) AnnotatedString(" (edited)", SpanStyle(color = JaceColors.muted)) else AnnotatedString(""))
                for (a in m.attachments) {
                    if (a.type.startsWith("image/")) AsyncImage(a.url, a.name, Modifier.padding(top = 4.dp).widthIn(max = 260.dp).heightIn(max = 260.dp)
                        .clip(RoundedCornerShape(8.dp)).clickable { openUrl(context, a.url) })
                    else Text("📎 ${a.name} (${a.size / 1024} KB)", color = JaceColors.green, modifier = Modifier.padding(top = 4.dp).clickable { openUrl(context, a.url) })
                }
                if (settings.show_embeds != false) for (e in m.embeds) if (e.title != null || e.description != null || e.image != null)
                    Column(Modifier.padding(top = 4.dp).clip(RoundedCornerShape(8.dp)).background(JaceColors.bg2).clickable { openLink(context, store, e.url) }.padding(10.dp)) {
                        e.site?.let { Text(it, color = JaceColors.muted, style = MaterialTheme.typography.labelSmall) }
                        e.title?.let { Text(it, color = JaceColors.green, fontWeight = FontWeight.SemiBold) }
                        e.description?.let { Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 3, overflow = TextOverflow.Ellipsis) }
                        e.image?.let { AsyncImage(it, null, Modifier.padding(top = 4.dp).widthIn(max = 260.dp).heightIn(max = 200.dp).clip(RoundedCornerShape(6.dp))) }
                    }
                if (m.reactions.isNotEmpty()) FlowRow(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    for (r in m.reactions) Box(Modifier.clip(RoundedCornerShape(8.dp))
                        .background(if (r.me) JaceColors.green.copy(alpha = 0.18f) else JaceColors.bg2)
                        .border(1.dp, if (r.me) JaceColors.green else JaceColors.bg2, RoundedCornerShape(8.dp))
                        .clickable { onReact(r.emoji) }.padding(horizontal = 7.dp, vertical = 2.dp)) {
                        Text("${r.emoji} ${r.count}", style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            Row(Modifier.padding(horizontal = 8.dp)) {
                for (e in QUICK) Text(e, style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.clickable { menu = false; onReact(e) }.padding(6.dp))
            }
            DropdownMenuItem(text = { Text("Reply") }, onClick = { menu = false; onReply() })
            DropdownMenuItem(text = { Text("Copy text") }, onClick = { menu = false; copyText(context, m.body); store.toast("Copied") })
            if (canPin) DropdownMenuItem(text = { Text(if (m.pinned_at != null) "Unpin" else "Pin") }, onClick = { menu = false; onPin(m.pinned_at == null) })
            if (mine) DropdownMenuItem(text = { Text("Edit") }, onClick = { menu = false; onEdit() })
            if (canDelete) DropdownMenuItem(text = { Text("Delete", color = JaceColors.red) }, onClick = { menu = false; deleting = true })
            if (settings.developer == true) DropdownMenuItem(text = { Text("Copy message id") }, onClick = { menu = false; copyText(context, m.id.toString()) })
        }
    }
    if (deleting) Confirm("Delete this message?", yes = "Delete", danger = true, onDismiss = { deleting = false }, onYes = onDelete)
}

private val TOKENS = Regex("""(\*\*[^*]+\*\*|\*[^*\s][^*]*\*|`[^`]+`|~~[^~]+~~|https?://\S+|<@([0-9a-f]{32})>)""")

/** The website's message formatting, the common part: **bold**, *italic*, `code`, ~~strike~~, links, @mentions.
 *  Links go through onLink (the link warning). */
fun rich(body: String, who: (String?) -> Person?, me: String? = null, onLink: ((String) -> Unit)? = null): AnnotatedString = buildAnnotatedString {
    var at = 0
    for (m in TOKENS.findAll(body)) {
        append(body.substring(at, m.range.first))
        val t = m.value
        when {
            t.startsWith("**") -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(t.drop(2).dropLast(2)) }
            t.startsWith("`") -> withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = JaceColors.bg2)) { append(t.drop(1).dropLast(1)) }
            t.startsWith("~~") -> withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) { append(t.drop(2).dropLast(2)) }
            t.startsWith("*") -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(t.drop(1).dropLast(1)) }
            t.startsWith("<@") -> withStyle(SpanStyle(color = if (m.groupValues[2] == me) JaceColors.yellow else JaceColors.green, fontWeight = FontWeight.SemiBold)) {
                append("@" + (who(m.groupValues[2])?.shownName ?: "someone"))
            }
            onLink != null -> withLink(LinkAnnotation.Clickable(t, TextLinkStyles(SpanStyle(color = JaceColors.green))) { onLink(t) }) { append(t) }
            else -> withLink(LinkAnnotation.Url(t, TextLinkStyles(SpanStyle(color = JaceColors.green)))) { append(t) }
        }
        at = m.range.last + 1
    }
    append(body.substring(at))
}

private val TIME12 = DateTimeFormatter.ofPattern("h:mm a").withZone(ZoneId.systemDefault())
private val TIME24 = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault())

/** The server's timestamps ("2026-10-12T12:00:00.123+00:00" or "...Z") */
fun parseTime(iso: String): Instant? = runCatching { java.time.OffsetDateTime.parse(iso).toInstant() }.getOrNull()
    ?: runCatching { Instant.parse(iso) }.getOrNull()

fun timeOf(iso: String, settings: Settings? = null): String =
    parseTime(iso)?.let { (if (settings?.time_format == "24h") TIME24 else TIME12).format(it) } ?: ""

private fun minutesBetween(a: String, b: String): Long {
    val x = parseTime(a) ?: return 99
    val y = parseTime(b) ?: return 99
    return (y.epochSecond - x.epochSecond) / 60
}

/** A message without its formatting marks, for one-line previews. */
fun plain(body: String) = body.replace(Regex("""\*\*|__|~~|[*`]"""), "")
