package com.example.ui.player

import android.util.Log
import java.net.URI
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Structured diagnostic events for the Unified Media3 ExoPlayer Engine.
 * Covers all lifecycle, network, playlist/segment, decoder, watchdog, and server-failover stages.
 */
enum class PlayerDiagnosticEvent {
    PLAY_REQUESTED,
    PLAYLIST_LOADED,
    SEGMENT_LOADING,
    SEGMENT_FAILED,
    BUFFERING_STARTED,
    BUFFERING_ENDED,
    POSITION_STALLED,
    NETWORK_ERROR,
    HTTP_ERROR,
    DECODER_ERROR,
    PLAYER_ERROR,
    RECOVERY_ATTEMPT,
    RECOVERY_SUCCESS,
    RECOVERY_FAILED,
    SERVER_SWITCHED,
    PLAYBACK_READY
}

data class PlayerDiagnosticEntry(
    val timestampMs: Long,
    val event: PlayerDiagnosticEvent,
    val sanitizedUrl: String,
    val serverIndex: Int,
    val sourceType: String,
    val details: String
)

/**
 * Privacy-safe, toggleable diagnostics logger for FOUL TV's Unified Player Engine.
 * Sanitizes both URLs AND free-form error/detail messages so Xtream usernames, passwords,
 * tokens, basic-auth credentials, or session keys never leak into Logcat or memory buffers.
 */
object PlayerDiagnostics {
    private const val TAG = "FoulTvUnifiedPlayer"
    private const val MAX_HISTORY_SIZE = 120

    private val embeddedUrlRegex = Regex("https?://[^\\s\"'<>\\])}]+", RegexOption.IGNORE_CASE)
    private val xtreamPathRegex = Regex(
        "/(live|movie|series)/([^/\\s]+)/([^/\\s]+)/([^\\s\"'<>\\])}?]+)",
        RegexOption.IGNORE_CASE
    )
    private val xtreamShortPathRegex = Regex(
        "(?<![a-zA-Z0-9])/([a-zA-Z0-9_.\\-]{3,})/([a-zA-Z0-9_.\\-]{3,})/(\\d+(?:\\.[a-zA-Z0-9]+)?)"
    )
    private val basicAuthRegex = Regex("://[^/@\\s]+@")
    private val sensitiveParamRegex = Regex(
        "(?i)\\b(password|pass|passwd|pwd|username|user|u|p|token|access_token|auth|api_key|apikey|key|sig|signature|secret|hash)=([^&\\s\"'<>\\])}]+)"
    )
    private val bearerRegex = Regex("(?i)\\b(Bearer|Basic)\\s+[A-Za-z0-9\\-._~+/]+=*")

    /**
     * Disabled by default for regular users to avoid logcat overhead in production,
     * while retaining lightweight in-memory ring-buffer tracking for recovery diagnostics and tests.
     */
    @Volatile
    var isVerboseLoggingEnabled: Boolean = false

    private val recentEntries = CopyOnWriteArrayList<PlayerDiagnosticEntry>()

    /**
     * Redacts Xtream credentials (`/live/user/pass/123.m3u8` -> `/live/[REDACTED]/[REDACTED]/123.m3u8`),
     * basic auth (`user:pass@host`), and sensitive query tokens (`password=`, `token=`, `username=`, `auth=`).
     */
    fun sanitizeUrlForLogs(rawUrl: String): String {
        val trimmed = rawUrl.trim()
        if (trimmed.isEmpty()) return ""

        return try {
            val cleanedInput = trimmed.replace(basicAuthRegex, "://[REDACTED]@")
            val parsed = URI(cleanedInput)
            val scheme = parsed.scheme ?: "http"
            val host = parsed.host ?: "unknown-host"
            val portSuffix = if (parsed.port != -1) ":${parsed.port}" else ""
            val userInfoPrefix = if (parsed.rawUserInfo != null || trimmed.contains("@")) "[REDACTED]@" else ""
            val segments = parsed.rawPath
                ?.split('/')
                ?.filter { it.isNotEmpty() }
                .orEmpty()

            val sanitizedPath = when {
                // Xtream standard pattern: /live/{user}/{pass}/{streamId.ext} or /movie/{user}/{pass}/{id} or /series/{user}/{pass}/{id}
                segments.size >= 4 && segments[0].lowercase() in setOf("live", "movie", "series") -> {
                    val prefix = segments[0]
                    val tail = segments.drop(3).joinToString("/")
                    "/$prefix/[REDACTED]/[REDACTED]/$tail"
                }
                // Xtream 3-segment pattern: /{user}/{pass}/{streamId}
                segments.size == 3 && segments[2].any { it.isDigit() } -> {
                    "/[REDACTED]/[REDACTED]/${segments[2]}"
                }
                segments.isNotEmpty() -> {
                    val lastSegment = segments.last()
                    if (segments.size > 1) "/.../$lastSegment" else "/$lastSegment"
                }
                else -> "/"
            }

            val rawQuery = parsed.rawQuery.orEmpty()
            val sanitizedQuery = if (rawQuery.isNotEmpty()) {
                val safeParams = rawQuery.split('&').filter { it.isNotBlank() }.map { pair ->
                    val key = pair.substringBefore('=')
                    val value = pair.substringAfter('=', "")
                    val lowerKey = key.lowercase()
                    if (
                        lowerKey.contains("pass") ||
                        lowerKey.contains("pwd") ||
                        lowerKey.contains("user") ||
                        lowerKey == "u" ||
                        lowerKey == "p" ||
                        lowerKey.contains("token") ||
                        lowerKey.contains("auth") ||
                        lowerKey.contains("key") ||
                        lowerKey.contains("sig") ||
                        lowerKey.contains("secret") ||
                        lowerKey.contains("hash")
                    ) {
                        "$key=[REDACTED]"
                    } else {
                        if (value.length > 16) "$key=[MASKED]" else "$key=$value"
                    }
                }
                "?" + safeParams.joinToString("&")
            } else {
                ""
            }

            "$scheme://$userInfoPrefix$host$portSuffix$sanitizedPath$sanitizedQuery"
        } catch (_: Throwable) {
            sanitizeFreeTextFallback(trimmed)
        }
    }

    /**
     * Sanitizes arbitrary diagnostic details and exception messages so embedded URLs, Xtream paths,
     * query parameters, or authorization headers inside exception strings never leak credentials.
     */
    fun sanitizeDetailsForLogs(rawDetails: String): String {
        val trimmed = rawDetails.trim()
        if (trimmed.isEmpty()) return ""

        // 1. Replace any full http/https URLs embedded inside the message
        val withSanitizedUrls = embeddedUrlRegex.replace(trimmed) { matchResult ->
            sanitizeUrlForLogs(matchResult.value)
        }

        // 2. Scrub any remaining relative Xtream paths, basic auth, query tokens, or Bearer tokens
        return sanitizeFreeTextFallback(withSanitizedUrls)
    }

    private fun sanitizeFreeTextFallback(text: String): String {
        return text
            .replace(basicAuthRegex, "://[REDACTED]@")
            .replace(xtreamPathRegex) { m ->
                "/${m.groupValues[1]}/[REDACTED]/[REDACTED]/${m.groupValues[4]}"
            }
            .replace(xtreamShortPathRegex) { m ->
                val userCandidate = m.groupValues[1]
                val passCandidate = m.groupValues[2]
                val streamId = m.groupValues[3]
                if (userCandidate == "[REDACTED]" || passCandidate == "[REDACTED]") {
                    m.value
                } else {
                    "/[REDACTED]/[REDACTED]/$streamId"
                }
            }
            .replace(sensitiveParamRegex, "$1=[REDACTED]")
            .replace(bearerRegex, "$1 [REDACTED]")
    }

    fun record(
        event: PlayerDiagnosticEvent,
        rawUrl: String = "",
        serverIndex: Int = 0,
        sourceType: String = "",
        details: String = ""
    ) {
        val safeUrl = sanitizeUrlForLogs(rawUrl)
        val safeDetails = sanitizeDetailsForLogs(details)
        val entry = PlayerDiagnosticEntry(
            timestampMs = System.currentTimeMillis(),
            event = event,
            sanitizedUrl = safeUrl,
            serverIndex = serverIndex,
            sourceType = sourceType,
            details = safeDetails
        )

        recentEntries.add(entry)
        while (recentEntries.size > MAX_HISTORY_SIZE) {
            recentEntries.removeAt(0)
        }

        if (isVerboseLoggingEnabled) {
            val message = buildString {
                append("[")
                append(event.name)
                append("] srv=#")
                append(serverIndex + 1)
                if (sourceType.isNotBlank()) {
                    append(" type=")
                    append(sourceType)
                }
                if (safeUrl.isNotBlank()) {
                    append(" url=")
                    append(safeUrl)
                }
                if (safeDetails.isNotBlank()) {
                    append(" | ")
                    append(safeDetails)
                }
            }
            when (event) {
                PlayerDiagnosticEvent.SEGMENT_FAILED,
                PlayerDiagnosticEvent.POSITION_STALLED,
                PlayerDiagnosticEvent.NETWORK_ERROR,
                PlayerDiagnosticEvent.HTTP_ERROR,
                PlayerDiagnosticEvent.DECODER_ERROR,
                PlayerDiagnosticEvent.PLAYER_ERROR,
                PlayerDiagnosticEvent.RECOVERY_FAILED -> Log.w(TAG, message)
                else -> Log.d(TAG, message)
            }
        }
    }

    fun getRecentEntries(): List<PlayerDiagnosticEntry> = recentEntries.toList()

    fun clearHistory() {
        recentEntries.clear()
    }
}
