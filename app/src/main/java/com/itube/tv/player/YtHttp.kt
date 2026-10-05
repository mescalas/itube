package com.itube.tv.player

import com.itube.tv.data.remote.BROWSER_USER_AGENT
import com.itube.tv.data.remote.Http
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper
import java.util.concurrent.atomic.AtomicLong

/**
 * Makes requests to YouTube's media servers look like the official clients' (port of NewPipe's
 * YoutubeHttpDataSource): byte ranges travel as a `range` URL parameter, each request gets a
 * request number (`rn`) and is sent as a POST. Without this, YouTube throttles or refuses the streams.
 */
object YtHttp {
    private val requestNumber = AtomicLong(0)
    private val postBody = byteArrayOf(0x78, 0)
    private val RANGE = Regex("bytes=(\\d+)-(\\d+)")

    private val interceptor = Interceptor { chain ->
        val req = chain.request()
        if (!req.url.encodedPath.startsWith("/videoplayback")) return@Interceptor chain.proceed(req)
        var url = req.url.toString()
        if (!url.contains("&rn=")) url += "&rn=" + requestNumber.getAndIncrement()
        // Bounded range (DASH segments): moved to the URL, the server then answers 200 with only that range.
        val range = req.header("Range")?.let { RANGE.matchEntire(it) }
        if (range != null) url += "&range=" + range.groupValues[1] + "-" + range.groupValues[2]
        val builder = req.newBuilder().url(url).post(postBody.toRequestBody())
        if (range != null) builder.removeHeader("Range")
        if (YoutubeParsingHelper.isWebStreamingUrl(url)) {
            builder.header("Origin", "https://www.youtube.com").header("Referer", "https://www.youtube.com")
        }
        builder.header(
            "User-Agent",
            if (YoutubeParsingHelper.isVisionOsStreamingUrl(url)) YoutubeParsingHelper.getVisionOsUserAgent(null) else BROWSER_USER_AGENT,
        )
        val resp = chain.proceed(builder.build())
        if (range != null && resp.code == 200) fakePartial(resp, range.groupValues[1].toLong(), range.groupValues[2].toLong()) else resp
    }

    /** The player asked for a range: report the answer as the partial content it is, so nothing gets skipped. */
    private fun fakePartial(resp: Response, start: Long, end: Long): Response =
        resp.newBuilder().code(206).header("Content-Range", "bytes $start-$end/*").build()

    val client: OkHttpClient by lazy { Http.client.newBuilder().addInterceptor(interceptor).build() }
}
