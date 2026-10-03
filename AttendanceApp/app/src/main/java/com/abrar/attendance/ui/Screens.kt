@file:OptIn(ExperimentalLayoutApi::class)

package com.abrar.attendance.ui

import android.app.DatePickerDialog
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.abrar.attendance.core.*
import com.abrar.attendance.data.*
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

private val GREEN = Color(0xFF2E7D32)
private val RED = Color(0xFFC62828)
private val AMBER = Color(0xFFEF6C00)
private val DATE_FMT = DateTimeFormatter.ofPattern("EEE, d MMM yyyy", Locale.ENGLISH)

private fun SheetE.days() = LocalDate.of(year, month, 1).lengthOfMonth()
private fun SheetE.date(day: Int) = LocalDate.of(year, month, day)

@Composable
fun App(vm: AppVm = viewModel()) {
    val sheets by vm.sheets.collectAsStateWithLifecycle()
    val students by vm.students.collectAsStateWithLifecycle()
    val marks by vm.marks.collectAsStateWithLifecycle()
    val holidays by vm.holidays.collectAsStateWithLifecycle()
    val follow by vm.follow.collectAsStateWithLifecycle()
    val status by vm.status.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()

    var tab by rememberSaveable { mutableIntStateOf(0) }
    var sheetName by rememberSaveable { mutableStateOf<String?>(null) }
    var day by rememberSaveable { mutableIntStateOf(0) }
    var detail by remember { mutableStateOf<Int?>(null) }

    val markMap = remember(marks) { marks.filter { it.value.isNotBlank() }.associate { Triple(it.sheet, it.roll, it.day) to it.value } }
    val holidaySet = remember(holidays) { holidays.filter { it.state != 2 }.map { LocalDate.ofEpochDay(it.epochDay) }.toSet() }
    val sheet = sheets.firstOrNull { it.name == sheetName } ?: sheets.lastOrNull()
    val effDay = remember(sheet, day, markMap) {
        when {
            sheet == null -> 0
            day in 1..sheet.days() -> day
            LocalDate.now().year == sheet.year && LocalDate.now().monthValue == sheet.month -> LocalDate.now().dayOfMonth
            else -> markMap.keys.filter { it.first == sheet.name }.maxOfOrNull { it.third } ?: 1
        }
    }

    val tabs = listOf("Attendance" to "✅", "Absent" to "📞", "Follow-ups" to "📝", "Holidays" to "🏖", "Sync" to "☁")
    Scaffold(bottomBar = {
        NavigationBar {
            tabs.forEachIndexed { i, (t, e) ->
                NavigationBarItem(selected = tab == i, onClick = { tab = i }, icon = { Text(e) }, label = { Text(t, fontSize = 10.sp, maxLines = 1) })
            }
        }
    }) { pad ->
        Column(Modifier.padding(pad).padding(horizontal = 12.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(if (busy) "Syncing…" else status.lineSequence().firstOrNull().orEmpty(), Modifier.weight(1f), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                TextButton(onClick = { vm.sync() }, enabled = !busy) { Text("Sync") }
            }
            when (tab) {
                0 -> AttendanceTab(vm, sheets, sheet, effDay, students, markMap, holidaySet, { sheetName = it; day = 0 }, { day = it }) { detail = it }
                1 -> AbsentTab(vm, sheets, sheet, effDay, students, markMap, holidaySet, { day = it }) { detail = it }
                2 -> FollowTab(vm, follow)
                3 -> HolidayTab(vm, holidaySet)
                else -> SyncTab(vm)
            }
        }
    }
    detail?.let { roll ->
        students.firstOrNull { it.roll == roll }?.let { StudentDialog(vm, it, sheets, sheet, markMap, holidaySet, follow) { detail = null } }
    }
}

private fun streakFor(roll: Int, sheets: List<SheetE>, markMap: Map<Triple<String, Int, Int>, String>): Int {
    val seq = sheets.sortedWith(compareBy({ it.year }, { it.month })).flatMap { s ->
        (1..s.days()).mapNotNull { d -> markMap[Triple(s.name, roll, d)] }
    }
    return Stats.absentStreak(seq)
}

private fun percent(sheet: SheetE, roll: Int, markMap: Map<Triple<String, Int, Int>, String>, hol: Set<LocalDate>): Triple<Int, Int, Int> {
    val present = (1..sheet.days()).count { markMap[Triple(sheet.name, roll, it)] == "P" }
    val total = Stats.workingDays(sheet.year, sheet.month, hol)
    return Triple(present, total, if (total > 0) present * 100 / total else 0)
}

@Composable private fun Empty(msg: String) = Box(Modifier.fillMaxSize(), Alignment.Center) { Text(msg, textAlign = TextAlign.Center) }

@Composable
private fun DayBar(sheet: SheetE, day: Int, hol: Set<LocalDate>, onDay: (Int) -> Unit) {
    val st = rememberLazyListState()
    LaunchedEffect(sheet.name, day) { st.animateScrollToItem((day - 3).coerceAtLeast(0)) }
    LazyRow(state = st) {
        items((1..sheet.days()).toList()) { d ->
            val dt = sheet.date(d)
            val off = Stats.isOffDay(dt, hol)
            FilterChip(
                selected = d == day, onClick = { onDay(d) }, modifier = Modifier.padding(end = 6.dp),
                label = {
                    Text("${dt.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.ENGLISH)}\n$d", textAlign = TextAlign.Center,
                        color = if (off && d != day) Color.Gray else Color.Unspecified, fontSize = 12.sp)
                },
            )
        }
    }
}

@Composable
private fun MarkButton(label: String, selected: Boolean, color: Color, onClick: () -> Unit) {
    Box(
        Modifier.padding(start = 6.dp).size(42.dp).clip(CircleShape)
            .background(if (selected) color else MaterialTheme.colorScheme.surfaceVariant).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text(label, color = if (selected) Color.White else MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 16.sp) }
}

@Composable
private fun AttendanceTab(
    vm: AppVm, sheets: List<SheetE>, sheet: SheetE?, day: Int, students: List<StudentE>,
    markMap: Map<Triple<String, Int, Int>, String>, hol: Set<LocalDate>,
    onSheet: (String) -> Unit, onDay: (Int) -> Unit, onStudent: (Int) -> Unit,
) {
    if (sheet == null) { Empty("No month sheet yet.\nOpen the Sync tab and choose the file."); return }
    Column {
        if (sheets.size > 1) LazyRow { items(sheets) { s ->
            FilterChip(selected = s.name == sheet.name, onClick = { onSheet(s.name) }, label = { Text(s.name) }, modifier = Modifier.padding(end = 6.dp))
        } }
        DayBar(sheet, day, hol, onDay)
        val dayVals = students.map { markMap[Triple(sheet.name, it.roll, day)] }
        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("${sheet.date(day).format(DATE_FMT)}\nP ${dayVals.count { it == "P" }}  A ${dayVals.count { it == "A" }}  L ${dayVals.count { it == "L" }}  –  ${dayVals.count { it == null }} unmarked",
                Modifier.weight(1f), fontSize = 13.sp)
            OutlinedButton(onClick = {
                vm.markMany(sheet.name, students.filter { markMap[Triple(sheet.name, it.roll, day)] == null }.map { it.roll }, day, "P")
            }) { Text("All present") }
        }
        LazyColumn(Modifier.weight(1f)) {
            items(students, key = { it.roll }) { s ->
                val cur = markMap[Triple(sheet.name, s.roll, day)]
                val (p, t, pct) = percent(sheet, s.roll, markMap, hol)
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f).clickable { onStudent(s.roll) }) {
                        Text("${s.roll}  ${s.name}", maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("$p / $t days  ($pct%)", fontSize = 11.sp, color = Color.Gray)
                    }
                    fun tap(v: String) = vm.mark(sheet.name, s.roll, day, if (cur == v) "" else v)
                    MarkButton("P", cur == "P", GREEN) { tap("P") }
                    MarkButton("A", cur == "A", RED) { tap("A") }
                    MarkButton("L", cur == "L", AMBER) { tap("L") }
                }
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun AbsentTab(
    vm: AppVm, sheets: List<SheetE>, sheet: SheetE?, day: Int, students: List<StudentE>,
    markMap: Map<Triple<String, Int, Int>, String>, hol: Set<LocalDate>, onDay: (Int) -> Unit, onStudent: (Int) -> Unit,
) {
    if (sheet == null) { Empty("No month sheet yet."); return }
    val ctx = LocalContext.current
    val absent = students.filter { markMap[Triple(sheet.name, it.roll, day)] == "A" }
    Column {
        DayBar(sheet, day, hol, onDay)
        Text("Absent on ${sheet.date(day).format(DATE_FMT)}: ${absent.size}", Modifier.padding(vertical = 8.dp))
        LazyColumn {
            items(absent, key = { it.roll }) { s ->
                val streak = streakFor(s.roll, sheets, markMap)
                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f).clickable { onStudent(s.roll) }) {
                        Text("${s.roll}  ${s.name}", maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(s.phone.ifBlank { "no number" } + if (streak >= 3) "   ⚠ $streak days in a row" else "", fontSize = 12.sp, color = if (streak >= 3) RED else Color.Gray)
                    }
                    if (s.phone.isNotBlank()) OutlinedButton(onClick = { ctx.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:${s.phone}"))) }) { Text("Call") }
                }
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun StatusChips(options: List<String>, value: String, onPick: (String) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        options.forEach { o -> FilterChip(selected = value == o, onClick = { onPick(if (value == o) "" else o) }, label = { Text(o, fontSize = 12.sp) }) }
    }
}

@Composable
private fun StudentDialog(
    vm: AppVm, s: StudentE, sheets: List<SheetE>, sheet: SheetE?, markMap: Map<Triple<String, Int, Int>, String>,
    hol: Set<LocalDate>, follow: List<FollowE>, onClose: () -> Unit,
) {
    val ctx = LocalContext.current
    val streak = streakFor(s.roll, sheets, markMap)
    var info by remember(s.roll) { mutableStateOf(s.info) }
    var reason by remember(s.roll) { mutableStateOf("") }
    var hw by remember(s.roll) { mutableStateOf("") }
    var call by remember(s.roll) { mutableStateOf("Spoke with Parent") }
    var verified by remember(s.roll) { mutableStateOf("") }
    fun close() { if (info != s.info) vm.saveStudent(s.copy(info = info)); onClose() }
    AlertDialog(
        onDismissRequest = { close() },
        confirmButton = { TextButton(onClick = { close() }) { Text("Close") } },
        title = { Text("${s.roll}  ${s.name}", fontSize = 18.sp) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(s.phone.ifBlank { "No phone number" }, Modifier.weight(1f))
                    if (s.phone.isNotBlank()) OutlinedButton(onClick = { ctx.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:${s.phone}"))) }) { Text("Call") }
                }
                if (sheet != null) { val (p, t, pct) = percent(sheet, s.roll, markMap, hol); Text("${sheet.name}: $p / $t days ($pct%)") }
                if (streak >= 3) Text("⚠ Absent $streak days in a row", color = RED)
                OutlinedTextField(info, { info = it }, label = { Text("Info") }, modifier = Modifier.fillMaxWidth())
                HorizontalDivider()
                Text("New follow-up", fontSize = 14.sp)
                OutlinedTextField(reason, { reason = it }, label = { Text(if (streak >= 3) "Reason ([3+ Days Absent] is added)" else "Reason") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(hw, { hw = it }, label = { Text("Topic / homework") }, modifier = Modifier.fillMaxWidth())
                StatusChips(CALL_STATUSES, call) { call = it }
                StatusChips(TASK_STATUSES, verified) { verified = it }
                Button(onClick = {
                    val r = if (streak >= 3 && !reason.startsWith("[3+")) "[3+ Days Absent] $reason".trim() else reason
                    vm.saveFollow(FollowE(0, null, LocalDate.now().toEpochDay(), s.roll, s.name, s.phone, r, hw, call, verified, true))
                    reason = ""; hw = ""; verified = ""
                }) { Text("Save follow-up") }
                val past = follow.filter { it.roll == s.roll }.take(5)
                if (past.isNotEmpty()) {
                    HorizontalDivider(); Text("Earlier", fontSize = 14.sp)
                    past.forEach { Text("${LocalDate.ofEpochDay(it.epochDay).format(DATE_FMT)} · ${it.callStatus}\n${it.reason}", fontSize = 12.sp) }
                }
            }
        },
    )
}

@Composable
private fun FollowTab(vm: AppVm, follow: List<FollowE>) {
    var editing by remember { mutableStateOf<FollowE?>(null) }
    if (follow.isEmpty()) { Empty("No follow-ups yet.\nTap a student to add one."); return }
    LazyColumn {
        items(follow, key = { it.id }) { f ->
            Card(Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable { editing = f }) {
                Column(Modifier.padding(12.dp)) {
                    Text("${f.roll}  ${f.name}", maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${LocalDate.ofEpochDay(f.epochDay).format(DATE_FMT)}  ·  ${f.callStatus}  ·  ${f.taskVerified}", fontSize = 12.sp, color = Color.Gray)
                    if (f.reason.isNotBlank()) Text(f.reason, fontSize = 13.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    if (f.dirty) Text("not synced yet", fontSize = 11.sp, color = AMBER)
                }
            }
        }
    }
    editing?.let { f ->
        var reason by remember(f.id) { mutableStateOf(f.reason) }
        var hw by remember(f.id) { mutableStateOf(f.homework) }
        var call by remember(f.id) { mutableStateOf(f.callStatus) }
        var tv by remember(f.id) { mutableStateOf(f.taskVerified) }
        AlertDialog(
            onDismissRequest = { editing = null },
            dismissButton = { TextButton(onClick = { editing = null }) { Text("Cancel") } },
            confirmButton = { TextButton(onClick = { vm.saveFollow(f.copy(reason = reason, homework = hw, callStatus = call, taskVerified = tv)); editing = null }) { Text("Save") } },
            title = { Text("${f.roll}  ${f.name}", fontSize = 18.sp) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(reason, { reason = it }, label = { Text("Reason") }, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(hw, { hw = it }, label = { Text("Topic / homework") }, modifier = Modifier.fillMaxWidth())
                    StatusChips(CALL_STATUSES, call) { call = it }
                    StatusChips(TASK_STATUSES, tv) { tv = it }
                }
            },
        )
    }
}

@Composable
private fun HolidayTab(vm: AppVm, hol: Set<LocalDate>) {
    val ctx = LocalContext.current
    Column {
        Button(onClick = {
            val t = LocalDate.now()
            DatePickerDialog(ctx, { _, y, m, d -> vm.addHoliday(LocalDate.of(y, m + 1, d)) }, t.year, t.monthValue - 1, t.dayOfMonth).show()
        }, Modifier.padding(vertical = 8.dp)) { Text("Add holiday") }
        Text("Fri and Sat are weekends automatically. Holidays reduce the working-day total.", fontSize = 12.sp, color = Color.Gray)
        LazyColumn {
            items(hol.sortedDescending(), key = { it.toEpochDay() }) { d ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(d.format(DATE_FMT), Modifier.weight(1f))
                    TextButton(onClick = { vm.removeHoliday(d) }) { Text("Remove") }
                }
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun SyncTab(vm: AppVm) {
    val status by vm.status.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val name by vm.fileName.collectAsStateWithLifecycle()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) vm.chooseFile(uri) }
    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(if (name.isBlank()) "No file chosen yet." else "File: $name")
        Button(onClick = { picker.launch(arrayOf("*/*")) }) { Text(if (name.isBlank()) "Choose Excel file" else "Choose a different file") }
        Text("In the picker, open the OneDrive app (menu ☰ → OneDrive) and select Attendence_XI_B__26-27.xlsm. " +
            "In the OneDrive app, long-press the file → Make available offline, so the phone always has a copy.", fontSize = 12.sp, color = Color.Gray)
        if (name.isNotBlank()) {
            Text("Last sync: ${vm.repo.lastSync}")
            Button(onClick = { vm.sync() }, enabled = !busy) { Text("Sync now") }
        }
        Text(status, fontSize = 13.sp)
        Text("Edits are written to the file a few seconds after your last change. Excel changes appear after you tap Sync. " +
            "If the same cell changed both here and in Excel before a sync, the phone's value wins. " +
            "The app keeps a copy of the file as it was before each write, and OneDrive keeps earlier versions.", fontSize = 12.sp, color = Color.Gray)
    }
}
