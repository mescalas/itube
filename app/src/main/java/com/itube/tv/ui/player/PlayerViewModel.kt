package com.itube.tv.ui.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import com.itube.tv.AppContainer
import com.itube.tv.data.CodecPref
import com.itube.tv.data.Segment
import com.itube.tv.data.SponsorBlock
import com.itube.tv.data.Video
import com.itube.tv.data.YouTube
import com.itube.tv.data.best
import com.itube.tv.data.describe
import com.itube.tv.data.videos
import com.itube.tv.player.StreamChoice
import com.itube.tv.player.StreamSelector
import com.itube.tv.player.isLive
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.SubtitlesStream

/** Everything the player screen shows about the current video once extracted. */
data class Details(
    val info: StreamInfo,
    val video: Video,
    val description: String,
    val likes: Long,
    val subscribers: Long,
    val related: List<Video>,
    val heights: List<Int>,
    val subtitles: List<SubtitlesStream>,
    val isLive: Boolean,
)

class PlayerViewModel(private val c: AppContainer) : ViewModel() {
    val pm = c.player
    private val repo = c.repository
    private val settings = c.settings
    val request = c.playback.request

    private var queue: List<Video> = request?.queue.orEmpty()
    private val _index = MutableStateFlow(request?.index ?: 0)
    val index: StateFlow<Int> = _index.asStateFlow()

    private val _current = MutableStateFlow(queue.getOrNull(_index.value))
    val current: StateFlow<Video?> = _current.asStateFlow()

    private val _details = MutableStateFlow<Details?>(null)
    val details: StateFlow<Details?> = _details.asStateFlow()

    private val _loading = MutableStateFlow(true)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private val _height = MutableStateFlow(0)
    /** Height of the video stream playing (0 = unknown / muxed). */
    val height: StateFlow<Int> = _height.asStateFlow()

    private val _subtitle = MutableStateFlow<SubtitlesStream?>(null)
    val subtitle: StateFlow<SubtitlesStream?> = _subtitle.asStateFlow()

    private val _speed = MutableStateFlow(settings.value.speed)
    val speed: StateFlow<Float> = _speed.asStateFlow()

    private val _notice = MutableStateFlow<String?>(null)
    val notice: StateFlow<String?> = _notice.asStateFlow()
    fun consumeNotice() { _notice.value = null }

    private val _finished = MutableStateFlow(false)
    /** The queue is over and nothing plays next: the screen closes. */
    val finished: StateFlow<Boolean> = _finished.asStateFlow()

    var segments: List<Segment> = emptyList()
        private set
    private val skipped = HashSet<Segment>()

    /** Quality picked in the menu for this session (0 = automatic, from the settings). */
    private var chosenHeight = 0
    /** Set after a decoder failure: H.264 only until the screen closes. */
    private var safeCodec = false
    private var refetched = false
    private var loadJob: Job? = null
    private var started = false

    val upNext: Video?
        get() = queue.getOrNull(_index.value + 1)
            ?: if (settings.value.autoplayNext) _details.value?.related?.firstOrNull { !it.isLive } else null

    /** Position to resume at when the screen comes back (after visiting the channel page, for instance). */
    private var resumeAt = -1L

    fun start() {
        if (started) return
        started = true
        pm.recover = ::recover
        load(if (resumeAt >= 0) resumeAt else request?.startPositionMs ?: -1)
    }

    private fun load(startPositionMs: Long, fresh: Boolean = false) {
        val video = queue.getOrNull(_index.value) ?: return
        _current.value = video
        _loading.value = true
        _details.value = null
        segments = emptyList()
        skipped.clear()
        loadJob?.cancel()
        pm.player.stop()
        pm.clearError()
        loadJob = viewModelScope.launch {
            val info = try {
                YouTube.stream(video.id, fresh)
            } catch (e: Exception) {
                _loading.value = false
                pm.showError(e.describe())
                return@launch
            }
            val enriched = video.copy(
                title = info.name.ifBlank { video.title },
                channelName = info.uploaderName ?: video.channelName,
                channelUrl = info.uploaderUrl ?: video.channelUrl,
                channelAvatar = info.uploaderAvatars.best(200) ?: video.channelAvatar,
                durationSec = info.duration.takeIf { it > 0 } ?: video.durationSec,
                views = info.viewCount,
                uploadedAt = info.uploadDate?.instant?.toEpochMilli() ?: video.uploadedAt,
                uploadedText = info.textualUploadDate ?: video.uploadedText,
                isLive = info.isLive(),
            )
            _current.value = enriched
            val hideShorts = settings.value.hideShorts
            _details.value = Details(
                info = info,
                video = enriched,
                description = info.description?.content.orEmpty().htmlToText(),
                likes = info.likeCount,
                subscribers = info.uploaderSubscriberCount,
                related = info.relatedItems.videos().filter { !(hideShorts && it.isShort) },
                heights = StreamSelector.heights(info, codecPref()),
                subtitles = StreamSelector.subtitles(info),
                isLive = info.isLive(),
            )
            // A new video gets the default subtitles; a refresh of the same one keeps the user's choice.
            if (!fresh) _subtitle.value = StreamSelector.pickSubtitle(info, settings.value.subtitleLang)
            val start = when {
                info.isLive() -> 0L
                startPositionMs >= 0 -> startPositionMs
                else -> repo.historyOf(video.id)?.takeIf { it.progress in 0.01f..0.94f && it.positionMs > 20_000 }?.positionMs ?: 0L
            }
            playStreams(info, start)
            _loading.value = false
            if (!info.isLive() && settings.value.sponsorBlock) {
                segments = SponsorBlock.segments(video.id, settings.value.sponsorBlockExtended)
            }
        }
    }

    private fun codecPref(): CodecPref = if (safeCodec) CodecPref.H264 else settings.value.codec

    private fun playStreams(info: StreamInfo, startMs: Long) {
        val maxHeight = if (chosenHeight > 0) chosenHeight else settings.value.maxQuality.height
        val video = StreamSelector.pickVideo(info, maxHeight, codecPref())
        val choice = StreamChoice(video, StreamSelector.pickAudio(info), StreamSelector.pickMuxed(info), _subtitle.value)
        _height.value = video?.height ?: choice.muxed?.height ?: 0
        pm.play(info, choice, startMs, _speed.value)
    }

    private fun replayAtCurrentPosition() {
        val d = _details.value ?: return
        playStreams(d.info, if (d.isLive) 0 else pm.player.currentPosition)
    }

    /** Called by the player on a failure; true when handled here. */
    private fun recover(e: PlaybackException): Boolean {
        val d = _details.value ?: return false
        return when {
            // Stream URLs expired (long pause) or were refused: extract again and resume.
            e.errorCode == PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS && !refetched -> {
                refetched = true
                load(pm.player.currentPosition, fresh = true)
                true
            }
            e.errorCode in DECODER_ERRORS && !safeCodec && codecPref() != CodecPref.H264 && !d.isLive -> {
                safeCodec = true
                _notice.value = "Codec compatible activé"
                _details.value = d.copy(heights = StreamSelector.heights(d.info, CodecPref.H264))
                replayAtCurrentPosition()
                true
            }
            else -> false
        }
    }

    // ------------------------------------------------------------------ user actions

    fun setQuality(height: Int) {
        chosenHeight = height
        replayAtCurrentPosition()
    }

    fun setSubtitle(sub: SubtitlesStream?) {
        if (sub == _subtitle.value) return
        _subtitle.value = sub
        replayAtCurrentPosition()
    }

    fun setSpeed(speed: Float) {
        _speed.value = speed
        pm.player.setPlaybackSpeed(speed)
    }

    fun retry() {
        refetched = false
        load(pm.player.currentPosition.takeIf { it > 0 } ?: -1, fresh = true)
    }

    /** Next in the queue, else the first related video. Returns false when there is nothing to play. */
    fun next(): Boolean {
        saveProgress()
        if (_index.value + 1 < queue.size) {
            _index.value += 1
        } else {
            val nextVideo = upNext ?: return false
            queue = queue + nextVideo
            _index.value = queue.size - 1
        }
        chosenHeight = 0
        refetched = false
        load(0)
        return true
    }

    fun playRelated(video: Video) {
        saveProgress()
        queue = queue.take(_index.value + 1) + video
        _index.value = queue.size - 1
        refetched = false
        load(-1)
    }

    fun onEnded() = saveProgress(finished = true)

    fun endOfQueue() { _finished.value = true }

    /** A sponsored segment the position just entered (each one is skipped once). */
    fun segmentToSkip(positionMs: Long): Segment? {
        val s = segments.firstOrNull { positionMs >= it.startMs && positionMs < it.endMs - 500 } ?: return null
        if (!skipped.add(s)) return null
        return s
    }

    fun saveProgress(finished: Boolean = false) {
        val d = _details.value ?: return
        if (d.isLive) return
        val p = pm.playerOrNull() ?: return
        val duration = (p.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: (d.info.duration * 1000)) / 1000
        val position = if (finished) duration * 1000 else p.currentPosition
        if (position < 3_000 && !finished) return
        val video = d.video
        viewModelScope.launch { repo.saveProgress(video, position, duration) }
    }

    fun onExit() {
        saveProgress()
        resumeAt = pm.playerOrNull()?.currentPosition?.takeIf { _details.value?.isLive == false } ?: -1
        started = false
        loadJob?.cancel()
        pm.stop()
    }

    companion object {
        private val DECODER_ERRORS = setOf(
            PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
            PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
            PlaybackException.ERROR_CODE_DECODING_FAILED,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
        )
    }
}

/** YouTube descriptions come as light HTML (links, line breaks). */
private fun String.htmlToText(): String =
    androidx.core.text.HtmlCompat.fromHtml(this, androidx.core.text.HtmlCompat.FROM_HTML_MODE_COMPACT).toString().trim()
