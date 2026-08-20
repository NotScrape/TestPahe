package eu.kanade.tachiyomi.animeextension.en.animepahe.repository

import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import okhttp3.Response

interface AnimePaheRepository {
    fun latestUpdatesParse(response: Response): AnimesPage
    fun searchAnimeParse(response: Response): AnimesPage
    fun animeDetailsParse(response: Response): SAnime
    fun episodeListParse(response: Response): List<SEpisode>
    fun hosterListParse(response: Response, targetQuality: Int, useHLSLink: Boolean): List<Hoster>
    suspend fun fetchEpisodeSession(animeSession: String, episodeIndex: String): String?
    suspend fun getOrFetchAnimeSession(animeId: Int, title: String, forceRefresh: Boolean = false): String?
    fun invalidateSession(animeId: Int)
}
