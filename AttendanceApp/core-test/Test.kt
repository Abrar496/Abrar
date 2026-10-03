import com.abrar.attendance.core.*
import java.io.File
import java.time.LocalDate

fun main() {
    val orig = File("/mnt/user-data/uploads/Attendence_XI_B__26-27.xlsm").readBytes()
    val r1 = SyncEngine.sync(LocalState(), orig)
    val s = r1.state
    println("pull1: sheets=${s.sheets} students=${s.students.size} marks=${s.marks.size} hol=${s.holidays.size} follow=${s.follow.size} bytes=${r1.bytes} warn=${r1.warnings}")
    println("first students: " + s.students.take(2))
    println("follow: " + s.follow.take(2))
    println("holidays: " + s.holidays.take(2) + " ... " + s.holidays.last())
    val sh = s.sheets[0]
    val hs = s.holidays.map { it.date }.toSet()
    println("working days=" + Stats.workingDays(sh.year, sh.month, hs))
    val rolls = s.students.map { it.roll }
    println("rolls ${rolls.first()}..${rolls.last()}  marksByVal=" + s.marks.groupingBy { it.value }.eachCount())

    // local edits
    val r0 = rolls.first(); val r1_ = rolls[1]
    val edit = s.copy(
        marks = s.marks + MarkRec(sh.name, r0, 30, "A", true) + MarkRec(sh.name, r1_, 29, "L", true) + MarkRec(sh.name, r0, 24, "", true),
        students = s.students.map { if (it.roll == r1_) it.copy(phone = "01700000000", info = "Test & <info>", dirty = true) else it },
        holidays = s.holidays + HolidayRec(LocalDate.of(2026, 10, 10), 1),
        follow = s.follow + FollowRec(null, LocalDate.of(2026, 9, 30), r0, "X", "0170", "[3+ Days Absent] test", "", "Spoke with Parent", "", true),
    )
    val r2 = SyncEngine.sync(edit, orig)
    println("push: pushed=${r2.pushed} pulled=${r2.pulled} warn=${r2.warnings} bytes=${r2.bytes?.size}")
    File("/tmp/out.xlsm").writeBytes(r2.bytes!!)

    // re-read written file with a fresh engine
    val r3 = SyncEngine.sync(LocalState(), r2.bytes!!)
    val s3 = r3.state
    fun mk(roll:Int, d:Int) = s3.marks.firstOrNull{ it.roll==roll && it.day==d }?.value
    println("re-read: r0 d30=${mk(r0,30)} d24=${mk(r0,24)} r1 d29=${mk(r1_,29)}")
    println("student: " + s3.students.first{it.roll==r1_})
    println("holidays=${s3.holidays.size} last=${s3.holidays.last()}")
    println("follow=${s3.follow.size} last=${s3.follow.last()}")
    // idempotent second push of clean state => no bytes
    val r4 = SyncEngine.sync(s3, r2.bytes!!)
    println("idempotent: bytes=${r4.bytes} pulled=${r4.pulled}")
}
