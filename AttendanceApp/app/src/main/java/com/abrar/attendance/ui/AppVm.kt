package com.abrar.attendance.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.abrar.attendance.data.*
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate

class AppVm(app: Application) : AndroidViewModel(app) {
    val repo = Repo(app)
    private fun <T> kotlinx.coroutines.flow.Flow<List<T>>.hot() = stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val sheets = repo.sheets.hot()
    val students = repo.students.hot()
    val marks = repo.marks.hot()
    val holidays = repo.holidays.hot()
    val follow = repo.follow.hot()

    val status = MutableStateFlow("")
    val busy = MutableStateFlow(false)
    val fileName = MutableStateFlow(repo.fileName)
    private var debounce: Job? = null

    init { if (repo.fileUri != null) sync() }

    fun sync() {
        if (busy.value) return
        viewModelScope.launch {
            busy.value = true; status.value = "Syncing…"
            status.value = try { repo.sync() } catch (e: Exception) { "Sync failed: ${e.message}" }
            busy.value = false
        }
    }

    /** Called after every local edit: write to the file a few seconds after the last change. */
    private fun touch() {
        debounce?.cancel()
        if (repo.fileUri != null) debounce = viewModelScope.launch { delay(3000); sync() }
    }

    fun chooseFile(uri: Uri) { repo.setFile(uri); fileName.value = repo.fileName; sync() }

    fun mark(sheet: String, roll: Int, day: Int, v: String) { viewModelScope.launch { repo.setMark(sheet, roll, day, v); touch() } }
    fun markMany(sheet: String, rolls: List<Int>, day: Int, v: String) {
        viewModelScope.launch { rolls.forEach { repo.setMark(sheet, it, day, v) }; touch() }
    }
    fun saveStudent(s: StudentE) { viewModelScope.launch { repo.saveStudent(s); touch() } }
    fun addHoliday(d: LocalDate) { viewModelScope.launch { repo.addHoliday(d); touch() } }
    fun removeHoliday(d: LocalDate) { viewModelScope.launch { repo.removeHoliday(d); touch() } }
    fun saveFollow(f: FollowE) { viewModelScope.launch { repo.saveFollow(f); touch() } }
}
