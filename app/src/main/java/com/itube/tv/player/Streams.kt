package com.itube.tv.player

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.os.Build
import com.itube.tv.data.CodecPref
import org.schabi.newpipe.extractor.MediaFormat
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.AudioTrackType
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.SubtitlesStream
import org.schabi.newpipe.extractor.stream.VideoStream

enum class Codec(val label: String, val mime: String) {
    H264("H.264", "video/avc"), VP9("VP9", "video/x-vnd.on2.vp9"), AV1("AV1", "video/av01")
}

fun VideoStream.codecFamily(): Codec? {
    val c = codec?.lowercase() ?: return null
    return when {
        c.startsWith("avc") -> Codec.H264
        c.startsWith("vp9") || c.startsWith("vp09") -> Codec.VP9
        c.startsWith("av01") -> Codec.AV1
        else -> null
    }
}

/** What the TV can decode in hardware (software VP9/AV1 is too slow for HD on most TVs). */
object Codecs {
    private val sizes = listOf(3840 to 2160, 2560 to 1440, 1920 to 1080, 1280 to 720, 854 to 480)

    private val maxHeights: Map<Codec, Int> by lazy {
        val infos = runCatching { MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.toList() }.getOrDefault(emptyList())
        Codec.entries.associateWith { codec ->
            infos.filter { !it.isEncoder && it.supportedTypes.any { t -> t.equals(codec.mime, true) } && isHardware(it) }
                .maxOfOrNull { info ->
                    val caps = runCatching { info.getCapabilitiesForType(codec.mime).videoCapabilities }.getOrNull()
                    sizes.firstOrNull { (w, h) -> caps?.isSizeSupported(w, h) == true }?.second ?: 0
                } ?: 0
        }.let { map ->
            // H.264 is always decodable (in software at worst) up to 1080p.
            map + (Codec.H264 to maxOf(map[Codec.H264] ?: 0, 1080))
        }
    }

    private fun isHardware(info: MediaCodecInfo): Boolean {
        if (Build.VERSION.SDK_INT >= 29) return info.isHardwareAccelerated
        val n = info.name.lowercase()
        return !(n.startsWith("omx.google.") || n.startsWith("c2.android.") || n.contains(".sw."))
    }

    fun maxHeight(codec: Codec): Int = maxHeights[codec] ?: 0

    fun summary(): String = Codec.entries.joinToString("  ·  ") { c ->
        val h = maxHeight(c)
        c.label + " " + if (h > 0) "${h}p" else "non"
    }
}

/** Streams chosen for one playback. */
data class StreamChoice(
    val video: VideoStream?,
    val audio: AudioStream?,
    /** Video+audio stream used when no separate streams can be played (old 360p format). */
    val muxed: VideoStream?,
    val subtitle: SubtitlesStream?,
)

object StreamSelector {
    private fun order(pref: CodecPref): List<Codec> = when (pref) {
        CodecPref.AUTO -> listOf(Codec.VP9, Codec.AV1, Codec.H264)
        CodecPref.H264 -> listOf(Codec.H264)
        CodecPref.VP9 -> listOf(Codec.VP9, Codec.H264)
        CodecPref.AV1 -> listOf(Codec.AV1, Codec.VP9, Codec.H264)
    }

    private fun playable(info: StreamInfo, pref: CodecPref): List<VideoStream> {
        val codecs = order(pref)
        return info.videoOnlyStreams.filter { v ->
            val family = v.codecFamily()
            family != null && family in codecs && v.height in 1..Codecs.maxHeight(family) &&
                v.itagItem != null && v.isUrl &&
                (v.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP || v.deliveryMethod == DeliveryMethod.DASH)
        }
    }

    /** Heights offered in the quality menu, highest first. */
    fun heights(info: StreamInfo, pref: CodecPref): List<Int> =
        playable(info, pref).map { it.height }.distinct().sortedDescending()

    fun pickVideo(info: StreamInfo, maxHeight: Int, pref: CodecPref): VideoStream? {
        val codecs = order(pref)
        val all = playable(info, pref)
        val fitting = all.filter { it.height <= maxHeight }.ifEmpty { all.filter { it.height == all.minOf { s -> s.height } } }
        return fitting.sortedWith(
            compareByDescending<VideoStream> { it.height }
                .thenBy { codecs.indexOf(it.codecFamily()) }
                .thenByDescending { it.fps }
                .thenByDescending { it.bitrate }
        ).firstOrNull()
    }

    /** The original soundtrack (not a dub), AAC first for compatibility, best bitrate. */
    fun pickAudio(info: StreamInfo): AudioStream? {
        val usable = info.audioStreams.filter {
            it.isUrl && it.itagItem != null &&
                (it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP || it.deliveryMethod == DeliveryMethod.DASH)
        }
        val original = usable.filter { it.audioTrackType == null || it.audioTrackType == AudioTrackType.ORIGINAL }.ifEmpty { usable }
        return original.sortedWith(
            compareByDescending<AudioStream> { it.format == MediaFormat.M4A }.thenByDescending { it.averageBitrate }
        ).firstOrNull()
    }

    fun pickMuxed(info: StreamInfo): VideoStream? =
        info.videoStreams.filter { it.isUrl && it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP }.maxByOrNull { it.height }

    /** WebVTT subtitles, one per language (manual ones before auto-generated). */
    fun subtitles(info: StreamInfo): List<SubtitlesStream> =
        info.subtitles.filter { it.format == MediaFormat.VTT && it.isUrl }
            .sortedBy { it.isAutoGenerated }
            .distinctBy { it.languageTag + it.isAutoGenerated }

    fun pickSubtitle(info: StreamInfo, lang: String): SubtitlesStream? {
        if (lang.isBlank()) return null
        val subs = subtitles(info)
        return subs.firstOrNull { it.languageTag.startsWith(lang, true) && !it.isAutoGenerated }
            ?: subs.firstOrNull { it.languageTag.startsWith(lang, true) }
    }
}
