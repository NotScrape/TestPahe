/** The following file is slightly modified and taken from: https://github.com/LagradOst/CloudStream-3/blob/4d6050219083d675ba9c7088b59a9492fcaa32c7/app/src/main/java/com/lagradost/cloudstream3/animeproviders/AnimePaheProvider.kt
 * It is published under the following license:
 *
MIT License

Copyright (c) 2021 Osten

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
 *
 */

package eu.kanade.tachiyomi.animeextension.en.animepahe

import dev.datlag.jsunpacker.JsUnpacker
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.OkHttpClient
import java.io.IOException

class KwikExtractor(
    private val client: OkHttpClient,
    private val useDefaultUserAgent: () -> Boolean,
) {
    private val kwikClient = client.newBuilder()
        .build()

    private var userAgentProvider: (() -> String)? = null

    // Let's get the userAgent provider from constructor or pass it
    constructor(
        client: OkHttpClient,
        useDefaultUserAgent: () -> Boolean,
        userAgentProvider: () -> String,
    ) : this(client, useDefaultUserAgent) {
        this.userAgentProvider = userAgentProvider
    }

    private val activeUserAgent: String
        get() = userAgentProvider?.invoke() ?: ""

    fun getHlsStreamUrl(kwikUrl: String, referer: String): String {
        val doc = kwikClient.newCall(GET(kwikUrl, Headers.headersOf("referer", referer))).execute().use { response ->
            response.asJsoup()
        }
        val script = doc.selectFirst("script:containsData(eval\\(function)")?.data()
            ?.substringAfterLast("eval(function(")
            ?: throw IOException("Failed to find script in Kwik HLS page")
        val unpacked = JsUnpacker.unpackAndCombine("eval(function($script")
            ?: throw IOException("Failed to unpack Kwik HLS script")
        return unpacked.substringAfter("const source=\\'").substringBefore("\\';")
    }

    fun getStreamUrlFromKwik(paheUrl: String, referer: String): String {
        val noRedirectClient = kwikClient.newBuilder()
            .followRedirects(false)
            .followSslRedirects(false)
            .build()

        val kwikUrl = noRedirectClient.newCall(GET("$paheUrl/i", Headers.headersOf("user-agent", activeUserAgent))).execute().use { redirectResponse ->
            val location = redirectResponse.header("location") ?: throw IOException("Redirect missing location header")
            "https://" + location.substringAfterLast("https://")
        }

        val (cookieHeader, docBody) = kwikClient.newCall(GET(kwikUrl, Headers.headersOf("referer", referer, "user-agent", activeUserAgent))).execute().use { docResponse ->
            val cookie = docResponse.headers("set-cookie").joinToString("; ") { it.substringBefore(";") }
            val body = docResponse.body.string()
            Pair(cookie, body)
        }

        val match = KWIK_PARAMS_REGEX.find(docBody) ?: throw IOException("Failed to match Kwik params")
        val (fullString, key, v1, v2) = match.destructured
        val decrypted = decrypt(fullString, key, v1.toInt(), v2.toInt())

        val postUri = KWIK_DURL_REGEX.find(decrypted)?.groupValues?.get(1) ?: throw IOException("Failed to extract POST URI")
        val token = KWIK_DTOKEN_REGEX.find(decrypted)?.groupValues?.get(1) ?: throw IOException("Failed to extract token")

        var tries = 0
        while (tries < 5) {
            val response = noRedirectClient.newCall(
                POST(
                    postUri,
                    Headers.headersOf(
                        "Referer",
                        kwikUrl,
                        "Cookie",
                        cookieHeader.replace("path=/;", ""),
                        "User-Agent",
                        activeUserAgent,
                    ),
                    FormBody.Builder().add("_token", token).build(),
                ),
            ).execute()

            response.use {
                if (it.code == 302) {
                    return it.header("location").toString()
                }
            }
            tries++
        }
        throw IOException("Failed to extract the stream uri from Kwik after 5 attempts")
    }

    private fun decrypt(fullString: String, key: String, v1: Int, v2: Int): String {
        val keyIndexMap = key.withIndex().associate { it.value to it.index }
        val delimiter = key[v2]
        return buildString {
            var i = 0
            while (i < fullString.length) {
                val nextIndex = fullString.indexOf(delimiter, i)
                if (nextIndex == -1) break
                val baseValue = fullString.substring(i, nextIndex).fold(0) { acc, c ->
                    acc * v2 + (keyIndexMap[c] ?: 0)
                }
                append((baseValue - v1).toChar())
                i = nextIndex + 1
            }
        }
    }

    companion object {
        private val KWIK_PARAMS_REGEX = Regex("""\("(\w+)",\d+,"(\w+)",(\d+),(\d+),\w+\)""")
        private val KWIK_DURL_REGEX = Regex("""action="([^"]+)"""")
        private val KWIK_DTOKEN_REGEX = Regex("""value="([^"]+)"""")
    }
}
