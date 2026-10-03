package com.abrar.attendance.core

import java.math.BigDecimal
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

private val MONTHS = listOf(
    "january", "february", "march", "april", "may", "june",
    "july", "august", "september", "october", "november", "december",
)

class MonthData(
    val name: String, val year: Int, val month: Int,
    val rowOfRoll: Map<Int, Int>, val nameOfRoll: Map<Int, String>,
    val marks: Map<Pair<Int, Int>, String>,   // (roll, day) -> P/A/L
) { val days: Int get() = LocalDate.of(year, month, 1).lengthOfMonth() }

class ContactRow(val roll: Int, val name: String, val phone: String, val info: String, val row: Int)
class RemoteFollow(val row: Int, val rec: FollowRec)

object Reader {
    fun num(s: String?): Double? = s?.trim()?.toDoubleOrNull()

    fun phone(raw: String): String {
        var p = raw.trim()
        if (p.isEmpty()) return ""
        if (p.contains('E') || p.contains('e')) p = runCatching { BigDecimal(p).toPlainString() }.getOrDefault(p)
        if (p.endsWith(".0")) p = p.dropLast(2)
        return if (p.startsWith("0") || p.startsWith("+")) p else "0$p"
    }

    fun months(wb: Wb): List<MonthData> {
        val out = mutableListOf<MonthData>()
        for (name in wb.sheetPaths.keys) {
            val v = wb.sheet(name)!!.values()
            if (v["A6"]?.trim() != "Roll No.") continue
            val month = MONTHS.indexOf(v["F5"]?.trim()?.lowercase()) + 1
            val year = num(v["Z5"])?.toInt() ?: continue
            if (month == 0) continue
            val rows = HashMap<Int, Int>(); val names = HashMap<Int, String>()
            for ((ref, value) in v) {
                val (c, r) = X.split(ref)
                if (c == "A" && r >= 7) num(value)?.toInt()?.let { rows[it] = r; names[it] = v["B$r"] ?: "" }
            }
            val md = LocalDate.of(year, month, 1).lengthOfMonth()
            val marks = HashMap<Pair<Int, Int>, String>()
            for ((roll, r) in rows) for (day in 1..md) {
                val cell = v[X.idxToCol(day + 2) + r]?.trim()?.uppercase() ?: continue
                val m = if (cell == "TRUE") "P" else cell
                if (m == "P" || m == "A" || m == "L") marks[roll to day] = m
            }
            out += MonthData(name, year, month, rows, names, marks)
        }
        return out
    }

    fun contacts(wb: Wb): List<ContactRow> {
        val v = wb.sheet(CONTACTS_SHEET)?.values() ?: return emptyList()
        val out = mutableListOf<ContactRow>()
        for ((ref, value) in v) {
            val (c, r) = X.split(ref)
            if (c == "A" && r >= 2) num(value)?.toInt()?.let {
                out += ContactRow(it, v["B$r"] ?: "", phone(v["C$r"] ?: ""), v["D$r"] ?: "", r)
            }
        }
        return out
    }

    fun holidays(wb: Wb): List<LocalDate> {
        val v = wb.sheet(HOLIDAYS_SHEET)?.values() ?: return emptyList()
        return v.entries.mapNotNull { (ref, value) ->
            val (c, r) = X.split(ref)
            if (c == "G" && r >= 2) num(value)?.let { r to X.date(it.toLong()) } else null
        }.sortedBy { it.first }.map { it.second }.distinct()
    }

    private val TEXT_DATE = DateTimeFormatter.ofPattern("d-MMM-yy", Locale.ENGLISH)

    fun follow(wb: Wb): List<RemoteFollow> {
        val v = wb.sheet(FOLLOW_SHEET)?.values() ?: return emptyList()
        val rows = v.keys.map { X.split(it).second }.filter { it >= 2 }.distinct().sorted()
        val out = mutableListOf<RemoteFollow>()
        for (r in rows) {
            val roll = num(v["B$r"])?.toInt() ?: continue
            val a = v["A$r"] ?: continue
            val date = num(a)?.let { X.date(it.toLong()) } ?: runCatching { LocalDate.parse(a.trim(), TEXT_DATE) }.getOrNull() ?: continue
            out += RemoteFollow(r, FollowRec(r, date, roll, v["C$r"] ?: "", phone(v["D$r"] ?: ""),
                v["E$r"] ?: "", v["F$r"] ?: "", v["G$r"] ?: "", v["H$r"] ?: ""))
        }
        return out
    }
}

/** Same numbers the workbook shows: NETWORKDAYS.INTL(first, last, 7 = Fri+Sat weekend, Holidays). */
object Stats {
    fun workingDays(year: Int, month: Int, holidays: Set<LocalDate>): Int {
        var d = LocalDate.of(year, month, 1)
        val end = d.withDayOfMonth(d.lengthOfMonth())
        var n = 0
        while (!d.isAfter(end)) {
            if (d.dayOfWeek != DayOfWeek.FRIDAY && d.dayOfWeek != DayOfWeek.SATURDAY && d !in holidays) n++
            d = d.plusDays(1)
        }
        return n
    }

    fun isOffDay(d: LocalDate, holidays: Set<LocalDate>) =
        d.dayOfWeek == DayOfWeek.FRIDAY || d.dayOfWeek == DayOfWeek.SATURDAY || d in holidays

    /** Trailing run of 'A' marks, ignoring unmarked days; a P or L ends it (same as the workbook macro). */
    fun absentStreak(marksChronological: List<String>): Int {
        var n = 0
        for (m in marksChronological.asReversed()) {
            if (m == "A") n++ else if (m == "P" || m == "L") break
        }
        return n
    }
}

object SyncEngine {
    fun sync(local: LocalState, remoteBytes: ByteArray): SyncResult {
        val wb = Wb(XlsxPackage.read(remoteBytes))
        val warnings = mutableListOf<String>()
        var pushed = 0
        var pulled = 0
        var wrote = false

        val months = Reader.months(wb)
        val contacts = Reader.contacts(wb)
        val remoteHolidays = Reader.holidays(wb)
        val remoteFollow = Reader.follow(wb)

        // ---------------- attendance marks ----------------
        val marks = LinkedHashMap<Triple<String, Int, Int>, MarkRec>()
        for (m in months) for ((k, v) in m.marks) marks[Triple(m.name, k.first, k.second)] = MarkRec(m.name, k.first, k.second, v)
        val oldClean = local.marks.filter { !it.dirty }.associate { Triple(it.sheet, it.roll, it.day) to it.value }
        pulled += marks.count { (k, r) -> oldClean[k] != r.value } + oldClean.keys.count { it !in marks }

        val touched = HashMap<String, LocalDate>()
        for (d in local.marks.filter { it.dirty }) {
            val md = months.find { it.name == d.sheet }
            val row = md?.rowOfRoll?.get(d.roll)
            if (md == null || row == null || d.day !in 1..md.days) {
                warnings += "Skipped ${d.sheet} roll ${d.roll} day ${d.day}: not found in workbook"
                marks[Triple(d.sheet, d.roll, d.day)] = d
                continue
            }
            wb.sheet(md.name)!!.setCell(X.idxToCol(d.day + 2) + row, if (d.value.isBlank()) Val.Clear else Val.Str(d.value))
            val key = Triple(d.sheet, d.roll, d.day)
            if (d.value.isBlank()) marks.remove(key) else marks[key] = MarkRec(d.sheet, d.roll, d.day, d.value)
            val date = LocalDate.of(md.year, md.month, d.day)
            if (touched[md.name]?.isBefore(date) != false) touched[md.name] = date
            pushed++; wrote = true
        }
        // "Today" selector used by the Absent sheet
        for ((name, date) in touched) wb.sheet(name)!!.setCell("AI5", Val.Num(X.serial(date).toString()))

        // ---------------- students (Contacts) ----------------
        val students = LinkedHashMap<Int, StudentRec>()
        for (c in contacts) students[c.roll] = StudentRec(c.roll, c.name, c.phone, c.info)
        for (m in months) for ((roll, nm) in m.nameOfRoll) if (roll !in students) students[roll] = StudentRec(roll, nm, "", "")
        pulled += students.values.count { s -> local.students.firstOrNull { it.roll == s.roll && !it.dirty }?.let { it.phone != s.phone || it.info != s.info } ?: true }
        for (d in local.students.filter { it.dirty }) {
            val row = contacts.firstOrNull { it.roll == d.roll }?.row
            val cs = wb.sheet(CONTACTS_SHEET)
            if (row == null || cs == null) { warnings += "Roll ${d.roll} not in Contacts sheet"; students[d.roll] = d; continue }
            cs.setCell("C$row", if (d.phone.isBlank()) Val.Clear else Val.Str(d.phone))
            cs.setCell("D$row", if (d.info.isBlank()) Val.Clear else Val.Str(d.info))
            students[d.roll] = StudentRec(d.roll, students[d.roll]?.name ?: d.name, d.phone, d.info)
            pushed++; wrote = true
        }

        // ---------------- holidays ----------------
        val hol = LinkedHashSet(remoteHolidays)
        for (h in local.holidays) when (h.state) { 1 -> hol += h.date; 2 -> hol -= h.date }
        if (hol.toList() != remoteHolidays) {
            val hs = wb.sheet(HOLIDAYS_SHEET)
            if (hs == null) warnings += "Holidays sheet missing" else {
                hol.forEachIndexed { i, d -> hs.setCell("G${i + 2}", Val.Num(X.serial(d).toString()), 2) }
                for (i in hol.size until remoteHolidays.size) hs.setCell("G${i + 2}", Val.Clear)
                pushed += local.holidays.count { it.state != 0 }; wrote = true
            }
        }
        pulled += (remoteHolidays.toSet() - local.holidays.filter { it.state != 2 }.map { it.date }.toSet()).size

        // ---------------- follow-ups ----------------
        val follow = remoteFollow.map { it.rec }.toMutableList()
        var nextRow = (remoteFollow.maxOfOrNull { it.row } ?: 1) + 1
        pulled += remoteFollow.count { r -> local.follow.none { it.row == r.row && !it.dirty && it.copy(dirty = false) == r.rec } }
        val fs = wb.sheet(FOLLOW_SHEET)
        for (d in local.follow.filter { it.dirty }) {
            if (fs == null) { warnings += "Guardian_FollowUp sheet missing"; follow += d; continue }
            val at = remoteFollow.firstOrNull { it.row == d.row && it.rec.roll == d.roll && it.rec.date == d.date }
            val row = at?.row ?: nextRow++
            if (at == null) {
                fs.setCell("A$row", Val.Num(X.serial(d.date).toString()), row - 1)
                fs.setCell("B$row", Val.Num(d.roll.toString()), row - 1)
                fs.setCell("C$row", Val.Str(d.name), row - 1)
                fs.setCell("D$row", Val.Str(d.phone), row - 1)
            }
            fun put(col: String, s: String) = fs.setCell("$col$row", if (s.isBlank()) Val.Clear else Val.Str(s), row - 1)
            put("E", d.reason); put("F", d.homework); put("G", d.callStatus); put("H", d.taskVerified)
            val done = d.copy(row = row, dirty = false)
            if (at != null) follow[follow.indexOfFirst { it.row == row }] = done else follow += done
            pushed++; wrote = true
        }

        val state = LocalState(
            students = students.values.sortedBy { it.roll },
            marks = marks.values.toList(),
            holidays = hol.map { HolidayRec(it) },
            follow = follow,
            sheets = months.map { SheetRec(it.name, it.year, it.month) },
        )
        return SyncResult(state, if (wrote) wb.commit().toBytes() else null, pulled, pushed, warnings)
    }
}
