package dev.jacedeb.jacesocial

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import dev.jacedeb.jacesocial.data.Session
import dev.jacedeb.jacesocial.ui.HomeScreen
import dev.jacedeb.jacesocial.ui.JaceTheme
import dev.jacedeb.jacesocial.ui.SignInScreen

class MainActivity : ComponentActivity() {
    private val session by lazy { Session(applicationContext) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            JaceTheme {
                Surface(Modifier.fillMaxSize()) {
                    val token by session.token.collectAsState()
                    if (token == null) SignInScreen(session) else HomeScreen(session)
                }
            }
        }
    }
}
