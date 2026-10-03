# Attendance (XI-B 26-27) – Android app with two-way OneDrive sync

Does what the workbook does: mark P/A/L per day, Present count / working days / %, Absent list for a date,
3+ day absence alert, guardian follow-ups (call status, task verified), contacts + info, holidays
(Fri+Sat weekend, same as NETWORKDAYS.INTL code 7). Syncs with the same .xlsm on OneDrive; macros,
formulas, styles and other sheets are left byte-for-byte untouched – only the cells you changed are rewritten.

## Build (no Microsoft/Azure registration needed)
* Android Studio: open this folder -> Run, or
* No Android Studio: put this folder in a GitHub repo -> Actions -> "Build APK" -> download `attendance-apk`.

## Use
Sync tab -> "Choose Excel file" -> in the picker open the OneDrive app and pick Attendence_XI_B__26-27.xlsm.
(In the OneDrive app: long-press the file -> Make available offline.)
Edits are written to the file ~3 s after your last change; "Sync now" picks up changes made in Excel.

## Notes
* A new month = copy the last month sheet in Excel once; the app picks it up on next sync.
* Absent sheet / Student_Dashboard stay formula-driven in Excel (recalculated when the file opens).
* Same cell edited on phone and in Excel between syncs: phone wins.
