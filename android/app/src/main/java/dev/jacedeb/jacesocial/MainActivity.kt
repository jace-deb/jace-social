package dev.jacedeb.jacesocial

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import dev.jacedeb.jacesocial.data.Session
import dev.jacedeb.jacesocial.data.SignedIn
import dev.jacedeb.jacesocial.data.Store
import dev.jacedeb.jacesocial.ui.JaceTheme
import dev.jacedeb.jacesocial.ui.MainScreen
import dev.jacedeb.jacesocial.ui.SignInScreen
import kotlinx.coroutines.flow.MutableStateFlow

class MainActivity : ComponentActivity() {
    private val session by lazy { Session(applicationContext) }
    private var startAt: String? = null
    /** Invite links and notifications to open (handled once signed in). */
    private val links = MutableStateFlow<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        testHooks(intent)
        handle(intent)
        setContent {
            val token by session.token.collectAsState()
            val store = remember(token) { token?.let { Store(session, applicationContext) } }
            val me = store?.me?.collectAsState()?.value
            JaceTheme(me?.settings) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    if (store == null) SignInScreen(session) else {
                        DisposableEffect(store) { store.start(); onDispose { store.stop() } }
                        MainScreen(session, store, startAt, links)
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    private fun handle(intent: Intent?) {
        val link = intent?.getStringExtra("open") ?: intent?.data?.toString() ?: return
        links.value = null
        links.value = link
    }

    /** Test builds only (CI's screenshots, with native-test/mock_api.py): --es jace_token <t> signs
     *  in with that token, --es jace_open <route> opens a screen (e.g. "chat/dm/<uuid>?title=Steve"). */
    private fun testHooks(intent: Intent) {
        if (!BuildConfig.DEBUG) return
        intent.getStringExtra("jace_token")?.let { session.signedIn(SignedIn(token = it)) }
        startAt = intent.getStringExtra("jace_open")
    }
}
