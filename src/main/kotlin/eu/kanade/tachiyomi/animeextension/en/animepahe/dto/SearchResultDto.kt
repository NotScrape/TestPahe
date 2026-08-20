package eu.kanade.tachiyomi.animeextension.en.animepahe.dto

import kotlinx.serialization.Serializable

@Serializable
data class SearchResultDto(
    val title: String,
    val poster: String,
    val id: Int,
    val session: String,
)
