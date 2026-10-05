package com.itube.tv.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class MaxQuality(val label: String, val height: Int) {
    P2160("4K (2160p)", 2160), P1440("1440p", 1440), P1080("1080p", 1080), P720("720p", 720), P480("480p", 480)
}

/** Video codec order of preference; AUTO uses VP9 / AV1 when the TV decodes them in hardware. */
enum class CodecPref(val label: String) {
    AUTO("Automatique"), H264("H.264 (compatibilité)"), VP9("VP9"), AV1("AV1")
}

enum class BufferMode(val label: String) {
    FAST("Rapide"), BALANCED("Équilibré"), STABLE("Stable")
}

enum class Region(val label: String, val language: String, val country: String) {
    FR("France", "fr", "FR"), BE("Belgique", "fr", "BE"), CH("Suisse", "fr", "CH"), CA("Canada", "fr", "CA"),
    US("États-Unis", "en", "US"), GB("Royaume-Uni", "en", "GB")
}

/** Next (or previous, with a negative [step]) value of an enum, wrapping around. */
inline fun <reified E : Enum<E>> E.cycle(step: Int = 1): E {
    val values = enumValues<E>()
    return values[(ordinal + step).mod(values.size)]
}

val SPEEDS = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f)

data class AppSettings(
    val maxQuality: MaxQuality = MaxQuality.P1080,
    val codec: CodecPref = CodecPref.AUTO,
    val bufferMode: BufferMode = BufferMode.BALANCED,
    val region: Region = Region.FR,
    val autoplayNext: Boolean = true,
    val hideShorts: Boolean = true,
    /** Skip sponsored segments (SponsorBlock, community-submitted). */
    val sponsorBlock: Boolean = true,
    /** Also skip self-promotion, intros and "subscribe" reminders. */
    val sponsorBlockExtended: Boolean = false,
    /** Language of the subtitles shown automatically ("" = off). */
    val subtitleLang: String = "",
    val speed: Float = 1f,
    val autoUpdateCheck: Boolean = true,
)

class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val state = MutableStateFlow(load())
    val flow: StateFlow<AppSettings> = state.asStateFlow()
    val value: AppSettings get() = state.value

    private fun load(): AppSettings {
        val d = AppSettings()
        return AppSettings(
            maxQuality = enumOr(prefs.getString("maxQuality", null), d.maxQuality),
            codec = enumOr(prefs.getString("codec", null), d.codec),
            bufferMode = enumOr(prefs.getString("bufferMode", null), d.bufferMode),
            region = enumOr(prefs.getString("region", null), d.region),
            autoplayNext = prefs.getBoolean("autoplayNext", d.autoplayNext),
            hideShorts = prefs.getBoolean("hideShorts", d.hideShorts),
            sponsorBlock = prefs.getBoolean("sponsorBlock", d.sponsorBlock),
            sponsorBlockExtended = prefs.getBoolean("sponsorBlockExtended", d.sponsorBlockExtended),
            subtitleLang = prefs.getString("subtitleLang", d.subtitleLang) ?: "",
            speed = prefs.getFloat("speed", d.speed),
            autoUpdateCheck = prefs.getBoolean("autoUpdateCheck", d.autoUpdateCheck),
        )
    }

    fun update(transform: (AppSettings) -> AppSettings) {
        val s = transform(state.value)
        state.value = s
        prefs.edit()
            .putString("maxQuality", s.maxQuality.name)
            .putString("codec", s.codec.name)
            .putString("bufferMode", s.bufferMode.name)
            .putString("region", s.region.name)
            .putBoolean("autoplayNext", s.autoplayNext)
            .putBoolean("hideShorts", s.hideShorts)
            .putBoolean("sponsorBlock", s.sponsorBlock)
            .putBoolean("sponsorBlockExtended", s.sponsorBlockExtended)
            .putString("subtitleLang", s.subtitleLang)
            .putFloat("speed", s.speed)
            .putBoolean("autoUpdateCheck", s.autoUpdateCheck)
            .apply()
    }

    private inline fun <reified E : Enum<E>> enumOr(name: String?, default: E): E =
        name?.let { n -> enumValues<E>().firstOrNull { it.name == n } } ?: default
}
