package com.itube.tv.util

import com.itube.tv.data.Video
import java.util.Locale

fun formatDuration(ms: Long): String {
    if (ms <= 0) return "0:00"
    val total = ms / 1000
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) String.format(Locale.ROOT, "%d:%02d:%02d", h, m, s)
    else String.format(Locale.ROOT, "%d:%02d", m, s)
}

private fun compact(n: Long): String {
    fun one(v: Double) = if (v < 10) String.format(Locale.FRANCE, "%.1f", v).replace(",0", "") else v.toLong().toString()
    return when {
        n >= 1_000_000_000 -> one(n / 1e9) + " Md"
        n >= 1_000_000 -> one(n / 1e6) + " M"
        n >= 1_000 -> one(n / 1e3) + " k"
        else -> n.toString()
    }
}

fun formatViews(n: Long): String? = when {
    n < 0 -> null
    n == 0L -> "Aucune vue"
    n == 1L -> "1 vue"
    else -> compact(n) + " vues"
}

fun formatSubscribers(n: Long): String? = when {
    n < 0 -> null
    n < 2 -> "$n abonné"
    else -> compact(n) + " abonnés"
}

fun formatLikes(n: Long): String? = if (n < 0) null else compact(n)

fun formatAgo(epochMs: Long): String? {
    if (epochMs <= 0) return null
    val s = (System.currentTimeMillis() - epochMs) / 1000
    fun plural(n: Long, one: String, many: String) = "il y a $n " + if (n > 1) many else one
    return when {
        s < 60 -> "à l'instant"
        s < 3600 -> plural(s / 60, "minute", "minutes")
        s < 86_400 -> plural(s / 3600, "heure", "heures")
        s < 7 * 86_400 -> plural(s / 86_400, "jour", "jours")
        s < 30 * 86_400 -> plural(s / (7 * 86_400), "semaine", "semaines")
        s < 365 * 86_400 -> "il y a ${s / (30 * 86_400)} mois"
        else -> plural(s / (365 * 86_400), "an", "ans")
    }
}

/** "1,2 M vues · il y a 3 jours" */
fun Video.metaLine(withChannel: Boolean = false): String = listOfNotNull(
    channelName?.takeIf { withChannel && it.isNotBlank() },
    if (isLive) null else formatViews(views),
    if (isLive) "En direct" else formatAgo(uploadedAt) ?: uploadedText?.takeIf { it.isNotBlank() },
).joinToString("  ·  ")

private val timeFormat = object : ThreadLocal<java.text.SimpleDateFormat>() {
    override fun initialValue() = java.text.SimpleDateFormat("HH:mm", Locale.FRANCE)
}

fun formatClock(epochMs: Long): String = timeFormat.get()!!.format(java.util.Date(epochMs))
