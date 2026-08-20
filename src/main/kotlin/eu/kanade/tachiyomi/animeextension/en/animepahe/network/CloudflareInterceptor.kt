package eu.kanade.tachiyomi.animeextension.en.animepahe.network

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.ValueCallback
import android.webkit.WebSettings
import android.webkit.WebView
import okhttp3.Cookie
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import eu.kanade.tachiyomi.network.NetworkHelper
import uy.kohesive.injekt.injectLazy
import java.io.File
import java.io.IOException
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class CloudflareInterceptor(
    private val client: OkHttpClient,
    private val useDefaultUserAgent: () -> Boolean
) : Interceptor {
    private val context: Application by injectLazy()
    private val network: NetworkHelper by injectLazy()
    private val handler by lazy { Handler(Looper.getMainLooper()) }
    private val userAgent: String
        get() = if (useDefaultUserAgent()) (webViewUserAgent ?: WebSettings.getDefaultUserAgent(context)) else network.defaultUserAgentProvider()
    @Volatile
    private var isLoggingEnabled = false

    private fun logToCrashFile(message: String) {
        Log.e("CloudflareInterceptor", message)
        if (isLoggingEnabled) {
            runCatching {
                val file = File(context.externalCacheDir, "aniyomi_crash_logs.txt")
                file.appendText("[CF_INTERCEPTOR] ${Instant.now()}: $message\n")
            }
        }
    }

    private fun findWebView(view: View): WebView? {
        if (view is WebView) return view
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                val child = view.getChildAt(i)
                val wv = findWebView(child)
                if (wv != null) return wv
            }
        }
        return null
    }

    @Synchronized
    override fun intercept(chain: Interceptor.Chain): Response {
        val originalRequest = chain.request()
        val cookieMgr = CookieManager.getInstance()
        val origRequestUrl = originalRequest.url.toString()
        val requestUri = Uri.parse(origRequestUrl)
        val baseUrl = "${requestUri.scheme}://${requestUri.host}/"
        val cookies = cookieMgr.getCookie(baseUrl)

        // Check if logs file exists once per intercept request
        isLoggingEnabled = runCatching {
            File(context.externalCacheDir, "aniyomi_crash_logs.txt").exists()
        }.getOrDefault(false)

        logToCrashFile("intercept() - Request URL: $origRequestUrl")
        logToCrashFile("intercept() - Initial cookies: $cookies")

        var request = originalRequest.newBuilder()
            .header("User-Agent", userAgent)
            .build()

        // Check if existing cookies contain cf_clearance
        if (cookies != null && cookies.contains("cf_clearance")) {
            val host = originalRequest.url.host.removePrefix(".")
            val parsedCookies = cookies.split(";").mapNotNull {
                Cookie.parse(originalRequest.url, "${it.trim()}; Domain=$host; Path=/")
            }
            logToCrashFile("intercept() - Found existing cf_clearance cookie. Attaching to request.")
            request = createRequestWithCookies(request, parsedCookies, userAgent)
        }

        val originalResponse = chain.proceed(request)
        logToCrashFile("intercept() - Response code: ${originalResponse.code}, Server: ${originalResponse.header("Server")}")

        // If request was successful, bypass challenge solving
        if (!(originalResponse.code in ERROR_CODES && originalResponse.header("Server") in SERVER_CHECK)) {
            logToCrashFile("intercept() - Request succeeded or not a CF challenge. Returning response.")
            return originalResponse
        }

        logToCrashFile("intercept() - Detected Cloudflare challenge (code ${originalResponse.code}). Resolving via WebView.")
        return try {
            originalResponse.close()
            val resolvedRequest = resolveWithWebView(request)
            chain.proceed(resolvedRequest)
        } catch (e: Exception) {
            logToCrashFile("intercept() - Error resolving challenge: ${e.message}")
            throw IOException(e)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    fun resolveWithWebView(request: Request): Request {
        val host = request.url.host.removePrefix(".")
        val cookieMgr = CookieManager.getInstance()
        val origRequestUrl = request.url.toString()

        logToCrashFile("resolveWithWebView() - Resolving for host: $host, URL: $origRequestUrl")

        var latch = activeResolutions[host]
        val isNewResolution = if (latch == null) {
            val newLatch = CountDownLatch(1)
            val existing = activeResolutions.putIfAbsent(host, newLatch)
            if (existing == null) {
                latch = newLatch
                true
            } else {
                latch = existing
                false
            }
        } else {
            false
        }

        if (!isNewResolution) {
            logToCrashFile("resolveWithWebView() - Resolution already active for $host. Waiting...")
            latch?.await(2, TimeUnit.MINUTES)
            val requestUri = Uri.parse(origRequestUrl)
            val baseUrl = "${requestUri.scheme}://${requestUri.host}/"
            val cookieString = cookieMgr.getCookie(baseUrl)
            logToCrashFile("resolveWithWebView() - Active resolution finished. Current cookies: $cookieString")
            val cookies = cookieString
                ?.split(";")
                ?.mapNotNull { Cookie.parse(request.url, "${it.trim()}; Domain=$host; Path=/") }
                ?: emptyList()
            return createRequestWithCookies(request, cookies, userAgent)
        }

        val isDone = java.util.concurrent.atomic.AtomicBoolean(false)
        val cfFlag = java.util.concurrent.atomic.AtomicBoolean(false)
        var lastWebViewUrl = origRequestUrl

        val callback = object : Application.ActivityLifecycleCallbacks {
            var webViewActivityRef: Activity? = null
            val fallbackLatch = CountDownLatch(1)

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
                if (activity.javaClass.name == "eu.kanade.tachiyomi.ui.webview.WebViewActivity") {
                    logToCrashFile("Lifecycle - WebViewActivity created")
                    webViewActivityRef = activity
                    handler.post {
                        activity.window?.decorView?.let { findWebView(it) }?.let { webView ->
                            if (!useDefaultUserAgent()) {
                                webView.settings.userAgentString = userAgent
                                logToCrashFile("Forced WebView User-Agent to match OkHttp: $userAgent")
                            } else {
                                val ua = webView.settings.userAgentString
                                webViewUserAgent = ua
                                logToCrashFile("Detected and saved native WebView User-Agent: $ua")
                            }
                        }
                    }
                }
            }
            override fun onActivityStarted(activity: Activity) {}
            override fun onActivityResumed(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivityStopped(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {
                if (activity.javaClass.name == "eu.kanade.tachiyomi.ui.webview.WebViewActivity") {
                    logToCrashFile("Lifecycle - WebViewActivity destroyed")
                    context.unregisterActivityLifecycleCallbacks(this)
                    webViewActivityRef = null
                    fallbackLatch.countDown()
                }
            }
        }

        val checkCookiesTask = object : Runnable {
            var cachedWebView: WebView? = null

            override fun run() {
                cookieMgr.flush()
                handler.postDelayed({
                    if (isDone.get()) return@postDelayed
                    val activity = callback.webViewActivityRef
                    val webView = cachedWebView ?: activity?.window?.decorView?.let { findWebView(it) }?.also { cachedWebView = it }
                    if (webView != null) {
                        if (!useDefaultUserAgent()) {
                            if (webView.settings.userAgentString != userAgent) {
                                webView.settings.userAgentString = userAgent
                                logToCrashFile("Polling - Forced WebView User-Agent to: $userAgent")
                            }
                        } else {
                            if (webViewUserAgent == null) {
                                val ua = webView.settings.userAgentString
                                webViewUserAgent = ua
                                logToCrashFile("Polling - Detected and saved native WebView User-Agent: $ua")
                            }
                        }
                    }


                    val js = "(function() { \n" +
                        "    if (typeof _cf_chl_opt === 'undefined') return false;\n" +
                        "    try {\n" +
                        "        return JSON.stringify(_cf_chl_opt).includes('cloudflare.com');\n" +
                        "    } catch (e) {\n" +
                        "        return false;\n" +
                        "    }\n" +
                        "})()"
                    webView?.evaluateJavascript(js) { value ->
                        val currentUrl = webView.url ?: origRequestUrl
                        lastWebViewUrl = currentUrl
                        val requestUri = Uri.parse(currentUrl)
                        val currentHost = requestUri.host ?: host
                        val baseUrl = "${requestUri.scheme}://${currentHost}/"
                        val currentCookies = cookieMgr.getCookie(baseUrl)

                        // value needs to be true before, before we can proceed
                        if (value == "true")
                        {
                            cfFlag.set(true)
                        }

                        val bypassed = currentCookies != null
                            && currentCookies.contains("cf_clearance")
                            && cfFlag.get()
                            && value != "true"
                        logToCrashFile("Polling - Current URL: $currentUrl | Cookies: $currentCookies | isBypassed: $bypassed")
                        if (bypassed) {
                            Thread.sleep(100)
                            cookieMgr.flush()
                            logToCrashFile("Polling - Bypassed successfully! Closing WebViewActivity.")
                            callback.webViewActivityRef?.finish()
                            callback.fallbackLatch.countDown()
                        } else if (!isDone.get() && callback.fallbackLatch.count > 0) {
                            handler.post(this)
                        }
                    }
                }, 1000)
            }
        }

        try {
            logToCrashFile("resolveWithWebView() - Clearing all cookies to ensure clean challenge state")
            logToCrashFile("resolveWithWebView() - Clearing old expired/stub cf_clearance cookies")
            cookieMgr.setCookie(origRequestUrl, "cf_clearance=; Expires=Thu, 01 Jan 1970 00:00:00 GMT; Path=/; Domain=.$host")
            cookieMgr.flush()

            context.registerActivityLifecycleCallbacks(callback)
            handler.post(checkCookiesTask)

            val intent = Intent().apply {
                component = ComponentName(context, "eu.kanade.tachiyomi.ui.webview.WebViewActivity")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_HISTORY)
                putExtra("url_key", origRequestUrl)
                putExtra("source_key", 123999L)
                putExtra("title_key", "Solve Cloudflare Captcha")
            }

            try {
                logToCrashFile("resolveWithWebView() - Starting WebViewActivity")
                context.startActivity(intent)
            } catch (e: Exception) {
                logToCrashFile("resolveWithWebView() - Failed to start WebViewActivity: ${e.message}")
                context.unregisterActivityLifecycleCallbacks(callback)
                callback.fallbackLatch.countDown()
            }

            logToCrashFile("resolveWithWebView() - Waiting for WebView resolution to complete...")
            callback.fallbackLatch.await(2, TimeUnit.MINUTES)
            logToCrashFile("resolveWithWebView() - WebView resolution wait finished.")

            try {
                context.unregisterActivityLifecycleCallbacks(callback)
            } catch (e: Exception) {
                // Ignored
            }


            val currentUrl = lastWebViewUrl
            val requestUri = Uri.parse(currentUrl)
            val currentHost = requestUri.host?.removePrefix(".") ?: host
            val baseUrl = "${requestUri.scheme}://${currentHost}/"
            val cookieString = cookieMgr.getCookie(baseUrl)
            logToCrashFile("resolveWithWebView() - Got raw cookies for $currentUrl: $cookieString")

            cleanAndSaveCookies(currentUrl, cookieString, currentHost)

            val origRequestUri = Uri.parse(origRequestUrl)
            val origHost = origRequestUri.host?.removePrefix(".") ?: host
            if (origHost != currentHost) {
                val origBaseUrl = "${origRequestUri.scheme}://${origHost}/"
                val origCookieString = cookieMgr.getCookie(origBaseUrl)
                logToCrashFile("resolveWithWebView() - Redirect detected. Got raw cookies for original host $origHost: $origCookieString")
                cleanAndSaveCookies(origRequestUrl, origCookieString, origHost)
            }

            val cookies = cookieString
                ?.split(";")
                ?.mapNotNull { Cookie.parse(request.url, "${it.trim()}; Domain=$currentHost; Path=/") }
                ?: emptyList()

            return createRequestWithCookies(request, cookies, userAgent)
        } finally {
            isDone.set(true)
            handler.removeCallbacks(checkCookiesTask)
            handler.postDelayed({
                activeResolutions.remove(host)
            }, 1000)
            latch?.countDown()
        }
    }

    private fun cleanAndSaveCookies(url: String, cookieString: String?, host: String) {
        val cookieManager = CookieManager.getInstance()
        val parsedCookies = cookieString
            ?.split(";")
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?.distinctBy { it.substringBefore("=").trim() }
            ?: emptyList()

        if (parsedCookies.isNotEmpty()) {
            parsedCookies.forEach { cookieStr ->
                val name = cookieStr.substringBefore("=").trim()
                val value = cookieStr.substringAfter("=").trim()
                if (name.isNotEmpty()) {
                    logToCrashFile("cleanAndSaveCookies() - Saving cookie: $name=$value")
                    if (name == "cf_clearance") {
                        cookieManager.setCookie(url, "$name=$value; Path=/; Expires=Thu, 01 Jan 1970 00:00:00 GMT; Domain=.$host")
                    } else {
                        cookieManager.setCookie(url, "$name=$value; Path=/; Expires=Thu, 01 Jan 1970 00:00:00 GMT; Domain=$host")
                    }
                }
            }
            cookieManager.flush()

            parsedCookies.forEach { cookieStr ->
                val name = cookieStr.substringBefore("=").trim()
                val value = cookieStr.substringAfter("=").trim()
                if (name.isNotEmpty()) {
                    logToCrashFile("cleanAndSaveCookies() - Saving cookie: $name=$value")
                    if (name == "cf_clearance") {
                        cookieManager.setCookie(url, "$name=$value; Path=/; Domain=.$host")
                    } else {
                        cookieManager.setCookie(url, "$name=$value; Path=/; Domain=$host")
                    }
                }
            }
            cookieManager.flush()
        }
    }

    private fun createRequestWithCookies(request: Request, cookies: List<Cookie>, userAgent: String): Request {
        val convertedForThisRequest = cookies.filter { it.matches(request.url) }
        val existingCookies = Cookie.parseAll(request.url, request.headers)
        val filteredExisting = existingCookies.filter { existing ->
            convertedForThisRequest.none { converted -> converted.name == existing.name }
        }

        val newCookies = (filteredExisting + convertedForThisRequest).distinctBy { it.name }
        val cookieString = newCookies.joinToString("; ") { "${it.name}=${it.value}" }
        logToCrashFile("createRequestWithCookies() - Attaching cookies to request: $cookieString")
        return request.newBuilder()
            .header("User-Agent", userAgent)
            .header("Cookie", cookieString)
            .build()
    }

    companion object {
        private val ERROR_CODES = listOf(403, 503)
        private val SERVER_CHECK = arrayOf("cloudflare-nginx", "cloudflare")
        private val activeResolutions = ConcurrentHashMap<String, CountDownLatch>()

        @Volatile
        private var webViewUserAgent: String? = null
    }
}
