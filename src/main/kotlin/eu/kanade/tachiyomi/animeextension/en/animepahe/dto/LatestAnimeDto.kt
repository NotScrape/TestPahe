package eu.kanade.tachiyomi.animeextension.en.animepahe.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class LatestAnimeDto(
    @SerialName("anime_title")
    val title: String,
    val snapshot: String,
    @SerialName("anime_id")
    val id: Int,
    val fansub: String,
    @SerialName("anime_session")
    val animeSession: String,
)
