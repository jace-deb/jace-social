package dev.jacedeb.jacesocial.ui

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import dev.jacedeb.jacesocial.data.Store

/** The app's state and navigation, for every screen. */
val LocalStore = staticCompositionLocalOf<Store> { error("no store") }
val LocalNav = staticCompositionLocalOf<AppNav> { error("no navigation") }

/** Where you can go from anywhere (MainScreen fills these in). */
interface AppNav {
    fun back()
    fun go(route: String)
    fun chat(t: dev.jacedeb.jacesocial.data.ChatTarget, title: String)
    fun profile(uuid: String, server: String? = null)
    /** Ask for the microphone (calls, voice), then go on either way (you can still listen). */
    fun withMic(then: () -> Unit)
    /** Ask for the camera, then go on if allowed. */
    fun withCamera(then: () -> Unit)
    /** Ask to capture the screen; then(consent) once allowed. */
    fun screenShare(then: (android.content.Intent) -> Unit)
}

/** A screen with a title bar and a back arrow. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Page(title: String, actions: @Composable () -> Unit = {}, scroll: Boolean = true, content: @Composable ColumnScope.() -> Unit) {
    val nav = LocalNav.current
    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            navigationIcon = { IconButton(onClick = { nav.back() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            title = { Text(title, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            actions = { actions() })
        val m = Modifier.fillMaxSize().let { if (scroll) it.verticalScroll(rememberScrollState()) else it }
        Column(m.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
    }
}

@Composable
fun Muted(text: String, small: Boolean = true) =
    Text(text, color = JaceColors.muted, style = if (small) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyMedium)

@Composable
fun Heading(text: String) = Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)

/** An on/off setting with its explanation. */
@Composable
fun ToggleRow(label: String, desc: String? = null, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(enabled = enabled) { onChange(!checked) }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, fontWeight = FontWeight.SemiBold)
            desc?.takeIf { it.isNotBlank() }?.let { Muted(it) }
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}

/** A row that opens something. */
@Composable
fun NavRow(label: String, sub: String? = null, color: Color = Color.Unspecified, icon: String? = null, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        icon?.let { Text(it, modifier = Modifier.width(32.dp)) }
        Column(Modifier.weight(1f)) {
            Text(label, fontWeight = FontWeight.SemiBold, color = if (color == Color.Unspecified) MaterialTheme.colorScheme.onBackground else color)
            sub?.let { Muted(it) }
        }
        Text("›", color = JaceColors.muted)
    }
}

/** "Are you sure?" */
@Composable
fun Confirm(title: String, text: String = "", yes: String = "OK", danger: Boolean = false, onDismiss: () -> Unit, onYes: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text(title) }, text = if (text.isBlank()) null else ({ Text(text) }),
        confirmButton = { TextButton(onClick = { onDismiss(); onYes() }) { Text(yes, color = if (danger) JaceColors.red else JaceColors.green) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}

/** Ask for one line of text. */
@Composable
fun Prompt(title: String, initial: String = "", label: String = "", yes: String = "OK", note: String? = null, allowEmpty: Boolean = false,
           onDismiss: () -> Unit, onDone: (String) -> Unit) {
    var v by remember { mutableStateOf(initial) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                note?.let { Muted(it) }
                OutlinedTextField(v, { v = it }, label = if (label.isBlank()) null else ({ Text(label) }), singleLine = true, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = { TextButton(enabled = allowEmpty || v.isNotBlank(), onClick = { onDismiss(); onDone(v.trim()) }) { Text(yes) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}

/** Choose one of a few. */
@Composable
fun Choose(title: String, options: List<Pair<String, String>>, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text(title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                for ((value, label) in options) Text(label, Modifier.fillMaxWidth().clickable { onDismiss(); onPick(value) }.padding(vertical = 12.dp))
            }
        },
        confirmButton = {}, dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}

/** A picture (or initials) for a server, group or bot. */
@Composable
fun IconPic(name: String, url: String?, size: Int, shape: Shape = RoundedCornerShape((size / 3).dp)) {
    Box(Modifier.size(size.dp).clip(shape).background(JaceColors.panel), contentAlignment = Alignment.Center) {
        if (url != null) AsyncImage(url, name, Modifier.fillMaxSize())
        else Text(name.split(" ").mapNotNull { it.firstOrNull()?.uppercaseChar() }.take(2).joinToString(""), fontWeight = FontWeight.Bold)
    }
}

/** The accent color choices (the website's). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun Swatches(selected: String?, withNone: Boolean = false, onPick: (String?) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (withNone) Box(Modifier.size(36.dp).clip(CircleShape).background(JaceColors.panel)
            .border(2.dp, if (selected == null) JaceColors.text else Color.Transparent, CircleShape).clickable { onPick(null) })
        for (c in ACCENTS) Box(Modifier.size(36.dp).clip(CircleShape).background(hexColor(c))
            .border(2.dp, if (selected.equals(c, true)) JaceColors.text else Color.Transparent, CircleShape).clickable { onPick(c) })
    }
}

/** A picked file: its bytes, type and name (and size, for pictures). */
class Picked(val bytes: ByteArray, val type: String, val name: String, val width: Int? = null, val height: Int? = null)

fun readPicked(context: Context, uri: Uri): Picked? = runCatching {
    val cr = context.contentResolver
    val bytes = cr.openInputStream(uri)!!.use { it.readBytes() }
    val type = cr.getType(uri) ?: "application/octet-stream"
    val name = cr.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
        if (c.moveToFirst()) c.getString(0) else null
    } ?: "file"
    var w: Int? = null; var h: Int? = null
    if (type.startsWith("image/")) {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
        if (o.outWidth > 0) { w = o.outWidth; h = o.outHeight }
    }
    Picked(bytes, type, name, w, h)
}.getOrNull()

/** Pick a photo (avatars, icons, banners): -> launch() */
@Composable
fun rememberPhotoPicker(onPicked: (Picked) -> Unit): () -> Unit {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri?.let { readPicked(context, it) }?.let(onPicked)
    }
    return { launcher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
}

/** Pick any files (attachments): -> launch() */
@Composable
fun rememberFilePicker(onPicked: (List<Picked>) -> Unit): () -> Unit {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        onPicked(uris.take(10).mapNotNull { readPicked(context, it) })
    }
    return { launcher.launch("*/*") }
}

/** A labelled text box. */
@Composable
fun Field(label: String, value: String, onChange: (String) -> Unit, max: Int = 100, lines: Int = 1, placeholder: String = "") {
    OutlinedTextField(value, { if (it.length <= max) onChange(it) }, label = { Text(label) }, singleLine = lines == 1, minLines = lines,
        placeholder = if (placeholder.isBlank()) null else ({ Text(placeholder) }), modifier = Modifier.fillMaxWidth())
}

/** "2 minutes", "3 h 5 min", "4 days" (since an ISO time). */
fun since(iso: String?): String {
    val t = iso?.let { parseTime(it) } ?: return ""
    val s = maxOf(0L, (System.currentTimeMillis() - t.toEpochMilli()) / 1000)
    return when {
        s < 60 -> "just now"
        s < 3600 -> "${s / 60} min"
        s < 86400 -> "${s / 3600} h ${(s % 3600) / 60} min"
        else -> "${s / 86400} days"
    }
}

/** Open a link in the browser. */
fun openUrl(context: Context, url: String) {
    runCatching {
        androidx.browser.customtabs.CustomTabsIntent.Builder().build().launchUrl(context, Uri.parse(url))
    }.onFailure {
        runCatching { context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, Uri.parse(url))) }
    }
}

fun copyText(context: Context, text: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
    cm.setPrimaryClip(android.content.ClipData.newPlainText("Jace Social", text))
}

fun share(context: Context, text: String) {
    val i = android.content.Intent(android.content.Intent.ACTION_SEND).setType("text/plain").putExtra(android.content.Intent.EXTRA_TEXT, text)
    context.startActivity(android.content.Intent.createChooser(i, null))
}

@Composable
fun Card(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(JaceColors.panel).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
}
