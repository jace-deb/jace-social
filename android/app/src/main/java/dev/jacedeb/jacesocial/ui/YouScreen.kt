package dev.jacedeb.jacesocial.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.jacedeb.jacesocial.data.Person
import dev.jacedeb.jacesocial.data.Session
import dev.jacedeb.jacesocial.data.Store
import kotlinx.coroutines.launch

/** You: your profile and status. (Settings and editing come later.) */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun YouScreen(session: Session, store: Store) {
    val me by store.me.collectAsState()
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxSize()) {
        TopAppBar(title = { Text("You", fontWeight = FontWeight.Bold) })
        Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            me?.let { m ->
                Avatar(Person(m.uuid, m.name, avatar_url = m.avatar_url, online = true, status = m.status), 88)
                Text(m.display_name ?: m.name, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                listOfNotNull(m.status_emoji, m.custom_status).joinToString(" ").takeIf { it.isNotBlank() }?.let { Text(it, color = JaceColors.muted) }
            }
            OutlinedButton(onClick = { scope.launch { store.stop(); session.signOut() } }) { Text("Sign out") }
        }
    }
}
