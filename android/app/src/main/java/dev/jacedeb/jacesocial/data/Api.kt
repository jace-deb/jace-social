package dev.jacedeb.jacesocial.data

import dev.jacedeb.jacesocial.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/** An error the server explained (its "error" field), or a network problem. */
class ApiError(val status: Int, message: String) : Exception(message)

/** The Jace Social API (docs/api.md). One instance per signed-in account. */
class Api(private val token: () -> String?) {
    val base = BuildConfig.API_BASE
    val json = Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = false }
    private val http = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()

    /** method + path ("/me") -> the answer's JSON text. Throws ApiError. */
    suspend fun call(method: String, path: String, body: String? = null): String = withContext(Dispatchers.IO) {
        val req = Request.Builder().url("$base/api/v1$path")
            .method(method, body?.toRequestBody("application/json".toMediaType())
                ?: if (method == "GET" || method == "DELETE") null else "{}".toRequestBody("application/json".toMediaType()))
            .header("User-Agent", "JaceSocialAndroid/${BuildConfig.VERSION_NAME}")
        token()?.let { req.header("Authorization", "Bearer $it") }
        val res = try {
            http.newCall(req.build()).execute()
        } catch (e: IOException) {
            throw ApiError(0, "Can't reach Jace Social - check your internet connection")
        }
        res.use {
            val text = it.body?.string().orEmpty()
            if (!it.isSuccessful) {
                val msg = runCatching { json.parseToJsonElement(text).jsonObject["error"]?.jsonPrimitive?.contentOrNull }.getOrNull()
                throw ApiError(it.code, msg ?: "Jace Social error (${it.code})")
            }
            text
        }
    }

    suspend inline fun <reified T> get(path: String): T = json.decodeFromString(call("GET", path))
    suspend inline fun <reified T> post(path: String, body: JsonObject = JsonObject(emptyMap())): T =
        json.decodeFromString(call("POST", path, body.toString()))

    // --- the endpoints so far (more as screens are added)
    suspend fun me(): Me = get("/me")
    suspend fun friends(): FriendsData = get("/friends")
    suspend fun startJace(): JaceStart = post("/auth/jace")
    /** null while the browser part isn't done yet */
    suspend fun pollJace(start: JaceStart): SignedIn? {
        val text = call("GET", "/auth/jace/poll?state=${start.state}&key=${start.poll_key}")
        if (json.parseToJsonElement(text).jsonObject.containsKey("pending")) return null
        return json.decodeFromString(text)
    }
    suspend fun startMinecraft(): DeviceCode = post("/auth/minecraft")
    suspend fun pollMinecraft(ticket: String): SignedIn? {
        val text = call("POST", "/auth/minecraft/poll", """{"ticket":${kotlinx.serialization.json.JsonPrimitive(ticket)}}""")
        if (json.parseToJsonElement(text).jsonObject.containsKey("pending")) return null
        return json.decodeFromString(text)
    }
    suspend fun signOut() { runCatching { call("POST", "/auth/signout") } }
}
