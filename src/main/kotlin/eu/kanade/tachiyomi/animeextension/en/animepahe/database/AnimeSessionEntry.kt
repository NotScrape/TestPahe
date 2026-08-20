package eu.kanade.tachiyomi.animeextension.en.animepahe.database

data class AnimeSessionEntry(
    val animeId: Int,
    val session: String,
    val title: String? = null,
)
