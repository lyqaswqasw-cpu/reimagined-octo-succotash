package com.example.ui.player

import android.app.ActivityManager
import android.content.Context
import android.media.MediaCodecList
import android.media.MediaFormat
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.mp4.Mp4Extractor
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory
import androidx.media3.extractor.ts.TsExtractor
import com.example.data.model.ChannelServer
import okhttp3.Call
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.math.min

/**
 * Supported media container/stream formats handled natively by the Unified Media3 ExoPlayer Engine.
 */
enum class StreamSourceType(val label: String) {
    HLS("HLS/M3U8"),
    MPEG_TS("MPEG-TS"),
    MP4("MP4"),
    MKV("MKV/WebM"),
    PROGRESSIVE_DIRECT("DIRECT")
}

/**
 * Hardware decoder and memory capabilities of the current device, used to make safe,
 * capability-aware server and track selection decisions without arbitrary caps on strong devices.
 */
data class DeviceCodecCapabilities(
    val supportsHardwareH264: Boolean = true,
    val supportsHardwareHevc: Boolean = true,
    val supportsHardware4k: Boolean = true,
    val isLowRamDevice: Boolean = false,
    val maxSupportedFrameRate4k: Double = 30.0
)

/**
 * Parsed representation of an HTTP `Content-Range` response header (`bytes start-end/total`).
 */
data class ParsedContentRange(
    val start: Long,
    val end: Long,
    val totalLength: Long
) {
    val rangeLength: Long
        get() = if (end >= start && start >= 0L) (end - start + 1L) else C.LENGTH_UNSET.toLong()

    val reachesEndOfResource: Boolean
        get() = totalLength > 0L && end >= totalLength - 1L
}

/**
 * Shared, connection-pooled OkHttpClient provider tuned for live HLS, MPEG-TS, and VOD streaming
 * across both strong fiber connections and weak/congested mobile networks.
 *
 * Timeout Engineering Rationale:
 * - `CONNECT_TIMEOUT_MS = 10_000L` (10s): handles high-RTT mobile DNS + TLS handshakes without stalling failover.
 * - `READ_TIMEOUT_MS = 20_000L` (20s): accommodates 6–10s HLS segments over slow 1–2 Mbps links and prevents
 *   premature socket disconnects when ExoPlayer's LoadControl briefly pauses reading at maxBufferMs,
 *   while still bounding dead-socket detection.
 * - `PING_INTERVAL_MS = 15_000L` (15s): keeps HTTP/2 multiplexed connections alive and evicts silently dropped NAT sockets.
 */
object StreamOkHttpClientProvider {
    const val CONNECT_TIMEOUT_MS: Long = 10_000L
    const val READ_TIMEOUT_MS: Long = 20_000L
    const val WRITE_TIMEOUT_MS: Long = 10_000L
    const val PING_INTERVAL_MS: Long = 15_000L

    val defaultUserAgent: String =
        "Mozilla/5.0 (Linux; Android 14; SmartTV) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36 FoulTV/1.2.1"

    val sharedClient: OkHttpClient by lazy {
        val dispatcher = Dispatcher().apply {
            maxRequests = 48
            maxRequestsPerHost = 12
        }
        OkHttpClient.Builder()
            .dispatcher(dispatcher)
            .connectionPool(ConnectionPool(16, 3, TimeUnit.MINUTES))
            .protocols(listOf(Protocol.HTTP_2, Protocol.HTTP_1_1))
            .connectTimeout(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .readTimeout(READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .writeTimeout(WRITE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .pingInterval(PING_INTERVAL_MS, TimeUnit.MILLISECONDS)
            .retryOnConnectionFailure(true)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }
}

/**
 * Production-grade Media3 [HttpDataSource] backed by OkHttp with:
 * - Protocol-clean headers: delegates `Connection` management natively to OkHttp (`BridgeInterceptor` / HTTP/2 framer)
 *   so forbidden HTTP/2 connection-specific headers (`Connection: keep-alive`) are never forced.
 * - Proper `Range` request handling (`206 Partial Content` and `200 OK` byte-skipping fallback).
 * - Zero-extra-request `Content-Type` header inspection on open to classify extensionless stream URLs accurately.
 * - Active [Call] cancellation on [close] so switching channels immediately aborts in-flight sockets.
 * - Strict EOF verification (`EOFException` when stream ends before expected `bytesToRead`).
 */
@OptIn(UnstableApi::class)
class OkHttpStreamDataSource(
    private val callFactory: Call.Factory,
    private val userAgent: String,
    private val defaultHeaders: Map<String, String> = emptyMap(),
    private val onHttpCodeError: ((Int, String) -> Unit)? = null,
    private val onContentTypeDetected: ((String, String) -> Unit)? = null
) : BaseDataSource(true), HttpDataSource {

    private var dataSpec: DataSpec? = null
    private var currentCall: Call? = null
    private var response: Response? = null
    private var inputStream: InputStream? = null
    private var opened = false
    private var bytesToRead: Long = 0
    private var bytesRead: Long = 0
    private val requestProperties = mutableMapOf<String, String>()

    companion object {
        private val CONTENT_RANGE_REGEX = Regex(
            "^bytes\\s+(\\d+)-(\\d+)/(\\d+|\\*)$",
            RegexOption.IGNORE_CASE
        )

        /**
         * Parses an RFC 7233 `Content-Range` header (e.g. `bytes 90-99/100` or `bytes 0-1023` with unknown total).
         */
        fun parseContentRange(contentRangeHeader: String?): ParsedContentRange? {
            val raw = contentRangeHeader?.trim().orEmpty()
            if (raw.isEmpty()) return null
            val match = CONTENT_RANGE_REGEX.matchEntire(raw) ?: return null
            val start = match.groupValues[1].toLongOrNull() ?: return null
            val end = match.groupValues[2].toLongOrNull() ?: return null
            if (end < start) return null
            val totalRaw = match.groupValues[3]
            val total = if (totalRaw == "*") {
                C.LENGTH_UNSET.toLong()
            } else {
                totalRaw.toLongOrNull() ?: C.LENGTH_UNSET.toLong()
            }
            return ParsedContentRange(start = start, end = end, totalLength = total)
        }

        /**
         * Determines the exact number of bytes expected from the HTTP response by inspecting
         * `statusCode`, `DataSpec.position`, `DataSpec.length`, `Content-Range`, and `Content-Length`.
         *
         * Distinguishes between:
         * 1. **Natural End-Of-File (EOF)** on a `206 Partial Content` response that is shorter than `DataSpec.length`
         *    because the requested range reached the end of the resource (`Content-Range: bytes 90-99/100`, `Content-Length: 10`
         *    when `DataSpec.length` was `50`). In this case, `bytesToRead` is clamped to `10` so reading finishes cleanly.
         * 2. **Premature Truncation** where the server promised more bytes (`Content-Length: 20` or `Content-Range: bytes 0-19/100`,
         *    or `Content-Range` stopped at `bytes 0-4/100` well before the resource's 100-byte end), ensuring `read()`
         *    throws `EOFException` if the stream closes before delivering the expected bytes.
         */
        fun resolveExpectedBytesToRead(
            statusCode: Int,
            requestedPosition: Long,
            requestedLength: Long,
            bytesToSkip: Long,
            contentLengthHeader: String?,
            contentRangeHeader: String?,
            bodyContentLength: Long = C.LENGTH_UNSET.toLong()
        ): Long {
            val parsedContentLength = contentLengthHeader?.trim()?.toLongOrNull()?.takeIf { it >= 0L }
            val parsedRange = parseContentRange(contentRangeHeader)

            if (statusCode == 206) {
                val declaredPartialLength = when {
                    parsedRange != null && parsedRange.rangeLength > 0L -> {
                        if (parsedContentLength != null && parsedContentLength != parsedRange.rangeLength) {
                            if (parsedRange.reachesEndOfResource) {
                                min(parsedContentLength, parsedRange.rangeLength)
                            } else {
                                maxOf(parsedContentLength, parsedRange.rangeLength)
                            }
                        } else {
                            parsedRange.rangeLength
                        }
                    }
                    parsedContentLength != null -> parsedContentLength
                    else -> C.LENGTH_UNSET.toLong()
                }

                if (requestedLength != C.LENGTH_UNSET.toLong()) {
                    if (declaredPartialLength == C.LENGTH_UNSET.toLong()) {
                        return requestedLength
                    }
                    if (declaredPartialLength >= requestedLength) {
                        return requestedLength
                    }
                    // declaredPartialLength < requestedLength on a 206 response:
                    // Check if this shorter response is a legitimate Natural EOF at the end of the file.
                    val isNaturalEof = when {
                        parsedRange != null && parsedRange.totalLength > 0L ->
                            parsedRange.reachesEndOfResource
                        parsedRange != null && parsedRange.totalLength == C.LENGTH_UNSET.toLong() ->
                            parsedContentLength == null || parsedContentLength == parsedRange.rangeLength
                        else ->
                            parsedContentLength != null && parsedContentLength > 0L
                    }
                    return if (isNaturalEof) {
                        declaredPartialLength
                    } else if (parsedRange != null && parsedRange.totalLength > 0L) {
                        val remainingInResource = (parsedRange.totalLength - requestedPosition).coerceAtLeast(0L)
                        min(requestedLength, remainingInResource)
                    } else {
                        requestedLength
                    }
                } else {
                    return declaredPartialLength
                }
            }

            // statusCode == 200 (or other 2xx)
            val effectiveContentLength = parsedContentLength ?: bodyContentLength.takeIf { it >= 0L }
            val availableAfterSkip = if (effectiveContentLength != null) {
                (effectiveContentLength - bytesToSkip).coerceAtLeast(0L)
            } else {
                C.LENGTH_UNSET.toLong()
            }

            return if (requestedLength != C.LENGTH_UNSET.toLong()) {
                if (parsedContentLength != null && availableAfterSkip != C.LENGTH_UNSET.toLong()) {
                    min(requestedLength, availableAfterSkip)
                } else {
                    requestedLength
                }
            } else {
                availableAfterSkip
            }
        }
    }

    class Factory(
        private val callFactory: Call.Factory = StreamOkHttpClientProvider.sharedClient,
        private val userAgent: String = StreamOkHttpClientProvider.defaultUserAgent,
        private val onHttpCodeError: ((Int, String) -> Unit)? = null,
        private val onContentTypeDetected: ((String, String) -> Unit)? = null
    ) : HttpDataSource.Factory {
        private val defaultRequestProperties = mutableMapOf<String, String>()

        override fun createDataSource(): HttpDataSource {
            return OkHttpStreamDataSource(
                callFactory = callFactory,
                userAgent = userAgent,
                defaultHeaders = defaultRequestProperties.toMap(),
                onHttpCodeError = onHttpCodeError,
                onContentTypeDetected = onContentTypeDetected
            )
        }

        override fun setDefaultRequestProperties(defaultRequestProperties: MutableMap<String, String>): HttpDataSource.Factory {
            this.defaultRequestProperties.clear()
            this.defaultRequestProperties.putAll(defaultRequestProperties)
            return this
        }
    }

    override fun getUri(): Uri? {
        return dataSpec?.uri
    }

    override fun getResponseCode(): Int {
        return response?.code ?: -1
    }

    override fun getResponseHeaders(): Map<String, List<String>> {
        return response?.headers?.toMultimap() ?: emptyMap()
    }

    override fun setRequestProperty(name: String, value: String) {
        requestProperties[name] = value
    }

    override fun clearRequestProperty(name: String) {
        requestProperties.remove(name)
    }

    override fun clearAllRequestProperties() {
        requestProperties.clear()
    }

    @Throws(HttpDataSource.HttpDataSourceException::class)
    override fun open(dataSpec: DataSpec): Long {
        this.dataSpec = dataSpec
        transferInitializing(dataSpec)
        return openInternal(
            requestUrlString = dataSpec.uri.toString(),
            position = dataSpec.position,
            length = dataSpec.length,
            httpRequestHeaders = dataSpec.httpRequestHeaders,
            activeSpec = dataSpec
        )
    }

    /**
     * Opens a stream directly by URL and optional byte range using the exact same OkHttp pipeline.
     * Used by unit tests to verify HTTP/2 header hygiene, Range requests, 200 OK byte skipping,
     * Content-Type detection, and call cancellation without requiring an Android `Uri` stub.
     */
    @Throws(IOException::class)
    fun openUrl(
        requestUrlString: String,
        position: Long = 0L,
        length: Long = C.LENGTH_UNSET.toLong(),
        httpRequestHeaders: Map<String, String> = emptyMap()
    ): Long {
        this.dataSpec = null
        return openInternal(
            requestUrlString = requestUrlString,
            position = position,
            length = length,
            httpRequestHeaders = httpRequestHeaders,
            activeSpec = null
        )
    }

    @Throws(IOException::class)
    private fun openInternal(
        requestUrlString: String,
        position: Long,
        length: Long,
        httpRequestHeaders: Map<String, String>,
        activeSpec: DataSpec?
    ): Long {
        this.bytesRead = 0
        this.bytesToRead = 0

        val requestBuilder = try {
            Request.Builder().url(requestUrlString)
        } catch (e: IllegalArgumentException) {
            val ioEx = IOException("Malformed URL", e)
            if (activeSpec != null) {
                throw HttpDataSource.HttpDataSourceException(
                    ioEx,
                    activeSpec,
                    PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
                    HttpDataSource.HttpDataSourceException.TYPE_OPEN
                )
            }
            throw ioEx
        }

        // Set standard User-Agent and Accept without forcing 'Connection: keep-alive'
        // (OkHttp manages keep-alive automatically for HTTP/1.1 and forbids 'Connection' header in HTTP/2 per RFC 7540).
        requestBuilder.header("User-Agent", userAgent)
        requestBuilder.header("Accept", "*/*")

        val forbiddenProtocolHeaders = setOf("connection", "keep-alive", "upgrade", "http2-settings")
        for ((k, v) in defaultHeaders) {
            if (k.lowercase() !in forbiddenProtocolHeaders) {
                requestBuilder.header(k, v)
            }
        }
        for ((k, v) in httpRequestHeaders) {
            if (k.lowercase() !in forbiddenProtocolHeaders) {
                requestBuilder.header(k, v)
            }
        }
        for ((k, v) in requestProperties) {
            if (k.lowercase() !in forbiddenProtocolHeaders) {
                requestBuilder.header(k, v)
            }
        }

        if (position != 0L || length != C.LENGTH_UNSET.toLong()) {
            val rangeRequest = buildString {
                append("bytes=")
                append(position)
                append("-")
                if (length != C.LENGTH_UNSET.toLong()) {
                    append(position + length - 1)
                }
            }
            requestBuilder.header("Range", rangeRequest)
        }

        val call = callFactory.newCall(requestBuilder.build())
        this.currentCall = call

        val executedResponse = try {
            call.execute()
        } catch (e: IOException) {
            this.currentCall = null
            if (activeSpec != null) {
                throw HttpDataSource.HttpDataSourceException.createForIOException(
                    e,
                    activeSpec,
                    HttpDataSource.HttpDataSourceException.TYPE_OPEN
                )
            }
            throw e
        }

        this.response = executedResponse
        val body = executedResponse.body
        val statusCode = executedResponse.code

        if (!executedResponse.isSuccessful || body == null) {
            val headersMap = executedResponse.headers.toMultimap()
            closeQuietly()
            onHttpCodeError?.invoke(statusCode, requestUrlString)
            if (activeSpec != null) {
                throw HttpDataSource.InvalidResponseCodeException(
                    statusCode,
                    executedResponse.message,
                    null,
                    headersMap,
                    activeSpec,
                    ByteArray(0)
                )
            }
            throw IOException("HTTP $statusCode ${executedResponse.message}")
        }

        // Capture Content-Type from the active response without any extra network request
        val contentTypeHeader = executedResponse.header("Content-Type")
            ?: body.contentType()?.toString()
        if (!contentTypeHeader.isNullOrBlank()) {
            StreamSourceResolver.recordObservedContentType(requestUrlString, contentTypeHeader)
            onContentTypeDetected?.invoke(requestUrlString, contentTypeHeader)
        }

        val stream = body.byteStream()
        this.inputStream = stream

        // If server returned 200 OK instead of 206 Partial Content when a non-zero position was requested,
        // skip the leading bytes up to position.
        val bytesToSkip = if (statusCode == 200 && position != 0L) position else 0L
        if (bytesToSkip > 0L) {
            try {
                var skippedTotal = 0L
                val skipBuffer = ByteArray(4096)
                while (skippedTotal < bytesToSkip) {
                    val readCount = stream.read(
                        skipBuffer,
                        0,
                        min((bytesToSkip - skippedTotal).toInt(), skipBuffer.size)
                    )
                    if (Thread.currentThread().isInterrupted) {
                        throw IOException("Interrupted while skipping bytes")
                    }
                    if (readCount == -1) {
                        throw EOFException("Premature EOF while skipping to position $bytesToSkip")
                    }
                    skippedTotal += readCount
                }
            } catch (e: IOException) {
                closeQuietly()
                if (activeSpec != null) {
                    throw HttpDataSource.HttpDataSourceException.createForIOException(
                        e,
                        activeSpec,
                        HttpDataSource.HttpDataSourceException.TYPE_OPEN
                    )
                }
                throw e
            }
        }

        bytesToRead = resolveExpectedBytesToRead(
            statusCode = statusCode,
            requestedPosition = position,
            requestedLength = length,
            bytesToSkip = bytesToSkip,
            contentLengthHeader = executedResponse.header("Content-Length"),
            contentRangeHeader = executedResponse.header("Content-Range"),
            bodyContentLength = body.contentLength()
        )

        opened = true
        if (activeSpec != null) {
            transferStarted(activeSpec)
        }
        return bytesToRead
    }

    @Throws(HttpDataSource.HttpDataSourceException::class)
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (bytesToRead != C.LENGTH_UNSET.toLong()) {
            val bytesRemaining = bytesToRead - bytesRead
            if (bytesRemaining == 0L) {
                return C.RESULT_END_OF_INPUT
            }
        }

        if (!opened && dataSpec == null) return C.RESULT_END_OF_INPUT
        val currentSpec = dataSpec
        val maxReadLength = if (bytesToRead == C.LENGTH_UNSET.toLong()) {
            length
        } else {
            min(length.toLong(), bytesToRead - bytesRead).toInt()
        }

        val readBytes = try {
            inputStream?.read(buffer, offset, maxReadLength) ?: C.RESULT_END_OF_INPUT
        } catch (e: IOException) {
            if (currentSpec != null) {
                throw HttpDataSource.HttpDataSourceException.createForIOException(
                    e,
                    currentSpec,
                    HttpDataSource.HttpDataSourceException.TYPE_READ
                )
            }
            throw RuntimeException(e)
        }

        if (readBytes == -1) {
            if (bytesToRead != C.LENGTH_UNSET.toLong() && bytesRead < bytesToRead) {
                val eof = EOFException("Stream ended prematurely: read $bytesRead of $bytesToRead bytes")
                if (currentSpec != null) {
                    throw HttpDataSource.HttpDataSourceException.createForIOException(
                        eof,
                        currentSpec,
                        HttpDataSource.HttpDataSourceException.TYPE_READ
                    )
                }
                throw RuntimeException(eof)
            }
            return C.RESULT_END_OF_INPUT
        }

        bytesRead += readBytes
        if (currentSpec != null) {
            bytesTransferred(readBytes)
        }
        return readBytes
    }

    override fun close() {
        try {
            closeQuietly()
        } finally {
            if (opened) {
                opened = false
                if (dataSpec != null) {
                    transferEnded()
                }
            }
        }
    }

    private fun closeQuietly() {
        try {
            inputStream?.close()
        } catch (_: Exception) {
        }
        inputStream = null

        try {
            response?.body?.close()
            response?.close()
        } catch (_: Exception) {
        }
        response = null

        try {
            currentCall?.cancel()
        } catch (_: Exception) {
        }
        currentCall = null
    }
}

/**
 * Resolves stream source types (HLS/M3U8, MPEG-TS, MP4, MKV, Direct), learns `Content-Type` headers
 * observed during actual playback without extra network requests, queries hardware codec capabilities,
 * and builds the appropriate Media3 [MediaSource] without mutating URL extensions (.m3u8 <-> .ts).
 */
@OptIn(UnstableApi::class)
object StreamSourceResolver {

    private const val MAX_CONTENT_TYPE_CACHE = 256
    private val observedContentTypeByUrl = ConcurrentHashMap<String, StreamSourceType>()

    @Volatile
    private var cachedDeviceCapabilities: DeviceCodecCapabilities? = null

    /**
     * Maps an HTTP `Content-Type` response header to a concrete [StreamSourceType] when recognizable.
     */
    fun resolveFromContentType(contentTypeHeader: String?): StreamSourceType? {
        val raw = contentTypeHeader?.trim()?.lowercase()?.substringBefore(';')?.trim().orEmpty()
        if (raw.isEmpty()) return null
        return when {
            raw == "application/vnd.apple.mpegurl" ||
                raw == "application/x-mpegurl" ||
                raw == "audio/mpegurl" ||
                raw == "audio/x-mpegurl" ||
                raw.contains("mpegurl") ||
                raw.contains("m3u8") -> StreamSourceType.HLS

            raw == "video/mp2t" ||
                raw == "video/mpeg2ts" ||
                raw == "application/mp2t" ||
                raw.contains("mp2t") -> StreamSourceType.MPEG_TS

            raw == "video/mp4" ||
                raw == "application/mp4" ||
                raw == "video/quicktime" -> StreamSourceType.MP4

            raw == "video/x-matroska" ||
                raw == "video/webm" ||
                raw.contains("matroska") -> StreamSourceType.MKV

            else -> null
        }
    }

    /**
     * Records the `Content-Type` observed from an active `OkHttpStreamDataSource` response
     * so extensionless URLs can be immediately resolved to their exact container on subsequent loads/retries.
     */
    fun recordObservedContentType(url: String, contentTypeHeader: String?) {
        val normalizedUrl = url.trim()
        if (normalizedUrl.isEmpty()) return
        val resolved = resolveFromContentType(contentTypeHeader) ?: return
        if (observedContentTypeByUrl.size >= MAX_CONTENT_TYPE_CACHE) {
            observedContentTypeByUrl.clear()
        }
        observedContentTypeByUrl[normalizedUrl] = resolved
    }

    fun getObservedSourceType(url: String): StreamSourceType? {
        return observedContentTypeByUrl[url.trim()]
    }

    fun clearObservedContentTypes() {
        observedContentTypeByUrl.clear()
    }

    /**
     * Classifies the stream URL using:
     * 1. Explicit `Content-Type` header if already observed for this URL or passed in.
     * 2. Explicit `serverStreamTypeHint` (`ChannelServer.streamType`, e.g. "m3u8", "hls", "ts", "mp4", "mkv").
     * 3. URL path extension and path segments (before query parameters).
     * 4. Query string format hints (`output=m3u8`, `output=ts`, `type=m3u8`, `extension=ts`, etc.).
     * 5. Server metadata (`serverNameHint`, e.g. server named "Server 1 HLS" or "Server 2 MPEG-TS") for extensionless URLs.
     * 6. Xtream Codes extensionless live/VOD URL structure (`/live/user/pass/12345`, `/movie/user/pass/12345`, etc.).
     */
    fun detectStreamSourceType(
        url: String,
        serverStreamTypeHint: String = "",
        serverNameHint: String = "",
        contentTypeHint: String? = null,
        isLiveChannel: Boolean = true
    ): StreamSourceType {
        // 1. Check explicit Content-Type hint or previously observed Content-Type for this exact URL
        resolveFromContentType(contentTypeHint)?.let { return it }
        val cleanUrl = url.trim()
        if (cleanUrl.isNotEmpty()) {
            observedContentTypeByUrl[cleanUrl]?.let { return it }
        }

        // 2. Check explicit serverStreamTypeHint
        val hintLower = serverStreamTypeHint.trim().lowercase()
        when (hintLower) {
            "m3u8", "hls", "m3u", "application/x-mpegurl", "application/vnd.apple.mpegurl" ->
                return StreamSourceType.HLS
            "ts", "mpegts", "mpeg-ts", "mp2t", "video/mp2t" ->
                return StreamSourceType.MPEG_TS
            "mp4", "m4v", "mov", "video/mp4" ->
                return StreamSourceType.MP4
            "mkv", "webm", "matroska", "video/x-matroska", "video/webm" ->
                return StreamSourceType.MKV
        }

        if (cleanUrl.isEmpty()) {
            return if (isLiveChannel) StreamSourceType.HLS else StreamSourceType.PROGRESSIVE_DIRECT
        }

        val lowerUrl = cleanUrl.lowercase()
        val pathBeforeQuery = lowerUrl.substringBefore('?').substringBefore('#')
        val queryPart = lowerUrl.substringAfter('?', "")

        // 3. Check explicit path extensions and manifest path tokens
        when {
            pathBeforeQuery.endsWith(".m3u8") ||
                pathBeforeQuery.endsWith(".m3u") ||
                pathBeforeQuery.contains(".m3u8/") ||
                pathBeforeQuery.contains("/hls/") ||
                pathBeforeQuery.contains("/m3u8/") ||
                pathBeforeQuery.contains("/manifest.m3u8") ||
                pathBeforeQuery.contains("/playlist.m3u8") -> return StreamSourceType.HLS

            pathBeforeQuery.endsWith(".ts") ||
                pathBeforeQuery.endsWith(".mts") ||
                pathBeforeQuery.endsWith(".m2ts") ||
                pathBeforeQuery.endsWith(".mpegts") ||
                pathBeforeQuery.contains("/mpegts/") -> return StreamSourceType.MPEG_TS

            pathBeforeQuery.endsWith(".mp4") ||
                pathBeforeQuery.endsWith(".m4v") ||
                pathBeforeQuery.endsWith(".mov") -> return StreamSourceType.MP4

            pathBeforeQuery.endsWith(".mkv") ||
                pathBeforeQuery.endsWith(".webm") -> return StreamSourceType.MKV
        }

        // 4. Check query parameter format hints
        if (queryPart.isNotEmpty()) {
            when {
                queryPart.contains("output=m3u8") ||
                    queryPart.contains("output=hls") ||
                    queryPart.contains("type=m3u8") ||
                    queryPart.contains("type=hls") ||
                    queryPart.contains("format=m3u8") ||
                    queryPart.contains("format=hls") ||
                    queryPart.contains("extension=m3u8") ||
                    queryPart.contains("ext=m3u8") ||
                    queryPart.contains(".m3u8") -> return StreamSourceType.HLS

                queryPart.contains("output=ts") ||
                    queryPart.contains("output=mpegts") ||
                    queryPart.contains("type=ts") ||
                    queryPart.contains("type=mpegts") ||
                    queryPart.contains("format=ts") ||
                    queryPart.contains("extension=ts") ||
                    queryPart.contains("ext=ts") -> return StreamSourceType.MPEG_TS

                queryPart.contains("ext=mp4") ||
                    queryPart.contains("format=mp4") ||
                    queryPart.contains("type=mp4") -> return StreamSourceType.MP4

                queryPart.contains("ext=mkv") ||
                    queryPart.contains("format=mkv") ||
                    queryPart.contains("type=mkv") -> return StreamSourceType.MKV
            }
        }

        // 5. For extensionless URLs, check serverNameHint metadata if provided (e.g., "Server 1 (HLS)" vs "Server 2 (TS)")
        val nameLower = serverNameHint.trim().lowercase()
        if (nameLower.isNotEmpty()) {
            when {
                Regex("\\b(m3u8|hls)\\b").containsMatchIn(nameLower) -> return StreamSourceType.HLS
                Regex("\\b(mpeg-?ts|ts)\\b").containsMatchIn(nameLower) -> return StreamSourceType.MPEG_TS
                Regex("\\bmp4\\b").containsMatchIn(nameLower) -> return StreamSourceType.MP4
                Regex("\\bmkv\\b").containsMatchIn(nameLower) -> return StreamSourceType.MKV
            }
        }

        // 6. Xtream Codes extensionless live stream pattern:
        // e.g. http://host:port/live/user/pass/12345 or http://host:port/user/pass/12345
        // Extensionless Xtream live URLs deliver raw MPEG-TS byte streams by default.
        val isXtreamExtensionlessLive = isLiveChannel && (
            Regex(".*/live/[^/]+/[^/]+/\\d+$").matches(pathBeforeQuery) ||
                Regex("^https?://[^/]+/[^/]+/[^/]+/\\d+$").matches(pathBeforeQuery)
            )
        if (isXtreamExtensionlessLive) {
            return StreamSourceType.MPEG_TS
        }

        return if (isLiveChannel) {
            StreamSourceType.PROGRESSIVE_DIRECT
        } else {
            StreamSourceType.MP4
        }
    }

    /**
     * Evaluates whether a video decoder genuinely supports 4K UHD (`3840×2160`) resolution
     * by checking hardware acceleration, RAM budget, explicit `3840×2160` frame dimensions,
     * and supported frame rates (`24fps` / `30fps` / `60fps`) rather than relying solely on `supportedHeights.upper`.
     */
    fun evaluate4kDecoderSupport(
        isHardwareAccelerated: Boolean,
        isLowRamDevice: Boolean,
        supportedWidthUpper: Int,
        supportedHeightUpper: Int,
        isSize3840x2160Supported: Boolean,
        is3840x2160At24FpsSupported: Boolean,
        is3840x2160At30FpsSupported: Boolean,
        is3840x2160At60FpsSupported: Boolean = false
    ): Pair<Boolean, Double> {
        if (!isHardwareAccelerated || isLowRamDevice) {
            return false to 0.0
        }
        if (supportedWidthUpper < 3840 || supportedHeightUpper < 2160 || !isSize3840x2160Supported) {
            return false to 0.0
        }
        val maxFps = when {
            is3840x2160At60FpsSupported -> 60.0
            is3840x2160At30FpsSupported -> 30.0
            is3840x2160At24FpsSupported -> 24.0
            else -> 0.0
        }
        return (maxFps >= 24.0) to maxFps
    }

    /**
     * Queries hardware video decoders (`MediaCodecList`) and RAM tier once and caches the result.
     * Verifies actual `3840×2160` width/height and frame-rate support (`isSizeSupported(3840, 2160)` and
     * `areSizeAndRateSupported(3840, 2160, 30.0 / 24.0)`) on hardware decoders.
     */
    fun queryDeviceCapabilities(context: Context?): DeviceCodecCapabilities {
        cachedDeviceCapabilities?.let { return it }
        if (context == null) return DeviceCodecCapabilities()

        return try {
            val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            val isLowRam = activityManager?.isLowRamDevice == true

            val codecList = MediaCodecList(MediaCodecList.REGULAR_CODECS)
            var hasHwH264 = false
            var hasHwHevc = false
            var supportsTrue4k = false
            var best4kFps = 0.0

            for (info in codecList.codecInfos) {
                if (info.isEncoder) continue
                val codecName = info.name.lowercase()
                val isSoftwareOnly = codecName.startsWith("omx.google.") ||
                    codecName.startsWith("c2.android.") ||
                    codecName.contains(".sw.") ||
                    codecName.contains("ffmpeg")
                val isHardware = !isSoftwareOnly

                for (type in info.supportedTypes) {
                    val isAvc = type.equals(MediaFormat.MIMETYPE_VIDEO_AVC, ignoreCase = true)
                    val isHevc = type.equals(MediaFormat.MIMETYPE_VIDEO_HEVC, ignoreCase = true)
                    if (!isAvc && !isHevc) continue

                    if (isAvc && isHardware) hasHwH264 = true
                    if (isHevc && isHardware) hasHwHevc = true

                    if (isHardware && !isLowRam) {
                        runCatching {
                            val caps = info.getCapabilitiesForType(type)?.videoCapabilities
                            if (caps != null) {
                                val widthUpper = caps.supportedWidths.upper
                                val heightUpper = caps.supportedHeights.upper
                                val sizeSupported = caps.isSizeSupported(3840, 2160)
                                val rate24 = sizeSupported && caps.areSizeAndRateSupported(3840, 2160, 24.0)
                                val rate30 = sizeSupported && caps.areSizeAndRateSupported(3840, 2160, 30.0)
                                val rate60 = sizeSupported && caps.areSizeAndRateSupported(3840, 2160, 60.0)

                                val (codecSupports4k, codecMaxFps) = evaluate4kDecoderSupport(
                                    isHardwareAccelerated = true,
                                    isLowRamDevice = false,
                                    supportedWidthUpper = widthUpper,
                                    supportedHeightUpper = heightUpper,
                                    isSize3840x2160Supported = sizeSupported,
                                    is3840x2160At24FpsSupported = rate24,
                                    is3840x2160At30FpsSupported = rate30,
                                    is3840x2160At60FpsSupported = rate60
                                )
                                if (codecSupports4k) {
                                    supportsTrue4k = true
                                    best4kFps = maxOf(best4kFps, codecMaxFps)
                                }
                            }
                        }
                    }
                }
            }

            DeviceCodecCapabilities(
                supportsHardwareH264 = hasHwH264,
                supportsHardwareHevc = hasHwHevc,
                supportsHardware4k = supportsTrue4k,
                isLowRamDevice = isLowRam,
                maxSupportedFrameRate4k = best4kFps
            ).also {
                cachedDeviceCapabilities = it
            }
        } catch (_: Throwable) {
            DeviceCodecCapabilities()
        }
    }

    /**
     * Builds a dedicated [MediaItem] and [MediaSource] tailored to the detected [StreamSourceType].
     * - HLS -> [HlsMediaSource] with `allowChunklessPreparation = true` and stable live offset configuration.
     * - MPEG-TS -> [ProgressiveMediaSource] with a high-tolerance [TsExtractor] (non-IDR keyframes, access unit detection, splice info ignore, 1300 TS packet timestamp search).
     * - MP4 / MKV -> [ProgressiveMediaSource] with constant bitrate seeking enabled.
     * - PROGRESSIVE_DIRECT -> [DefaultMediaSourceFactory] backed by both HLS and tuned TS/Progressive extractors for extensionless URLs.
     */
    fun createMediaSource(
        url: String,
        sourceType: StreamSourceType,
        isLiveChannel: Boolean,
        dataSourceFactory: DataSource.Factory
    ): MediaSource {
        val uri = Uri.parse(url.trim())
        val mediaItemBuilder = MediaItem.Builder().setUri(uri)

        if (isLiveChannel && sourceType == StreamSourceType.HLS) {
            mediaItemBuilder.setMimeType(MimeTypes.APPLICATION_M3U8)
            mediaItemBuilder.setLiveConfiguration(
                MediaItem.LiveConfiguration.Builder()
                    .setTargetOffsetMs(6_000L)
                    .setMinOffsetMs(3_000L)
                    .setMaxOffsetMs(18_000L)
                    .setMinPlaybackSpeed(1.0f)
                    .setMaxPlaybackSpeed(1.0f)
                    .build()
            )
        } else {
            when (sourceType) {
                StreamSourceType.HLS -> mediaItemBuilder.setMimeType(MimeTypes.APPLICATION_M3U8)
                StreamSourceType.MPEG_TS -> mediaItemBuilder.setMimeType(MimeTypes.VIDEO_MP2T)
                StreamSourceType.MP4 -> mediaItemBuilder.setMimeType(MimeTypes.VIDEO_MP4)
                StreamSourceType.MKV -> mediaItemBuilder.setMimeType(MimeTypes.VIDEO_MATROSKA)
                StreamSourceType.PROGRESSIVE_DIRECT -> {
                    // Leave MIME type open for container sniffing by DefaultMediaSourceFactory
                }
            }
        }

        val mediaItem = mediaItemBuilder.build()

        return when (sourceType) {
            StreamSourceType.HLS -> {
                HlsMediaSource.Factory(dataSourceFactory)
                    .setAllowChunklessPreparation(true)
                    .createMediaSource(mediaItem)
            }

            StreamSourceType.MPEG_TS -> {
                val tsExtractorsFactory = DefaultExtractorsFactory()
                    .setTsExtractorFlags(
                        DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES or
                            DefaultTsPayloadReaderFactory.FLAG_DETECT_ACCESS_UNITS or
                            DefaultTsPayloadReaderFactory.FLAG_IGNORE_SPLICE_INFO_STREAM
                    )
                    .setTsExtractorTimestampSearchBytes(1300 * TsExtractor.TS_PACKET_SIZE)
                    .setConstantBitrateSeekingEnabled(true)

                ProgressiveMediaSource.Factory(dataSourceFactory, tsExtractorsFactory)
                    .setContinueLoadingCheckIntervalBytes(512 * 1024)
                    .createMediaSource(mediaItem)
            }

            StreamSourceType.MP4,
            StreamSourceType.MKV -> {
                val progressiveExtractors = DefaultExtractorsFactory()
                    .setConstantBitrateSeekingEnabled(true)
                    .setMp4ExtractorFlags(Mp4Extractor.FLAG_WORKAROUND_IGNORE_EDIT_LISTS)

                ProgressiveMediaSource.Factory(dataSourceFactory, progressiveExtractors)
                    .createMediaSource(mediaItem)
            }

            StreamSourceType.PROGRESSIVE_DIRECT -> {
                val hybridExtractors = DefaultExtractorsFactory()
                    .setTsExtractorFlags(
                        DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES or
                            DefaultTsPayloadReaderFactory.FLAG_DETECT_ACCESS_UNITS or
                            DefaultTsPayloadReaderFactory.FLAG_IGNORE_SPLICE_INFO_STREAM
                    )
                    .setTsExtractorTimestampSearchBytes(1300 * TsExtractor.TS_PACKET_SIZE)
                    .setConstantBitrateSeekingEnabled(true)

                DefaultMediaSourceFactory(dataSourceFactory, hybridExtractors)
                    .createMediaSource(mediaItem)
            }
        }
    }

    /**
     * Selects the safest, highest-compatibility initial server index by evaluating:
     * 1. Valid non-blank stream URL.
     * 2. Hardware decoder compatibility (`DeviceCodecCapabilities`):
     *    - Avoids `HEVC / H.265` as the automatic initial server unless explicitly the only option or safely supported,
     *      and strictly skips it if `!deviceCapabilities.supportsHardwareHevc`.
     *    - Avoids `4K / UHD / 2160p` as the automatic initial server when `!deviceCapabilities.supportsHardware4k`
     *      or `deviceCapabilities.isLowRamDevice`, or when a mainstream H.264 `HD / FHD` primary server is available
     *      for faster initial time-to-first-frame.
     * 3. Does NOT blindly assume `SD / Low` is faster than `FHD / HD`: preserves the primary server order (`index 0`)
     *    whenever `index 0` is a standard H.264 stream (including `FHD 1080p` and `HD 720p` on normal devices).
     * 4. On low-RAM devices (`isLowRamDevice == true`), prefers `720p HD` over `1080p FHD` if both exist.
     */
    fun selectOptimalInitialServerIndex(
        servers: List<ChannelServer>,
        isLiveChannel: Boolean,
        deviceCapabilities: DeviceCodecCapabilities = DeviceCodecCapabilities()
    ): Int {
        if (servers.isEmpty()) return 0
        val firstValidIdx = servers.indexOfFirst { it.streamUrl.isNotBlank() }.takeIf { it >= 0 } ?: 0
        if (!isLiveChannel || servers.size <= 1) return firstValidIdx

        fun isHevc(server: ChannelServer): Boolean {
            val combined = "${server.name} ${server.streamUrl}".lowercase()
            return combined.contains("hevc") ||
                combined.contains("h265") ||
                combined.contains("h.265") ||
                combined.contains("x265")
        }

        fun is4kUhd(server: ChannelServer): Boolean {
            val combined = "${server.name} ${server.streamUrl}".lowercase()
            return combined.contains("4k") ||
                combined.contains("uhd") ||
                combined.contains("2160") ||
                combined.contains("raw")
        }

        fun is1080pFhd(server: ChannelServer): Boolean {
            val combined = "${server.name} ${server.streamUrl}".lowercase()
            return combined.contains("fhd") || combined.contains("1080")
        }

        fun isStandardHdOrAuto(server: ChannelServer): Boolean {
            val label = server.name.lowercase()
            return !isHevc(server) &&
                !is4kUhd(server) &&
                !is1080pFhd(server) &&
                (label.contains("hd") || label.contains("720") || label.contains("auto") || label.contains("متوسط") || label.contains("أساسي"))
        }

        val primaryServer = servers[firstValidIdx]

        // On normal (non-low-RAM) devices, if the primary server is not 4K/RAW and not HEVC
        // (i.e. it is standard H.264 HD, FHD 1080p, or standard HLS/TS), keep the primary server!
        if (!is4kUhd(primaryServer) && !isHevc(primaryServer)) {
            if (!deviceCapabilities.isLowRamDevice || !is1080pFhd(primaryServer)) {
                return firstValidIdx
            }
        }

        // If primary server is 4K/UHD, HEVC, or FHD on a low-RAM device, look for the best mainstream H.264 HD/FHD server
        // 1. Prefer standard H.264 HD (720p) or Auto
        val hdIdx = servers.indexOfFirst { srv ->
            srv.streamUrl.isNotBlank() && isStandardHdOrAuto(srv) && (!deviceCapabilities.isLowRamDevice || !is1080pFhd(srv))
        }
        if (hdIdx >= 0) return hdIdx

        // 2. On non-low-RAM devices, allow standard H.264 FHD (1080p) before dropping to SD
        if (!deviceCapabilities.isLowRamDevice) {
            val fhdIdx = servers.indexOfFirst { srv ->
                srv.streamUrl.isNotBlank() && !isHevc(srv) && !is4kUhd(srv) && is1080pFhd(srv)
            }
            if (fhdIdx >= 0) return fhdIdx
        }

        // 3. Any non-HEVC, non-4K server with a valid URL that fits RAM budget (e.g. SD / 480p / Server 2)
        val safeRamAwareH264Idx = servers.indexOfFirst { srv ->
            srv.streamUrl.isNotBlank() &&
                !isHevc(srv) &&
                !is4kUhd(srv) &&
                (!deviceCapabilities.isLowRamDevice || !is1080pFhd(srv))
        }
        if (safeRamAwareH264Idx >= 0) return safeRamAwareH264Idx

        val safeH264Idx = servers.indexOfFirst { srv ->
            srv.streamUrl.isNotBlank() && !isHevc(srv) && !is4kUhd(srv)
        }
        if (safeH264Idx >= 0) return safeH264Idx

        // 4. If hardware supports HEVC and only HEVC vs 4K remains, pick non-4K HEVC
        if (deviceCapabilities.supportsHardwareHevc) {
            val hevcIdx = servers.indexOfFirst { srv ->
                srv.streamUrl.isNotBlank() && !is4kUhd(srv)
            }
            if (hevcIdx >= 0) return hevcIdx
        }

        return firstValidIdx
    }
}
