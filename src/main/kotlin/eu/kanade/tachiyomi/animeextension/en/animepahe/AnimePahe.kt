package eu.kanade.tachiyomi.animeextension.en.animepahe

import android.app.Application
import android.util.Log
import android.webkit.WebSettings
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animeextension.en.animepahe.database.AnimePaheDatabase
import eu.kanade.tachiyomi.animeextension.en.animepahe.network.HttpClientFactory
import eu.kanade.tachiyomi.animeextension.en.animepahe.proxy.KwikProxyServer
import eu.kanade.tachiyomi.animeextension.en.animepahe.repository.AnimePaheRepository
import eu.kanade.tachiyomi.animeextension.en.animepahe.repository.AnimePaheRepositoryImpl
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.HttpException
import eu.kanade.tachiyomi.network.awaitSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.IOException
import java.net.URLDecoder

class AnimePahe : ConfigurableAnimeSource, AnimeHttpSource() {

    override val name = "AnimePahe"
    override val lang = "en"
    override val supportsLatest = true

    private val context by lazy {
        Injekt.get<Application>()
    }

    private val preferences: AnimePahePreferences by lazy {
        AnimePahePreferences(context, id)
    }

    private val database: AnimePaheDatabase by lazy {
        AnimePaheDatabase(context)
    }

    override val baseUrl by lazy {
        preferences.preferredDomain
    }

    private val userAgent: String
        get() = if (preferences.useDefaultUserAgent) {
            WebSettings.getDefaultUserAgent(context)
        } else {
            network.defaultUserAgentProvider()
        }

    private val httpClientFactory by lazy {
        HttpClientFactory(network.client, preferences)
    }

    override val client: OkHttpClient by lazy {
        httpClientFactory.createClient()
    }

    private val repository: AnimePaheRepository by lazy {
        AnimePaheRepositoryImpl(client, database) { baseUrl }
    }

    private val extractor: KwikExtractor by lazy {
        KwikExtractor(client, { preferences.useDefaultUserAgent }, { userAgent })
    }

    // =========================== Anime Details ============================
    override suspend fun getAnimeDetails(anime: SAnime): SAnime = executeWithRetry(anime) { session ->
        val response = client.newCall(GET("$baseUrl/anime/$session")).awaitSuccess()
        animeDetailsParse(response).apply { initialized = true }
    }

    override fun animeDetailsRequest(anime: SAnime): Request {
        val session = runBlocking(Dispatchers.IO) { resolveSession(anime) }
        return GET("$baseUrl/anime/$session")
    }

    override fun animeDetailsParse(response: Response): SAnime =
        repository.animeDetailsParse(response)

    // =============================== Latest ===============================
    override fun latestUpdatesRequest(page: Int): Request =
        GET("$baseUrl/api?m=airing&page=$page")

    override fun latestUpdatesParse(response: Response): AnimesPage =
        repository.latestUpdatesParse(response)

    // =============================== Search ===============================
    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request =
        GET("$baseUrl/api?m=search&l=8&q=$query")

    override fun searchAnimeParse(response: Response): AnimesPage =
        repository.searchAnimeParse(response)

    // ============================== Popular ===============================
    // This source doesnt have a popular animes page,
    // so we use latest animes page instead.
    override suspend fun getPopularAnime(page: Int): AnimesPage =
        getLatestUpdates(page)

    override fun popularAnimeParse(response: Response): AnimesPage = TODO()
    override fun popularAnimeRequest(page: Int): Request = TODO()

    // ============================== Episodes ==============================
    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> = executeWithRetry(anime) { session ->
        val request = GET("$baseUrl/api?m=release&id=$session&sort=episode_desc&page=1")
        val response = client.newCall(request).awaitSuccess()
        episodeListParse(response)
    }

    override fun episodeListRequest(anime: SAnime): Request {
        val session = runBlocking(Dispatchers.IO) { resolveSession(anime) }
        return GET("$baseUrl/api?m=release&id=$session&sort=episode_desc&page=1")
    }

    override fun episodeListParse(response: Response): List<SEpisode> =
        repository.episodeListParse(response)

    override fun seasonListParse(response: Response): List<SAnime> = emptyList()

    // ============================== Hosters ==============================
    override fun hosterListParse(response: Response): List<Hoster> =
        repository.hosterListParse(response, preferences.preferredQuality, preferences.useHlsLinks)

    override fun videoListParse(response: Response, hoster: Hoster): List<Video> =
        hoster.videoList ?: emptyList()

    override fun videoListRequest(hoster: Hoster): Request =
        GET(hoster.hosterUrl, headers)

    override fun List<Video>.sortVideos(): List<Video> {
        val subPreference = preferences.preferSub
        val quality = preferences.preferredQuality.toString()

        return this.sortedWith(
            compareBy(
                { it.resolution == null },
                { it.videoTitle.contains(ENG_REGEX) != (subPreference == "eng") },
                { !it.resolution.toString().contains(quality) },
            ),
        )
    }

    // ============================ Video Links =============================
    override suspend fun resolveVideo(video: Video): Video? {
        val referer = "https://kwik.cx/"
        return try {
            val resolvedUrl = if (preferences.useHlsLinks) {
                extractor.getHlsStreamUrl(video.videoUrl, referer = referer)
            } else {
                extractor.getStreamUrlFromKwik(video.videoUrl, referer = referer)
            }
            val proxiedUrl = KwikProxyServer.getProxyUrl(client, { userAgent }, resolvedUrl)

            Video(
                videoUrl = proxiedUrl,
                videoTitle = video.videoTitle,
                resolution = video.resolution,
                headers = null,
                preferred = video.preferred,
                initialized = true,
            )
        } catch (e: Exception) {
            Log.e("AnimePahe", "Failed to resolve video URL: ${video.videoUrl}", e)
            null
        }
    }

    // ============================== Settings ==============================
    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        preferences.setupPreferenceScreen(screen, database)
    }

    // ============================= Utilities ==============================
    private suspend fun <T> executeWithRetry(anime: SAnime, block: suspend (session: String) -> T): T {
        val session = resolveSession(anime)
        return try {
            block(session)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val animeId = anime.getAnimeId()
            if (animeId == null || !isSessionError(e)) {
                throw e
            }
            Log.w("AnimePahe", "Session error for '${anime.title}', refreshing session...", e)
            repository.invalidateSession(animeId)
            val freshSession = resolveSession(anime, forceRefresh = true)
            block(freshSession)
        }
    }

    private fun isSessionError(e: Exception): Boolean {
        return when (e) {
            is HttpException -> e.code == 404
            is IOException -> false
            else -> true
        }
    }

    private fun SAnime.getAnimeId(): Int? =
        url.substringAfter("?id=", "").substringBefore("&").toIntOrNull()

    private fun SAnime.getAnimeTitle(): String {
        val encoded = url.substringAfter("&title=", "")
        return runCatching { URLDecoder.decode(encoded, "UTF-8") }.getOrDefault(encoded).ifEmpty { title }
    }

    private suspend fun resolveSession(anime: SAnime, forceRefresh: Boolean = false): String {
        val animeId = anime.getAnimeId()
        if (animeId != null) {
            val session = repository.getOrFetchAnimeSession(animeId, anime.getAnimeTitle(), forceRefresh)
            if (!session.isNullOrBlank()) return session
            throw IllegalStateException("Unable to resolve active session for '${anime.title}'")
        }
        // Fallback
        val legacySession = anime.url.substringAfterLast("anime/").substringBefore("?").trim()
        if (legacySession.isNotBlank()) return legacySession
        throw IllegalStateException("Invalid anime URL: ${anime.url}")
    }

    companion object {
        private val ENG_REGEX = Regex("""\beng\b""")
    }
}
