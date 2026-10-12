package dev.jacedeb.jacesocial.ui

import androidx.compose.foundation.background
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
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
import dev.jacedeb.jacesocial.data.Ban
import dev.jacedeb.jacesocial.data.OnboardingOption
import dev.jacedeb.jacesocial.data.OnboardingQuestion
import dev.jacedeb.jacesocial.data.P
import dev.jacedeb.jacesocial.data.Person
import dev.jacedeb.jacesocial.data.Role
import dev.jacedeb.jacesocial.data.ServerDetail
import dev.jacedeb.jacesocial.data.WelcomeChannel
import dev.jacedeb.jacesocial.data.jo
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Server settings: overview, roles and permissions, members, invites and the custom link, onboarding,
 *  bans and bots (each tab only when your permissions allow it), and leaving or deleting the server. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServerSettingsScreen(id: String) {
    val store = LocalStore.current
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    var d by remember { mutableStateOf<ServerDetail?>(null) }
    var tab by remember { mutableStateOf(0) }
    var dialog by remember { mutableStateOf<String?>(null) }
    suspend fun load() { store.act { d = it.server(id) } }
    LaunchedEffect(id) { load() }
    val reload: () -> Unit = { scope.launch { load() } }
    val detail = d
    Column(Modifier.fillMaxSize()) {
        TopAppBar(navigationIcon = { IconButton(onClick = { nav.back() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            title = { Text(detail?.server?.name ?: "Server settings", fontWeight = FontWeight.Bold) })
        if (detail == null) { Muted("Loading…", small = false); return@Column }
        val p = detail.perms
        val tabs = listOf("Overview" to P.has(p, P.MANAGE_SERVER), "Roles" to P.has(p, P.MANAGE_ROLES),
            "Members" to (P.has(p, P.KICK) || P.has(p, P.BAN) || P.has(p, P.MANAGE_ROLES) || P.has(p, P.MANAGE_NICKS) || P.has(p, P.TIMEOUT)),
            "Invites" to (P.has(p, P.INVITE) || P.has(p, P.MANAGE_SERVER)), "Onboarding" to P.has(p, P.MANAGE_SERVER),
            "Bans" to P.has(p, P.BAN), "Bots" to P.has(p, P.MANAGE_BOTS)).filter { it.second }.map { it.first }
        if (tabs.isNotEmpty()) ScrollableTabRow(selectedTabIndex = tab.coerceAtMost(tabs.size - 1), edgePadding = 8.dp) {
            tabs.forEachIndexed { i, t -> Tab(selected = tab == i, onClick = { tab = i }, text = { Text(t) }) }
        }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (tabs.isEmpty()) Muted("You don't have permission to change this server's settings.", small = false)
            when (tabs.getOrNull(tab)) {
                "Overview" -> OverviewTab(detail, reload)
                "Roles" -> RolesTab(detail, reload)
                "Members" -> MembersTab(detail, reload)
                "Invites" -> InvitesTab(detail, reload)
                "Onboarding" -> OnboardingTab(detail, reload)
                "Bans" -> BansTab(detail)
                "Bots" -> BotsTab(detail, reload)
            }
            Spacer(Modifier.height(12.dp))
            if (detail.owner) TextButton(onClick = { dialog = "delete" }) { Text("Delete server", color = JaceColors.red) }
            else TextButton(onClick = { dialog = "leave" }) { Text("Leave server", color = JaceColors.red) }
        }
    }
    val me = store.me.collectAsState().value
    when (dialog) {
        "delete" -> Prompt("Delete ${detail?.server?.name} forever?", note = "Type the server name to delete it and everything in it.", yes = "Delete",
            onDismiss = { dialog = null }) { typed ->
            if (typed != detail?.server?.name) store.toast("The name didn't match") else
                scope.launch { if (store.act { it.deleteServer(id) }) { store.refreshServers(); nav.go("servers") } }
        }
        "leave" -> Confirm("Leave ${detail?.server?.name}?", yes = "Leave", danger = true, onDismiss = { dialog = null }) {
            scope.launch { if (store.act { it.removeMember(id, me?.uuid ?: "") }) { store.refreshServers(); nav.go("servers") } }
        }
    }
}

@Composable
private fun OverviewTab(d: ServerDetail, reload: () -> Unit) {
    val store = LocalStore.current
    val scope = rememberCoroutineScope()
    val s = d.server
    var name by remember(s.id) { mutableStateOf(s.name) }
    var desc by remember(s.id) { mutableStateOf(s.description ?: "") }
    var accent by remember(s.id) { mutableStateOf(s.accent_color) }
    var system by remember(s.id) { mutableStateOf(s.system_channel) }
    var pickSystem by remember { mutableStateOf(false) }
    val texts = d.channels.filter { it.kind == "text" || it.kind == "announcement" }
    val pickIcon = rememberPhotoPicker { p -> scope.launch { if (store.act { it.setServerIcon(s.id, p.bytes, p.type) }) reload() } }
    val pickBanner = rememberPhotoPicker { p -> scope.launch { if (store.act { it.setServerBanner(s.id, p.bytes, p.type) }) reload() } }
    Box(Modifier.fillMaxWidth().height(100.dp).clip(RoundedCornerShape(12.dp)).background(hexColor(s.accent_color, JaceColors.panel))) {
        s.banner_url?.let { AsyncImage(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
        Row(Modifier.align(Alignment.BottomEnd).padding(8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Button(onClick = pickBanner) { Text("Change banner") }
            if (s.banner_url != null) OutlinedButton(onClick = { scope.launch { if (store.act { it.removeServerBanner(s.id) }) reload() } }) { Text("Remove") }
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        IconPic(s.name, s.icon_url, 64)
        OutlinedButton(onClick = pickIcon) { Text("Change icon") }
    }
    Field("Server name", name, { name = it }, max = 48)
    Field("Description", desc, { desc = it }, max = 300, lines = 2)
    Heading("Accent color")
    Swatches(accent, withNone = true) { accent = it }
    Heading("Messages like “X joined” go to")
    OutlinedButton(onClick = { pickSystem = true }) { Text(texts.firstOrNull { it.id == system }?.let { "#${it.name}" } ?: "Nowhere (no join messages)") }
    Button(onClick = { scope.launch { if (store.act { it.updateServer(s.id, jo("name" to name, "description" to desc, "accent_color" to accent, "system_channel" to system)) }) { store.toast("Saved"); reload() } } }) { Text("Save") }
    if (pickSystem) Choose("Join messages go to", listOf("" to "Nowhere") + texts.map { it.id to "#${it.name}" }, onDismiss = { pickSystem = false }) { system = it.ifEmpty { null } }
}

/** The permission switches (or allow / inherit / deny for channel overrides). */
@Composable
fun PermissionList(value: Long, editable: Long, onChange: (Long) -> Unit, only: ((P.Info) -> Boolean)? = null,
                   tri: Triple<Long, Long, (Long, Long) -> Unit>? = null) {
    for ((group, items) in P.GROUPS) {
        val shown = items.filter { (only == null || only(it)) && (tri == null || it.channel) }
        if (shown.isEmpty()) continue
        Muted(group.uppercase())
        for (info in shown) {
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) { Text(info.name, fontWeight = FontWeight.SemiBold); if (info.desc.isNotBlank()) Muted(info.desc) }
                val can = (editable and info.bit) != 0L
                if (tri != null) {
                    val (allow, deny, set) = tri
                    val cur = if (allow and info.bit != 0L) "allow" else if (deny and info.bit != 0L) "deny" else "inherit"
                    for ((v, label) in listOf("deny" to "✕", "inherit" to "/", "allow" to "✓")) {
                        Box(Modifier.padding(start = 4.dp).size(34.dp).clip(RoundedCornerShape(8.dp))
                            .background(if (cur == v) when (v) { "allow" -> JaceColors.green; "deny" -> JaceColors.red; else -> JaceColors.gray } else JaceColors.panel)
                            .clickable(enabled = can) {
                                var a = allow and info.bit.inv(); var dn = deny and info.bit.inv()
                                if (v == "allow") a = a or info.bit
                                if (v == "deny") dn = dn or info.bit
                                set(a, dn)
                            }, contentAlignment = Alignment.Center) { Text(label, color = if (cur == v) Color.White else JaceColors.muted) }
                    }
                } else Switch(checked = (value and info.bit) == info.bit, enabled = can,
                    onCheckedChange = { onChange(if (it) value or info.bit else value and info.bit.inv()) })
            }
        }
    }
}

@Composable
private fun RolesTab(d: ServerDetail, reload: () -> Unit) {
    val store = LocalStore.current
    val scope = rememberCoroutineScope()
    val roles = d.roles.sortedByDescending { it.position }
    var selected by remember { mutableStateOf(roles.firstOrNull { !it.is_default }?.id ?: roles.firstOrNull()?.id) }
    val role = roles.firstOrNull { it.id == selected } ?: roles.firstOrNull() ?: return
    var draft by remember(role, d) { mutableStateOf(role) }
    var creating by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    fun canEdit(r: Role) = d.owner || r.is_default || r.position < d.top
    val editable = if (d.owner) P.ALL else d.perms
    val movable = roles.filter { !it.is_default && canEdit(it) }
    fun move(id: String, dir: Int) {
        val order = movable.map { it.id }.toMutableList()
        val i = order.indexOf(id); val j = i + dir
        if (i < 0 || j < 0 || j >= order.size) return
        order[i] = order[j].also { order[j] = order[i] }
        scope.launch { if (store.act { it.reorderRoles(d.server.id, order) }) reload() }
    }
    Muted("People get every permission from all their roles. Higher roles can manage the ones below them. The @everyone role is what everybody gets.")
    Button(onClick = { creating = true }) { Text("＋ Create role") }
    for (r in roles) Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(if (r.id == role.id) JaceColors.panel else Color.Transparent)
        .clickable { selected = r.id }.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(12.dp).clip(CircleShape).background(hexColor(r.color, JaceColors.gray)))
        Spacer(Modifier.width(8.dp))
        Text("${r.icon ?: ""} ${r.name}".trim(), Modifier.weight(1f))
        Muted("${if (r.is_default) d.members.size else d.members.count { it.roles.contains(r.id) }}")
        if (!r.is_default && canEdit(r)) {
            TextButton(onClick = { move(r.id, -1) }) { Text("▲") }
            TextButton(onClick = { move(r.id, 1) }) { Text("▼") }
        }
    }
    Card {
        if (!canEdit(draft)) Muted("This role is the same as or above your highest role, so you can't change it.")
        if (!draft.is_default) {
            Field("Role name", draft.name, { draft = draft.copy(name = it) }, max = 32)
            Heading("Color")
            Swatches(draft.color, withNone = true) { draft = draft.copy(color = it) }
            Field("Icon (an emoji)", draft.icon ?: "", { draft = draft.copy(icon = it.ifBlank { null }) }, max = 16)
            ToggleRow("Show members with this role separately", checked = draft.hoist) { draft = draft.copy(hoist = it) }
            ToggleRow("Anyone can @mention this role", checked = draft.mentionable) { draft = draft.copy(mentionable = it) }
        }
        PermissionList(draft.permissions, if (canEdit(draft)) editable else 0L, { draft = draft.copy(permissions = it) })
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(enabled = canEdit(draft), onClick = {
                scope.launch {
                    if (store.act { it.updateRole(d.server.id, draft.id, jo("name" to draft.name, "color" to draft.color, "icon" to draft.icon,
                            "hoist" to draft.hoist, "mentionable" to draft.mentionable, "permissions" to draft.permissions)) }) { store.toast("Saved"); reload() }
                }
            }) { Text("Save role") }
            if (!draft.is_default && canEdit(draft)) OutlinedButton(onClick = { deleting = true }) { Text("Delete role", color = JaceColors.red) }
        }
    }
    if (creating) Prompt("New role", "new role", label = "Role name", yes = "Create", onDismiss = { creating = false }) { n ->
        scope.launch {
            store.act { val r = it.createRole(d.server.id, n, ACCENTS[roles.size % ACCENTS.size]); selected = r.id }
            reload()
        }
    }
    if (deleting) Confirm("Delete the ${draft.name} role?", yes = "Delete", danger = true, onDismiss = { deleting = false }) {
        scope.launch { if (store.act { it.deleteRole(d.server.id, draft.id) }) reload() }
    }
}

@Composable
private fun MembersTab(d: ServerDetail, reload: () -> Unit) {
    val store = LocalStore.current
    val me = store.me.collectAsState().value ?: return
    var q by remember { mutableStateOf("") }
    OutlinedTextField(q, { q = it }, placeholder = { Text("Search members") }, singleLine = true, modifier = Modifier.fillMaxWidth())
    val list = d.members.filter { "${it.nickname ?: ""} ${it.name}".lowercase().contains(q.lowercase()) }.sortedBy { it.name.lowercase() }
    Heading("Members (${d.members.size})")
    for (m in list) Card {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Avatar(m.copy(unread = 0), 36)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(m.nickname?.takeIf { it.isNotBlank() } ?: m.name, fontWeight = FontWeight.Bold)
                    if (m.is_bot) BotTag()
                    if (m.uuid == d.server.owner) Text(" 👑")
                }
                Muted(listOfNotNull(m.nickname?.let { m.name }, m.joined_at?.let { "joined ${it.take(10)}" }).joinToString(" · "))
            }
        }
        MemberActions(d, m, me, reload)
    }
}

@Composable
private fun InvitesTab(d: ServerDetail, reload: () -> Unit) {
    val store = LocalStore.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val s = d.server
    var code by remember(s.id) { mutableStateOf(s.invite_code) }
    var vanity by remember(s.id) { mutableStateOf(s.vanity ?: "") }
    var renew by remember { mutableStateOf(false) }
    val c = code
    if (c != null) {
        Heading("Invite link")
        Card {
            Text(inviteLink(c), fontWeight = FontWeight.SemiBold)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { share(context, "Join ${s.name} on Jace Social: ${inviteLink(c)}") }) { Text("Share") }
                OutlinedButton(onClick = { copyText(context, inviteLink(c)); store.toast("Copied") }) { Text("Copy") }
                OutlinedButton(onClick = { renew = true }) { Text("New link") }
            }
        }
        Muted("Opening the link shows a page where people choose the app or the web.")
    } else Muted("You can't create invites in this server.", small = false)
    if (P.has(d.perms, P.MANAGE_SERVER)) {
        Heading("Custom link")
        Row(verticalAlignment = Alignment.CenterVertically) {
            Muted("jace-social.vercel.app/", small = false)
            OutlinedTextField(vanity, { vanity = it.lowercase().filter { ch -> ch.isLetterOrDigit() || ch == '-' }.take(32) },
                placeholder = { Text("yourserver") }, singleLine = true, modifier = Modifier.weight(1f))
        }
        Button(onClick = { scope.launch { if (store.act { it.updateServer(s.id, jo("vanity" to vanity)) }) { store.toast("Saved"); reload() } } }) { Text("Save custom link") }
        Muted("3-32 lowercase letters, numbers and dashes. It works like an invite that never changes.")
        ToggleRow("Pause invites", "Nobody new can join for now", s.invites_paused) { on ->
            scope.launch { if (store.act { it.updateServer(s.id, jo("invites_paused" to on)) }) reload() }
        }
    }
    if (renew) Confirm("Make a new link?", "The old one will stop working.", yes = "New link", onDismiss = { renew = false }) {
        scope.launch { store.act { code = it.newInvite(s.id) } }
    }
}

@Composable
private fun OnboardingTab(d: ServerDetail, reload: () -> Unit) {
    val store = LocalStore.current
    val scope = rememberCoroutineScope()
    val s = d.server
    var rules by remember(s.id) { mutableStateOf(s.rules ?: "") }
    var welcome by remember(s.id) { mutableStateOf(s.welcome?.message ?: "") }
    val channels = remember(s.id) { mutableStateListOf<WelcomeChannel>().apply { addAll(s.welcome?.channels ?: emptyList()) } }
    val questions = remember(s.id) { mutableStateListOf<OnboardingQuestion>().apply { addAll(s.onboarding) } }
    val roles = d.roles.filter { !it.is_default }.sortedByDescending { it.position }
    val texts = d.channels.filter { it.kind != "category" && it.kind != "voice" }
    var picking by remember { mutableStateOf<Pair<Int, Int>?>(null) }      // question, answer: choose roles
    var pickChannel by remember { mutableStateOf<Int?>(null) }
    Muted("What new members see when they join: the rules to agree to, a few questions whose answers give them roles, and a welcome screen.")
    Field("Rules", rules, { rules = it }, max = 2000, lines = 5, placeholder = "1. Be kind\n2. No spam")
    Field("Welcome message", welcome, { welcome = it }, max = 300, lines = 2, placeholder = "Welcome! Start in #general.")
    Heading("Suggested channels")
    channels.forEachIndexed { i, wc ->
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(wc.emoji ?: "", { channels[i] = wc.copy(emoji = it.take(16)) }, placeholder = { Text("👋") }, singleLine = true, modifier = Modifier.width(64.dp))
            OutlinedButton(onClick = { pickChannel = i }) { Text("#" + (texts.firstOrNull { it.id == wc.id }?.name ?: "?")) }
            OutlinedTextField(wc.description ?: "", { channels[i] = wc.copy(description = it.take(60)) }, placeholder = { Text("What it's for") }, singleLine = true, modifier = Modifier.weight(1f))
            IconButton(onClick = { channels.removeAt(i) }) { Text("✕") }
        }
    }
    if (channels.size < 5 && texts.isNotEmpty()) TextButton(onClick = { channels.add(WelcomeChannel(texts[0].id, "", "")) }) { Text("＋ Add channel") }
    Heading("Questions")
    questions.forEachIndexed { qi, q ->
        Card {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(q.question, { questions[qi] = q.copy(question = it.take(100)) }, placeholder = { Text("What do you play?") }, singleLine = true, modifier = Modifier.weight(1f))
                IconButton(onClick = { questions.removeAt(qi) }) { Text("✕") }
            }
            ToggleRow("People can pick more than one", checked = q.multiple) { questions[qi] = q.copy(multiple = it) }
            q.options.forEachIndexed { oi, o ->
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(o.emoji ?: "", { questions[qi] = q.copy(options = q.options.mapIndexed { j, x -> if (j == oi) x.copy(emoji = it.take(16)) else x }) },
                        placeholder = { Text("⛏") }, singleLine = true, modifier = Modifier.width(64.dp))
                    OutlinedTextField(o.label, { questions[qi] = q.copy(options = q.options.mapIndexed { j, x -> if (j == oi) x.copy(label = it.take(50)) else x }) },
                        placeholder = { Text("Answer") }, singleLine = true, modifier = Modifier.weight(1f))
                    IconButton(onClick = { questions[qi] = q.copy(options = q.options.filterIndexed { j, _ -> j != oi }) }) { Text("✕") }
                }
                TextButton(onClick = { picking = qi to oi }) {
                    Text("Roles: " + (roles.filter { o.role_ids.contains(it.id) }.joinToString { it.name }.ifEmpty { "none" }))
                }
            }
            if (q.options.size < 10) TextButton(onClick = { questions[qi] = q.copy(options = q.options + OnboardingOption()) }) { Text("＋ Add answer") }
        }
    }
    if (questions.size < 5) TextButton(onClick = { questions.add(OnboardingQuestion(options = listOf(OnboardingOption()))) }) { Text("＋ Add question") }
    Button(onClick = {
        scope.launch {
            val qs = questions.filter { it.question.isNotBlank() }.map { q ->
                mapOf("question" to q.question, "multiple" to q.multiple,
                    "options" to q.options.filter { it.label.isNotBlank() }.map { o -> mapOf("label" to o.label, "emoji" to o.emoji, "role_ids" to o.role_ids) })
            }
            val wcs = channels.map { mapOf("id" to it.id, "description" to it.description, "emoji" to it.emoji) }
            if (store.act { it.updateServer(s.id, jo("rules" to rules, "welcome" to mapOf("message" to welcome, "channels" to wcs), "onboarding" to qs)) }) {
                store.toast("Saved"); reload()
            }
        }
    }) { Text("Save onboarding") }
    picking?.let { (qi, oi) ->
        val o = questions.getOrNull(qi)?.options?.getOrNull(oi)
        if (o == null) { picking = null; return@let }
        androidx.compose.material3.AlertDialog(onDismissRequest = { picking = null }, title = { Text("Roles for “${o.label}”") },
            text = {
                Column {
                    if (roles.isEmpty()) Muted("Make some roles first (the Roles tab).")
                    for (r in roles) Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable {
                        val ids = if (o.role_ids.contains(r.id)) o.role_ids - r.id else o.role_ids + r.id
                        val q = questions[qi]
                        questions[qi] = q.copy(options = q.options.mapIndexed { j, x -> if (j == oi) x.copy(role_ids = ids) else x })
                    }) {
                        Checkbox(checked = questions[qi].options[oi].role_ids.contains(r.id), onCheckedChange = null)
                        Text(r.name)
                    }
                }
            }, confirmButton = { TextButton(onClick = { picking = null }) { Text("Done") } })
    }
    pickChannel?.let { i ->
        Choose("Suggested channel", texts.map { it.id to "#${it.name}" }, onDismiss = { pickChannel = null }) { cid -> channels[i] = channels[i].copy(id = cid) }
    }
}

@Composable
private fun BansTab(d: ServerDetail) {
    val store = LocalStore.current
    val scope = rememberCoroutineScope()
    var bans by remember { mutableStateOf<List<Ban>?>(null) }
    suspend fun load() { store.act { bans = it.bans(d.server.id) } }
    LaunchedEffect(d.server.id) { load() }
    val list = bans
    when {
        list == null -> Muted("Loading…", small = false)
        list.isEmpty() -> Muted("Nobody is banned.", small = false)
        else -> for (b in list) Row(verticalAlignment = Alignment.CenterVertically) {
            Avatar(b.person ?: Person(b.uuid, "?"), 36)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) { Text(b.person?.name ?: b.uuid, fontWeight = FontWeight.Bold); Muted("${b.reason ?: "No reason given"} · ${b.created_at.take(10)}") }
            OutlinedButton(onClick = { scope.launch { if (store.act { it.unban(d.server.id, b.uuid) }) load() } }) { Text("Unban") }
        }
    }
}

@Composable
private fun BotsTab(d: ServerDetail, reload: () -> Unit) {
    val store = LocalStore.current
    val scope = rememberCoroutineScope()
    var q by remember { mutableStateOf("") }
    var found by remember { mutableStateOf<List<Person>>(emptyList()) }
    var mine by remember { mutableStateOf<List<Person>>(emptyList()) }
    LaunchedEffect(Unit) { runCatching { mine = store.api.bots().map { it.asPerson() } } }
    LaunchedEffect(q) { delay(250); runCatching { found = store.api.directory(q) }.onFailure { store.toast(it.message ?: "") } }
    val inServer = d.members.map { it.uuid }.toSet()
    Muted("Bots join as members. Give them roles to let them do more (like giving roles or deleting messages).")
    @Composable fun row(b: Person) = Row(verticalAlignment = Alignment.CenterVertically) {
        Avatar(b, 36)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) { Text(b.name, fontWeight = FontWeight.Bold); BotTag() }
            b.bio?.let { Muted(it) }
        }
        if (inServer.contains(b.uuid)) Muted("Added")
        else Button(onClick = { scope.launch { if (store.act { it.addBot(d.server.id, b.uuid) }) reload() } }) { Text("Add") }
    }
    if (mine.isNotEmpty()) { Heading("Your bots"); for (b in mine) row(b) }
    Heading("Find bots")
    OutlinedTextField(q, { q = it }, placeholder = { Text("Search public bots") }, singleLine = true, modifier = Modifier.fillMaxWidth())
    for (b in found) row(b)
}
