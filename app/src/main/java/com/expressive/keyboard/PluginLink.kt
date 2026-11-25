package com.expressive.keyboard

import java.io.ByteArrayOutputStream
import java.net.URL
import javax.net.ssl.HttpsURLConnection

internal object PluginLink {
    private val github = Regex("https://github\\.com/([^/]+)/([^/]+)/(?:blob|raw)/(.+)")
    private val digits = Regex("\\d+")

    fun newer(candidate: String, installed: String): Boolean {
        fun numbers(version: String) = digits.findAll(version).take(16).map { it.value.toLongOrNull() ?: Long.MAX_VALUE }.toList()
        val a = numbers(candidate)
        val b = numbers(installed)
        if (a.isEmpty() && b.isEmpty()) return candidate.trim() != installed.trim()
        for (index in 0 until maxOf(a.size, b.size)) {
            val left = a.getOrElse(index) { 0 }
            val right = b.getOrElse(index) { 0 }
            if (left != right) return left > right
        }
        return false
    }

    fun normalize(raw: String): URL {
        val trimmed = raw.trim()
        require(trimmed.length <= 2000 && trimmed.none(Char::isWhitespace))
        val match = github.matchEntire(trimmed.substringBefore('#').substringBefore('?'))
        val value = if (match == null) trimmed else {
            val (_, owner, repo, file) = match.groupValues
            "https://raw.githubusercontent.com/$owner/$repo/$file"
        }
        return checked(URL(value))
    }

    private fun checked(url: URL): URL {
        require(url.protocol == "https" && url.host.isNotBlank() && url.userInfo == null) { "HTTPS URL required" }
        return url
    }

    fun download(raw: String, byteLimit: Int): ByteArray {
        var url = normalize(raw)
        repeat(6) {
            require(!ScriptHost.isPrivateHost(url.toExternalForm())) { "Local addresses are not supported" }
            val connection = url.openConnection() as HttpsURLConnection
            try {
                connection.instanceFollowRedirects = false
                connection.connectTimeout = 12000
                connection.readTimeout = 12000
                val status = connection.responseCode
                if (status in 300..399) {
                    url = checked(URL(url, requireNotNull(connection.getHeaderField("Location"))))
                    return@repeat
                }
                check(status == 200) { "HTTP $status" }
                require(connection.contentLengthLong <= byteLimit)
                return connection.inputStream.use { input ->
                    val output = ByteArrayOutputStream()
                    val buffer = ByteArray(16384)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        require(output.size() + count <= byteLimit)
                        output.write(buffer, 0, count)
                    }
                    output.toByteArray()
                }
            } finally {
                connection.disconnect()
            }
        }
        error("Too many redirects")
    }
}
