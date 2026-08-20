package eu.kanade.tachiyomi.animeextension.en.animepahe

import android.content.Context
import android.content.SharedPreferences
import android.widget.Toast
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import eu.kanade.tachiyomi.animeextension.en.animepahe.database.AnimePaheDatabase

class AnimePahePreferences(context: Context, sourceId: Long) {
    private val preferences: SharedPreferences = context.getSharedPreferences("source_$sourceId", 0)

    val preferredQuality: Int
        get() = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)?.toIntOrNull() ?: 0

    val preferredDomain: String
        get() = preferences.getString(PREF_DOMAIN_KEY, PREF_DOMAIN_DEFAULT) ?: PREF_DOMAIN_DEFAULT

    val preferSub: String
        get() = preferences.getString(PREF_SUB_KEY, PREF_SUB_DEFAULT) ?: PREF_SUB_DEFAULT

    val useHlsLinks: Boolean
        get() = preferences.getBoolean(PREF_LINK_TYPE_KEY, PREF_LINK_TYPE_DEFAULT)

    val useAv1Codec: Boolean
        get() = preferences.getBoolean(PREF_AV1_KEY, PREF_AV1_DEFAULT)

    val useDefaultUserAgent: Boolean
        get() = preferences.getBoolean(PREF_DEFAULT_UA_KEY, PREF_DEFAULT_UA_DEFAULT)

    val useOfflineMode: Boolean
        get() = preferences.getBoolean(PREF_OFFLINE_KEY, PREF_OFFLINE_DEFAULT)

    fun setupPreferenceScreen(screen: PreferenceScreen, database: AnimePaheDatabase) {
        val videoQualityPref = ListPreference(screen.context).apply {
            key = PREF_QUALITY_KEY
            title = PREF_QUALITY_TITLE
            entries = PREF_QUALITY_ENTRIES
            entryValues = PREF_QUALITY_ENTRIES
            setDefaultValue(PREF_QUALITY_DEFAULT)
            summary = "%s"
            bindToStringPreference()
        }
        val domainPref = ListPreference(screen.context).apply {
            key = PREF_DOMAIN_KEY
            title = PREF_DOMAIN_TITLE
            entries = PREF_DOMAIN_ENTRIES
            entryValues = PREF_DOMAIN_VALUES
            setDefaultValue(PREF_DOMAIN_DEFAULT)
            summary = "%s"
            bindToStringPreference()
        }
        val subPref = ListPreference(screen.context).apply {
            key = PREF_SUB_KEY
            title = PREF_SUB_TITLE
            entries = PREF_SUB_ENTRIES
            entryValues = PREF_SUB_VALUES
            setDefaultValue(PREF_SUB_DEFAULT)
            summary = "%s"
            bindToStringPreference()
        }
        val linkPref = SwitchPreferenceCompat(screen.context).apply {
            key = PREF_LINK_TYPE_KEY
            title = PREF_LINK_TYPE_TITLE
            summary = PREF_LINK_TYPE_SUMMARY
            setDefaultValue(PREF_LINK_TYPE_DEFAULT)
            bindToBooleanPreference()
        }
        val av1Pref = SwitchPreferenceCompat(screen.context).apply {
            key = PREF_AV1_KEY
            title = PREF_AV1_TITLE
            summary = PREF_AV1_SUMMARY
            setDefaultValue(PREF_AV1_DEFAULT)
            bindToBooleanPreference()
        }
        val defaultUaPref = SwitchPreferenceCompat(screen.context).apply {
            key = PREF_DEFAULT_UA_KEY
            title = PREF_DEFAULT_UA_TITLE
            summary = PREF_DEFAULT_UA_SUMMARY
            setDefaultValue(PREF_DEFAULT_UA_DEFAULT)
            bindToBooleanPreference()
        }
        val offlinePref = SwitchPreferenceCompat(screen.context).apply {
            key = PREF_OFFLINE_KEY
            title = PREF_OFFLINE_TITLE
            summary = PREF_OFFLINE_SUMMARY
            setDefaultValue(PREF_OFFLINE_DEFAULT)
            bindToBooleanPreference()
        }
        screen.addPreference(videoQualityPref)
        screen.addPreference(domainPref)
        screen.addPreference(subPref)
        screen.addPreference(linkPref)
        screen.addPreference(av1Pref)
        screen.addPreference(defaultUaPref)
        screen.addPreference(offlinePref)

        val clearCachePref = Preference().apply {
            key = PREF_CLEAR_CACHE_KEY
            title = PREF_CLEAR_CACHE_TITLE
            summary = PREF_CLEAR_CACHE_SUMMARY

            setOnPreferenceClickListener {
                val count = database.clearAllSessions()
                Toast.makeText(
                    screen.context,
                    "Cleared $count cached session(s)",
                    Toast.LENGTH_SHORT,
                ).show()
                true
            }
        }
        screen.addPreference(clearCachePref)
    }

    private fun ListPreference.bindToStringPreference() {
        setOnPreferenceChangeListener { _, newValue ->
            val selected = newValue as String
            val index = findIndexOfValue(selected)
            val entry = entryValues[index] as String
            preferences.edit().putString(key, entry).apply()
            true
        }
    }

    private fun SwitchPreferenceCompat.bindToBooleanPreference() {
        setOnPreferenceChangeListener { _, newValue ->
            val new = newValue as Boolean
            preferences.edit().putBoolean(key, new).apply()
            true
        }
    }

    companion object {
        private const val PREF_QUALITY_KEY = "preffered_quality"
        private const val PREF_QUALITY_TITLE = "Preferred quality"
        private const val PREF_QUALITY_DEFAULT = "1080"
        private val PREF_QUALITY_ENTRIES = arrayOf("1080", "720", "360")

        private const val PREF_DOMAIN_KEY = "preffered_domain"
        private const val PREF_DOMAIN_TITLE = "Preferred domain (requires app restart)"
        private const val PREF_DOMAIN_DEFAULT = "https://animepahe.pw"
        private val PREF_DOMAIN_ENTRIES = arrayOf("animepahe.pw", "animepahe.com", "animepahe.org")
        private val PREF_DOMAIN_VALUES by lazy {
            PREF_DOMAIN_ENTRIES.map { "https://" + it }.toTypedArray()
        }

        private const val PREF_SUB_KEY = "preffered_sub"
        private const val PREF_SUB_TITLE = "Prefer subs or dubs?"
        private const val PREF_SUB_DEFAULT = "jpn"
        private val PREF_SUB_ENTRIES = arrayOf("sub", "dub")
        private val PREF_SUB_VALUES = arrayOf("jpn", "eng")

        private const val PREF_LINK_TYPE_KEY = "preffered_link_type"
        private const val PREF_LINK_TYPE_TITLE = "Use HLS links"
        private const val PREF_LINK_TYPE_DEFAULT = false
        private val PREF_LINK_TYPE_SUMMARY by lazy {
            """Enable this if you are having Cloudflare issues.
            |Note that this will break the ability to seek inside of the video unless the episode is downloaded in advance.
            """.trimMargin()
        }

        // Big slap to whoever misspelled `preferred`
        private const val PREF_AV1_KEY = "preffered_av1"
        private const val PREF_AV1_TITLE = "Use AV1 codec"
        private const val PREF_AV1_DEFAULT = false
        private val PREF_AV1_SUMMARY by lazy {
            """Enable to use AV1 if available
            |Turn off to never select av1 as preferred codec
            """.trimMargin()
        }

        private const val PREF_DEFAULT_UA_KEY = "use_default_user_agent"
        private const val PREF_DEFAULT_UA_TITLE = "Use default WebView User-Agent"
        private const val PREF_DEFAULT_UA_DEFAULT = false
        private const val PREF_DEFAULT_UA_SUMMARY = "Enabling this uses the device's native WebView User-Agent."

        private const val PREF_OFFLINE_KEY = "offline_mode"
        private const val PREF_OFFLINE_TITLE = "Offline mode"
        private const val PREF_OFFLINE_DEFAULT = false
        private const val PREF_OFFLINE_SUMMARY = "Disables the Cloudflare interceptor (requires app restart)."

        private const val PREF_CLEAR_CACHE_KEY = "clear_session_cache"
        private const val PREF_CLEAR_CACHE_TITLE = "Clear session cache"
        private const val PREF_CLEAR_CACHE_SUMMARY = "Delete all cached anime sessions from the local database"
    }
}
