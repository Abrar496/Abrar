package com.abrar.attendance.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.room.*
import com.abrar.attendance.core.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.time.LocalDate

@Entity(tableName = "students") data class StudentE(@PrimaryKey val roll: Int, val name: String, val phone: String, val info: String, val dirty: Boolean)
@Entity(tableName = "marks", primaryKeys = ["sheet", "roll", "day"]) data class MarkE(val sheet: String, val roll: Int, val day: Int, val value: String, val dirty: Boolean)
@Entity(tableName = "holidays") data class HolidayE(@PrimaryKey val epochDay: Long, val state: Int)
@Entity(tableName = "follow") data class FollowE(
    @PrimaryKey(autoGenerate = true) val id: Long = 0, val row: Int?, val epochDay: Long, val roll: Int, val name: String,
    val phone: String, val reason: String, val homework: String, val callStatus: String, val taskVerified: String, val dirty: Boolean,
)
@Entity(tableName = "sheets") data class SheetE(@PrimaryKey val name: String, val year: Int, val month: Int)

@Dao
interface AppDao {
    @Query("SELECT * FROM students ORDER BY roll") fun students(): Flow<List<StudentE>>
    @Query("SELECT * FROM marks") fun marks(): Flow<List<MarkE>>
    @Query("SELECT * FROM holidays ORDER BY epochDay") fun holidays(): Flow<List<HolidayE>>
    @Query("SELECT * FROM follow ORDER BY epochDay DESC, id DESC") fun follow(): Flow<List<FollowE>>
    @Query("SELECT * FROM sheets ORDER BY year, month") fun sheets(): Flow<List<SheetE>>

    @Query("SELECT * FROM students") suspend fun studentsNow(): List<StudentE>
    @Query("SELECT * FROM marks") suspend fun marksNow(): List<MarkE>
    @Query("SELECT * FROM holidays") suspend fun holidaysNow(): List<HolidayE>
    @Query("SELECT * FROM follow") suspend fun followNow(): List<FollowE>
    @Query("SELECT * FROM sheets") suspend fun sheetsNow(): List<SheetE>
    @Query("SELECT COUNT(*) FROM marks WHERE dirty=1") suspend fun dirtyMarks(): Int
    @Query("SELECT COUNT(*) FROM students WHERE dirty=1") suspend fun dirtyStudents(): Int
    @Query("SELECT COUNT(*) FROM holidays WHERE state<>0") suspend fun dirtyHolidays(): Int
    @Query("SELECT COUNT(*) FROM follow WHERE dirty=1") suspend fun dirtyFollow(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun put(m: MarkE)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun put(s: StudentE)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun put(h: HolidayE)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun put(f: FollowE)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putStudents(l: List<StudentE>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putMarks(l: List<MarkE>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putHolidays(l: List<HolidayE>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putFollow(l: List<FollowE>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putSheets(l: List<SheetE>)
    @Query("DELETE FROM students") suspend fun clearStudents()
    @Query("DELETE FROM marks") suspend fun clearMarks()
    @Query("DELETE FROM holidays") suspend fun clearHolidays()
    @Query("DELETE FROM follow") suspend fun clearFollow()
    @Query("DELETE FROM sheets") suspend fun clearSheets()
    @Query("DELETE FROM holidays WHERE epochDay=:d AND state=1") suspend fun dropAddedHoliday(d: Long)
}

@Database(entities = [StudentE::class, MarkE::class, HolidayE::class, FollowE::class, SheetE::class], version = 1, exportSchema = false)
abstract class AppDb : RoomDatabase() { abstract fun dao(): AppDao }

class Repo(private val ctx: Context) {
    private val db = Room.databaseBuilder(ctx, AppDb::class.java, "attendance.db").build()
    private val dao = db.dao()
    private val prefs = ctx.getSharedPreferences("app", Context.MODE_PRIVATE)
    private val mutex = Mutex()

    val students = dao.students(); val marks = dao.marks(); val holidays = dao.holidays()
    val follow = dao.follow(); val sheets = dao.sheets()

    val fileUri: Uri? get() = prefs.getString("uri", null)?.let { Uri.parse(it) }
    val fileName: String get() = prefs.getString("fname", "") ?: ""
    val lastSync: String get() = prefs.getString("last", "never")!!

    fun setFile(uri: Uri) {
        runCatching {
            ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        }
        val name = ctx.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        } ?: uri.lastPathSegment ?: "file"
        prefs.edit().putString("uri", uri.toString()).putString("fname", name).putString("hash", "").apply()
    }

    // Edits made while a sync is running are replayed after it commits, so nothing typed mid-sync is lost.
    @Volatile private var syncing = false
    private val replay = mutableListOf<suspend () -> Unit>()
    private suspend fun edit(block: suspend () -> Unit) {
        block()
        if (syncing) synchronized(replay) { replay += block }
    }

    suspend fun setMark(sheet: String, roll: Int, day: Int, value: String) = edit { dao.put(MarkE(sheet, roll, day, value, true)) }
    suspend fun saveStudent(s: StudentE) = edit { dao.put(s.copy(dirty = true)) }
    suspend fun addHoliday(d: LocalDate) = edit { dao.put(HolidayE(d.toEpochDay(), 1)) }
    suspend fun removeHoliday(d: LocalDate) = edit {
        val cur = dao.holidaysNow().firstOrNull { it.epochDay == d.toEpochDay() }
        if (cur?.state == 1) dao.dropAddedHoliday(d.toEpochDay()) else dao.put(HolidayE(d.toEpochDay(), 2))
    }
    suspend fun saveFollow(f: FollowE) = edit { dao.put(f.copy(dirty = true)) }

    private suspend fun hasDirty() = dao.dirtyMarks() + dao.dirtyStudents() + dao.dirtyHolidays() + dao.dirtyFollow() > 0

    private suspend fun snapshot() = LocalState(
        students = dao.studentsNow().map { StudentRec(it.roll, it.name, it.phone, it.info, it.dirty) },
        marks = dao.marksNow().map { MarkRec(it.sheet, it.roll, it.day, it.value, it.dirty) },
        holidays = dao.holidaysNow().map { HolidayRec(LocalDate.ofEpochDay(it.epochDay), it.state) },
        follow = dao.followNow().map { FollowRec(it.row, LocalDate.ofEpochDay(it.epochDay), it.roll, it.name, it.phone, it.reason, it.homework, it.callStatus, it.taskVerified, it.dirty) },
        sheets = dao.sheetsNow().map { SheetRec(it.name, it.year, it.month) },
    )

    private suspend fun commit(s: LocalState) = db.withTransaction {
        dao.clearStudents(); dao.clearMarks(); dao.clearHolidays(); dao.clearFollow(); dao.clearSheets()
        dao.putStudents(s.students.map { StudentE(it.roll, it.name, it.phone, it.info, it.dirty) })
        dao.putMarks(s.marks.map { MarkE(it.sheet, it.roll, it.day, it.value, it.dirty) })
        dao.putHolidays(s.holidays.map { HolidayE(it.date.toEpochDay(), it.state) })
        dao.putFollow(s.follow.map { FollowE(0, it.row, it.date.toEpochDay(), it.roll, it.name, it.phone, it.reason, it.homework, it.callStatus, it.taskVerified, it.dirty) })
        dao.putSheets(s.sheets.map { SheetE(it.name, it.year, it.month) })
    }

    private fun hash(b: ByteArray) = java.security.MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }
    private fun readFile(uri: Uri): ByteArray =
        ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: throw Exception("Cannot open the file. Choose it again in the Sync tab.")

    /** Two-way sync with the chosen workbook. Returns a short human-readable result. */
    suspend fun sync(): String = mutex.withLock {
        val uri = fileUri ?: return "Choose the Excel file first (Sync tab)"
        withContext(Dispatchers.IO) {
            for (attempt in 1..3) {
                val bytes = readFile(uri)
                val h = hash(bytes)
                val dirty = hasDirty()
                if (!dirty && h == prefs.getString("hash", "")) return@withContext "Up to date"
                File(ctx.filesDir, "last_remote.xlsm").writeBytes(bytes)   // safety copy of the file as it was
                synchronized(replay) { replay.clear() }
                syncing = true
                try {
                    val res = SyncEngine.sync(snapshot(), bytes)
                    var finalHash = h
                    if (res.bytes != null) {
                        XlsxPackage.read(res.bytes).text("xl/workbook.xml")     // sanity check before touching the file
                        if (hash(readFile(uri)) != h) continue                   // file changed meanwhile: start over
                        File(ctx.filesDir, "last_written.xlsm").writeBytes(res.bytes)
                        ctx.contentResolver.openOutputStream(uri, "wt")?.use { it.write(res.bytes) }
                            ?: throw Exception("Cannot write to the file")
                        finalHash = hash(res.bytes)
                    }
                    commit(res.state)
                    val pending = synchronized(replay) { replay.toList().also { replay.clear() } }
                    for (r in pending) r()
                    val now = java.text.SimpleDateFormat("d MMM, HH:mm", java.util.Locale.getDefault()).format(java.util.Date())
                    prefs.edit().putString("hash", finalHash).putString("last", now).apply()
                    val warn = if (res.warnings.isEmpty()) "" else "\n" + res.warnings.joinToString("\n")
                    return@withContext "Synced $now — sent ${res.pushed}, received ${res.pulled}$warn"
                } finally { syncing = false }
            }
            "The file kept changing — try again"
        }
    }
}
