package eu.kanade.tachiyomi.animeextension.en.animepahe.network

import eu.kanade.tachiyomi.animeextension.en.animepahe.AnimePahePreferences
import okhttp3.OkHttpClient

class HttpClientFactory(
    private val baseClient: OkHttpClient,
    private val preferences: AnimePahePreferences,
) {
    fun createClient(): OkHttpClient {
        val builder = baseClient.newBuilder()

        if (!preferences.useOfflineMode) {
            val cloudflareInterceptor = CloudflareInterceptor(
                baseClient,
                useDefaultUserAgent = { preferences.useDefaultUserAgent },
            )
            builder.addInterceptor(cloudflareInterceptor)
        }

        val ddosGuardInterceptor = DdosGuardInterceptor(baseClient)

        return builder
            .addInterceptor(ddosGuardInterceptor)
            .build()
    }
}
