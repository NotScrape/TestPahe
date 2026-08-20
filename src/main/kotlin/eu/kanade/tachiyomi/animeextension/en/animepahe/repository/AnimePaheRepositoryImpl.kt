package eu.kanade.tachiyomi.animeextension.en.animepahe.repository

import eu.kanade.tachiyomi.animeextension.en.animepahe.database.AnimePaheDatabase
import eu.kanade.tachiyomi.animeextension.en.animepahe.database.AnimeSessionEntry
import eu.kanade.tachiyomi.animeextension.en.animepahe.dto.EpisodeDto
import eu.kanade.tachiyomi.animeextension.en.animepahe.dto.LatestAnimeDto
import eu.kanade.tachiyomi.animeextension.en.animepahe.dto.ResponseDto
import eu.kanade.tachiyomi.animeextension.en.animepahe.dto.SearchResultDto
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import eu.kanade.tachiyomi.util.parseAs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Response
import org.jsoup.nodes.Element
import org.jsoup.select.Elements
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Locale

class AnimePaheRepositoryImpl(
    private val client: OkHttpClient,
    private val database: AnimePaheDatabase,
    private val baseUrlProvider: () -> String,
) : AnimePaheRepository {

    override fun latestUpdatesParse(response: Response): AnimesPage {
        val latestData = response.parseAs<ResponseDto<LatestAnimeDto>>()
        val hasNextPage = latestData.currentPage < latestData.lastPage
        val sessionEntries = latestData.items.map {
            AnimeSessionEntry(it.id, it.animeSession, it.title)
        }
        database.saveSessions(sessionEntries)

        val animeList = latestData.items.map { anime ->
            SAnime.create().apply {
                title = anime.title
                thumbnail_url = anime.snapshot
                url = buildStableUrl(anime.id, anime.title)
                artist = anime.fansub
            }
        }
        return AnimesPage(animeList, hasNextPage)
    }

    override fun searchAnimeParse(response: Response): AnimesPage {
        val searchData = response.parseAs<ResponseDto<SearchResultDto>>()
        val sessionEntries = searchData.items.map {
            AnimeSessionEntry(it.id, it.session, it.title)
        }
        database.saveSessions(sessionEntries)

        val animeList = searchData.items.map { anime ->
            SAnime.create().apply {
                title = anime.title
                thumbnail_url = anime.poster
                url = buildStableUrl(anime.id, anime.title)
            }
        }
        return AnimesPage(animeList, false)
    }

    override suspend fun getOrFetchAnimeSession(animeId: Int, title: String, forceRefresh: Boolean): String? {
        if (!forceRefresh) {
            database.getSession(animeId)?.let { return it }
        }

        val encodedTitle = withContext(Dispatchers.IO) {
            URLEncoder.encode(title, "UTF-8")
        }
        val request = GET("${baseUrlProvider()}/api?m=search&l=8&q=$encodedTitle")
        return client.newCall(request).execute().use { response ->
            val searchData = response.parseAs<ResponseDto<SearchResultDto>>()
            val matched = searchData.items.firstOrNull { it.id == animeId }
                ?: searchData.items.firstOrNull { it.title.equals(title, ignoreCase = true) }

            if (matched != null) {
                database.saveSession(matched.id, matched.session, matched.title)
                matched.session
            } else {
                null
            }
        }
    }

    override fun invalidateSession(animeId: Int) {
        database.removeSession(animeId)
    }

    private fun buildStableUrl(id: Int, title: String): String {
        val encodedTitle = URLEncoder.encode(title, "UTF-8")
        return "/anime/?id=$id&title=$encodedTitle"
    }

    override fun animeDetailsParse(response: Response): SAnime {
        val document = response.asJsoup()
        return SAnime.create().apply {
            title = document.selectFirst("div.title-wrapper > h1 > span")!!.text()
            author = document.selectFirst("div.col-sm-4.anime-info p:contains(Studios:)")
                ?.text()
                ?.replace("Studios: ", "")
            status = parseStatus(
                document.selectFirst("div.col-sm-4.anime-info p:contains(Status:) strong")
                    ?.text()
                    ?.replace("Status: ", ""),
            )
            background_url = "https:" + document.selectFirst("div.anime-cover")!!.attr("data-src")
            thumbnail_url = document.selectFirst("div.anime-poster a")!!.attr("href")
            genre = document.select("div.anime-genre ul li").joinToString { it.text() }
            val synonyms = document.selectFirst("div.col-sm-4.anime-info p:contains(Synonyms:)")?.text()
            description = document.select("div.anime-summary").text() +
                if (synonyms.isNullOrEmpty()) "" else "\n\n$synonyms"
        }
    }

    override fun episodeListParse(response: Response): List<SEpisode> {
        val url = response.request.url.toString()
        val session = url.substringAfter("&id=").substringBefore("&")
        return parseEpisodes(response, session)
            .sortedBy { it.episode_number }
            .mapIndexed { index, episode ->
                episode.apply {
                    episode_number = (index + 1).toFloat()
                    name = "Episode ${index + 1}"
                }
            }
            .reversed()
    }

    override fun hosterListParse(response: Response, targetQuality: Int, useHLSLink: Boolean): List<Hoster> {
        val document = response.asJsoup()
        val downloadLinks = document.select("div#pickDownload > a")

        val videos = document.select("div#resolutionMenu > button").mapIndexedNotNull { index, btn ->
            parseVideo(btn, downloadLinks, index, targetQuality, useHLSLink)
        }.reversed()

        return listOf(Hoster(baseUrlProvider(), "AnimePahe", videos))
    }

    override suspend fun fetchEpisodeSession(animeSession: String, episodeIndex: String): String? {
        val request = GET("${baseUrlProvider()}/api?m=release&id=$animeSession&sort=episode_desc")
        return client.newCall(request).execute().use { response ->
            val episodesData = response.parseAs<ResponseDto<EpisodeDto>>()
            episodesData.items.firstOrNull { it.episodeNumber == episodeIndex.toFloatOrNull() }?.session
        }
    }

    private fun parseStatus(statusString: String?): Int {
        return when (statusString) {
            "Currently Airing" -> SAnime.ONGOING
            "Finished Airing" -> SAnime.COMPLETED
            else -> SAnime.UNKNOWN
        }
    }

    private fun parseVideo(
        btn: Element,
        downloadLinks: Elements,
        index: Int,
        targetQuality: Int,
        useHLSLink: Boolean,
    ): Video? {
        val qualityStr = btn.text()
        val quality = qualityStr.split("·").getOrNull(1)
            ?.trim()
            ?.takeWhile { it.isDigit() }
            ?.toIntOrNull() ?: return null

        val isPreferred = quality == targetQuality
        val kwikLink = btn.attr("data-src")
        val paheWinLink = downloadLinks.getOrNull(index)?.attr("href").orEmpty()

        return Video(
            videoUrl = if (useHLSLink) kwikLink else paheWinLink,
            videoTitle = qualityStr,
            resolution = quality,
            preferred = isPreferred,
            initialized = false,
        )
    }

    private fun parseEpisodes(initialResponse: Response, animeSession: String): List<SEpisode> {
        val episodeList = mutableListOf<SEpisode>()
        var currentResponse = initialResponse
        var page = 1

        try {
            while (true) {
                val episodesData = currentResponse.parseAs<ResponseDto<EpisodeDto>>()
                episodeList += parseEpisodePage(episodesData.items, animeSession)

                val currentPage = episodesData.currentPage
                val hasNextPage = currentPage < episodesData.lastPage

                if (!hasNextPage) break

                page++
                val url = initialResponse.request.url.toString()
                val nextUrl = buildNextPageUrl(url, page)

                val nextResponse = client.newCall(GET(nextUrl)).execute()
                if (currentResponse != initialResponse) {
                    currentResponse.close()
                }
                currentResponse = nextResponse
            }
        } finally {
            if (currentResponse != initialResponse) {
                currentResponse.close()
            }
        }
        return episodeList
    }

    private fun parseEpisodePage(episodes: List<EpisodeDto>, animeSession: String): List<SEpisode> {
        return episodes.map { episode ->
            SEpisode.create().apply {
                episode_number = episode.episodeNumber
                name = "Episode ${episode.episodeNumber}"
                url = "/play/$animeSession/${episode.session}"
                date_upload = episode.createdAt.toDate()
                fillermark = episode.filler != 0
                preview_url = episode.snapshot
            }
        }
    }

    private fun buildNextPageUrl(url: String, page: Int): String {
        return url.substringBeforeLast("&page=") + "&page=$page"
    }

    private fun String.toDate(): Long {
        return runCatching {
            DATE_FORMATTER.parse(this)?.time ?: 0L
        }.getOrNull() ?: 0L
    }

    companion object {
        private val DATE_FORMATTER by lazy {
            SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ENGLISH)
        }
    }
}
