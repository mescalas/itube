package com.itube.tv.player

import android.content.Context
import android.net.Uri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.Format
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.dash.DashMediaSource
import androidx.media3.exoplayer.dash.DefaultDashChunkSource
import androidx.media3.exoplayer.dash.manifest.DashManifestParser
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.text.DefaultSubtitleParserFactory
import androidx.media3.extractor.text.SubtitleExtractor
import com.itube.tv.data.BufferMode
import com.itube.tv.data.SettingsStore
import com.itube.tv.data.remote.BROWSER_USER_AGENT
import com.itube.tv.data.remote.Http
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.schabi.newpipe.extractor.services.youtube.dashmanifestcreators.YoutubeOtfDashManifestCreator
import org.schabi.newpipe.extractor.services.youtube.dashmanifestcreators.YoutubeProgressiveDashManifestCreator
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.Stream
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamType
import java.io.ByteArrayInputStream

fun StreamInfo.isLive(): Boolean = streamType == StreamType.LIVE_STREAM || streamType == StreamType.AUDIO_LIVE_STREAM

/** Owns the single ExoPlayer instance and turns extracted YouTube streams into media sources. */
class PlayerManager(private val context: Context, private val settings: SettingsStore) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var exo: ExoPlayer? = null
    private var configSignature = ""

    private val ytFactory = OkHttpDataSource.Factory(YtHttp.client)
    private val plainFactory = OkHttpDataSource.Factory(Http.client).setUserAgent(BROWSER_USER_AGENT)

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _playerFlow = MutableStateFlow<ExoPlayer?>(null)
    val playerFlow: StateFlow<ExoPlayer?> = _playerFlow.asStateFlow()

    /**
     * Given the failure, the screen may recover (fresh stream URLs, safer codec): returns true when handled.
     * Otherwise the error is shown.
     */
    var recover: ((PlaybackException) -> Boolean)? = null

    private var retryCount = 0
    private var retryJob: Job? = null
    private var live = false
    private var wasPlayingBeforeStop = false

    val player: ExoPlayer
        get() {
            val sig = settings.value.bufferMode.name
            val p = exo
            if (p != null && sig == configSignature) return p
            p?.release()
            return build().also {
                exo = it
                configSignature = sig
                _playerFlow.value = it
            }
        }

    fun playerOrNull(): ExoPlayer? = exo

    private fun build(): ExoPlayer {
        val (minBuf, maxBuf, startBuf, rebuf) = when (settings.value.bufferMode) {
            BufferMode.FAST -> listOf(10_000, 40_000, 1_000, 2_000)
            BufferMode.BALANCED -> listOf(20_000, 60_000, 1_500, 3_000)
            BufferMode.STABLE -> listOf(40_000, 120_000, 3_000, 6_000)
        }
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(minBuf, maxBuf, startBuf, rebuf)
            .setPrioritizeTimeOverSizeThresholds(true)
            .setBackBuffer(20_000, true)
            .build()
        val renderers = DefaultRenderersFactory(context).setEnableDecoderFallback(true)
        return ExoPlayer.Builder(context, renderers)
            .setLoadControl(loadControl)
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(),
                true,
            )
            .setHandleAudioBecomingNoisy(true)
            .setSeekBackIncrementMs(10_000)
            .setSeekForwardIncrementMs(10_000)
            .build()
            .also { it.addListener(listener) }
    }

    private val listener = object : Player.Listener {
        override fun onPlaybackStateChanged(state: Int) {
            if (state == Player.STATE_READY) {
                retryCount = 0
                _error.value = null
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            val p = exo ?: return
            when {
                error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW -> {
                    p.seekToDefaultPosition()
                    p.prepare()
                }
                recover?.invoke(error) == true -> Unit
                isRetryable(error) && retryCount < 3 -> {
                    retryCount++
                    retryJob?.cancel()
                    retryJob = scope.launch {
                        delay(1000L * retryCount)
                        if (live) p.seekToDefaultPosition()
                        p.prepare()
                        p.play()
                    }
                }
                else -> _error.value = describe(error)
            }
        }
    }

    private fun isRetryable(e: PlaybackException) = e.errorCode in setOf(
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
        PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
        PlaybackException.ERROR_CODE_TIMEOUT,
    )

    private fun describe(e: PlaybackException): String = when (e.errorCode) {
        PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> "YouTube a refusé le flux vidéo."
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> "Connexion impossible. Vérifiez le réseau."
        PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
        PlaybackException.ERROR_CODE_DECODING_FAILED,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES -> "Format vidéo non pris en charge par ce téléviseur."
        else -> "Lecture impossible."
    } + "\n" + e.errorCodeName

    // ------------------------------------------------------------------ media sources

    private fun buildSource(info: StreamInfo, choice: StreamChoice): MediaSource {
        if (info.isLive()) {
            val item = MediaItem.Builder()
                .setUri(info.hlsUrl)
                .setMimeType(MimeTypes.APPLICATION_M3U8)
                .setLiveConfiguration(MediaItem.LiveConfiguration.Builder().setTargetOffsetMs(10_000).build())
                .build()
            return HlsMediaSource.Factory(plainFactory)
                .setAllowChunklessPreparation(true)
                .setLoadErrorHandlingPolicy(DefaultLoadErrorHandlingPolicy(6))
                .createMediaSource(item)
        }
        val sources = ArrayList<MediaSource>()
        if (choice.video != null && choice.audio != null) {
            sources += youtubeSource(choice.video, info.duration)
            sources += youtubeSource(choice.audio, info.duration)
        } else {
            val muxed = choice.muxed ?: throw IllegalStateException("Aucun flux lisible pour cette vidéo.")
            sources += ProgressiveMediaSource.Factory(ytFactory).createMediaSource(MediaItem.fromUri(muxed.content))
        }
        choice.subtitle?.let { sub ->
            // Same path as Media3's own side-loaded subtitles: parsed into cues while loading.
            val format = Format.Builder()
                .setSampleMimeType(MimeTypes.TEXT_VTT)
                .setLanguage(sub.languageTag)
                .setSelectionFlags(C.SELECTION_FLAG_DEFAULT or C.SELECTION_FLAG_FORCED)
                .build()
            val parsers = DefaultSubtitleParserFactory()
            val extractors = ExtractorsFactory { arrayOf(SubtitleExtractor(parsers.create(format), format)) }
            sources += ProgressiveMediaSource.Factory(plainFactory, extractors).createMediaSource(MediaItem.fromUri(sub.content))
        }
        return if (sources.size == 1) sources[0] else MergingMediaSource(true, *sources.toTypedArray())
    }

    /** Separate audio or video stream, played through a DASH manifest generated on the fly (seekable, no throttling). */
    private fun youtubeSource(stream: Stream, durationSec: Long): MediaSource {
        val manifest = runCatching {
            when (stream.deliveryMethod) {
                DeliveryMethod.DASH -> YoutubeOtfDashManifestCreator.fromOtfStreamingUrl(stream.content, stream.itagItem!!, durationSec)
                else -> YoutubeProgressiveDashManifestCreator.fromProgressiveStreamingUrl(stream.content, stream.itagItem!!, durationSec)
            }
        }.getOrNull()
        if (manifest != null) {
            val uri = Uri.parse(stream.content)
            val parsed = DashManifestParser().parse(uri, ByteArrayInputStream(manifest.toByteArray()))
            return DashMediaSource.Factory(DefaultDashChunkSource.Factory(ytFactory), ytFactory)
                .setLoadErrorHandlingPolicy(DefaultLoadErrorHandlingPolicy(4))
                .createMediaSource(parsed, MediaItem.Builder().setUri(uri).build())
        }
        return ProgressiveMediaSource.Factory(ytFactory).createMediaSource(MediaItem.fromUri(stream.content))
    }

    // ------------------------------------------------------------------ control

    fun play(info: StreamInfo, choice: StreamChoice, startPositionMs: Long, speed: Float) {
        val p = player
        retryJob?.cancel()
        retryCount = 0
        _error.value = null
        live = info.isLive()
        val source = try {
            buildSource(info, choice)
        } catch (e: Exception) {
            p.stop()
            _error.value = e.message ?: "Lecture impossible."
            return
        }
        if (live || startPositionMs <= 0) p.setMediaSource(source) else p.setMediaSource(source, startPositionMs)
        p.setPlaybackSpeed(if (live) 1f else speed)
        p.prepare()
        p.playWhenReady = true
    }

    fun showError(message: String) {
        exo?.stop()
        _error.value = message
    }

    fun clearError() { _error.value = null }

    fun stop() {
        retryJob?.cancel()
        recover = null
        exo?.stop()
        exo?.clearMediaItems()
        _error.value = null
    }

    /** App sent to background: pause (and drop the live stream) but remember what was playing. */
    fun onBackground() {
        val p = exo ?: return
        wasPlayingBeforeStop = p.playWhenReady && p.mediaItemCount > 0
        if (live) p.stop() else p.pause()
    }

    fun onForeground() {
        val p = exo ?: return
        if (wasPlayingBeforeStop && p.mediaItemCount > 0) {
            if (live) { p.seekToDefaultPosition(); p.prepare() }
            p.play()
        }
    }
}
