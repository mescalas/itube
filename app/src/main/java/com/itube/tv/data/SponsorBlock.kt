package com.itube.tv.data

import com.itube.tv.data.remote.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.net.URLEncoder

/** A part of a video to skip (sponsor read, self-promotion…), in milliseconds. */
data class Segment(val startMs: Long, val endMs: Long, val category: String) {
    val label: String
        get() = when (category) {
            "sponsor" -> "Sponsor ignoré"
            "selfpromo" -> "Autopromotion ignorée"
            "interaction" -> "Rappel d'abonnement ignoré"
            "intro" -> "Générique ignoré"
            "outro" -> "Fin ignorée"
            else -> "Passage ignoré"
        }
}

/** Community database of sponsored segments: https://sponsor.ajay.app */
object SponsorBlock {
    suspend fun segments(videoId: String, extended: Boolean): List<Segment> = withContext(Dispatchers.IO) {
        val categories = if (extended) """["sponsor","selfpromo","interaction","intro","outro"]""" else """["sponsor"]"""
        val url = "https://sponsor.ajay.app/api/skipSegments?videoID=$videoId&categories=" +
            URLEncoder.encode(categories, "UTF-8")
        runCatching {
            Http.get(url).use { resp ->
                val arr = JSONArray(resp.body!!.string())
                (0 until arr.length()).mapNotNull { i ->
                    val o = arr.getJSONObject(i)
                    if (o.optString("actionType", "skip") != "skip") return@mapNotNull null
                    val seg = o.getJSONArray("segment")
                    Segment((seg.getDouble(0) * 1000).toLong(), (seg.getDouble(1) * 1000).toLong(), o.optString("category"))
                }.filter { it.endMs - it.startMs > 1000 }
            }
        }.getOrDefault(emptyList()) // 404 = no segment for this video
    }
}
