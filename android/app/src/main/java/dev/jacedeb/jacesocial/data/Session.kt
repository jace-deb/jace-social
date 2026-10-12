package dev.jacedeb.jacesocial.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** The signed-in account: its token (kept encrypted on the phone) and the API that uses it. */
class Session(context: Context) {
    private val prefs: SharedPreferences = EncryptedSharedPreferences.create(
        context, "jace_session",
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )
    private val _token = MutableStateFlow(prefs.getString("token", null))
    val token: StateFlow<String?> = _token
    val api = Api { _token.value }

    fun signedIn(s: SignedIn) {
        prefs.edit().putString("token", s.token).apply()
        _token.value = s.token
    }

    suspend fun signOut() {
        api.signOut()
        prefs.edit().remove("token").apply()
        _token.value = null
    }

    /** The server said the token is no good any more (401). */
    fun expired() {
        prefs.edit().remove("token").apply()
        _token.value = null
    }
}
