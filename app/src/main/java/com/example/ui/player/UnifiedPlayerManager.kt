package com.example.ui.player

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.trackselection.AdaptiveTrackSelection
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Pure-Kotlin lifecycle state tracker for the single-engine playback session.
 * Verifies and enforces that:
 * 1. Switching between Home Live Channels, IPTV Live Channels, and Servers reuses the SAME session without releasing the player.
 * 2. `release()` is idempotent and any post-release operation is safely blocked and counted without throwing `IllegalStateException`.
 * 3. True Adaptive Bitrate (ABR) is reported only when the active source is multi-variant HLS (`StreamSourceType.HLS` with >1 video tracks),
 *    never falsely claimed for fixed-bitrate `MPEG-TS` streams.
 */
class PlayerSessionLifecycleController(
    val deviceCapabilities: DeviceCodecCapabilities = DeviceCodecCapabilities()
) {
    private val released = AtomicBoolean(false)
    private val streamLoadCounter = AtomicInteger(0)
    private val channelSwitchCounter = AtomicInteger(0)
    private val releaseCallCounter = AtomicInteger(0)
    private val ignoredPostReleaseCounter = AtomicInteger(0)

    var activeChannelId: String = ""
        private set
    var activeUrl: String = ""
        private set
    var activeServerIndex: Int = 0
        private set
    var activeSourceType: StreamSourceType = StreamSourceType.HLS
        private set
    var activeIsLive: Boolean = true
        private set

    fun isReleased(): Boolean = released.get()

    fun getStreamLoadCount(): Int = streamLoadCounter.get()

    fun getChannelSwitchCount(): Int = channelSwitchCounter.get()

    fun getReleaseCallCount(): Int = releaseCallCounter.get()

    fun getIgnoredPostReleaseCallCount(): Int = ignoredPostReleaseCounter.get()

    /**
     * Records a stream load or channel/server transition if the session is still active.
     * Returns `null` if the player has already been released.
     */
    fun beginStreamLoad(
        channelId: String = "",
        url: String,
        serverIndex: Int,
        serverStreamTypeHint: String = "",
        serverNameHint: String = "",
        isLiveChannel: Boolean
    ): StreamSourceType? {
        if (released.get()) {
            ignoredPostReleaseCounter.incrementAndGet()
            return null
        }

        val cleanUrl = url.trim()
        val resolvedType = StreamSourceResolver.detectStreamSourceType(
            url = cleanUrl,
            serverStreamTypeHint = serverStreamTypeHint,
            serverNameHint = serverNameHint,
            isLiveChannel = isLiveChannel
        )

        if (activeChannelId.isNotEmpty() && channelId.isNotEmpty() && activeChannelId != channelId) {
            channelSwitchCounter.incrementAndGet()
        }
        if (channelId.isNotEmpty()) {
            activeChannelId = channelId
        }
        activeUrl = cleanUrl
        activeServerIndex = serverIndex
        activeSourceType = resolvedType
        activeIsLive = isLiveChannel
        streamLoadCounter.incrementAndGet()
        return resolvedType
    }

    /**
     * Computes the optimal auto-quality ceiling based on hardware decoder capabilities (`DeviceCodecCapabilities`).
     * - Strong 4K-capable devices: no artificial ceiling (`maxWidth = Int.MAX_VALUE, maxHeight = Int.MAX_VALUE`).
     * - Standard 1080p devices without 4K hardware decoder: `1920x1080`.
     * - Low-RAM devices: `1280x720` to prevent decoder/buffer OOM.
     */
    fun resolveAutoQualityMaxVideoSize(
        isDataSaver: Boolean,
        downgradeBitrateCap: Boolean
    ): Triple<Int, Int, Int> {
        return when {
            isDataSaver -> Triple(854, 480, 1_100_000)
            downgradeBitrateCap || deviceCapabilities.isLowRamDevice -> Triple(1280, 720, 2_800_000)
            !deviceCapabilities.supportsHardware4k -> Triple(1920, 1080, Int.MAX_VALUE)
            else -> Triple(Int.MAX_VALUE, Int.MAX_VALUE, Int.MAX_VALUE)
        }
    }

    /**
     * Returns `true` ONLY when the stream is HLS and exposes multiple video quality representations.
     * Single-bitrate `MPEG-TS` streams do NOT support internal ABR and must not claim to do so.
     */
    fun isTrueAdaptiveBitrateActive(discoveredVideoTrackCount: Int): Boolean {
        return activeSourceType == StreamSourceType.HLS && discoveredVideoTrackCount > 1
    }

    fun guardActiveCall(): Boolean {
        if (released.get()) {
            ignoredPostReleaseCounter.incrementAndGet()
            return false
        }
        return true
    }

    /**
     * Marks the session as released exactly once. Returns `true` on the first release call, `false` on subsequent calls.
     */
    fun markReleased(): Boolean {
        return if (released.compareAndSet(false, true)) {
            releaseCallCounter.incrementAndGet()
            true
        } else {
            ignoredPostReleaseCounter.incrementAndGet()
            false
        }
    }
}

/**
 * Centralized, single-engine Media3 [ExoPlayer] controller for FOUL TV.
 *
 * Guarantees:
 * - Only ONE active [ExoPlayer] instance exists during a playback session.
 * - Channel switching (Home <-> IPTV) and server switching reuse the SAME [ExoPlayer] instance
 *   (`stop()` -> `clearMediaItems()` -> `setMediaSource()` -> `prepare()`),
 *   preventing decoder/surface recreation overhead and eliminating use-after-release bugs.
 * - Balanced [DefaultLoadControl] tuned for fast initial startup (1.2s bufferForPlayback) and smooth anti-jitter buffering (12s–36s).
 * - Capability-aware [DefaultTrackSelector] that unlocks full multi-variant HLS ABR up to device hardware limits
 *   without imposing an artificial 1080p ceiling on 4K-capable devices.
 * - Thread-safe `isReleased` guard via [sessionController] so no late coroutine or callback ever invokes methods on a released player.
 */
@OptIn(UnstableApi::class)
class UnifiedPlayerManager internal constructor(
    context: Context?,
    customCapabilities: DeviceCodecCapabilities? = null,
    customExoPlayer: ExoPlayer? = null,
    private val customMediaSourceProvider: ((url: String, sourceType: StreamSourceType, isLiveChannel: Boolean) -> MediaSource)? = null
) {
    constructor(context: Context) : this(
        context = context,
        customCapabilities = null,
        customExoPlayer = null,
        customMediaSourceProvider = null
    )

    private val appContext: Context? = context?.applicationContext
    val deviceCapabilities: DeviceCodecCapabilities =
        customCapabilities ?: StreamSourceResolver.queryDeviceCapabilities(appContext)
    val sessionController = PlayerSessionLifecycleController(deviceCapabilities)

    val activeUrl: String
        get() = sessionController.activeUrl
    val activeServerIndex: Int
        get() = sessionController.activeServerIndex
    val activeSourceType: StreamSourceType
        get() = sessionController.activeSourceType
    val activeIsLive: Boolean
        get() = sessionController.activeIsLive

    private val analyticsListener = object : AnalyticsListener {
        override fun onLoadStarted(
            eventTime: AnalyticsListener.EventTime,
            loadEventInfo: LoadEventInfo,
            mediaLoadData: MediaLoadData
        ) {
            if (mediaLoadData.dataType == C.DATA_TYPE_MEDIA) {
                PlayerDiagnostics.record(
                    event = PlayerDiagnosticEvent.SEGMENT_LOADING,
                    rawUrl = loadEventInfo.uri.toString(),
                    serverIndex = activeServerIndex,
                    sourceType = activeSourceType.label,
                    details = "trackType=${mediaLoadData.trackType}"
                )
            }
        }

        override fun onLoadCompleted(
            eventTime: AnalyticsListener.EventTime,
            loadEventInfo: LoadEventInfo,
            mediaLoadData: MediaLoadData
        ) {
            if (mediaLoadData.dataType == C.DATA_TYPE_MANIFEST) {
                PlayerDiagnostics.record(
                    event = PlayerDiagnosticEvent.PLAYLIST_LOADED,
                    rawUrl = loadEventInfo.uri.toString(),
                    serverIndex = activeServerIndex,
                    sourceType = activeSourceType.label,
                    details = "durationMs=${loadEventInfo.loadDurationMs} bytes=${loadEventInfo.bytesLoaded}"
                )
            }
        }

        override fun onLoadError(
            eventTime: AnalyticsListener.EventTime,
            loadEventInfo: LoadEventInfo,
            mediaLoadData: MediaLoadData,
            error: IOException,
            wasCanceled: Boolean
        ) {
            if (!wasCanceled) {
                PlayerDiagnostics.record(
                    event = PlayerDiagnosticEvent.SEGMENT_FAILED,
                    rawUrl = loadEventInfo.uri.toString(),
                    serverIndex = activeServerIndex,
                    sourceType = activeSourceType.label,
                    details = "dataType=${mediaLoadData.dataType} err=${error.javaClass.simpleName}: ${error.message.orEmpty().take(120)}"
                )
            }
        }
    }

    private val dataSourceFactory: DefaultDataSource.Factory? = appContext?.let { ctx ->
        val okHttpFactory = OkHttpStreamDataSource.Factory(
            callFactory = StreamOkHttpClientProvider.sharedClient,
            userAgent = StreamOkHttpClientProvider.defaultUserAgent,
            onHttpCodeError = { statusCode, errorUrl ->
                PlayerDiagnostics.record(
                    event = PlayerDiagnosticEvent.HTTP_ERROR,
                    rawUrl = errorUrl,
                    serverIndex = activeServerIndex,
                    sourceType = activeSourceType.label,
                    details = "HTTP response status $statusCode"
                )
            }
        )
        DefaultDataSource.Factory(ctx, okHttpFactory)
    }

    val exoPlayer: ExoPlayer = customExoPlayer ?: run {
        val ctx = requireNotNull(appContext) { "Context is required when customExoPlayer is not provided" }
        val selector = DefaultTrackSelector(
            ctx,
            AdaptiveTrackSelection.Factory(
                /* minDurationForQualityIncreaseMs = */ 6_000,
                /* maxDurationForQualityDecreaseMs = */ 8_000,
                /* minDurationToRetainAfterDiscardMs = */ 12_000,
                /* bandwidthFraction = */ 0.78f
            )
        ).apply {
            val (maxW, maxH, maxBr) = sessionController.resolveAutoQualityMaxVideoSize(
                isDataSaver = false,
                downgradeBitrateCap = false
            )
            val paramsBuilder = buildUponParameters()
                .setAllowVideoMixedMimeTypeAdaptiveness(true)
                .setAllowVideoNonSeamlessAdaptiveness(true)
                .setAllowAudioMixedMimeTypeAdaptiveness(true)
                .setAllowAudioMixedSampleRateAdaptiveness(true)
                .setExceedRendererCapabilitiesIfNecessary(false)
                .setExceedVideoConstraintsIfNecessary(true)

            if (maxW != Int.MAX_VALUE && maxH != Int.MAX_VALUE) {
                paramsBuilder.setMaxVideoSize(maxW, maxH)
            } else {
                paramsBuilder.clearVideoSizeConstraints()
            }
            paramsBuilder.setMaxVideoBitrate(maxBr)
            setParameters(paramsBuilder)
        }

        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                /* minBufferMs = */ 12_000,
                /* maxBufferMs = */ 36_000,
                /* bufferForPlaybackMs = */ 1_200,
                /* bufferForPlaybackAfterRebufferMs = */ 2_500
            )
            .setTargetBufferBytes(24 * 1024 * 1024)
            .setPrioritizeTimeOverSizeThresholds(true)
            .setBackBuffer(
                /* backBufferDurationMs = */ 8_000,
                /* retainBackBufferFromKeyframe = */ false
            )
            .build()

        val renderersFactory = DefaultRenderersFactory(ctx)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)
            .setEnableDecoderFallback(true)

        ExoPlayer.Builder(ctx, renderersFactory)
            .setTrackSelector(selector)
            .setLoadControl(loadControl)
            .setSeekParameters(SeekParameters.CLOSEST_SYNC)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .build(),
                true
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()
            .apply {
                playWhenReady = true
                addAnalyticsListener(analyticsListener)
            }
    }

    fun isReleased(): Boolean = sessionController.isReleased()

    fun isTrueAdaptiveBitrateActive(discoveredVideoTrackCount: Int): Boolean {
        return sessionController.isTrueAdaptiveBitrateActive(discoveredVideoTrackCount)
    }

    /**
     * Loads and prepares a stream URL on the single [ExoPlayer] instance without recreating the player.
     * Cleanly aborts any previous network load before starting the new MediaSource.
     */
    fun loadStream(
        channelId: String = "",
        url: String,
        serverIndex: Int,
        serverStreamTypeHint: String = "",
        serverNameHint: String = "",
        isLiveChannel: Boolean,
        startPositionMs: Long = C.TIME_UNSET,
        playImmediately: Boolean = true,
        downgradeBitrateCap: Boolean = false
    ): StreamSourceType {
        val resolvedType = sessionController.beginStreamLoad(
            channelId = channelId,
            url = url,
            serverIndex = serverIndex,
            serverStreamTypeHint = serverStreamTypeHint,
            serverNameHint = serverNameHint,
            isLiveChannel = isLiveChannel
        ) ?: return activeSourceType

        val cleanUrl = url.trim()

        PlayerDiagnostics.record(
            event = PlayerDiagnosticEvent.PLAY_REQUESTED,
            rawUrl = cleanUrl,
            serverIndex = serverIndex,
            sourceType = resolvedType.label,
            details = "isLive=$isLiveChannel downgradeCap=$downgradeBitrateCap"
        )

        // If recovery requested a safer bitrate cap (e.g. after a stall or decoder hiccup),
        // constrain video selection to 720p HD while keeping adaptive switching active.
        if (downgradeBitrateCap) {
            val (maxW, maxH, maxBr) = sessionController.resolveAutoQualityMaxVideoSize(
                isDataSaver = false,
                downgradeBitrateCap = true
            )
            exoPlayer.trackSelectionParameters = exoPlayer.trackSelectionParameters
                .buildUpon()
                .setMaxVideoSize(maxW, maxH)
                .setMaxVideoBitrate(maxBr)
                .build()
        }

        // Stop and clear any previous stream cleanly before setting the new MediaSource
        exoPlayer.stop()
        exoPlayer.clearMediaItems()

        if (isLiveChannel && exoPlayer.playbackParameters.speed != 1.0f) {
            exoPlayer.playbackParameters = PlaybackParameters(1.0f)
        }

        val mediaSource = customMediaSourceProvider?.invoke(cleanUrl, resolvedType, isLiveChannel)
            ?: StreamSourceResolver.createMediaSource(
                url = cleanUrl,
                sourceType = resolvedType,
                isLiveChannel = isLiveChannel,
                dataSourceFactory = requireNotNull(dataSourceFactory)
            )

        if (startPositionMs != C.TIME_UNSET && startPositionMs > 0L && !isLiveChannel) {
            exoPlayer.setMediaSource(mediaSource, startPositionMs)
        } else {
            exoPlayer.setMediaSource(mediaSource, /* resetPosition = */ true)
        }

        exoPlayer.playWhenReady = playImmediately
        exoPlayer.prepare()
        return resolvedType
    }

    /**
     * Performs a fast, non-destructive live-edge resync without tearing down the MediaSource.
     */
    fun seekToLiveEdgeNonDestructive() {
        if (!sessionController.guardActiveCall()) return
        if (exoPlayer.isCurrentMediaItemLive || activeIsLive) {
            exoPlayer.seekToDefaultPosition()
        }
        exoPlayer.playWhenReady = true
        if (exoPlayer.playbackState == Player.STATE_IDLE) {
            exoPlayer.prepare()
        } else {
            exoPlayer.play()
        }
    }

    /**
     * Applies user-selected adaptive quality / data-saving mode to the underlying [ExoPlayer],
     * respecting device hardware capabilities without imposing an artificial 1080p ceiling on 4K-capable devices.
     */
    fun applyQualityMode(
        isAutoQuality: Boolean,
        isDataSaver: Boolean,
        manualTrackModification: ((TrackSelectionParameters.Builder) -> Unit)? = null
    ) {
        if (!sessionController.guardActiveCall()) return
        val builder = exoPlayer.trackSelectionParameters.buildUpon()
        builder.clearOverridesOfType(C.TRACK_TYPE_VIDEO)

        when {
            manualTrackModification != null -> {
                manualTrackModification(builder)
            }
            isDataSaver -> {
                val (maxW, maxH, maxBr) = sessionController.resolveAutoQualityMaxVideoSize(
                    isDataSaver = true,
                    downgradeBitrateCap = false
                )
                builder.setMaxVideoSize(maxW, maxH)
                builder.setMaxVideoBitrate(maxBr)
            }
            isAutoQuality -> {
                val (maxW, maxH, maxBr) = sessionController.resolveAutoQualityMaxVideoSize(
                    isDataSaver = false,
                    downgradeBitrateCap = false
                )
                if (maxW != Int.MAX_VALUE && maxH != Int.MAX_VALUE) {
                    builder.setMaxVideoSize(maxW, maxH)
                } else {
                    builder.clearVideoSizeConstraints()
                }
                builder.setMaxVideoBitrate(maxBr)
            }
        }
        exoPlayer.trackSelectionParameters = builder.build()
    }

    /**
     * Safely releases the [ExoPlayer] once and only once when the player screen exits.
     */
    fun release() {
        if (sessionController.markReleased()) {
            try {
                exoPlayer.removeAnalyticsListener(analyticsListener)
                exoPlayer.stop()
                exoPlayer.clearMediaItems()
                exoPlayer.release()
            } catch (_: Throwable) {
            }
        }
    }
}
