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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import dev.jacedeb.jacesocial.data.ChatTarget
import dev.jacedeb.jacesocial.data.Message
import dev.jacedeb.jacesocial.data.Person
import dev.jacedeb.jacesocial.data.ReplyPreview
import dev.jacedeb.jacesocial.data.Session
import dev.jacedeb.jacesocial.data.Store
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val QUICK = listOf("👍", "❤️", "😂", "😮", "😢", "🎉")

/** One conversation: a friend, a group chat or a server channel. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(session: Session, store: Store, target: ChatTarget, title: String, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val me by store.me.collectAsState()
    val typing by store.typing.collectAsState()
    var messages by remember { mutableStateOf<List<Message>>(emptyList()) }
    var replies by remember { mutableStateOf<Map<String, ReplyPreview>>(emptyMap()) }
    var people by remember { mutableStateOf<Map<String, Person>>(emptyMap()) }
    var error by remember { mutableStateOf<String?>(null) }
    var loaded by remember { mutableStateOf(false) }
    var olderDone by remember { mutableStateOf(false) }
    var text by remember { mutableStateOf("") }
    var replyTo by remember { mutableStateOf<Message?>(null) }
    var editing by remember { mutableStateOf<Message?>(null) }
    var lastTyping by remember { mutableLongStateOf(0L) }
    val list = rememberLazyListState()

    fun who(uuid: String?): Person? = uuid?.let { people[it] ?: store.person(it) ?: me?.let { m -> if (m.uuid == uuid) Person(m.uuid, m.name, avatar_url = m.avatar_url, online = true, status = m.status, display_name = m.display_name) else null } }

    suspend fun loadNewest() {
        try {
            val page = session.api.messages(target)
            val byId = (messages + page.messages).associateBy { it.id }
            messages = byId.values.sortedBy { it.id }.filter { m -> page.messages.any { it.id == m.id } || m.id < (page.messages.firstOrNull()?.id ?: 0) }
            replies = replies + page.replies
            people = people + page.people
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

    LaunchedEffect(target.key) { loadNewest() }
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

    fun send() {
        val body = text.trim()
        if (body.isEmpty()) return
        val edit = editing
        val reply = replyTo
        text = ""; replyTo = null; editing = null
        scope.launch {
            try {
                if (edit != null) {
                    session.api.edit(target, edit.id, body)
                    messages = messages.map { if (it.id == edit.id) it.copy(body = body, edited_at = "now") else it }
                } else {
                    val m = session.api.send(target, body, reply?.id)
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

    Column(Modifier.fillMaxSize().imePadding()) {
        TopAppBar(
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            title = {
                Column {
                    Text(title, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    val names = typing[target.key].orEmpty()
                    if (names.isNotEmpty()) Text("${names.joinToString()} ${if (names.size == 1) "is" else "are"} typing…",
                        style = MaterialTheme.typography.labelSmall, color = JaceColors.muted)
                }
            })
        error?.let { Text(it, color = JaceColors.red, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) }
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = list, reverseLayout = true,
            contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 8.dp)) {
            val shown = messages.asReversed()
            items(shown, key = { it.id }) { m ->
                val i = shown.indexOf(m)
                val prev = shown.getOrNull(i + 1)        // the one above it
                val grouped = prev != null && prev.sender == m.sender && prev.kind != "system" && minutesBetween(prev.created_at, m.created_at) < 5
                MessageRow(m, who(m.sender), grouped, m.reply_to?.let { replies[it.toString()] }, ::who,
                    mine = m.sender == me?.uuid,
                    onReply = { replyTo = m; editing = null },
                    onEdit = { editing = m; replyTo = null; text = m.body },
                    onDelete = { scope.launch { runCatching { session.api.delete(target, m.id) }.onSuccess { messages = messages.filter { it.id != m.id } }.onFailure { error = it.message } } },
                    onReact = { e -> react(m, e) })
            }
            if (loaded && messages.isEmpty()) item { Text("No messages yet - say hi!", color = JaceColors.muted, modifier = Modifier.padding(24.dp)) }
        }
        // the reply / edit banner, then the message box
        val banner = editing?.let { "Editing your message" } ?: replyTo?.let { "Replying to ${who(it.sender)?.shownName ?: "someone"}" }
        if (banner != null) Row(Modifier.fillMaxWidth().background(JaceColors.bg2).padding(start = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(banner, color = JaceColors.muted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
            IconButton(onClick = { replyTo = null; if (editing != null) { editing = null; text = "" } }) { Icon(Icons.Filled.Close, "Cancel") }
        }
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(text, onValueChange = {
                text = it
                val now = System.currentTimeMillis()
                if (it.isNotBlank() && now - lastTyping > 4000) { lastTyping = now; scope.launch { session.api.typing(target) } }
            }, placeholder = { Text("Message $title", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                modifier = Modifier.weight(1f).heightIn(max = 160.dp), shape = RoundedCornerShape(22.dp))
            IconButton(onClick = ::send, enabled = text.isNotBlank()) { Icon(Icons.AutoMirrored.Filled.Send, "Send", tint = JaceColors.green) }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
private fun MessageRow(m: Message, sender: Person?, grouped: Boolean, reply: ReplyPreview?, who: (String?) -> Person?, mine: Boolean,
                       onReply: () -> Unit, onEdit: () -> Unit, onDelete: () -> Unit, onReact: (String) -> Unit) {
    if (m.kind == "system") {
        Text("→ ${m.body}", color = JaceColors.muted, style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp))
        return
    }
    var menu by remember { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
    Box {
        Row(Modifier.fillMaxWidth().combinedClickable(onClick = {}, onLongClick = { menu = true })
            .padding(start = 12.dp, end = 12.dp, top = if (grouped) 1.dp else 10.dp, bottom = 1.dp)) {
            if (grouped) Spacer(Modifier.width(40.dp)) else Avatar(sender ?: Person(m.sender ?: "", "?"), 40)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                if (reply != null) Text("↪ ${who(reply.sender)?.shownName ?: "Someone"}: ${plain(reply.body).ifBlank { "(attachment)" }}",
                    color = JaceColors.muted, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (!grouped) Row(verticalAlignment = Alignment.Bottom) {
                    Text(sender?.shownName ?: "Someone", fontWeight = FontWeight.SemiBold)
                    if (sender?.is_bot == true) Text(" BOT", color = JaceColors.green, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                    Text("  ${timeOf(m.created_at)}", color = JaceColors.muted, style = MaterialTheme.typography.labelSmall)
                }
                if (m.body.isNotBlank()) Text(rich(m.body, who) + if (m.edited_at != null) AnnotatedString(" (edited)", SpanStyle(color = JaceColors.muted)) else AnnotatedString(""))
                for (a in m.attachments) {
                    if (a.type.startsWith("image/")) AsyncImage(a.url, a.name, Modifier.padding(top = 4.dp).widthIn(max = 260.dp).heightIn(max = 260.dp).clip(RoundedCornerShape(8.dp)))
                    else Text("📎 ${a.name}", color = JaceColors.green, modifier = Modifier.padding(top = 4.dp))
                }
                for (e in m.embeds) if (e.title != null || e.description != null) Column(Modifier.padding(top = 4.dp).clip(RoundedCornerShape(8.dp)).background(JaceColors.bg2).padding(10.dp)) {
                    e.site?.let { Text(it, color = JaceColors.muted, style = MaterialTheme.typography.labelSmall) }
                    e.title?.let { Text(it, color = JaceColors.green, fontWeight = FontWeight.SemiBold) }
                    e.description?.let { Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 3, overflow = TextOverflow.Ellipsis) }
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
            DropdownMenuItem(text = { Text("Copy text") }, onClick = { menu = false; clipboard.setText(AnnotatedString(m.body)) })
            if (mine) {
                DropdownMenuItem(text = { Text("Edit") }, onClick = { menu = false; onEdit() })
                DropdownMenuItem(text = { Text("Delete", color = JaceColors.red) }, onClick = { menu = false; onDelete() })
            }
        }
    }
}

private val TOKENS = Regex("""(\*\*[^*]+\*\*|\*[^*\s][^*]*\*|`[^`]+`|~~[^~]+~~|https?://\S+|<@([0-9a-f]{32})>)""")

/** The website's message formatting, the common part: **bold**, *italic*, `code`, ~~strike~~, links, @mentions. */
fun rich(body: String, who: (String?) -> Person?): AnnotatedString = buildAnnotatedString {
    var at = 0
    for (m in TOKENS.findAll(body)) {
        append(body.substring(at, m.range.first))
        val t = m.value
        when {
            t.startsWith("**") -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(t.drop(2).dropLast(2)) }
            t.startsWith("`") -> withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = JaceColors.bg2)) { append(t.drop(1).dropLast(1)) }
            t.startsWith("~~") -> withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) { append(t.drop(2).dropLast(2)) }
            t.startsWith("*") -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(t.drop(1).dropLast(1)) }
            t.startsWith("<@") -> withStyle(SpanStyle(color = JaceColors.green, fontWeight = FontWeight.SemiBold)) {
                append("@" + (who(m.groupValues[2])?.shownName ?: "someone"))
            }
            else -> withLink(LinkAnnotation.Url(t, TextLinkStyles(SpanStyle(color = JaceColors.green)))) { append(t) }
        }
        at = m.range.last + 1
    }
    append(body.substring(at))
}

private val TIME = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault())

/** The server's timestamps ("2026-10-12T12:00:00.123+00:00" or "...Z") */
fun parseTime(iso: String): Instant? = runCatching { java.time.OffsetDateTime.parse(iso).toInstant() }.getOrNull()
    ?: runCatching { Instant.parse(iso) }.getOrNull()

fun timeOf(iso: String): String = parseTime(iso)?.let { TIME.format(it) } ?: ""

private fun minutesBetween(a: String, b: String): Long {
    val x = parseTime(a) ?: return 99
    val y = parseTime(b) ?: return 99
    return (y.epochSecond - x.epochSecond) / 60
}

/** A message without its formatting marks, for one-line previews. */
fun plain(body: String) = body.replace(Regex("""\*\*|__|~~|[*`]"""), "")
