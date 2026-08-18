package com.nicobudget.desktop

import java.io.BufferedOutputStream
import java.io.File
import java.io.OutputStreamWriter
import java.io.Writer
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Base64
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.max
import kotlin.math.min

object DesktopXlsxExporter {
    data class Result(val tables: Int, val rows: Long, val file: File)
    private data class Sheet(val table: String, val name: String, val rows: Int)

    fun export(file: File): Result {
        file.parentFile?.mkdirs()
        val used = mutableSetOf<String>()
        val sheets = DesktopStore.tableNames().map { table ->
            Sheet(table, uniqueSheetName(table, used), DesktopStore.rowCount(table))
        }
        require(sheets.isNotEmpty()) { "Aucune table NicoBudget à exporter" }
        val totalRows = sheets.sumOf { it.rows.toLong() }
        ZipOutputStream(BufferedOutputStream(file.outputStream())).use { zip ->
            writeContentTypes(zip, sheets.size + 1)
            writeRootRels(zip)
            writeWorkbook(zip, sheets)
            writeWorkbookRels(zip, sheets.size + 1)
            writeStyles(zip)
            writeIndex(zip, sheets)
            sheets.forEachIndexed { index, sheet -> writeTable(zip, index + 2, sheet) }
        }
        return Result(sheets.size, totalRows, file)
    }

    private fun writeContentTypes(zip: ZipOutputStream, count: Int) = writeXml(zip, "[Content_Types].xml") { w ->
        w.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>")
        w.append("<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">")
        w.append("<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>")
        w.append("<Default Extension=\"xml\" ContentType=\"application/xml\"/>")
        w.append("<Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/>")
        w.append("<Override PartName=\"/xl/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml\"/>")
        for (i in 1..count) w.append("<Override PartName=\"/xl/worksheets/sheet$i.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>")
        w.append("</Types>")
    }

    private fun writeRootRels(zip: ZipOutputStream) = writeXml(zip, "_rels/.rels") { w ->
        w.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>")
        w.append("<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">")
        w.append("<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"xl/workbook.xml\"/>")
        w.append("</Relationships>")
    }

    private fun writeWorkbook(zip: ZipOutputStream, sheets: List<Sheet>) = writeXml(zip, "xl/workbook.xml") { w ->
        w.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>")
        w.append("<workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\"><bookViews><workbookView/></bookViews><sheets>")
        w.append("<sheet name=\"Index\" sheetId=\"1\" r:id=\"rId1\"/>")
        sheets.forEachIndexed { index, s ->
            val id = index + 2
            w.append("<sheet name=\"").append(xmlAttr(s.name)).append("\" sheetId=\"").append(id.toString()).append("\" r:id=\"rId$id\"/>")
        }
        w.append("</sheets></workbook>")
    }

    private fun writeWorkbookRels(zip: ZipOutputStream, count: Int) = writeXml(zip, "xl/_rels/workbook.xml.rels") { w ->
        w.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>")
        w.append("<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">")
        for (i in 1..count) w.append("<Relationship Id=\"rId$i\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet$i.xml\"/>")
        w.append("<Relationship Id=\"rId${count + 1}\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles\" Target=\"styles.xml\"/>")
        w.append("</Relationships>")
    }

    private fun writeStyles(zip: ZipOutputStream) = writeXml(zip, "xl/styles.xml") { w ->
        w.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>")
        w.append("<styleSheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">")
        w.append("<fonts count=\"2\"><font><sz val=\"11\"/><name val=\"Calibri\"/></font><font><b/><sz val=\"11\"/><name val=\"Calibri\"/></font></fonts>")
        w.append("<fills count=\"2\"><fill><patternFill patternType=\"none\"/></fill><fill><patternFill patternType=\"gray125\"/></fill></fills>")
        w.append("<borders count=\"1\"><border><left/><right/><top/><bottom/><diagonal/></border></borders>")
        w.append("<cellStyleXfs count=\"1\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\"/></cellStyleXfs>")
        w.append("<cellXfs count=\"2\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\"/><xf numFmtId=\"0\" fontId=\"1\" fillId=\"0\" borderId=\"0\" xfId=\"0\" applyFont=\"1\"/></cellXfs>")
        w.append("<cellStyles count=\"1\"><cellStyle name=\"Normal\" xfId=\"0\" builtinId=\"0\"/></cellStyles></styleSheet>")
    }

    private fun writeIndex(zip: ZipOutputStream, sheets: List<Sheet>) = writeXml(zip, "xl/worksheets/sheet1.xml") { w ->
        val last = sheets.size + 6
        w.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>")
        w.append("<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"><dimension ref=\"A1:D$last\"/><sheetViews><sheetView workbookViewId=\"0\"><pane ySplit=\"5\" topLeftCell=\"A6\" activePane=\"bottomLeft\" state=\"frozen\"/></sheetView></sheetViews><cols><col min=\"1\" max=\"1\" width=\"28\" customWidth=\"1\"/><col min=\"2\" max=\"2\" width=\"28\" customWidth=\"1\"/><col min=\"3\" max=\"3\" width=\"12\" customWidth=\"1\"/><col min=\"4\" max=\"4\" width=\"54\" customWidth=\"1\"/></cols><sheetData>")
        writeRow(w, 1, listOf("Export NicoBudget Desktop"), true)
        writeRow(w, 2, listOf("Généré le", LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.FRANCE))))
        writeRow(w, 3, listOf("Base locale", DesktopStore.databaseFile.absolutePath))
        writeRow(w, 4, listOf("Lignes", sheets.sumOf { it.rows }.toString()))
        writeRow(w, 5, listOf("Onglet", "Table", "Lignes", "Description"), true)
        sheets.forEachIndexed { index, s -> writeRow(w, index + 6, listOf(s.name, s.table, s.rows.toString(), describe(s.table))) }
        w.append("</sheetData><autoFilter ref=\"A5:D$last\"/></worksheet>")
    }

    private fun writeTable(zip: ZipOutputStream, number: Int, sheet: Sheet) = writeXml(zip, "xl/worksheets/sheet$number.xml") { w ->
        val cols = DesktopStore.columns(sheet.table).map { it.name }
        val rows = DesktopStore.rows(sheet.table)
        val lastCol = columnName(max(1, cols.size) - 1)
        val lastRow = max(1, rows.size + 1)
        w.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>")
        w.append("<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"><dimension ref=\"A1:$lastCol$lastRow\"/><sheetViews><sheetView workbookViewId=\"0\"><pane ySplit=\"1\" topLeftCell=\"A2\" activePane=\"bottomLeft\" state=\"frozen\"/></sheetView></sheetViews>")
        if (cols.isNotEmpty()) {
            w.append("<cols>")
            cols.forEachIndexed { index, name ->
                val width = min(34, max(12, name.length + 4))
                w.append("<col min=\"").append((index + 1).toString()).append("\" max=\"").append((index + 1).toString()).append("\" width=\"").append(width.toString()).append("\" customWidth=\"1\"/>")
            }
            w.append("</cols>")
        }
        w.append("<sheetData>")
        writeRow(w, 1, cols, true)
        rows.forEachIndexed { rowIndex, row ->
            w.append("<row r=\"").append((rowIndex + 2).toString()).append("\">")
            cols.forEachIndexed { colIndex, col -> writeCell(w, "${columnName(colIndex)}${rowIndex + 2}", row.values[col]) }
            w.append("</row>")
        }
        w.append("</sheetData>")
        if (cols.isNotEmpty()) w.append("<autoFilter ref=\"A1:$lastCol$lastRow\"/>")
        w.append("</worksheet>")
    }

    private fun writeCell(w: Writer, ref: String, value: Any?) {
        when (value) {
            null -> w.append("<c r=\"").append(ref).append("\"/>")
            is ByteArray -> writeStringCell(w, ref, Base64.getEncoder().encodeToString(value), false)
            is Byte, is Short, is Int, is Long, is Float, is Double -> {
                val n = (value as Number).toDouble()
                if (n.isFinite()) w.append("<c r=\"").append(ref).append("\" t=\"n\"><v>").append(value.toString()).append("</v></c>")
                else writeStringCell(w, ref, value.toString(), false)
            }
            else -> writeStringCell(w, ref, value.toString(), false)
        }
    }

    private fun writeRow(w: Writer, index: Int, values: List<String>, header: Boolean = false) {
        w.append("<row r=\"").append(index.toString()).append("\">")
        values.forEachIndexed { col, value -> writeStringCell(w, "${columnName(col)}$index", value, header) }
        w.append("</row>")
    }

    private fun writeStringCell(w: Writer, ref: String, value: String, header: Boolean) {
        w.append("<c r=\"").append(ref).append("\" t=\"inlineStr\"")
        if (header) w.append(" s=\"1\"")
        w.append("><is><t xml:space=\"preserve\">").append(xmlText(value)).append("</t></is></c>")
    }

    private fun writeXml(zip: ZipOutputStream, name: String, block: (Writer) -> Unit) {
        zip.putNextEntry(ZipEntry(name))
        val writer = OutputStreamWriter(zip, Charsets.UTF_8)
        block(writer)
        writer.flush()
        zip.closeEntry()
    }

    private fun columnName(index: Int): String {
        var n = index + 1
        val out = StringBuilder()
        while (n > 0) { val r = (n - 1) % 26; out.append(('A'.code + r).toChar()); n = (n - 1) / 26 }
        return out.reverse().toString()
    }

    private fun uniqueSheetName(raw: String, used: MutableSet<String>): String {
        val clean = raw.replace(Regex("[\\[\\]:*?/\\\\]"), "_").take(31).ifBlank { "Table" }
        var candidate = clean
        var i = 2
        while (!used.add(candidate.lowercase(Locale.FRANCE))) {
            val suffix = "_$i"; candidate = clean.take(31 - suffix.length) + suffix; i++
        }
        return candidate
    }

    private fun xmlText(value: String): String = value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").filter { it == '\n' || it == '\r' || it == '\t' || it.code >= 0x20 }
    private fun xmlAttr(value: String): String = xmlText(value).replace("\"", "&quot;").replace("'", "&apos;")

    private fun describe(table: String): String = when (table) {
        "monthly_budget" -> "Budget courant"
        "expenses" -> "Dépenses du cycle actif"
        "expense_archive" -> "Dépenses archivées"
        "fixed_charges" -> "Charges fixes"
        "fixed_incomes" -> "Revenus fixes"
        "expense_categories" -> "Catégories de dépenses"
        "drive_orders" -> "Commandes Leclerc Drive"
        "drive_order_lines" -> "Lignes produits Leclerc Drive"
        else -> "Données NicoBudget"
    }
}
