package org.siloserver.silo.network

import io.ktor.http.Url

data class HttpOrigin(
    val scheme: String,
    val host: String,
    val port: Int,
)

fun httpOrigin(raw: String): HttpOrigin? = runCatching {
    val url = Url(raw)
    if (url.user != null || url.password != null) return null

    val scheme = url.protocol.name.lowercase()
    if (scheme != "http" && scheme != "https") return null
    if (!raw.hasExplicitAuthority(scheme)) return null

    val host = url.host.lowercase()
    if (!host.isValidHttpHost()) return null

    HttpOrigin(
        scheme = scheme,
        host = host,
        port = url.port,
    )
}.getOrNull()

fun isSameHttpOrigin(serverUrl: String, requestUrl: String): Boolean {
    val serverOrigin = httpOrigin(serverUrl) ?: return false
    val requestOrigin = httpOrigin(requestUrl) ?: return false
    return serverOrigin == requestOrigin
}

private fun String.hasExplicitAuthority(scheme: String): Boolean {
    val prefix = "$scheme://"
    if (!startsWith(prefix, ignoreCase = true)) return false

    val authority = drop(prefix.length).takeWhile { it != '/' && it != '?' && it != '#' }
    if (authority.isBlank()) return false

    val hostAndPort = authority.substringAfterLast('@')
    val rawHost = when {
        hostAndPort.startsWith("[") -> hostAndPort.substringBefore(']', missingDelimiterValue = "")
        ':' in hostAndPort -> hostAndPort.substringBeforeLast(':')
        else -> hostAndPort
    }
    return rawHost.isNotBlank()
}

private fun String.isValidHttpHost(): Boolean =
    isNotBlank() &&
        none { character ->
            character.isWhitespace() ||
                character.code <= 0x1f ||
                character.code == 0x7f ||
                character in "/?#@\\"
        }
