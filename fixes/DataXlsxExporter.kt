package com.example.nicobudget.export

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.util.Base64
import java.io.BufferedOutputStream
import java.io.OutputStreamWriter
import java.io.Writer
import java.nio.charset.StandardCharsets
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.max
import kotlin.math.min

/**
 * Exporteur XLSX autonome, sans dépendance Apache POI.
 *
 * Il exporte toutes les tables applicatives des bases SQLite locales dans un
 * classeur Excel, en excluant les tables techniques SQLite/Room et les
 * préférences de l'application (qui peuvent contenir des identifiants).
 */
object DataXlsxExporter {

    data class ExportResult(
        val databases: Int,
        val dataSheets: Int,
        val rows: Long
    ) {
        val sheets: Int get() = dataSheets + 1 // + onglet Index
    }

    private data class SheetSpec(
        val databaseName: String,
        val databasePath: String,
        val tableName: String,
        val sheetName: String,
        val rowCount: Long
    )

    private val technicalTables = setOf(
        "android_metadata",
        "room_master_table"
    )

    fun export(context: Context, uri: Uri): ExportResult {
        val specs = discoverTables(context)
        if (specs.isEmpty()) {
            throw IllegalStateException("Aucune table de données locale à exporter.")
        }

        val output = context.contentResolver.openOutputStream(uri, "w")
            ?: throw IllegalStateException("Impossible d'ouvrir le fichier de destination.")

        val totalRows = specs.sumOf { it.rowCount }
        val totalSheets = specs.size + 1

        ZipOutputStream(BufferedOutputStream(output)).use { zip ->
            writeContentTypes(zip, totalSheets)
            writeRootRelationships(zip)
            writeWorkbook(zip, specs)
            writeWorkbookRelationships(zip, totalSheets)
            writeStyles(zip)
            writeIndexSheet(zip, specs)

            specs.forEachIndexed { index, spec ->
                writeTableSheet(zip, sheetNumber = index + 2, spec = spec)
            }
        }

        return ExportResult(
            databases = specs.map { it.databaseName }.distinct().size,
            dataSheets = specs.size,
            rows = totalRows
        )
    }

    private fun discoverTables(context: Context): List<SheetSpec> {
        val raw = mutableListOf<Triple<String, String, Pair<String, Long>>>()

        context.databaseList()
            .sorted()
            .forEach { databaseName ->
                val dbFile = context.getDatabasePath(databaseName)
                if (!dbFile.exists()) return@forEach

                var db: SQLiteDatabase? = null
                try {
                    db = SQLiteDatabase.openDatabase(
                        dbFile.absolutePath,
                        null,
                        SQLiteDatabase.OPEN_READONLY
                    )
                    db.rawQuery(
                        "SELECT name FROM sqlite_master " +
                            "WHERE type='table' AND name NOT LIKE 'sqlite_%' ORDER BY name",
                        null
                    ).use { cursor ->
                        while (cursor.moveToNext()) {
                            val table = cursor.getString(0) ?: continue
                            if (table in technicalTables) continue
                            val count = queryRowCount(db, table)
                            raw += Triple(
                                databaseName,
                                dbFile.absolutePath,
                                table to count
                            )
                        }
                    }
                } catch (_: Exception) {
                    // Une base annexe illisible ne doit pas empêcher l'export des autres.
                } finally {
                    db?.close()
                }
            }

        val usedNames = mutableSetOf<String>()
        val duplicateTables = raw.groupingBy { it.third.first }.eachCount()

        return raw.map { (databaseName, databasePath, tableAndCount) ->
            val (tableName, count) = tableAndCount
            val preferred = if ((duplicateTables[tableName] ?: 0) > 1) {
                "${databaseName.substringBeforeLast('.')}_$tableName"
            } else {
                tableName
            }
            SheetSpec(
                databaseName = databaseName,
                databasePath = databasePath,
                tableName = tableName,
                sheetName = uniqueSheetName(preferred, usedNames),
                rowCount = count
            )
        }
    }

    private fun queryRowCount(db: SQLiteDatabase, table: String): Long {
        val sql = "SELECT COUNT(*) FROM ${quoteIdentifier(table)}"
        return db.rawQuery(sql, null).use { cursor ->
            if (cursor.moveToFirst()) cursor.getLong(0) else 0L
        }
    }

    private fun writeContentTypes(zip: ZipOutputStream, sheetCount: Int) {
        writeXmlEntry(zip, "[Content_Types].xml") { w ->
            w.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>")
            w.append("<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">")
            w.append("<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>")
            w.append("<Default Extension=\"xml\" ContentType=\"application/xml\"/>")
            w.append("<Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/>")
            w.append("<Override PartName=\"/xl/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml\"/>")
            for (i in 1..sheetCount) {
                w.append("<Override PartName=\"/xl/worksheets/sheet$i.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>")
            }
            w.append("</Types>")
        }
    }

    private fun writeRootRelationships(zip: ZipOutputStream) {
        writeXmlEntry(zip, "_rels/.rels") { w ->
            w.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>")
            w.append("<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">")
            w.append("<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"xl/workbook.xml\"/>")
            w.append("</Relationships>")
        }
    }

    private fun writeWorkbook(zip: ZipOutputStream, specs: List<SheetSpec>) {
        writeXmlEntry(zip, "xl/workbook.xml") { w ->
            w.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>")
            w.append("<workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\">")
            w.append("<bookViews><workbookView/></bookViews><sheets>")
            w.append("<sheet name=\"Index\" sheetId=\"1\" r:id=\"rId1\"/>")
            specs.forEachIndexed { index, spec ->
                val id = index + 2
                w.append("<sheet name=\"")
                    .append(xmlAttr(spec.sheetName))
                    .append("\" sheetId=\"").append(id.toString())
                    .append("\" r:id=\"rId$id\"/>")
            }
            w.append("</sheets></workbook>")
        }
    }

    private fun writeWorkbookRelationships(zip: ZipOutputStream, sheetCount: Int) {
        writeXmlEntry(zip, "xl/_rels/workbook.xml.rels") { w ->
            w.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>")
            w.append("<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">")
            for (i in 1..sheetCount) {
                w.append("<Relationship Id=\"rId$i\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet$i.xml\"/>")
            }
            w.append("<Relationship Id=\"rId${sheetCount + 1}\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles\" Target=\"styles.xml\"/>")
            w.append("</Relationships>")
        }
    }

    private fun writeStyles(zip: ZipOutputStream) {
        writeXmlEntry(zip, "xl/styles.xml") { w ->
            w.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>")
            w.append("<styleSheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">")
            w.append("<fonts count=\"2\"><font><sz val=\"11\"/><name val=\"Calibri\"/></font><font><b/><sz val=\"11\"/><name val=\"Calibri\"/></font></fonts>")
            w.append("<fills count=\"2\"><fill><patternFill patternType=\"none\"/></fill><fill><patternFill patternType=\"gray125\"/></fill></fills>")
            w.append("<borders count=\"1\"><border><left/><right/><top/><bottom/><diagonal/></border></borders>")
            w.append("<cellStyleXfs count=\"1\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\"/></cellStyleXfs>")
            w.append("<cellXfs count=\"2\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\"/><xf numFmtId=\"0\" fontId=\"1\" fillId=\"0\" borderId=\"0\" xfId=\"0\" applyFont=\"1\"/></cellXfs>")
            w.append("<cellStyles count=\"1\"><cellStyle name=\"Normal\" xfId=\"0\" builtinId=\"0\"/></cellStyles>")
            w.append("</styleSheet>")
        }
    }

    private fun writeIndexSheet(zip: ZipOutputStream, specs: List<SheetSpec>) {
        writeXmlEntry(zip, "xl/worksheets/sheet1.xml") { w ->
            val lastRow = specs.size + 6
            w.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>")
            w.append("<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">")
            w.append("<dimension ref=\"A1:E$lastRow\"/>")
            w.append("<sheetViews><sheetView workbookViewId=\"0\"><pane ySplit=\"5\" topLeftCell=\"A6\" activePane=\"bottomLeft\" state=\"frozen\"/></sheetView></sheetViews>")
            w.append("<cols><col min=\"1\" max=\"1\" width=\"28\" customWidth=\"1\"/><col min=\"2\" max=\"3\" width=\"24\" customWidth=\"1\"/><col min=\"4\" max=\"4\" width=\"12\" customWidth=\"1\"/><col min=\"5\" max=\"5\" width=\"48\" customWidth=\"1\"/></cols>")
            w.append("<sheetData>")

            writeRow(w, 1, listOf("Export NicoBudget"), header = true)
            writeRow(w, 2, listOf("Généré le", LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.FRANCE))))
            writeRow(w, 3, listOf("Contenu", "Tables de données SQLite locales. Les préférences et identifiants ne sont pas exportés."))
            writeRow(w, 4, listOf("Lignes de données", specs.sumOf { it.rowCount }.toString()))
            writeRow(w, 5, listOf("Onglet", "Base", "Table", "Lignes", "Description"), header = true)

            specs.forEachIndexed { index, spec ->
                writeRow(
                    w,
                    index + 6,
                    listOf(
                        spec.sheetName,
                        spec.databaseName,
                        spec.tableName,
                        spec.rowCount.toString(),
                        describeTable(spec.tableName)
                    )
                )
            }
            w.append("</sheetData><autoFilter ref=\"A5:E$lastRow\"/></worksheet>")
        }
    }

    private fun writeTableSheet(zip: ZipOutputStream, sheetNumber: Int, spec: SheetSpec) {
        var db: SQLiteDatabase? = null
        try {
            db = SQLiteDatabase.openDatabase(
                spec.databasePath,
                null,
                SQLiteDatabase.OPEN_READONLY
            )
            val sql = "SELECT * FROM ${quoteIdentifier(spec.tableName)}"
            db.rawQuery(sql, null).use { cursor ->
                writeXmlEntry(zip, "xl/worksheets/sheet$sheetNumber.xml") { w ->
                    val columns = cursor.columnNames.toList()
                    val lastColumn = columnName(max(1, columns.size) - 1)
                    val lastRow = spec.rowCount + 1

                    w.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>")
                    w.append("<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">")
                    w.append("<dimension ref=\"A1:$lastColumn${max(1L, lastRow)}\"/>")
                    w.append("<sheetViews><sheetView workbookViewId=\"0\"><pane ySplit=\"1\" topLeftCell=\"A2\" activePane=\"bottomLeft\" state=\"frozen\"/></sheetView></sheetViews>")
                    writeColumnWidths(w, columns)
                    w.append("<sheetData>")

                    writeRow(w, 1, columns, header = true)
                    var rowIndex = 2
                    while (cursor.moveToNext()) {
                        writeCursorRow(w, cursor, rowIndex)
                        rowIndex++
                    }

                    w.append("</sheetData>")
                    if (columns.isNotEmpty()) {
                        w.append("<autoFilter ref=\"A1:$lastColumn${max(1, rowIndex - 1)}\"/>")
                    }
                    w.append("</worksheet>")
                }
            }
        } finally {
            db?.close()
        }
    }

    private fun writeColumnWidths(w: Writer, columns: List<String>) {
        if (columns.isEmpty()) return
        w.append("<cols>")
        columns.forEachIndexed { index, name ->
            val width = min(34, max(12, name.length + 4))
            val col = index + 1
            w.append("<col min=\"").append(col.toString())
                .append("\" max=\"").append(col.toString())
                .append("\" width=\"").append(width.toString())
                .append("\" customWidth=\"1\"/>")
        }
        w.append("</cols>")
    }

    private fun writeCursorRow(w: Writer, cursor: Cursor, rowIndex: Int) {
        w.append("<row r=\"").append(rowIndex.toString()).append("\">")
        for (columnIndex in 0 until cursor.columnCount) {
            val ref = "${columnName(columnIndex)}$rowIndex"
            when (cursor.getType(columnIndex)) {
                Cursor.FIELD_TYPE_NULL -> {
                    w.append("<c r=\"").append(ref).append("\"/>")
                }
                Cursor.FIELD_TYPE_INTEGER -> {
                    w.append("<c r=\"").append(ref).append("\" t=\"n\"><v>")
                        .append(cursor.getLong(columnIndex).toString())
                        .append("</v></c>")
                }
                Cursor.FIELD_TYPE_FLOAT -> {
                    val value = cursor.getDouble(columnIndex)
                    if (value.isFinite()) {
                        w.append("<c r=\"").append(ref).append("\" t=\"n\"><v>")
                            .append(value.toString())
                            .append("</v></c>")
                    } else {
                        writeInlineStringCell(w, ref, value.toString(), header = false)
                    }
                }
                Cursor.FIELD_TYPE_BLOB -> {
                    val encoded = Base64.encodeToString(cursor.getBlob(columnIndex), Base64.NO_WRAP)
                    writeInlineStringCell(w, ref, encoded, header = false)
                }
                else -> {
                    writeInlineStringCell(w, ref, cursor.getString(columnIndex).orEmpty(), header = false)
                }
            }
        }
        w.append("</row>")
    }

    private fun writeRow(w: Writer, rowIndex: Int, values: List<String>, header: Boolean = false) {
        w.append("<row r=\"").append(rowIndex.toString()).append("\">")
        values.forEachIndexed { columnIndex, value ->
            writeInlineStringCell(w, "${columnName(columnIndex)}$rowIndex", value, header)
        }
        w.append("</row>")
    }

    private fun writeInlineStringCell(w: Writer, ref: String, value: String, header: Boolean) {
        w.append("<c r=\"").append(ref).append("\" t=\"inlineStr\"")
        if (header) w.append(" s=\"1\"")
        w.append("><is><t xml:space=\"preserve\">")
            .append(xmlText(value))
            .append("</t></is></c>")
    }

    private fun writeXmlEntry(zip: ZipOutputStream, name: String, block: (Writer) -> Unit) {
        zip.putNextEntry(ZipEntry(name))
        val writer = OutputStreamWriter(zip, StandardCharsets.UTF_8)
        block(writer)
        writer.flush()
        zip.closeEntry()
    }

    private fun quoteIdentifier(value: String): String =
        "\"${value.replace("\"", "\"\"")}\""

    private fun uniqueSheetName(raw: String, used: MutableSet<String>): String {
        val cleaned = raw
            .replace(Regex("[\\\\/*?:\\[\\]]"), "_")
            .trim()
            .ifBlank { "Données" }

        var candidate = cleaned.take(31)
        var suffix = 2
        while (!used.add(candidate.lowercase(Locale.ROOT))) {
            val end = " ($suffix)"
            candidate = cleaned.take(31 - end.length) + end
            suffix++
        }
        return candidate
    }

    private fun columnName(index: Int): String {
        var n = index + 1
        val out = StringBuilder()
        while (n > 0) {
            val r = (n - 1) % 26
            out.append(('A'.code + r).toChar())
            n = (n - 1) / 26
        }
        return out.reverse().toString()
    }

    private fun describeTable(table: String): String = when (table.lowercase(Locale.ROOT)) {
        "drive_orders" -> "Commandes Leclerc Drive : date, magasin, total, économies, Ticket E.Leclerc."
        "drive_order_lines" -> "Lignes produits Drive : rayon, produit, quantité, prix unitaire et total."
        "expenses", "expense" -> "Dépenses enregistrées dans le budget."
        "categories", "category" -> "Catégories budgétaires."
        "budgets", "budget" -> "Données de budget."
        else -> "Données applicatives brutes de la table $table."
    }

    private fun xmlText(value: String): String {
        val cleaned = buildString(value.length) {
            value.forEach { ch ->
                if (ch == '\t' || ch == '\n' || ch == '\r' || ch.code >= 0x20) append(ch)
            }
        }
        return cleaned
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
    }

    private fun xmlAttr(value: String): String = xmlText(value)
        .replace("\"", "&quot;")
        .replace("'", "&apos;")
}
