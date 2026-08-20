package eu.kanade.tachiyomi.animeextension.en.animepahe.database

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

class AnimePaheDatabase(context: Context) : SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $TABLE_SESSIONS (
                $COL_ANIME_ID INTEGER PRIMARY KEY,
                $COL_SESSION TEXT NOT NULL,
                $COL_TITLE TEXT,
                $COL_UPDATED_AT INTEGER NOT NULL
            )
            """.trimIndent(),
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS $TABLE_SESSIONS")
        onCreate(db)
    }

    override fun onOpen(db: SQLiteDatabase) {
        super.onOpen(db)
        pruneOldSessions(db)
    }

    fun getSession(animeId: Int): String? {
        val cursor = readableDatabase.query(
            TABLE_SESSIONS,
            arrayOf(COL_SESSION),
            "$COL_ANIME_ID = ?",
            arrayOf(animeId.toString()),
            null,
            null,
            null,
        )
        return cursor.use {
            if (it.moveToFirst()) it.getString(0) else null
        }
    }

    fun saveSession(animeId: Int, session: String, title: String? = null) {
        saveSessions(listOf(AnimeSessionEntry(animeId, session, title)))
    }

    fun saveSessions(entries: List<AnimeSessionEntry>) {
        if (entries.isEmpty()) return
        val db = writableDatabase
        db.beginTransaction()
        try {
            val now = System.currentTimeMillis()
            for (entry in entries) {
                val values = ContentValues().apply {
                    put(COL_ANIME_ID, entry.animeId)
                    put(COL_SESSION, entry.session)
                    put(COL_TITLE, entry.title)
                    put(COL_UPDATED_AT, now)
                }
                db.insertWithOnConflict(TABLE_SESSIONS, null, values, SQLiteDatabase.CONFLICT_REPLACE)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun removeSession(animeId: Int): Int {
        return writableDatabase.delete(TABLE_SESSIONS, "$COL_ANIME_ID = ?", arrayOf(animeId.toString()))
    }

    fun clearAllSessions(): Int {
        return writableDatabase.delete(TABLE_SESSIONS, null, null)
    }

    fun pruneOldSessions(maxAgeDays: Int = DEFAULT_MAX_AGE_DAYS): Int {
        return pruneOldSessions(writableDatabase, maxAgeDays)
    }

    private fun pruneOldSessions(db: SQLiteDatabase, maxAgeDays: Int = DEFAULT_MAX_AGE_DAYS): Int {
        val threshold = System.currentTimeMillis() - (maxAgeDays * 24L * 60 * 60 * 1000)
        return db.delete(TABLE_SESSIONS, "$COL_UPDATED_AT < ?", arrayOf(threshold.toString()))
    }

    companion object {
        private const val DB_NAME = "animepahe_cache.db"
        private const val DB_VERSION = 1
        private const val DEFAULT_MAX_AGE_DAYS = 30

        private const val TABLE_SESSIONS = "anime_sessions"
        private const val COL_ANIME_ID = "anime_id"
        private const val COL_SESSION = "session"
        private const val COL_TITLE = "title"
        private const val COL_UPDATED_AT = "updated_at"
    }
}
