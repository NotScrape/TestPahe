package eu.kanade.tachiyomi.animeextension.en.animepahe.dto

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ResponseDto<T>(
    @SerialName("current_page")
    val currentPage: Int,
    @SerialName("last_page")
    val lastPage: Int,
    @EncodeDefault
    @SerialName("data")
    val items: List<T> = emptyList(),
)
