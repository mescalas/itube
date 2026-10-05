package com.itube.tv.update

import android.app.Application
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import com.itube.tv.BuildConfig
import com.itube.tv.data.SettingsStore
import com.itube.tv.data.remote.Http
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException

/** A published GitHub release whose `iTube.apk` asset can replace the running build. */
data class Release(
    val versionCode: Int,
    val versionName: String,
    val notes: String,
    val apkUrl: String,
    val size: Long,
)

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data class Available(val release: Release) : UpdateState
    data class Downloading(val release: Release, val progress: Float) : UpdateState
    /** APK handed to the system installer; waiting for it (and possibly the user) to finish. */
    data class Installing(val release: Release) : UpdateState
    data class Failed(val message: String, val release: Release? = null) : UpdateState
}

/**
 * Self-updater backed by the repository's GitHub Releases: CI publishes `v1.0.<run>` with
 * `versionCode = <run>`, so a release is newer when its tag's last number exceeds ours.
 * The APK is streamed straight into a [PackageInstaller] session; it installs over the current
 * app because every build is signed with the same key.
 */
class Updater(private val app: Application, private val settings: SettingsStore) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val prefs = app.getSharedPreferences("updater", Context.MODE_PRIVATE)
    private val state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val flow: StateFlow<UpdateState> = state.asStateFlow()
    private var job: Job? = null

    /** Version the user answered "Plus tard" to during this session: not prompted again for it. */
    private val dismissed = MutableStateFlow(0)
    val dismissedCode: StateFlow<Int> = dismissed.asStateFlow()

    fun dismiss(release: Release) { dismissed.value = release.versionCode }

    /** Debug builds have another application id, so a release APK cannot replace them. */
    val supported: Boolean get() = !BuildConfig.DEBUG && BuildConfig.UPDATE_REPO.isNotBlank()

    /** Automatic check on foreground, at most every [CHECK_INTERVAL_MS]. */
    fun checkIfDue() {
        if (!supported || !settings.value.autoUpdateCheck) return
        if (System.currentTimeMillis() - prefs.getLong("lastCheck", 0) < CHECK_INTERVAL_MS) return
        check(manual = false)
    }

    fun check(manual: Boolean = true) {
        if (!supported || job?.isActive == true) return
        if (!manual && state.value is UpdateState.Installing) return
        job = scope.launch {
            if (manual) state.value = UpdateState.Checking
            state.value = try {
                val release = fetchLatest()
                prefs.edit().putLong("lastCheck", System.currentTimeMillis()).apply()
                if (release != null && release.versionCode > BuildConfig.VERSION_CODE) UpdateState.Available(release)
                else UpdateState.UpToDate
            } catch (e: Exception) {
                if (manual) UpdateState.Failed("Vérification impossible : ${e.message ?: "erreur réseau"}") else UpdateState.Idle
            }
        }
    }

    fun install(release: Release) {
        if (!supported || job?.isActive == true) return
        job = scope.launch {
            state.value = UpdateState.Downloading(release, 0f)
            try {
                downloadAndCommit(release)
            } catch (e: Exception) {
                state.value = UpdateState.Failed("Téléchargement échoué : ${e.message ?: "erreur réseau"}", release)
            }
        }
    }

    /** Called by [UpdateReceiver] when the system installer reports a final failure or a cancellation. */
    internal fun onInstallFailed(status: Int, message: String?) {
        val release = (state.value as? UpdateState.Installing)?.release
        state.value = when (status) {
            PackageInstaller.STATUS_FAILURE_ABORTED ->
                release?.let { dismiss(it); UpdateState.Available(it) } ?: UpdateState.Idle
            PackageInstaller.STATUS_FAILURE_CONFLICT, PackageInstaller.STATUS_FAILURE_INCOMPATIBLE ->
                UpdateState.Failed("Installation refusée : désinstallez iTube puis réinstallez-le une fois.", release)
            PackageInstaller.STATUS_FAILURE_STORAGE -> UpdateState.Failed("Espace de stockage insuffisant.", release)
            else -> UpdateState.Failed("Installation échouée" + (message?.let { " : $it" } ?: "."), release)
        }
    }

    /** Version name to announce once after the app was replaced by a newer build, else null. */
    fun consumeJustUpdated(): String? {
        val previous = prefs.getInt("lastRunCode", 0)
        if (previous == BuildConfig.VERSION_CODE) return null
        prefs.edit().putInt("lastRunCode", BuildConfig.VERSION_CODE).apply()
        return if (previous in 1 until BuildConfig.VERSION_CODE) BuildConfig.VERSION_NAME else null
    }

    private fun fetchLatest(): Release? {
        val text = request("https://api.github.com/repos/${BuildConfig.UPDATE_REPO}/releases/latest")
            .use { it.body!!.string() }
        val json = JSONObject(text)
        val tag = json.optString("tag_name")
        val code = Regex("(\\d+)$").find(tag)?.value?.toIntOrNull() ?: return null
        val assets = json.optJSONArray("assets") ?: return null
        val apk = (0 until assets.length()).map { assets.getJSONObject(it) }
            .firstOrNull { it.optString("name") == APK_NAME } ?: return null
        return Release(
            versionCode = code,
            versionName = tag.removePrefix("v"),
            notes = json.optString("body").substringBefore("\n---").trim(),
            apkUrl = apk.getString("browser_download_url"),
            size = apk.optLong("size", -1),
        )
    }

    private fun downloadAndCommit(release: Release) {
        val installer = app.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(app.packageName)
            if (release.size > 0) setSize(release.size)
            // Android 12+: once iTube is the installer of record, later updates need no confirmation.
            if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val sessionId = installer.createSession(params)
        val session = installer.openSession(sessionId)
        try {
            request(release.apkUrl).use { resp ->
                val body = resp.body ?: throw IOException("réponse vide")
                val total = body.contentLength().takeIf { it > 0 } ?: release.size
                session.openWrite(APK_NAME, 0, total).use { out ->
                    body.byteStream().use { input ->
                        val buf = ByteArray(64 * 1024)
                        var done = 0L
                        var lastStep = -1
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            done += n
                            if (total > 0) {
                                val step = (done * 100 / total).toInt()
                                if (step != lastStep) {
                                    lastStep = step
                                    state.value = UpdateState.Downloading(release, step / 100f)
                                }
                            }
                        }
                    }
                    session.fsync(out)
                }
            }
            val intent = Intent(app, UpdateReceiver::class.java).setPackage(app.packageName)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            val pending = PendingIntent.getBroadcast(app, sessionId, intent, flags)
            // Set before committing: the receiver may report back immediately.
            state.value = UpdateState.Installing(release)
            session.commit(pending.intentSender)
        } catch (e: Exception) {
            session.abandon()
            throw e
        } finally {
            runCatching { session.close() }
        }
    }

    private fun request(url: String): Response {
        val req = Request.Builder().url(url).header("User-Agent", USER_AGENT).build()
        val resp = Http.client.newCall(req).execute()
        if (!resp.isSuccessful) {
            val code = resp.code
            resp.close()
            throw IOException(
                when (code) {
                    404 -> "aucune version publiée"
                    403, 429 -> "trop de requêtes, réessayez plus tard"
                    else -> "erreur du serveur ($code)"
                }
            )
        }
        return resp
    }

    companion object {
        private const val APK_NAME = "iTube.apk"
        private const val CHECK_INTERVAL_MS = 3 * 60 * 60 * 1000L
        private val USER_AGENT = "iTube/${BuildConfig.VERSION_NAME} (Android)"
    }
}
