package com.example.ui.player

import androidx.annotation.OptIn
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.hls.playlist.HlsPlaylistParser
import androidx.media3.exoplayer.hls.playlist.HlsPlaylistTracker

/**
 * Classification of playback errors to decide the fastest and least disruptive recovery action.
 */
enum class StreamFailureCategory {
    BEHIND_LIVE_WINDOW,
    HTTP_FATAL_STATUS,
    HTTP_TRANSIENT_STATUS,
    PLAYLIST_OR_SEGMENT_ERROR,
    NETWORK_OR_TIMEOUT,
    DECODER_ERROR,
    SOURCE_UNSUPPORTED,
    UNKNOWN_ERROR
}

/**
 * Action returned by [PlaybackRecoveryManager] upon error or watchdog stall detection.
 */
sealed class RecoveryDecision {
    /** No intervention required; playback is healthy, within normal loading window, or a recovery is already in flight. */
    data object None : RecoveryDecision()

    /** Fast seek to live default position and play without rebuilding the MediaSource. */
    data class SeekToDefaultLiveEdge(val reason: String) : RecoveryDecision()

    /** Reload the current server's MediaSource after [delayMs] backoff. */
    data class ReloadCurrentServer(
        val attemptNumber: Int,
        val delayMs: Long,
        val reason: String,
        val downgradeBitrateHint: Boolean = false
    ) : RecoveryDecision()

    /** Switch immediately (or after a short delay) to the next untried server at [nextServerIndex]. */
    data class SwitchToNextServer(
        val nextServerIndex: Int,
        val delayMs: Long,
        val reason: String
    ) : RecoveryDecision()

    /** All available servers and bounded retries have been exhausted. Present final error UI. */
    data class AllServersExhausted(
        val userMessageAr: String,
        val category: StreamFailureCategory
    ) : RecoveryDecision()
}

/**
 * Snapshot of current player state evaluated periodically by the non-destructive Watchdog.
 */
data class WatchdogPlayerSnapshot(
    val playbackState: Int,
    val playWhenReady: Boolean,
    val isPlaying: Boolean,
    val isLoading: Boolean,
    val currentPositionMs: Long,
    val bufferedPositionMs: Long,
    val isLiveChannel: Boolean,
    val currentServerIndex: Int,
    val totalServersCount: Int,
    val currentUrl: String,
    val sourceType: StreamSourceType,
    val nowRealtimeMs: Long
)

/**
 * Non-destructive Watchdog and Graduated Server-Failover Manager for the Unified Media3 Engine.
 *
 * Responsibilities:
 * 1. Distinguishes slow network loading (buffer actively advancing) from dead/stalled streams.
 * 2. Prevents concurrent or duplicate recovery triggers while an active recovery backoff/execution is in flight.
 * 3. Performs non-destructive recovery first (live edge seek / play nudge) before rebuilding MediaSource.
 * 4. Re-routes extensionless URLs if `OkHttpStreamDataSource` observed a different `Content-Type` header.
 * 5. Performs bounded retries on the current server with exponential backoff (1.4s -> 2.8s).
 * 6. Fails over immediately to the next server on HTTP 401/403/404/410 or unsupported container errors.
 * 7. Tracks visited servers (`triedServerIndices`) so failover never enters an infinite loop.
 */
@OptIn(UnstableApi::class)
class PlaybackRecoveryManager(
    private val maxRetriesPerServer: Int = 2
) {
    companion object {
        /** Aligned above StreamOkHttpClientProvider.READ_TIMEOUT_MS (20_000ms) so Watchdog never preempts an active OkHttp read. */
        const val INITIAL_LOADING_FIRST_BYTE_TIMEOUT_MS: Long = 22_000L
        const val INITIAL_IDLE_NO_BYTE_TIMEOUT_MS: Long = 14_000L
        const val ACTIVE_SLOW_BUFFER_MAX_WAIT_MS: Long = 28_000L
        const val ACTIVE_LOADING_STALL_TIMEOUT_MS: Long = 21_000L
        const val IDLE_BUFFER_STALL_LIVE_MS: Long = 11_000L
        const val IDLE_BUFFER_STALL_VOD_MS: Long = 14_000L
        const val READY_POSITION_FROZEN_LIVE_MS: Long = 9_000L
        const val READY_POSITION_FROZEN_VOD_MS: Long = 12_000L
        const val POST_DISPATCH_COOLDOWN_MS: Long = 500L
    }

    private var currentServerIndex: Int = 0
    private var totalServersCount: Int = 1
    private val triedServerIndices = LinkedHashSet<Int>()

    private var retryAttemptsOnCurrentServer: Int = 0
    private var nonDestructiveNudgeDoneOnCurrentServer: Boolean = false
    private var contentTypeReRouteDoneOnCurrentServer: Boolean = false
    private var pendingRecoveryInProgress: Boolean = false

    // Stream progress phase flags on the current server
    private var hasReceivedFirstByteOnCurrentServer: Boolean = false
    private var hasRenderedFirstFrameOnCurrentServer: Boolean = false

    // Concurrency & duplicate-recovery lock
    private var activeRecoveryInFlight: Boolean = false
    private var activeRecoveryLockUntilRealtimeMs: Long = 0L
    private var lastRecoveryDispatchRealtimeMs: Long = -10_000L

    private var lastAdvancedPositionMs: Long = -1L
    private var lastPositionAdvanceRealtimeMs: Long = 0L
    private var lastBufferedPositionMs: Long = -1L
    private var lastBufferAdvanceRealtimeMs: Long = 0L
    private var bufferingStartedRealtimeMs: Long = 0L

    /**
     * Resets state when a new channel is opened or when the user manually selects a server.
     */
    fun onChannelOrManualServerSelected(
        initialServerIndex: Int,
        totalServers: Int,
        nowRealtimeMs: Long = System.currentTimeMillis()
    ) {
        currentServerIndex = initialServerIndex.coerceAtLeast(0)
        totalServersCount = totalServers.coerceAtLeast(1)
        triedServerIndices.clear()
        triedServerIndices.add(currentServerIndex)
        resetCurrentServerCounters(nowRealtimeMs)
    }

    /**
     * Updates state when the manager automatically switches to a fallback server.
     */
    fun onAutoServerSwitched(
        newServerIndex: Int,
        totalServers: Int,
        nowRealtimeMs: Long = System.currentTimeMillis()
    ) {
        currentServerIndex = newServerIndex.coerceAtLeast(0)
        totalServersCount = totalServers.coerceAtLeast(1)
        triedServerIndices.add(currentServerIndex)
        resetCurrentServerCounters(nowRealtimeMs)
    }

    /**
     * Called when the player reaches `STATE_READY` so in-flight recovery locks are released cleanly.
     */
    fun onPlaybackReady(
        nowRealtimeMs: Long = System.currentTimeMillis(),
        currentUrl: String = "",
        sourceType: StreamSourceType = StreamSourceType.HLS
    ) {
        hasReceivedFirstByteOnCurrentServer = true
        hasRenderedFirstFrameOnCurrentServer = true
        activeRecoveryInFlight = false
        activeRecoveryLockUntilRealtimeMs = 0L
        bufferingStartedRealtimeMs = 0L
        lastPositionAdvanceRealtimeMs = nowRealtimeMs
        lastBufferAdvanceRealtimeMs = nowRealtimeMs

        if (pendingRecoveryInProgress) {
            pendingRecoveryInProgress = false
            retryAttemptsOnCurrentServer = 0
            PlayerDiagnostics.record(
                event = PlayerDiagnosticEvent.RECOVERY_SUCCESS,
                rawUrl = currentUrl,
                serverIndex = currentServerIndex,
                sourceType = sourceType.label,
                details = "Player reached STATE_READY after recovery"
            )
        }
    }

    /**
     * Marks that a scheduled recovery coroutine has dispatched `loadStream` on the player,
     * releasing the pre-dispatch lock while setting a brief post-dispatch cooldown so stale
     * callbacks from the aborted load cannot trigger a duplicate recovery.
     */
    fun onRecoveryDispatched(nowRealtimeMs: Long = System.currentTimeMillis()) {
        activeRecoveryInFlight = false
        activeRecoveryLockUntilRealtimeMs = 0L
        lastRecoveryDispatchRealtimeMs = nowRealtimeMs
        bufferingStartedRealtimeMs = nowRealtimeMs
        lastBufferAdvanceRealtimeMs = nowRealtimeMs
        lastPositionAdvanceRealtimeMs = nowRealtimeMs
    }

    fun isRecoveryLocked(nowRealtimeMs: Long = System.currentTimeMillis()): Boolean {
        if (activeRecoveryInFlight && nowRealtimeMs < activeRecoveryLockUntilRealtimeMs) {
            return true
        }
        if (nowRealtimeMs >= lastRecoveryDispatchRealtimeMs &&
            (nowRealtimeMs - lastRecoveryDispatchRealtimeMs) < POST_DISPATCH_COOLDOWN_MS
        ) {
            return true
        }
        return false
    }

    private fun acquireRecoveryLock(nowRealtimeMs: Long, delayMs: Long) {
        pendingRecoveryInProgress = true
        activeRecoveryInFlight = true
        // Lock covers the scheduled delayMs plus a 2500ms safety window until onRecoveryDispatched/onPlaybackReady is called
        activeRecoveryLockUntilRealtimeMs = nowRealtimeMs + delayMs + 2_500L
    }

    private fun resetCurrentServerCounters(nowRealtimeMs: Long) {
        retryAttemptsOnCurrentServer = 0
        nonDestructiveNudgeDoneOnCurrentServer = false
        contentTypeReRouteDoneOnCurrentServer = false
        pendingRecoveryInProgress = false
        hasReceivedFirstByteOnCurrentServer = false
        hasRenderedFirstFrameOnCurrentServer = false
        activeRecoveryInFlight = false
        activeRecoveryLockUntilRealtimeMs = 0L
        lastRecoveryDispatchRealtimeMs = -10_000L
        lastAdvancedPositionMs = -1L
        lastPositionAdvanceRealtimeMs = nowRealtimeMs
        lastBufferedPositionMs = -1L
        lastBufferAdvanceRealtimeMs = nowRealtimeMs
        bufferingStartedRealtimeMs = 0L
    }

    /**
     * Classifies a Media3 [PlaybackException] into a [StreamFailureCategory] and extracts HTTP status if present.
     */
    fun classifyException(error: PlaybackException): Pair<StreamFailureCategory, Int?> {
        return classifyErrorDetails(
            errorCode = error.errorCode,
            cause = error.cause,
            message = error.message
        )
    }

    /**
     * Classifies raw error details (`errorCode`, `cause`, `message`) into a [StreamFailureCategory] and optional HTTP status code.
     */
    fun classifyErrorDetails(
        errorCode: Int,
        cause: Throwable? = null,
        message: String? = null
    ): Pair<StreamFailureCategory, Int?> {
        if (errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
            return StreamFailureCategory.BEHIND_LIVE_WINDOW to null
        }

        // Inspect cause chain for HTTP status codes or HLS playlist specific exceptions
        var currentCause: Throwable? = cause
        var httpStatusCode: Int? = null
        var isPlaylistError = false
        while (currentCause != null) {
            if (currentCause is HttpDataSource.InvalidResponseCodeException) {
                httpStatusCode = currentCause.responseCode
                break
            }
            if (
                currentCause is HlsPlaylistTracker.PlaylistStuckException ||
                currentCause is HlsPlaylistTracker.PlaylistResetException ||
                currentCause is HlsPlaylistParser.DeltaUpdateException
            ) {
                isPlaylistError = true
            }
            currentCause = currentCause.cause
        }

        if (httpStatusCode == null && errorCode == PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS) {
            val match = Regex("\\b(4\\d{2}|5\\d{2})\\b").find(message.orEmpty())
            httpStatusCode = match?.groupValues?.getOrNull(1)?.toIntOrNull()
        }

        if (httpStatusCode != null) {
            return when (httpStatusCode) {
                400, 401, 403, 404, 405, 410, 451 ->
                    StreamFailureCategory.HTTP_FATAL_STATUS to httpStatusCode
                408, 429, 500, 502, 503, 504 ->
                    StreamFailureCategory.HTTP_TRANSIENT_STATUS to httpStatusCode
                else ->
                    StreamFailureCategory.HTTP_TRANSIENT_STATUS to httpStatusCode
            }
        }

        if (isPlaylistError) {
            return StreamFailureCategory.PLAYLIST_OR_SEGMENT_ERROR to null
        }

        val category = when (errorCode) {
            PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
            PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
            PlaybackException.ERROR_CODE_DECODING_FAILED,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
            PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED,
            PlaybackException.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED ->
                StreamFailureCategory.DECODER_ERROR

            PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
            PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED ->
                StreamFailureCategory.SOURCE_UNSUPPORTED

            PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED,
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED ->
                StreamFailureCategory.PLAYLIST_OR_SEGMENT_ERROR

            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
            PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
            PlaybackException.ERROR_CODE_IO_CLEARTEXT_NOT_PERMITTED,
            PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND,
            PlaybackException.ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE,
            PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE,
            PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
            PlaybackException.ERROR_CODE_TIMEOUT ->
                StreamFailureCategory.NETWORK_OR_TIMEOUT

            else -> StreamFailureCategory.UNKNOWN_ERROR
        }

        return category to null
    }

    /**
     * Handles an explicit [PlaybackException] from ExoPlayer and returns the appropriate [RecoveryDecision].
     */
    fun onPlayerError(
        error: PlaybackException,
        currentUrl: String,
        sourceType: StreamSourceType,
        isLiveChannel: Boolean,
        totalServers: Int,
        nowRealtimeMs: Long = System.currentTimeMillis()
    ): RecoveryDecision {
        return onPlayerErrorDetails(
            errorCode = error.errorCode,
            errorCodeName = error.errorCodeName,
            cause = error.cause,
            message = error.message,
            currentUrl = currentUrl,
            sourceType = sourceType,
            isLiveChannel = isLiveChannel,
            totalServers = totalServers,
            nowRealtimeMs = nowRealtimeMs
        )
    }

    /**
     * Handles raw error details and returns the appropriate [RecoveryDecision].
     * Guaranteed not to trigger overlapping or duplicate recovery decisions if a recovery is already locked in flight.
     */
    fun onPlayerErrorDetails(
        errorCode: Int,
        errorCodeName: String = "ERROR_$errorCode",
        cause: Throwable? = null,
        message: String? = null,
        currentUrl: String,
        sourceType: StreamSourceType,
        isLiveChannel: Boolean,
        totalServers: Int,
        nowRealtimeMs: Long = System.currentTimeMillis()
    ): RecoveryDecision {
        totalServersCount = totalServers.coerceAtLeast(1)

        // Concurrency guard: ignore duplicate error callbacks while a recovery backoff/switch is already counting down
        if (isRecoveryLocked(nowRealtimeMs)) {
            return RecoveryDecision.None
        }

        val (category, httpCode) = classifyErrorDetails(errorCode, cause, message)

        // Log diagnostic event based on error category
        when (category) {
            StreamFailureCategory.HTTP_FATAL_STATUS,
            StreamFailureCategory.HTTP_TRANSIENT_STATUS -> {
                PlayerDiagnostics.record(
                    event = PlayerDiagnosticEvent.HTTP_ERROR,
                    rawUrl = currentUrl,
                    serverIndex = currentServerIndex,
                    sourceType = sourceType.label,
                    details = "HTTP $httpCode ($errorCodeName)"
                )
            }

            StreamFailureCategory.DECODER_ERROR -> {
                PlayerDiagnostics.record(
                    event = PlayerDiagnosticEvent.DECODER_ERROR,
                    rawUrl = currentUrl,
                    serverIndex = currentServerIndex,
                    sourceType = sourceType.label,
                    details = errorCodeName
                )
            }

            StreamFailureCategory.NETWORK_OR_TIMEOUT,
            StreamFailureCategory.PLAYLIST_OR_SEGMENT_ERROR -> {
                PlayerDiagnostics.record(
                    event = PlayerDiagnosticEvent.NETWORK_ERROR,
                    rawUrl = currentUrl,
                    serverIndex = currentServerIndex,
                    sourceType = sourceType.label,
                    details = "${category.name}: $errorCodeName"
                )
            }

            else -> {
                PlayerDiagnostics.record(
                    event = PlayerDiagnosticEvent.PLAYER_ERROR,
                    rawUrl = currentUrl,
                    serverIndex = currentServerIndex,
                    sourceType = sourceType.label,
                    details = "${category.name}: $errorCodeName"
                )
            }
        }

        // 1. Behind Live Window -> Fast seek to default live position without rebuilding MediaSource
        if (category == StreamFailureCategory.BEHIND_LIVE_WINDOW) {
            acquireRecoveryLock(nowRealtimeMs, delayMs = 250L)
            PlayerDiagnostics.record(
                event = PlayerDiagnosticEvent.RECOVERY_ATTEMPT,
                rawUrl = currentUrl,
                serverIndex = currentServerIndex,
                sourceType = sourceType.label,
                details = "SeekToDefaultLiveEdge (BEHIND_LIVE_WINDOW)"
            )
            return RecoveryDecision.SeekToDefaultLiveEdge("إعادة مزامنة البث المباشر")
        }

        // 2. If an extensionless URL failed container/manifest parsing AND OkHttpStreamDataSource observed
        // a concrete Content-Type that differs from the initial sourceType, reload once with the learned Content-Type!
        if (
            (category == StreamFailureCategory.SOURCE_UNSUPPORTED ||
                category == StreamFailureCategory.PLAYLIST_OR_SEGMENT_ERROR) &&
            !contentTypeReRouteDoneOnCurrentServer
        ) {
            val observedType = StreamSourceResolver.getObservedSourceType(currentUrl)
            if (observedType != null && observedType != sourceType) {
                contentTypeReRouteDoneOnCurrentServer = true
                val delayMs = 200L
                acquireRecoveryLock(nowRealtimeMs, delayMs)
                PlayerDiagnostics.record(
                    event = PlayerDiagnosticEvent.RECOVERY_ATTEMPT,
                    rawUrl = currentUrl,
                    serverIndex = currentServerIndex,
                    sourceType = observedType.label,
                    details = "Re-routing source from ${sourceType.label} to ${observedType.label} based on HTTP Content-Type"
                )
                return RecoveryDecision.ReloadCurrentServer(
                    attemptNumber = retryAttemptsOnCurrentServer + 1,
                    delayMs = delayMs,
                    reason = "ضبط نوع المصدر تلقائياً (${observedType.label})...",
                    downgradeBitrateHint = false
                )
            }
        }

        // 3. Fatal HTTP status (401/403/404/410) or Unsupported Container -> Skip retries on this broken URL and switch server immediately!
        if (
            category == StreamFailureCategory.HTTP_FATAL_STATUS ||
            category == StreamFailureCategory.SOURCE_UNSUPPORTED
        ) {
            val nextServer = findNextUntriedServerIndex()
            if (nextServer != null) {
                val delayMs = 350L
                acquireRecoveryLock(nowRealtimeMs, delayMs)
                PlayerDiagnostics.record(
                    event = PlayerDiagnosticEvent.SERVER_SWITCHED,
                    rawUrl = currentUrl,
                    serverIndex = nextServer,
                    sourceType = sourceType.label,
                    details = "Immediate failover from server #${currentServerIndex + 1} to #${nextServer + 1} due to ${category.name} (${httpCode ?: errorCodeName})"
                )
                return RecoveryDecision.SwitchToNextServer(
                    nextServerIndex = nextServer,
                    delayMs = delayMs,
                    reason = "انتقال فوري للسيرفر البديل #${nextServer + 1}"
                )
            }
            activeRecoveryInFlight = false
            PlayerDiagnostics.record(
                event = PlayerDiagnosticEvent.RECOVERY_FAILED,
                rawUrl = currentUrl,
                serverIndex = currentServerIndex,
                sourceType = sourceType.label,
                details = "All servers exhausted after ${category.name} (${httpCode ?: errorCodeName})"
            )
            return RecoveryDecision.AllServersExhausted(
                userMessageAr = buildArabicErrorMessage(category, httpCode, isLiveChannel),
                category = category
            )
        }

        // 4. Decoder Error -> Try 1 reload with lower bitrate cap first, then switch server
        if (category == StreamFailureCategory.DECODER_ERROR) {
            if (retryAttemptsOnCurrentServer < 1) {
                retryAttemptsOnCurrentServer += 1
                val delayMs = 700L
                acquireRecoveryLock(nowRealtimeMs, delayMs)
                PlayerDiagnostics.record(
                    event = PlayerDiagnosticEvent.RECOVERY_ATTEMPT,
                    rawUrl = currentUrl,
                    serverIndex = currentServerIndex,
                    sourceType = sourceType.label,
                    details = "Decoder fallback reload #1 with safe SD/HD track constraint"
                )
                return RecoveryDecision.ReloadCurrentServer(
                    attemptNumber = retryAttemptsOnCurrentServer,
                    delayMs = delayMs,
                    reason = "ضبط ترميز الفيديو وإعادة المحاولة...",
                    downgradeBitrateHint = true
                )
            }
            val nextServer = findNextUntriedServerIndex()
            if (nextServer != null) {
                val delayMs = 450L
                acquireRecoveryLock(nowRealtimeMs, delayMs)
                PlayerDiagnostics.record(
                    event = PlayerDiagnosticEvent.SERVER_SWITCHED,
                    rawUrl = currentUrl,
                    serverIndex = nextServer,
                    sourceType = sourceType.label,
                    details = "Failover to server #${nextServer + 1} after decoder error"
                )
                return RecoveryDecision.SwitchToNextServer(
                    nextServerIndex = nextServer,
                    delayMs = delayMs,
                    reason = "تغيير السيرفر لتوافق جهازك (#${nextServer + 1})"
                )
            }
        }

        // 5. Transient network / timeout / playlist stuck -> Bounded retries with exponential backoff
        if (retryAttemptsOnCurrentServer < maxRetriesPerServer) {
            retryAttemptsOnCurrentServer += 1
            val backoffMs = if (retryAttemptsOnCurrentServer == 1) 1_400L else 2_800L
            acquireRecoveryLock(nowRealtimeMs, backoffMs)
            PlayerDiagnostics.record(
                event = PlayerDiagnosticEvent.RECOVERY_ATTEMPT,
                rawUrl = currentUrl,
                serverIndex = currentServerIndex,
                sourceType = sourceType.label,
                details = "Retry #$retryAttemptsOnCurrentServer/$maxRetriesPerServer on server #${currentServerIndex + 1} in ${backoffMs}ms"
            )
            return RecoveryDecision.ReloadCurrentServer(
                attemptNumber = retryAttemptsOnCurrentServer,
                delayMs = backoffMs,
                reason = "إعادة اتصال تلقائية ($retryAttemptsOnCurrentServer/$maxRetriesPerServer)...",
                downgradeBitrateHint = retryAttemptsOnCurrentServer >= 2
            )
        }

        // 6. Current server retries exhausted -> Switch to next untried server if available
        val nextServer = findNextUntriedServerIndex()
        if (nextServer != null) {
            val delayMs = 500L
            acquireRecoveryLock(nowRealtimeMs, delayMs)
            PlayerDiagnostics.record(
                event = PlayerDiagnosticEvent.SERVER_SWITCHED,
                rawUrl = currentUrl,
                serverIndex = nextServer,
                sourceType = sourceType.label,
                details = "Switching from server #${currentServerIndex + 1} to #${nextServer + 1} after $retryAttemptsOnCurrentServer retries"
            )
            return RecoveryDecision.SwitchToNextServer(
                nextServerIndex = nextServer,
                delayMs = delayMs,
                reason = "جاري التحويل للسيرفر البديل #${nextServer + 1}..."
            )
        }

        // 7. All servers exhausted
        activeRecoveryInFlight = false
        PlayerDiagnostics.record(
            event = PlayerDiagnosticEvent.RECOVERY_FAILED,
            rawUrl = currentUrl,
            serverIndex = currentServerIndex,
            sourceType = sourceType.label,
            details = "All $totalServersCount servers exhausted ($errorCodeName)"
        )
        return RecoveryDecision.AllServersExhausted(
            userMessageAr = buildArabicErrorMessage(category, httpCode, isLiveChannel),
            category = category
        )
    }

    /**
     * Evaluates a periodic watchdog tick (e.g. every 1000ms) and determines whether playback is healthy,
     * actively buffering on a slow network, or genuinely stalled requiring recovery.
     */
    fun evaluateWatchdogTick(snapshot: WatchdogPlayerSnapshot): RecoveryDecision {
        totalServersCount = snapshot.totalServersCount.coerceAtLeast(1)
        val now = snapshot.nowRealtimeMs

        // Do not trigger overlapping recovery while a recovery is already locked in flight
        if (isRecoveryLocked(now)) {
            return RecoveryDecision.None
        }

        // If user paused playback intentionally, keep timestamps fresh and do nothing
        if (!snapshot.playWhenReady) {
            lastPositionAdvanceRealtimeMs = now
            lastBufferAdvanceRealtimeMs = now
            bufferingStartedRealtimeMs = 0L
            return RecoveryDecision.None
        }

        // Check if playback position has advanced
        val positionAdvanced = snapshot.currentPositionMs > 0L &&
            snapshot.currentPositionMs != lastAdvancedPositionMs &&
            kotlin.math.abs(snapshot.currentPositionMs - lastAdvancedPositionMs) >= 120L

        if (positionAdvanced) {
            hasReceivedFirstByteOnCurrentServer = true
            hasRenderedFirstFrameOnCurrentServer = true
            lastAdvancedPositionMs = snapshot.currentPositionMs
            lastPositionAdvanceRealtimeMs = now
            bufferingStartedRealtimeMs = 0L
            nonDestructiveNudgeDoneOnCurrentServer = false
            activeRecoveryInFlight = false
            activeRecoveryLockUntilRealtimeMs = 0L

            if (pendingRecoveryInProgress) {
                pendingRecoveryInProgress = false
                retryAttemptsOnCurrentServer = 0
                PlayerDiagnostics.record(
                    event = PlayerDiagnosticEvent.RECOVERY_SUCCESS,
                    rawUrl = snapshot.currentUrl,
                    serverIndex = currentServerIndex,
                    sourceType = snapshot.sourceType.label,
                    details = "Playback resumed advancing at pos=${snapshot.currentPositionMs}ms"
                )
            }
            return RecoveryDecision.None
        }

        // Track buffer advancement to distinguish slow download from dead socket
        val bufferAdvanced = snapshot.bufferedPositionMs > lastBufferedPositionMs + 200L
        if (bufferAdvanced) {
            if (lastBufferedPositionMs >= 0L || snapshot.bufferedPositionMs > 0L) {
                hasReceivedFirstByteOnCurrentServer = true
            }
            lastBufferedPositionMs = snapshot.bufferedPositionMs
            lastBufferAdvanceRealtimeMs = now
        }

        // Case A: Player is in STATE_BUFFERING
        if (snapshot.playbackState == Player.STATE_BUFFERING) {
            if (bufferingStartedRealtimeMs == 0L) {
                bufferingStartedRealtimeMs = now
                lastBufferAdvanceRealtimeMs = now
                return RecoveryDecision.None
            }

            val totalBufferingDurationMs = now - bufferingStartedRealtimeMs
            val timeSinceLastBufferByteMs = now - lastBufferAdvanceRealtimeMs

            // Distinguish between:
            // 1. Active slow buffering (bytes are arriving within the last 6s while loader is active) -> allow up to 28s.
            // 2. Initial connection / waiting for first byte while loader is active -> allow 22s (above 20s OkHttp READ_TIMEOUT).
            // 3. Mid-stream rebuffering while loader is actively waiting on an HTTP read -> allow 21s (above 20s READ_TIMEOUT) without buffer progress.
            // 4. Loader is NOT loading at all (isLoading == false) and zero buffer progress -> intervene after 11s (Live) / 14s (VOD).
            val shouldTriggerStallRecovery = when {
                hasReceivedFirstByteOnCurrentServer && timeSinceLastBufferByteMs < 6_000L && snapshot.isLoading -> {
                    totalBufferingDurationMs >= ACTIVE_SLOW_BUFFER_MAX_WAIT_MS
                }

                !hasRenderedFirstFrameOnCurrentServer && !hasReceivedFirstByteOnCurrentServer -> {
                    if (snapshot.isLoading) {
                        totalBufferingDurationMs >= INITIAL_LOADING_FIRST_BYTE_TIMEOUT_MS
                    } else {
                        totalBufferingDurationMs >= INITIAL_IDLE_NO_BYTE_TIMEOUT_MS
                    }
                }

                snapshot.isLoading -> {
                    timeSinceLastBufferByteMs >= ACTIVE_LOADING_STALL_TIMEOUT_MS &&
                        totalBufferingDurationMs >= ACTIVE_LOADING_STALL_TIMEOUT_MS
                }

                else -> {
                    val idleThreshold = if (snapshot.isLiveChannel) {
                        IDLE_BUFFER_STALL_LIVE_MS
                    } else {
                        IDLE_BUFFER_STALL_VOD_MS
                    }
                    timeSinceLastBufferByteMs >= idleThreshold && totalBufferingDurationMs >= idleThreshold
                }
            }

            if (shouldTriggerStallRecovery) {
                bufferingStartedRealtimeMs = now
                PlayerDiagnostics.record(
                    event = PlayerDiagnosticEvent.POSITION_STALLED,
                    rawUrl = snapshot.currentUrl,
                    serverIndex = currentServerIndex,
                    sourceType = snapshot.sourceType.label,
                    details = "Buffering stalled (total=${totalBufferingDurationMs}ms, noByteFor=${timeSinceLastBufferByteMs}ms, " +
                        "isLoading=${snapshot.isLoading}, firstByte=$hasReceivedFirstByteOnCurrentServer, wasPlaying=$hasRenderedFirstFrameOnCurrentServer)"
                )
                return escalateStallRecovery(snapshot)
            }
            return RecoveryDecision.None
        }

        // Case B: Player is in STATE_READY with playWhenReady=true, or STATE_ENDED on a live channel
        hasRenderedFirstFrameOnCurrentServer = true
        hasReceivedFirstByteOnCurrentServer = true
        bufferingStartedRealtimeMs = 0L
        if (lastPositionAdvanceRealtimeMs == 0L) {
            lastPositionAdvanceRealtimeMs = now
            return RecoveryDecision.None
        }

        // Live stream unexpectedly hit STATE_ENDED -> immediate non-destructive or reload recovery
        if (snapshot.isLiveChannel && snapshot.playbackState == Player.STATE_ENDED) {
            PlayerDiagnostics.record(
                event = PlayerDiagnosticEvent.POSITION_STALLED,
                rawUrl = snapshot.currentUrl,
                serverIndex = currentServerIndex,
                sourceType = snapshot.sourceType.label,
                details = "Live stream entered STATE_ENDED unexpectedly"
            )
            return escalateStallRecovery(snapshot)
        }

        val frozenDurationMs = now - lastPositionAdvanceRealtimeMs
        val readyFreezeThresholdMs = if (snapshot.isLiveChannel) {
            READY_POSITION_FROZEN_LIVE_MS
        } else {
            READY_POSITION_FROZEN_VOD_MS
        }

        if (snapshot.playbackState == Player.STATE_READY && frozenDurationMs >= readyFreezeThresholdMs) {
            lastPositionAdvanceRealtimeMs = now
            PlayerDiagnostics.record(
                event = PlayerDiagnosticEvent.POSITION_STALLED,
                rawUrl = snapshot.currentUrl,
                serverIndex = currentServerIndex,
                sourceType = snapshot.sourceType.label,
                details = "STATE_READY position frozen for ${frozenDurationMs}ms at pos=${snapshot.currentPositionMs}ms"
            )
            return escalateStallRecovery(snapshot)
        }

        return RecoveryDecision.None
    }

    private fun escalateStallRecovery(snapshot: WatchdogPlayerSnapshot): RecoveryDecision {
        val now = snapshot.nowRealtimeMs

        // Step 1: Non-destructive live edge seek / play nudge first (for live streams in STATE_READY)
        if (snapshot.isLiveChannel && !nonDestructiveNudgeDoneOnCurrentServer && snapshot.playbackState == Player.STATE_READY) {
            nonDestructiveNudgeDoneOnCurrentServer = true
            acquireRecoveryLock(now, delayMs = 300L)
            PlayerDiagnostics.record(
                event = PlayerDiagnosticEvent.RECOVERY_ATTEMPT,
                rawUrl = snapshot.currentUrl,
                serverIndex = currentServerIndex,
                sourceType = snapshot.sourceType.label,
                details = "Non-destructive live-edge resync nudge"
            )
            return RecoveryDecision.SeekToDefaultLiveEdge("تنشيط مسار البث المباشر...")
        }

        // Step 2: Bounded source reload on current server
        if (retryAttemptsOnCurrentServer < maxRetriesPerServer) {
            retryAttemptsOnCurrentServer += 1
            val delayMs = if (retryAttemptsOnCurrentServer == 1) 900L else 2_000L
            acquireRecoveryLock(now, delayMs)
            PlayerDiagnostics.record(
                event = PlayerDiagnosticEvent.RECOVERY_ATTEMPT,
                rawUrl = snapshot.currentUrl,
                serverIndex = currentServerIndex,
                sourceType = snapshot.sourceType.label,
                details = "Watchdog reload #$retryAttemptsOnCurrentServer/$maxRetriesPerServer on server #${currentServerIndex + 1}"
            )
            return RecoveryDecision.ReloadCurrentServer(
                attemptNumber = retryAttemptsOnCurrentServer,
                delayMs = delayMs,
                reason = "إعادة تنشيط البث ($retryAttemptsOnCurrentServer/$maxRetriesPerServer)...",
                downgradeBitrateHint = retryAttemptsOnCurrentServer >= 2
            )
        }

        // Step 3: Switch to next untried server
        val nextServer = findNextUntriedServerIndex()
        if (nextServer != null) {
            val delayMs = 400L
            acquireRecoveryLock(now, delayMs)
            PlayerDiagnostics.record(
                event = PlayerDiagnosticEvent.SERVER_SWITCHED,
                rawUrl = snapshot.currentUrl,
                serverIndex = nextServer,
                sourceType = snapshot.sourceType.label,
                details = "Watchdog switching from stalled server #${currentServerIndex + 1} to #${nextServer + 1}"
            )
            return RecoveryDecision.SwitchToNextServer(
                nextServerIndex = nextServer,
                delayMs = delayMs,
                reason = "تحويل تلقائي للسيرفر البديل #${nextServer + 1}..."
            )
        }

        // Step 4: All servers tried
        activeRecoveryInFlight = false
        PlayerDiagnostics.record(
            event = PlayerDiagnosticEvent.RECOVERY_FAILED,
            rawUrl = snapshot.currentUrl,
            serverIndex = currentServerIndex,
            sourceType = snapshot.sourceType.label,
            details = "Watchdog exhausted all $totalServersCount servers"
        )
        return RecoveryDecision.AllServersExhausted(
            userMessageAr = "تعذر استقرار البث من جميع السيرفرات المتاحة حالياً. تحقق من سرعة الإنترنت أو أعد المحاولة.",
            category = StreamFailureCategory.NETWORK_OR_TIMEOUT
        )
    }

    private fun findNextUntriedServerIndex(): Int? {
        if (totalServersCount <= 1) return null
        for (offset in 1 until totalServersCount) {
            val candidate = (currentServerIndex + offset) % totalServersCount
            if (candidate !in triedServerIndices) {
                return candidate
            }
        }
        return null
    }

    private fun buildArabicErrorMessage(
        category: StreamFailureCategory,
        httpCode: Int?,
        isLiveChannel: Boolean
    ): String {
        return when (category) {
            StreamFailureCategory.HTTP_FATAL_STATUS -> when (httpCode) {
                401, 403 -> "السيرفر رفض الاتصال (خطأ صلاحية $httpCode). يرجى تجربة قناة أخرى أو تحديث بيانات الاشتراك."
                404, 410 -> "رابط القناة غير متوفر حالياً على المصدر (خطأ $httpCode)."
                else -> "المصدر غير متاح حالياً (رمز الخطأ ${httpCode ?: ""})."
            }

            StreamFailureCategory.DECODER_ERROR ->
                "جهازك لا يدعم ترميز الفيديو الخاص بهذا المصدر. جرب سيرفراً آخر إن وجد."

            StreamFailureCategory.SOURCE_UNSUPPORTED ->
                "صيغة البث غير مدعومة من المصدر الحالي."

            else -> if (isLiveChannel) {
                "تعذر تشغيل البث المباشر بعد تجربة السيرفرات المتاحة. تحقق من اتصال الإنترنت أو أعد المحاولة."
            } else {
                "تعذر تحميل الفيديو حالياً. تحقق من الاتصال بالإنترنت ثم اضغط إعادة المحاولة."
            }
        }
    }

    fun getCurrentRetryCount(): Int = retryAttemptsOnCurrentServer

    fun getTriedServersCount(): Int = triedServerIndices.size
}
