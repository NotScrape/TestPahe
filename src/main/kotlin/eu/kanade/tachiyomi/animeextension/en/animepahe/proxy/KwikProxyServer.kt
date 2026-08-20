package eu.kanade.tachiyomi.animeextension.en.animepahe.proxy

import android.util.Log
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoHTTPD.Response.Status
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayInputStream
import java.io.IOException
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

class KwikProxyServer(
    private val client: OkHttpClient,
    private val userAgentProvider: () -> String,
) : NanoHTTPD("127.0.0.1", 0) {

    @Volatile
    private var lastRequestTime = System.currentTimeMillis()
    private var idleTimer: java.util.Timer? = null
    private val keyCache = java.util.concurrent.ConcurrentHashMap<String, ByteArray>()

    private val headers: Headers
        get() = Headers.Builder()
            .add("Referer", "https://kwik.cx/")
            .add("User-Agent", userAgentProvider())
            .build()

    override fun start(timeout: Int, daemon: Boolean) {
        super.start(timeout, daemon)
        lastRequestTime = System.currentTimeMillis()
        // startIdleCheck()
    }

    override fun stop() {
        super.stop()
        // stopIdleCheck()
    }

    @Synchronized
    private fun startIdleCheck() {
        idleTimer?.cancel()
        idleTimer = java.util.Timer("KwikProxyIdleCheck", true).also { timer ->
            timer.schedule(
                object : java.util.TimerTask() {
                    override fun run() {
                        if (System.currentTimeMillis() - lastRequestTime > 60_000) {
                            Log.d("AnimePahe", "Stopping idle KwikProxyServer")
                            stop()
                        }
                    }
                },
                30_000,
                30_000,
            )
        }
    }

    @Synchronized
    private fun stopIdleCheck() {
        idleTimer?.cancel()
        idleTimer = null
    }

    override fun serve(session: IHTTPSession): Response {
        lastRequestTime = System.currentTimeMillis()
        return when {
            session.uri.startsWith("/m3u8") -> handleM3u8Request(session)
            session.uri.startsWith("/segment") -> handleSegmentRequest(session)
            session.uri.startsWith("/mp4") -> handleMp4Request(session)
            else -> newFixedLengthResponse(Status.NOT_FOUND, MIME_PLAINTEXT, "Not Found")
        }
    }

    private fun handleM3u8Request(session: IHTTPSession): Response = try {
        val url = session.parameters["url"]?.firstOrNull() ?: throw IOException("Missing url")
        val content = client.newCall(Request.Builder().url(url).headers(headers).build()).execute().use { res ->
            if (!res.isSuccessful) throw IOException("HTTP ${res.code}")
            res.body?.string().orEmpty()
        }
        newFixedLengthResponse(Status.OK, "application/vnd.apple.mpegurl", rewritePlaylist(content, url))
    } catch (e: Exception) {
        Log.e("KwikProxyServer", "m3u8 error", e)
        newFixedLengthResponse(Status.INTERNAL_ERROR, MIME_PLAINTEXT, e.message)
    }

    private fun handleSegmentRequest(session: IHTTPSession): Response = try {
        val url = session.parameters["url"]?.firstOrNull() ?: throw IOException("Missing url")
        val segmentData = fetchBytes(url)
        val keyUrl = session.parameters["key"]?.firstOrNull()
        val decryptedData = if (keyUrl.isNullOrBlank()) {
            segmentData
        } else {
            val iv = session.parameters["iv"]?.firstOrNull() ?: throw IOException("Missing IV")
            val keyBytes = keyCache.getOrPut(keyUrl) { fetchBytes(keyUrl) }
            CryptoUtils.decryptAes128Cbc(segmentData, keyBytes, iv)
        }
        newFixedLengthResponse(Status.OK, "video/mp2t", ByteArrayInputStream(decryptedData), decryptedData.size.toLong())
    } catch (e: Exception) {
        Log.e("KwikProxyServer", "segment error", e)
        newFixedLengthResponse(Status.INTERNAL_ERROR, MIME_PLAINTEXT, e.message)
    }

    private fun handleMp4Request(session: IHTTPSession): Response {
        var response: okhttp3.Response? = null
        try {
            val url = session.parameters["url"]?.firstOrNull() ?: throw IOException("Missing url")
            val reqHeaders = headers.newBuilder().apply {
                session.headers["range"]?.let { add("Range", it) }
            }.build()

            response = client.newCall(Request.Builder().url(url).headers(reqHeaders).build()).execute()
            if (!response.isSuccessful && response.code != 206) {
                throw IOException("HTTP ${response.code}")
            }

            val body = response.body ?: throw IOException("Empty body")
            val contentLength = response.header("Content-Length")?.toLongOrNull() ?: -1L
            val contentType = response.header("Content-Type") ?: "video/mp4"
            val status = Status.lookup(response.code) ?: Status.OK

            return newFixedLengthResponse(status, contentType, body.byteStream(), contentLength).apply {
                response.header("Accept-Ranges")?.let { addHeader("Accept-Ranges", it) }
                response.header("Content-Range")?.let { addHeader("Content-Range", it) }
            }
        } catch (e: Exception) {
            response?.close()
            Log.e("KwikProxyServer", "mp4 error", e)
            return newFixedLengthResponse(Status.INTERNAL_ERROR, MIME_PLAINTEXT, e.message)
        }
    }

    private fun fetchBytes(url: String): ByteArray =
        client.newCall(Request.Builder().url(url).headers(headers).build()).execute().use { res ->
            if (!res.isSuccessful) throw IOException("HTTP ${res.code}")
            res.body?.bytes() ?: ByteArray(0)
        }

    private fun rewritePlaylist(content: String, originalUrl: String): String {
        val baseHttpUrl = originalUrl.toHttpUrlOrNull()
        val modifiedLines = mutableListOf<String>()
        var mediaSequence = 0L
        var segmentSequence = mediaSequence
        var currentKey: Pair<String, String?>? = null

        content.lines().forEach { line ->
            when {
                line.startsWith("#EXT-X-MEDIA-SEQUENCE:") -> {
                    mediaSequence = line.substringAfter(":").trim().toLongOrNull() ?: mediaSequence
                    segmentSequence = mediaSequence
                    modifiedLines.add(line)
                }
                line.startsWith("#EXT-X-KEY:") -> {
                    val attributes = HLS_ATTRIBUTE_REGEX.findAll(line.substringAfter(":")).associate {
                        it.groupValues[1] to it.groupValues[2].trim('"')
                    }
                    if (attributes["METHOD"]?.uppercase() == "AES-128") {
                        val keyUri = attributes["URI"]
                        if (!keyUri.isNullOrBlank()) {
                            currentKey = Pair(
                                baseHttpUrl?.resolve(keyUri)?.toString() ?: keyUri,
                                attributes["IV"]?.removePrefix("0x")?.removePrefix("0X")?.padStart(32, '0'),
                            )
                        }
                    } else {
                        currentKey = null
                    }
                }
                line.startsWith("#") || line.isBlank() -> modifiedLines.add(line)
                else -> {
                    val resolvedUrl = baseHttpUrl?.resolve(line)?.toString() ?: line
                    if (resolvedUrl.contains(".m3u8", ignoreCase = true)) {
                        val encoded = URLEncoder.encode(resolvedUrl, StandardCharsets.UTF_8.name())
                        modifiedLines.add("http://127.0.0.1:$listeningPort/m3u8?url=$encoded")
                    } else {
                        val encodedUrl = URLEncoder.encode(resolvedUrl, StandardCharsets.UTF_8.name())
                        modifiedLines.add(
                            buildString {
                                append("http://127.0.0.1:$listeningPort/segment?url=$encodedUrl")
                                currentKey?.let {
                                    append("&key=${URLEncoder.encode(it.first, StandardCharsets.UTF_8.name())}")
                                    append("&iv=${URLEncoder.encode(it.second ?: segmentSequence.toString(16).padStart(32, '0'), StandardCharsets.UTF_8.name())}")
                                }
                            },
                        )
                        segmentSequence++
                    }
                }
            }
        }
        return modifiedLines.joinToString("\n")
    }

    companion object {
        private val HLS_ATTRIBUTE_REGEX = Regex("""([A-Z0-9-]+)=("[^"]*"|[^,]*)""")

        @Volatile
        private var proxyServer: KwikProxyServer? = null

        fun getProxyUrl(client: OkHttpClient, userAgentProvider: () -> String, targetUrl: String): String {
            val server = synchronized(this) {
                val currentServer = proxyServer
                if (currentServer != null && currentServer.isAlive) {
                    currentServer
                } else {
                    val newServer = KwikProxyServer(client, userAgentProvider)
                    try {
                        newServer.start(fi.iki.elonen.NanoHTTPD.SOCKET_READ_TIMEOUT, false)
                        proxyServer = newServer
                        Log.d("AnimePahe", "Started KwikProxyServer on port ${newServer.listeningPort}")
                        newServer
                    } catch (e: Exception) {
                        Log.e("AnimePahe", "Failed to start KwikProxyServer", e)
                        return targetUrl
                    }
                }
            }
            val encodedUrl = URLEncoder.encode(targetUrl, StandardCharsets.UTF_8.name())
            val path = if (targetUrl.contains(".m3u8", ignoreCase = true)) "/m3u8" else "/mp4"
            return "http://127.0.0.1:${server.listeningPort}$path?url=$encodedUrl"
        }
    }
}
