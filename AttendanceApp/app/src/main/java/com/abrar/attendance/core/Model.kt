package com.abrar.attendance.core

import java.time.LocalDate

/** Plain, Android-free models so the whole sync engine can be unit-tested on a JVM. */
data class StudentRec(val roll: Int, val name: String, val phone: String, val info: String, val dirty: Boolean = false)

/** value "" with dirty=true means "clear this cell on next sync". */
data class MarkRec(val sheet: String, val roll: Int, val day: Int, val value: String, val dirty: Boolean = false)

/** state: 0 = in sync, 1 = added locally, 2 = removed locally */
data class HolidayRec(val date: LocalDate, val state: Int = 0)

data class FollowRec(
    val row: Int?,                 // row in Guardian_FollowUp at last sync (null = created in app)
    val date: LocalDate,
    val roll: Int,
    val name: String,
    val phone: String,
    val reason: String,
    val homework: String,
    val callStatus: String,
    val taskVerified: String,
    val dirty: Boolean = false,
)

data class SheetRec(val name: String, val year: Int, val month: Int)

data class LocalState(
    val students: List<StudentRec> = emptyList(),
    val marks: List<MarkRec> = emptyList(),
    val holidays: List<HolidayRec> = emptyList(),
    val follow: List<FollowRec> = emptyList(),
    val sheets: List<SheetRec> = emptyList(),
)

class SyncResult(
    val state: LocalState,
    val bytes: ByteArray?,         // new workbook to upload, null when nothing to push
    val pulled: Int,
    val pushed: Int,
    val warnings: List<String>,
)

const val FOLLOW_SHEET = "Guardian_FollowUp"
const val CONTACTS_SHEET = "Contacts"
const val HOLIDAYS_SHEET = "Holidays"
val CALL_STATUSES = listOf("Spoke with Parent", "Spoke with Student", "No Answer", "Line Busy", "Follow-up Needed")
val TASK_STATUSES = listOf("Pending", "Submitted & Checked", "Incomplete", "Late", "Excused")
