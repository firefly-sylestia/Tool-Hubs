package com.sylestia.june.data

import android.content.ContentValues
import android.content.Context
import net.sqlcipher.database.SQLiteDatabase
import net.sqlcipher.database.SQLiteOpenHelper
import java.time.LocalDate

data class JournalEntry(
    val id: Long = 0,
    val title: String = "",
    val body: String = "",
    val journalDate: LocalDate = LocalDate.now(),
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val mode: String = "classic",
    val archived: Boolean = false,
    val deletedAt: Long? = null
)

class JournalDatabase(context: Context) :
    SQLiteOpenHelper(context.applicationContext, "june-journal.db", null, 1) {

    private val passphrase = JournalKeyStore.getDatabasePassphrase(context)

    init {
        SQLiteDatabase.loadLibs(context.applicationContext)
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE entries (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                title TEXT NOT NULL DEFAULT '',
                body TEXT NOT NULL DEFAULT '',
                journal_date TEXT NOT NULL,
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL,
                mode TEXT NOT NULL DEFAULT 'classic',
                archived INTEGER NOT NULL DEFAULT 0,
                deleted_at INTEGER
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX index_entries_date ON entries(journal_date DESC)")
        db.execSQL("CREATE INDEX index_entries_updated ON entries(updated_at DESC)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Schema changes must use explicit, non-destructive migrations.
    }

    private fun writable() = getWritableDatabase(passphrase)
    private fun readable() = getReadableDatabase(passphrase)

    fun save(entry: JournalEntry): JournalEntry {
        val now = System.currentTimeMillis()
        val values = ContentValues().apply {
            put("title", entry.title)
            put("body", entry.body)
            put("journal_date", entry.journalDate.toString())
            put("created_at", entry.createdAt)
            put("updated_at", now)
            put("mode", entry.mode)
            put("archived", if (entry.archived) 1 else 0)
            put("deleted_at", entry.deletedAt)
        }
        val db = writable()
        val id = if (entry.id == 0L) db.insertOrThrow("entries", null, values) else {
            db.update("entries", values, "id = ?", arrayOf(entry.id.toString()))
            entry.id
        }
        return entry.copy(id = id, updatedAt = now)
    }

    fun all(includeArchived: Boolean = false): List<JournalEntry> {
        val clause = if (includeArchived) "deleted_at IS NULL" else "deleted_at IS NULL AND archived = 0"
        return query("SELECT * FROM entries WHERE " + clause + " ORDER BY journal_date DESC, updated_at DESC")
    }

    fun search(term: String): List<JournalEntry> {
        val pattern = "%" + term.trim() + "%"
        if (term.isBlank()) return all()
        return query(
            "SELECT * FROM entries WHERE deleted_at IS NULL AND (title LIKE ? OR body LIKE ?) ORDER BY journal_date DESC",
            arrayOf(pattern, pattern)
        )
    }

    fun archived(): List<JournalEntry> =
        query("SELECT * FROM entries WHERE archived = 1 AND deleted_at IS NULL ORDER BY journal_date DESC")

    fun trash(): List<JournalEntry> =
        query("SELECT * FROM entries WHERE deleted_at IS NOT NULL ORDER BY deleted_at DESC")

    fun setArchived(id: Long, value: Boolean) {
        writable().execSQL("UPDATE entries SET archived = ?, updated_at = ? WHERE id = ?",
            arrayOf(if (value) 1 else 0, System.currentTimeMillis(), id))
    }

    fun moveToTrash(id: Long) {
        writable().execSQL("UPDATE entries SET deleted_at = ? WHERE id = ?",
            arrayOf(System.currentTimeMillis(), id))
    }

    fun restore(id: Long) {
        writable().execSQL("UPDATE entries SET deleted_at = NULL WHERE id = ?", arrayOf(id))
    }

    fun permanentlyDelete(id: Long) {
        writable().delete("entries", "id = ?", arrayOf(id.toString()))
    }

    private fun query(sql: String, args: Array<String> = emptyArray()): List<JournalEntry> {
        val result = mutableListOf<JournalEntry>()
        readable().rawQuery(sql, args).use { cursor ->
            while (cursor.moveToNext()) {
                val deletedIndex = cursor.getColumnIndexOrThrow("deleted_at")
                result += JournalEntry(
                    id = cursor.getLong(cursor.getColumnIndexOrThrow("id")),
                    title = cursor.getString(cursor.getColumnIndexOrThrow("title")) ?: "",
                    body = cursor.getString(cursor.getColumnIndexOrThrow("body")) ?: "",
                    journalDate = LocalDate.parse(cursor.getString(cursor.getColumnIndexOrThrow("journal_date"))),
                    createdAt = cursor.getLong(cursor.getColumnIndexOrThrow("created_at")),
                    updatedAt = cursor.getLong(cursor.getColumnIndexOrThrow("updated_at")),
                    mode = cursor.getString(cursor.getColumnIndexOrThrow("mode")) ?: "classic",
                    archived = cursor.getInt(cursor.getColumnIndexOrThrow("archived")) == 1,
                    deletedAt = if (cursor.isNull(deletedIndex)) null else cursor.getLong(deletedIndex)
                )
            }
        }
        return result
    }
}
