package com.itube.tv.data.account

import android.content.Context
import com.itube.tv.data.remote.Http
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.UUID

data class AccountInfo(val name: String, val avatar: String?)

sealed interface SignInState {
    data object SignedOut : SignInState
    data object Preparing : SignInState
    /** Waiting for the user to enter [userCode] at [url] on a phone or computer. */
    data class WaitingForCode(val userCode: String, val url: String) : SignInState
    data class SignedIn(val account: AccountInfo?) : SignInState
    data class Failed(val message: String) : SignInState
}

/**
 * Google account sign-in the way SmartTube does it: the YouTube TV app's OAuth client (read from its
 * web player at runtime) and the device flow ("enter this code at google.com/device"). The tokens let
 * the app call YouTube's TV API (InnerTube, TVHTML5 client) as the signed-in user.
 */
class YouTubeAccount(context: Context) {
    private val prefs = context.getSharedPreferences("account", Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val tokenLock = Mutex()
    private var signInJob: Job? = null

    private val _state = MutableStateFlow<SignInState>(
        if (prefs.getString("refreshToken", null) != null) SignInState.SignedIn(storedAccount()) else SignInState.SignedOut
    )
    val state: StateFlow<SignInState> = _state.asStateFlow()

    val signedIn: Boolean get() = prefs.getString("refreshToken", null) != null

    private val deviceId: String
        get() = prefs.getString("deviceId", null) ?: UUID.randomUUID().toString().also { prefs.edit().putString("deviceId", it).apply() }

    init {
        if (signedIn && storedAccount() == null) scope.launch { refreshAccountInfo() }
    }

    // ------------------------------------------------------------------ sign-in (device flow)

    fun startSignIn() {
        signInJob?.cancel()
        signInJob = scope.launch {
            _state.value = SignInState.Preparing
            try {
                val client = clientCredentials()
                val code = postJson(
                    "https://www.youtube.com/o/oauth2/device/code",
                    JSONObject()
                        .put("client_id", client.first)
                        .put("device_id", deviceId)
                        .put("model_name", "ytlr::")
                        .put("scope", SCOPE),
                )
                val deviceCode = code.getString("device_code")
                val interval = code.optLong("interval", 5).coerceAtLeast(2)
                val expiresAt = System.currentTimeMillis() + code.optLong("expires_in", 1800) * 1000
                _state.value = SignInState.WaitingForCode(code.getString("user_code"), code.optString("verification_url", "https://www.google.com/device"))
                while (isActive && System.currentTimeMillis() < expiresAt) {
                    delay(interval * 1000)
                    val token = postJson(
                        TOKEN_URL,
                        JSONObject()
                            .put("code", deviceCode)
                            .put("client_id", client.first)
                            .put("client_secret", client.second)
                            .put("grant_type", "http://oauth.net/grant_type/device/1.0"),
                        acceptErrors = true,
                    )
                    when (token.optString("error")) {
                        "" -> {
                            storeTokens(token)
                            _state.value = SignInState.SignedIn(null)
                            refreshAccountInfo()
                            return@launch
                        }
                        "authorization_pending", "slow_down" -> Unit
                        "access_denied" -> { _state.value = SignInState.Failed("Connexion refusée."); return@launch }
                        else -> { _state.value = SignInState.Failed("Connexion impossible (${token.optString("error")})."); return@launch }
                    }
                }
                if (isActive) _state.value = SignInState.Failed("Le code a expiré, recommencez.")
            } catch (e: Exception) {
                _state.value = SignInState.Failed("Connexion impossible : ${e.message ?: "erreur réseau"}")
            }
        }
    }

    fun cancelSignIn() {
        signInJob?.cancel()
        if (_state.value !is SignInState.SignedIn) _state.value = SignInState.SignedOut
    }

    fun signOut() {
        signInJob?.cancel()
        prefs.edit().remove("refreshToken").remove("accessToken").remove("expiresAt")
            .remove("accountName").remove("accountAvatar").apply()
        _state.value = SignInState.SignedOut
    }

    // ------------------------------------------------------------------ tokens

    private fun storeTokens(token: JSONObject) {
        val edit = prefs.edit()
            .putString("accessToken", token.getString("access_token"))
            .putLong("expiresAt", System.currentTimeMillis() + token.optLong("expires_in", 3600) * 1000 - 60_000)
        token.optString("refresh_token").takeIf { it.isNotBlank() }?.let { edit.putString("refreshToken", it) }
        edit.apply()
    }

    /** A valid access token (refreshed when needed), or null when signed out. */
    suspend fun accessToken(): String? = tokenLock.withLock {
        val refresh = prefs.getString("refreshToken", null) ?: return null
        val current = prefs.getString("accessToken", null)
        if (current != null && System.currentTimeMillis() < prefs.getLong("expiresAt", 0)) return current
        val client = clientCredentials()
        val token = postJson(
            TOKEN_URL,
            JSONObject()
                .put("refresh_token", refresh)
                .put("client_id", client.first)
                .put("client_secret", client.second)
                .put("grant_type", "refresh_token"),
            acceptErrors = true,
        )
        if (token.optString("error") == "invalid_grant") {
            // Access revoked from the Google account: back to signed out.
            signOut()
            return null
        }
        if (!token.has("access_token")) throw IOException("jeton refusé (${token.optString("error")})")
        storeTokens(token)
        prefs.getString("accessToken", null)
    }

    /**
     * The YouTube TV app's OAuth client id and secret, read from its web player script
     * (the first `clientId:"…apps.googleusercontent.com",x:"…"` pair). Cached for a week.
     */
    private suspend fun clientCredentials(): Pair<String, String> = withContext(Dispatchers.IO) {
        val id = prefs.getString("clientId", null)
        val secret = prefs.getString("clientSecret", null)
        if (id != null && secret != null && System.currentTimeMillis() - prefs.getLong("clientAt", 0) < 7 * 86_400_000L) {
            return@withContext id to secret
        }
        val page = getText("https://www.youtube.com/tv")
        val base = Regex("""\.src = '([^']*m=base)'""").find(page)?.groupValues?.get(1)
            ?: Regex("""id="base-js" src="([^"]+)"""").find(page)?.groupValues?.get(1)
            ?: throw IOException("application YouTube TV introuvable")
        Regex("\"INNERTUBE_CLIENT_VERSION\":\"([^\"]+)\"").find(page)?.groupValues?.get(1)?.let {
            prefs.edit().putString("tvVersion", it).apply()
        }
        val js = getText(if (base.startsWith("http")) base else "https://www.youtube.com$base")
        val m = Regex("""clientId:"([-\w]+\.apps\.googleusercontent\.com)",\n?[$\w]+:"(\w+)"""").find(js)
            ?: throw IOException("identifiants de l'application YouTube TV introuvables")
        prefs.edit()
            .putString("clientId", m.groupValues[1])
            .putString("clientSecret", m.groupValues[2])
            .putLong("clientAt", System.currentTimeMillis())
            .apply()
        m.groupValues[1] to m.groupValues[2]
    }

    // ------------------------------------------------------------------ InnerTube (TV client)

    private val tvVersion: String get() = prefs.getString("tvVersion", null) ?: DEFAULT_TV_VERSION

    private fun context(): JSONObject = JSONObject().put(
        "client",
        JSONObject()
            .put("clientName", "TVHTML5")
            .put("clientVersion", tvVersion)
            .put("hl", java.util.Locale.getDefault().language.ifBlank { "fr" })
            .put("gl", java.util.Locale.getDefault().country.ifBlank { "FR" })
    )

    /** POST to youtubei/v1/[endpoint] as the signed-in user. */
    suspend fun innertube(endpoint: String, body: JSONObject): JSONObject = withContext(Dispatchers.IO) {
        val token = accessToken() ?: throw IOException("non connecté")
        body.put("context", context())
        val req = Request.Builder()
            .url("https://www.youtube.com/youtubei/v1/$endpoint?prettyPrint=false")
            .header("User-Agent", TV_USER_AGENT)
            .header("Authorization", "Bearer $token")
            .header("X-YouTube-Client-Name", "7")
            .header("X-YouTube-Client-Version", tvVersion)
            .header("Origin", "https://www.youtube.com")
            .header("Referer", "https://www.youtube.com/tv")
            .post(body.toString().toRequestBody(JSON))
            .build()
        Http.client.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw IOException("YouTube a répondu ${resp.code}")
            JSONObject(text)
        }
    }

    /** Fire-and-forget GET as the signed-in user (watch-history pings). */
    suspend fun ping(url: String) = withContext(Dispatchers.IO) {
        val token = accessToken() ?: return@withContext
        runCatching {
            Http.client.newCall(
                Request.Builder().url(url).header("User-Agent", TV_USER_AGENT).header("Authorization", "Bearer $token").build()
            ).execute().close()
        }
    }

    private suspend fun refreshAccountInfo() {
        runCatching {
            val r = innertube(
                "account/accounts_list",
                JSONObject().put("accountReadMask", JSONObject().put("returnOwner", true).put("returnBrandAccounts", true).put("returnPersonaAccounts", false)),
            )
            val name = Json.findText(r, "accountName")
            val avatar = Json.findFirst(r, "accountPhoto")?.let { Json.bestThumbnail(it) }
            if (name != null) {
                prefs.edit().putString("accountName", name).putString("accountAvatar", avatar).apply()
                _state.value = SignInState.SignedIn(AccountInfo(name, avatar))
            }
        }
    }

    private fun storedAccount(): AccountInfo? =
        prefs.getString("accountName", null)?.let { AccountInfo(it, prefs.getString("accountAvatar", null)) }

    // ------------------------------------------------------------------ http helpers

    private fun getText(url: String): String {
        val req = Request.Builder().url(url).header("User-Agent", TV_USER_AGENT).build()
        Http.client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("erreur ${resp.code}")
            return resp.body!!.string()
        }
    }

    private fun postJson(url: String, body: JSONObject, acceptErrors: Boolean = false): JSONObject {
        val req = Request.Builder().url(url)
            .header("User-Agent", TV_USER_AGENT)
            .post(body.toString().toRequestBody(JSON))
            .build()
        Http.client.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful && !acceptErrors) throw IOException("erreur ${resp.code}")
            return runCatching { JSONObject(text) }.getOrElse { JSONObject().put("error", "http_${resp.code}") }
        }
    }

    companion object {
        private const val TOKEN_URL = "https://www.youtube.com/o/oauth2/token"
        private const val SCOPE = "http://gdata.youtube.com https://www.googleapis.com/auth/youtube-paid-content"
        private const val DEFAULT_TV_VERSION = "7.20260930.10.00"
        private val JSON = "application/json".toMediaType()
        const val TV_USER_AGENT = "Mozilla/5.0 (Linux armeabi-v7a; Android 7.1.2; Fire OS 6.0) Cobalt/22.lts.3.306369-gold " +
            "(unlike Gecko) v8/8.8.278.8-jit gles Starboard/13, Amazon_ATV_mediatek8695_2019/NS6294 (Amazon, AFTMM, Wireless) " +
            "com.amazon.firetv.youtube/22.3.r2.v66.0"
    }
}

/** Tolerant helpers over InnerTube JSON, whose exact nesting changes often. */
object Json {
    fun text(o: Any?): String? = when (o) {
        is JSONObject -> o.optString("simpleText").takeIf { it.isNotBlank() }
            ?: o.optJSONArray("runs")?.let { runs -> (0 until runs.length()).joinToString("") { runs.optJSONObject(it)?.optString("text").orEmpty() } }
                ?.takeIf { it.isNotBlank() }
            ?: o.optString("content").takeIf { it.isNotBlank() }
        is String -> o
        else -> null
    }

    fun findFirst(o: Any?, key: String): Any? {
        when (o) {
            is JSONObject -> {
                if (o.has(key)) return o.get(key)
                for (k in o.keys()) findFirst(o.get(k), key)?.let { return it }
            }
            is JSONArray -> for (i in 0 until o.length()) findFirst(o.get(i), key)?.let { return it }
        }
        return null
    }

    fun findText(o: Any?, key: String): String? = text(findFirst(o, key))

    /** Every object stored under [key], anywhere in the tree (outermost first). */
    fun findAll(o: Any?, key: String, out: MutableList<JSONObject> = ArrayList()): List<JSONObject> {
        when (o) {
            is JSONObject -> for (k in o.keys()) {
                val v = o.get(k)
                if (k == key && v is JSONObject) out += v else findAll(v, key, out)
            }
            is JSONArray -> for (i in 0 until o.length()) findAll(o.get(i), key, out)
        }
        return out
    }

    fun bestThumbnail(o: Any?): String? {
        val thumbs = (findFirst(o, "thumbnails") as? JSONArray) ?: return null
        var best: JSONObject? = null
        for (i in 0 until thumbs.length()) {
            val t = thumbs.optJSONObject(i) ?: continue
            if (best == null || t.optInt("width") > best.optInt("width")) best = t
        }
        return best?.optString("url")?.let { if (it.startsWith("//")) "https:$it" else it }
    }
}
