package org.siloserver.silo.common.diagnostics

import java.net.URI
import java.security.MessageDigest
import java.util.Locale

/**
 * Removes credentials and network identity before a value enters diagnostics storage.
 * This is intentionally independent of Koin so it is usable during early startup and crashes.
 */
class DiagnosticsRedactor(
    knownServerHosts: Set<String> = emptySet(),
    sensitiveValues: Set<String> = emptySet(),
) {
    private val knownServerHosts = knownServerHosts
        .mapTo(linkedSetOf()) { it.trim().trim('[', ']').lowercase(Locale.ROOT) }
        .filterTo(linkedSetOf()) { it.isNotEmpty() }
    private val sensitiveValues = sensitiveValues
        .filter { it.isNotEmpty() }
        .sortedByDescending(String::length)

    fun sanitize(value: String): String {
        var output = URL_PATTERN.replace(value) { match ->
            sanitizeMatchedUrl(match.value)
        }
        output = AUTHORIZATION_PATTERN.replace(output) { match ->
            "${match.groupValues[1]}: [REDACTED]"
        }
        output = COOKIE_PATTERN.replace(output) { match ->
            "${match.groupValues[1]}: [REDACTED]"
        }
        output = JWT_PATTERN.replace(output, "[REDACTED_JWT]")
        output = EMAIL_PATTERN.replace(output, "[REDACTED_EMAIL]")
        output = NAMED_SECRET_PATTERN.replace(output) { match ->
            "${match.groupValues[1]}=[REDACTED]"
        }
        sensitiveValues.forEach { secret -> output = output.replace(secret, "[REDACTED]") }
        knownServerHosts.forEach { host ->
            output = output.replace(host, stableHostToken(host), ignoreCase = true)
        }
        return output
    }

    fun sanitizeUrl(value: String): String {
        val uri = runCatching { URI(value) }.getOrNull() ?: return sanitize(value)
        val scheme = uri.scheme?.lowercase(Locale.ROOT) ?: return sanitize(value)
        val rawHost = uri.host ?: return sanitize(value)
        val host = rawHost.trim('[', ']').lowercase(Locale.ROOT)
        val safeHost = when {
            isLoopbackHost(host) -> host
            host in knownServerHosts -> stableHostToken(host)
            else -> stableHostToken(host)
        }
        return runCatching {
            URI(
                scheme,
                null,
                safeHost,
                uri.port,
                uri.rawPath.orEmpty(),
                null,
                null,
            ).toASCIIString()
        }.getOrElse { "$scheme://$safeHost" }
    }

    fun sanitizeThrowable(
        throwable: Throwable,
        maxDepth: Int = DEFAULT_THROWABLE_DEPTH,
        maxUtf8Bytes: Int = DEFAULT_THROWABLE_BYTES,
    ): String {
        if (maxDepth <= 0 || maxUtf8Bytes <= 0) return ""
        val seen = HashSet<Throwable>()
        val result = buildString {
            var current: Throwable? = throwable
            var depth = 0
            while (current != null && depth < maxDepth && seen.add(current)) {
                if (isNotEmpty()) append('\n')
                if (depth > 0) append("caused by ")
                append(current.javaClass.name)
                current.message?.takeIf(String::isNotBlank)?.let { message ->
                    append(": ")
                    append(sanitize(message))
                }
                current = current.cause
                depth += 1
            }
        }
        return result.truncateUtf8(maxUtf8Bytes)
    }

    private fun sanitizeMatchedUrl(candidate: String): String {
        val trailing = candidate.takeLastWhile { it in TRAILING_URL_PUNCTUATION }
        val url = candidate.dropLast(trailing.length)
        return sanitizeUrl(url) + trailing
    }

    private fun stableHostToken(host: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(host.lowercase(Locale.ROOT).encodeToByteArray())
        return buildString(HOST_TOKEN_HEX_BYTES * 2 + HOST_TOKEN_PREFIX.length) {
            append(HOST_TOKEN_PREFIX)
            repeat(HOST_TOKEN_HEX_BYTES) { index -> append("%02x".format(Locale.ROOT, digest[index])) }
        }
    }

    private fun isLoopbackHost(host: String): Boolean =
        host == "localhost" || host == "::1" || IPV4_LOOPBACK_PATTERN.matches(host)

    private fun String.truncateUtf8(maxBytes: Int): String {
        if (encodeToByteArray().size <= maxBytes) return this
        val result = StringBuilder(length.coerceAtMost(maxBytes))
        var index = 0
        var usedBytes = 0
        while (index < length) {
            val codePoint = codePointAt(index)
            val value = String(Character.toChars(codePoint))
            val valueBytes = value.encodeToByteArray().size
            if (usedBytes + valueBytes > maxBytes) break
            result.append(value)
            usedBytes += valueBytes
            index += Character.charCount(codePoint)
        }
        return result.toString()
    }

    private companion object {
        const val DEFAULT_THROWABLE_DEPTH = 6
        const val DEFAULT_THROWABLE_BYTES = 4 * 1_024
        const val HOST_TOKEN_PREFIX = "host_"
        const val HOST_TOKEN_HEX_BYTES = 8

        val TRAILING_URL_PUNCTUATION = setOf('.', ',', ';', ':', '!', '?', ')', ']', '}')
        val URL_PATTERN = Regex("(?i)\\bhttps?://[^\\s<>\\\"']+")
        val AUTHORIZATION_PATTERN = Regex(
            "(?i)\\b(authorization|proxy-authorization)\\s*[:=]\\s*(?:bearer\\s+)?[^\\s,;]+",
        )
        val COOKIE_PATTERN = Regex("(?i)\\b(cookie|set-cookie)\\s*:\\s*[^\\r\\n]+")
        val JWT_PATTERN = Regex("(?<![A-Za-z0-9_-])[A-Za-z0-9_-]{4,}\\.[A-Za-z0-9_-]{1,}\\.[A-Za-z0-9_-]{1,}(?![A-Za-z0-9_-])")
        val EMAIL_PATTERN = Regex("(?i)(?<![A-Z0-9._%+-])[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}(?![A-Z0-9.-])")
        val NAMED_SECRET_PATTERN = Regex(
            "(?i)\\b(access_token|refresh_token|profile_token|token|api_key)\\s*[:=]\\s*[^\\s,;&]+",
        )
        val IPV4_LOOPBACK_PATTERN = Regex("127(?:\\.\\d{1,3}){3}")
    }
}
