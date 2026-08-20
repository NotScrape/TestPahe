package eu.kanade.tachiyomi.animeextension.en.animepahe.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class EpisodeDto(
    val id: Int,
    @SerialName("anime_id")
    val animeId: Int,
    @SerialName("episode")
    val episodeNumber: Float,
    val episode2: Float,
    val edition: String,
    val title: String,
    val snapshot: String,
    val disc: String,
    val audio: String,
    val duration: String,
    val session: String,
    val filler: Int,
    @SerialName("created_at")
    val createdAt: String,
)
