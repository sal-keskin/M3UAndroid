package com.m3u.data.database

import android.content.Context
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Migration26To27Test {
    @get:Rule
    val migrationHelper = MigrationTestHelper(
        instrumentation = InstrumentationRegistry.getInstrumentation(),
        databaseClass = M3UDatabase::class.java,
    )

    @Test
    fun migrationKeepsChannelsAndAddsEmptyLinkRegistry() {
        migrationHelper.createDatabase(DATABASE_NAME, 26).apply {
            execSQL(
                """
                INSERT INTO playlists (title, url, pinned_groups, hidden_groups, source, epg_urls, auto_refresh_programmes)
                VALUES ('Playlist', '$PLAYLIST_URL', '[]', '[]', 'm3u', '[]', 0)
                """.trimIndent()
            )
            insertStream(id = 7, relationId = "ATV.tr", favourite = 1, seen = 1_700_000_000_000)
            insertStream(id = 8, relationId = null, favourite = 0, seen = 0)
            close()
        }

        val database = Room.databaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(),
            M3UDatabase::class.java,
            DATABASE_NAME,
        )
            .allowMainThreadQueries()
            .addMigrations(DatabaseMigrations.MIGRATION_26_27)
            .build()
        val migrated = database.openHelper.writableDatabase

        migrated.query(
            "SELECT id, url, favourite, seen, relation_id, http_headers FROM streams ORDER BY id"
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(7, cursor.getInt(0))
            assertEquals("https://example.test/7.m3u8", cursor.getString(1))
            assertEquals(1, cursor.getInt(2))
            assertEquals(1_700_000_000_000, cursor.getLong(3))
            assertEquals("ATV.tr", cursor.getString(4))
            assertTrue(cursor.isNull(5))
            assertTrue(cursor.moveToNext())
            assertEquals(8, cursor.getInt(0))
            assertTrue(cursor.isNull(5))
            assertFalse(cursor.moveToNext())
        }
        assertEquals(0, migrated.count("registry_channels"))
        assertEquals(0, migrated.count("channel_streams"))

        // the new tables are usable and cascade from the playlist
        migrated.execSQL(
            "INSERT INTO registry_channels (canonical_id, playlist_url, rank) VALUES ('ATV.tr', '$PLAYLIST_URL', 1)"
        )
        migrated.execSQL(
            """
            INSERT INTO channel_streams (canonical_id, url, position, source_id, match_tier, match_score, status)
            VALUES ('ATV.tr', 'https://example.test/atv.m3u8', 0, 'self', 1, 1.0, 'ok')
            """.trimIndent()
        )
        migrated.execSQL("UPDATE streams SET http_headers = '{\"Referer\":\"https://www.atv.com.tr/\"}' WHERE id = 7")
        migrated.query("SELECT http_headers FROM streams WHERE id = 7").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("{\"Referer\":\"https://www.atv.com.tr/\"}", cursor.getString(0))
        }
        migrated.execSQL("PRAGMA foreign_keys = ON")
        migrated.execSQL("DELETE FROM playlists WHERE url = '$PLAYLIST_URL'")
        assertEquals(0, migrated.count("registry_channels"))
        assertEquals(0, migrated.count("channel_streams"))
        database.close()
    }

    private fun SupportSQLiteDatabase.insertStream(
        id: Int,
        relationId: String?,
        favourite: Int,
        seen: Long,
    ) {
        execSQL(
            """
            INSERT INTO streams (url, `group`, title, playlist_url, id, favourite, hidden, seen, relation_id)
            VALUES (?, ?, ?, ?, ?, ?, 0, ?, ?)
            """.trimIndent(),
            arrayOf<Any?>(
                "https://example.test/$id.m3u8",
                "Ulusal",
                "Channel $id",
                PLAYLIST_URL,
                id,
                favourite,
                seen,
                relationId,
            )
        )
    }

    private fun SupportSQLiteDatabase.count(table: String): Int =
        query("SELECT COUNT(*) FROM $table").use { cursor ->
            cursor.moveToFirst()
            cursor.getInt(0)
        }

    private companion object {
        const val DATABASE_NAME = "migration-26-27"
        const val PLAYLIST_URL = "registry://default"
    }
}
