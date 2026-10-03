package com.abrar.attendance.core

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

object X {
    val EPOCH: LocalDate = LocalDate.of(1899, 12, 30)
    fun serial(d: LocalDate): Long = ChronoUnit.DAYS.between(EPOCH, d)
    fun date(serial: Long): LocalDate = EPOCH.plusDays(serial)

    fun colToIdx(col: String): Int {
        var n = 0
        for (c in col) n = n * 26 + (c.uppercaseChar() - 'A' + 1)
        return n
    }

    fun idxToCol(idx: Int): String {
        var n = idx
        val sb = StringBuilder()
        while (n > 0) { val r = (n - 1) % 26; sb.append('A' + r); n = (n - 1) / 26 }
        return sb.reverse().toString()
    }

    fun split(ref: String): Pair<String, Int> {
        val i = ref.indexOfFirst { it.isDigit() }
        return ref.substring(0, i) to ref.substring(i).toInt()
    }

    private val ENT = Regex("&(#x[0-9a-fA-F]+|#\\d+|amp|lt|gt|quot|apos);")
    fun unescape(s: String): String = if (!s.contains('&')) s else ENT.replace(s) {
        when (val g = it.groupValues[1]) {
            "amp" -> "&"
            "lt" -> "<"
            "gt" -> ">"
            "quot" -> "\""
            "apos" -> "'"
            else -> if (g.startsWith("#x")) String(Character.toChars(g.substring(2).toInt(16)))
            else String(Character.toChars(g.substring(1).toInt()))
        }
    }

    fun escape(s: String): String {
        val sb = StringBuilder()
        for (c in s) when {
            c == '&' -> sb.append("&amp;")
            c == '<' -> sb.append("&lt;")
            c == '>' -> sb.append("&gt;")
            c.code < 0x20 && c != '\n' && c != '\r' && c != '\t' -> {}
            else -> sb.append(c)
        }
        return sb.toString()
    }
}

/** A zip package whose untouched parts (macros, styles, printer settings...) are copied byte-for-byte. */
class XlsxPackage(val entries: LinkedHashMap<String, ByteArray>) {
    fun text(name: String): String = String(entries[name] ?: error("Missing part $name"), Charsets.UTF_8)
    fun putText(name: String, s: String) { entries[name] = s.toByteArray(Charsets.UTF_8) }

    fun toBytes(): ByteArray {
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { z ->
            for ((n, b) in entries) { z.putNextEntry(ZipEntry(n)); z.write(b); z.closeEntry() }
        }
        return bos.toByteArray()
    }

    companion object {
        fun read(bytes: ByteArray): XlsxPackage {
            val m = LinkedHashMap<String, ByteArray>()
            ZipInputStream(ByteArrayInputStream(bytes)).use { z ->
                var e = z.nextEntry
                while (e != null) { if (!e.isDirectory) m[e.name] = z.readBytes(); e = z.nextEntry }
            }
            return XlsxPackage(m)
        }
    }
}

sealed class Val {
    class Str(val s: String) : Val()
    class Num(val n: String) : Val()
    object Clear : Val()
}

/**
 * Text-level editor for one worksheet part. It only rewrites the single <c> it is asked to
 * change (plus the row's "spans" hint), so formulas, validations, conditional formats and styles
 * elsewhere in the sheet stay exactly as Excel wrote them.
 */
class SheetXml(xmlIn: String, private val shared: List<String>) {
    var xml: String = xmlIn.replace("<sheetData/>", "<sheetData></sheetData>")
        private set
    var modified = false
        private set

    private companion object {
        val CELL_RE = Regex("<c\\s([^>]*?)(?:/>|>(.*?)</c>)", RegexOption.DOT_MATCHES_ALL)
        val ROW_RE = Regex("<row\\s([^>]*?)(?:/>|>(.*?)</row>)", RegexOption.DOT_MATCHES_ALL)
        val ATTR_RE = Regex("([\\w:]+)=\"([^\"]*)\"")
        val V_RE = Regex("<v>(.*?)</v>", RegexOption.DOT_MATCHES_ALL)
        val T_RE = Regex("<t(?:\\s[^>]*)?>(.*?)</t>", RegexOption.DOT_MATCHES_ALL)
        val DIM_RE = Regex("<dimension ref=\"([A-Z]+)(\\d+)(?::([A-Z]+)(\\d+))?\"\\s*/>")
        val SPANS_RE = Regex("\\s?spans=\"[^\"]*\"")
    }

    private fun attrs(s: String) = ATTR_RE.findAll(s).associate { it.groupValues[1] to it.groupValues[2] }

    /** ref -> text value, blanks omitted. */
    fun values(): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for (m in CELL_RE.findAll(xml)) {
            val a = attrs(m.groupValues[1])
            val ref = a["r"] ?: continue
            val inner = m.groupValues[2]
            val v = V_RE.find(inner)?.groupValues?.get(1)
            val text = when (a["t"]) {
                "s" -> shared.getOrNull(v?.toIntOrNull() ?: -1) ?: ""
                "inlineStr" -> T_RE.findAll(inner).joinToString("") { X.unescape(it.groupValues[1]) }
                "b" -> if (v == "1") "TRUE" else "FALSE"
                "e" -> ""
                else -> v?.let { X.unescape(it) } ?: ""
            }
            if (text.isNotEmpty()) out[ref] = text
        }
        return out
    }

    private fun build(ref: String, style: String?, v: Val): String {
        val s = if (style != null) " s=\"$style\"" else ""
        return when (v) {
            is Val.Str -> "<c r=\"$ref\"$s t=\"inlineStr\"><is><t xml:space=\"preserve\">${X.escape(v.s)}</t></is></c>"
            is Val.Num -> "<c r=\"$ref\"$s><v>${v.n}</v></c>"
            Val.Clear -> "<c r=\"$ref\"$s/>"
        }
    }

    private fun colOf(cell: MatchResult): Int {
        val ref = attrs(cell.groupValues[1])["r"] ?: return 0
        return X.colToIdx(X.split(ref).first)
    }

    private fun styleAt(body: String, rowN: Int, colI: Int): String? {
        for (r in ROW_RE.findAll(body)) {
            if (attrs(r.groupValues[1])["r"]?.toIntOrNull() != rowN) continue
            for (c in CELL_RE.findAll(r.groupValues[2])) if (colOf(c) == colI) return attrs(c.groupValues[1])["s"]
        }
        return null
    }

    fun setCell(ref: String, v: Val, styleDonorRow: Int? = null) {
        val (colS, rowN) = X.split(ref)
        val colI = X.colToIdx(colS)
        val open = xml.indexOf("<sheetData>")
        val close = xml.indexOf("</sheetData>")
        require(open >= 0 && close > open) { "sheetData not found" }
        val head = xml.substring(0, open + 11)
        val body = xml.substring(open + 11, close)
        val tail = xml.substring(close)

        var found: MatchResult? = null
        var insertAt = body.length
        for (m in ROW_RE.findAll(body)) {
            val rn = attrs(m.groupValues[1])["r"]?.toIntOrNull() ?: continue
            if (rn == rowN) { found = m; break }
            if (rn > rowN) { insertAt = m.range.first; break }
        }

        val newBody: String
        if (found != null) {
            val rowAttrs = found.groupValues[1].replace(SPANS_RE, "")
            val inner = found.groupValues[2]
            val cells = CELL_RE.findAll(inner).toList()
            val existing = cells.firstOrNull { colOf(it) == colI }
            if (existing == null && v is Val.Clear) return
            val style = if (existing != null) attrs(existing.groupValues[1])["s"]
            else styleDonorRow?.let { styleAt(body, it, colI) }
            val cellXml = build(ref, style, v)
            val newInner = if (existing != null) inner.replaceRange(existing.range, cellXml) else {
                val after = cells.firstOrNull { colOf(it) > colI }
                if (after != null) inner.substring(0, after.range.first) + cellXml + inner.substring(after.range.first)
                else inner + cellXml
            }
            newBody = body.replaceRange(found.range, "<row $rowAttrs>$newInner</row>")
        } else {
            if (v is Val.Clear) return
            val style = styleDonorRow?.let { styleAt(body, it, colI) }
            val rowXml = "<row r=\"$rowN\">${build(ref, style, v)}</row>"
            newBody = body.substring(0, insertAt) + rowXml + body.substring(insertAt)
        }
        xml = head + newBody + tail
        expandDimension(colI, rowN)
        modified = true
    }

    private fun expandDimension(colI: Int, rowN: Int) {
        val m = DIM_RE.find(xml) ?: return
        val c1 = m.groupValues[1]
        val r1 = m.groupValues[2].toInt()
        val c2 = m.groupValues[3].ifEmpty { c1 }
        val r2 = m.groupValues[4].ifEmpty { m.groupValues[2] }.toInt()
        val maxC = maxOf(X.colToIdx(c2), colI)
        val maxR = maxOf(r2, rowN)
        xml = xml.replaceRange(m.range, "<dimension ref=\"$c1$r1:${X.idxToCol(maxC)}$maxR\"/>")
    }
}

/** Workbook-level view: sheet name -> part, shared strings, and commit back into the package. */
class Wb(val pkg: XlsxPackage) {
    val shared: List<String>
    val sheetPaths = LinkedHashMap<String, String>()
    private val cache = HashMap<String, SheetXml>()

    init {
        val sst = pkg.entries["xl/sharedStrings.xml"]?.let { String(it, Charsets.UTF_8) }
        shared = if (sst == null) emptyList() else {
            val rph = Regex("<rPh\\b.*?</rPh>", RegexOption.DOT_MATCHES_ALL)
            val t = Regex("<t(?:\\s[^>]*)?>(.*?)</t>", RegexOption.DOT_MATCHES_ALL)
            Regex("<si>(.*?)</si>", RegexOption.DOT_MATCHES_ALL).findAll(sst).map { si ->
                t.findAll(si.groupValues[1].replace(rph, "")).joinToString("") { X.unescape(it.groupValues[1]) }
            }.toList()
        }
        val attrRe = Regex("([\\w:]+)=\"([^\"]*)\"")
        val rels = pkg.text("xl/_rels/workbook.xml.rels")
        val targets = Regex("<Relationship\\s([^>]*?)/>").findAll(rels).associate {
            val a = attrRe.findAll(it.groupValues[1]).associate { m -> m.groupValues[1] to m.groupValues[2] }
            a["Id"] to a["Target"]
        }
        for (m in Regex("<sheet\\s([^>]*?)/>").findAll(pkg.text("xl/workbook.xml"))) {
            val a = attrRe.findAll(m.groupValues[1]).associate { it.groupValues[1] to it.groupValues[2] }
            val name = X.unescape(a["name"] ?: continue)
            val t = targets[a["r:id"]] ?: continue
            sheetPaths[name] = if (t.startsWith("/")) t.substring(1) else "xl/$t"
        }
    }

    fun sheet(name: String): SheetXml? {
        val path = sheetPaths[name] ?: return null
        return cache.getOrPut(name) { SheetXml(pkg.text(path), shared) }
    }

    fun commit(): XlsxPackage {
        for ((name, sx) in cache) if (sx.modified) pkg.putText(sheetPaths.getValue(name), sx.xml)
        var wbx = pkg.text("xl/workbook.xml")
        // Our edits change inputs of formulas (Present, %, Absent list...). Make Excel recompute on open.
        if (!wbx.contains("fullCalcOnLoad")) wbx = wbx.replaceFirst("<calcPr", "<calcPr fullCalcOnLoad=\"1\"")
        pkg.putText("xl/workbook.xml", wbx)
        return pkg
    }
}
