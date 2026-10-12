package dev.jacedeb.jacesocial.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** The signed-in account: its token (kept encrypted on the phone) and the API that uses it.
 *  Also the account switcher: the other accounts signed in on this phone (and bots you switched to). */
class Session(context: Context) {
    private val prefs: SharedPreferences = EncryptedSharedPreferences.create(
        context, "jace_session",
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )
    private val json = Json { ignoreUnknownKeys = true }
    private val _token = MutableStateFlow(prefs.getString("token", null))
    val token: StateFlow<String?> = _token
    val api = Api { _token.value }
    private val _accounts = MutableStateFlow(loadAccounts())
    val accounts: StateFlow<List<SavedAccount>> = _accounts

    fun signedIn(s: SignedIn) = use(s.token)

    private fun use(token: String?) {
        prefs.edit().apply { if (token == null) remove("token") else putString("token", token) }.apply()
        _token.value = token
    }

    suspend fun signOut() {
        val t = _token.value
        api.signOut()
        saveAccounts(_accounts.value.filter { it.token != t })
        use(null)
    }

    /** The server said the token is no good any more (401). */
    fun expired() {
        val t = _token.value
        saveAccounts(_accounts.value.filter { it.token != t })
        use(null)
    }

    // --- account switcher
    /** Remember the account that's signed in now (after loading /me). */
    fun remember(me: Me) {
        val t = _token.value ?: return
        remember(SavedAccount(me.uuid, me.shownName, me.avatar_url, t, me.is_bot))
    }
    fun remember(a: SavedAccount) = saveAccounts((listOf(a) + _accounts.value.filter { it.uuid != a.uuid }).take(10))
    fun switchTo(uuid: String) { _accounts.value.firstOrNull { it.uuid == uuid }?.let { use(it.token) } }
    fun forget(uuid: String) = saveAccounts(_accounts.value.filter { it.uuid != uuid })
    /** Sign in to another account: this one stays in the list. */
    fun addAnother() = use(null)

    private fun loadAccounts(): List<SavedAccount> =
        runCatching { json.decodeFromString(ListSerializer(SavedAccount.serializer()), prefs.getString("accounts", "[]")!!) }.getOrDefault(emptyList())
    private fun saveAccounts(list: List<SavedAccount>) {
        prefs.edit().putString("accounts", json.encodeToString(ListSerializer(SavedAccount.serializer()), list)).apply()
        _accounts.value = list
    }
}
