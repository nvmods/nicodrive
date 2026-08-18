package com.nicobudget.desktop

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.nio.file.Files
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Base64
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

private const val BACKUP_FORMAT = "NicoBudgetBackup"
private const val BACKUP_VERSION = 1
private const val INTERNAL_ID = "__desktop_id"

data class DbRow(val values: Map<String, Any?>) {
    val desktopId: Long get() = (values[INTERNAL_ID] as? Number)?.toLong() ?: 0L

    fun string(vararg names: String): String? {
        val entry = values.entries.firstOrNull { e -> names.any { it.equals(e.key, ignoreCase = true) } }
        return entry?.value?.toString()
    }

    fun double(vararg names: String): Double? {
        val entry = values.entries.firstOrNull { e -> names.any { it.equals(e.key, ignoreCase = true) } }
        return when (val value = entry?.value) {
            is Number -> value.toDouble()
            is String -> value.replace(',', '.').toDoubleOrNull()
            else -> null
        }
    }

    fun long(vararg names: String): Long? {
        val entry = values.entries.firstOrNull { e -> names.any { it.equals(e.key, ignoreCase = true) } }
        return when (val value = entry?.value) {
            is Number -> value.toLong()
            is String -> value.toLongOrNull()
            else -> null
        }
    }
}

data class ImportSummary(
    val tables: Int,
    val rows: Int,
    val backupCreatedAt: String?,
    val databaseVersion: Int
)

data class ExportSummary(val tables: Int, val rows: Int, val file: File)

data class TableColumn(val name: String, val affinity: String)

object DesktopStore {
    private val appDir: File by lazy {
        val base = System.getenv("LOCALAPPDATA")?.takeIf { it.isNotBlank() }
            ?: System.getProperty("user.home")
        File(base, "NicoBudget").apply { mkdirs() }
    }

    val databaseFile: File get() = File(appDir, "nicobudget-desktop.db")

    init {
        Class.forName("org.sqlite.JDBC")
        connection().use { db ->
            db.createStatement().use { st ->
                st.execute("PRAGMA foreign_keys=OFF")
                st.execute(
                    """
                    CREATE TABLE IF NOT EXISTS _nb_schema (
                        table_name TEXT NOT NULL,
                        ordinal INTEGER NOT NULL,
                        column_name TEXT NOT NULL,
                        affinity TEXT NOT NULL,
                        PRIMARY KEY(table_name, ordinal)
                    )
                    """.trimIndent()
                )
                st.execute(
                    """
                    CREATE TABLE IF NOT EXISTS _nb_preferences (
                        pref_name TEXT NOT NULL,
                        pref_key TEXT NOT NULL,
                        encoded_json TEXT NOT NULL,
                        PRIMARY KEY(pref_name, pref_key)
                    )
                    """.trimIndent()
                )
                st.execute(
                    """
                    CREATE TABLE IF NOT EXISTS _nb_meta (
                        meta_key TEXT PRIMARY KEY,
                        meta_value TEXT
                    )
                    """.trimIndent()
                )
            }
        }
    }

    private fun connection(): Connection = DriverManager.getConnection("jdbc:sqlite:${databaseFile.absolutePath}")

    fun hasDataset(): Boolean = tableNames().isNotEmpty()

    fun tableNames(): List<String> = connection().use { db ->
        db.prepareStatement("SELECT DISTINCT table_name FROM _nb_schema ORDER BY table_name").use { ps ->
            ps.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.getString(1)) } }
        }
    }

    fun tableExists(name: String): Boolean = connection().use { db ->
        db.prepareStatement("SELECT 1 FROM _nb_schema WHERE table_name=? LIMIT 1").use { ps ->
            ps.setString(1, name)
            ps.executeQuery().use { it.next() }
        }
    }

    fun columns(table: String): List<TableColumn> = connection().use { db ->
        db.prepareStatement(
            "SELECT column_name, affinity FROM _nb_schema WHERE table_name=? ORDER BY ordinal"
        ).use { ps ->
            ps.setString(1, table)
            ps.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) add(TableColumn(rs.getString(1), rs.getString(2)))
                }
            }
        }
    }

    fun rows(table: String): List<DbRow> {
        require(tableExists(table)) { "Table inconnue : $table" }
        return connection().use { db ->
            db.createStatement().use { st ->
                st.executeQuery("SELECT * FROM ${quoteIdent(table)}").use { rs -> readRows(rs) }
            }
        }
    }

    fun rowCount(table: String): Int {
        if (!tableExists(table)) return 0
        return connection().use { db ->
            db.createStatement().use { st ->
                st.executeQuery("SELECT COUNT(*) FROM ${quoteIdent(table)}").use { rs ->
                    if (rs.next()) rs.getInt(1) else 0
                }
            }
        }
    }

    fun deleteRow(table: String, desktopId: Long): Boolean {
        require(tableExists(table)) { "Table inconnue : $table" }
        return connection().use { db ->
            db.prepareStatement("DELETE FROM ${quoteIdent(table)} WHERE ${quoteIdent(INTERNAL_ID)}=?").use { ps ->
                ps.setLong(1, desktopId)
                ps.executeUpdate() > 0
            }
        }
    }

    fun meta(key: String): String? = connection().use { db ->
        db.prepareStatement("SELECT meta_value FROM _nb_meta WHERE meta_key=?").use { ps ->
            ps.setString(1, key)
            ps.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else null }
        }
    }

    fun preferenceEncoded(prefName: String, key: String): JSONObject? = connection().use { db ->
        db.prepareStatement(
            "SELECT encoded_json FROM _nb_preferences WHERE pref_name=? AND pref_key=?"
        ).use { ps ->
            ps.setString(1, prefName)
            ps.setString(2, key)
            ps.executeQuery().use { rs ->
                if (!rs.next()) null else runCatching { JSONObject(rs.getString(1)) }.getOrNull()
            }
        }
    }

    fun preferenceString(prefName: String, key: String): String? {
        val encoded = preferenceEncoded(prefName, key) ?: return null
        return when (encoded.optString("type")) {
            "string" -> encoded.optString("value", "")
            "int", "long", "float", "boolean" -> encoded.opt("value")?.toString()
            else -> null
        }
    }

    fun preferenceStringSet(prefName: String, key: String): Set<String> {
        val encoded = preferenceEncoded(prefName, key) ?: return emptySet()
        if (encoded.optString("type") != "stringSet") return emptySet()
        val array = encoded.optJSONArray("value") ?: return emptySet()
        return (0 until array.length()).mapNotNull { array.optString(it).takeIf(String::isNotBlank) }.toSet()
    }

    fun importBackup(file: File): ImportSummary {
        require(file.exists()) { "Fichier introuvable" }
        val entries = mutableMapOf<String, String>()
        ZipInputStream(BufferedInputStream(file.inputStream())).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (!entry.isDirectory && entry.name in setOf("manifest.json", "database.json", "preferences.json")) {
                    entries[entry.name] = zip.readBytes().toString(Charsets.UTF_8)
                }
                zip.closeEntry()
            }
        }

        val manifest = JSONObject(entries["manifest.json"] ?: error("manifest.json absent"))
        require(manifest.optString("format") == BACKUP_FORMAT) { "Ce fichier n'est pas une sauvegarde NicoBudget" }
        val formatVersion = manifest.optInt("version", 0)
        require(formatVersion in 1..BACKUP_VERSION) { "Version de sauvegarde non supportée : $formatVersion" }
        val dbVersion = manifest.optInt("databaseUserVersion", 0)
        val databaseJson = JSONObject(entries["database.json"] ?: error("database.json absent"))
        val tables = databaseJson.getJSONObject("tables")
        val preferences = entries["preferences.json"]?.let(::JSONObject) ?: JSONObject()

        connection().use { db ->
            db.autoCommit = false
            try {
                val existing = tableNames(db)
                db.createStatement().use { st ->
                    existing.forEach { st.execute("DROP TABLE IF EXISTS ${quoteIdent(it)}") }
                    st.execute("DELETE FROM _nb_schema")
                    st.execute("DELETE FROM _nb_preferences")
                    st.execute("DELETE FROM _nb_meta")
                }

                var totalRows = 0
                val names = tables.keys().asSequence().toList().sorted()
                names.forEach { table ->
                    val tableJson = tables.getJSONObject(table)
                    val colsJson = tableJson.getJSONArray("columns")
                    val rowsJson = tableJson.getJSONArray("rows")
                    val columnNames = (0 until colsJson.length()).map { colsJson.getString(it) }
                    val affinities = columnNames.indices.map { idx -> inferAffinity(rowsJson, idx) }

                    val createColumns = buildList {
                        add("${quoteIdent(INTERNAL_ID)} INTEGER PRIMARY KEY AUTOINCREMENT")
                        columnNames.forEachIndexed { index, name ->
                            add("${quoteIdent(name)} ${affinities[index]}")
                        }
                    }.joinToString(",")
                    db.createStatement().use { it.execute("CREATE TABLE ${quoteIdent(table)} ($createColumns)") }

                    db.prepareStatement(
                        "INSERT INTO _nb_schema(table_name,ordinal,column_name,affinity) VALUES(?,?,?,?)"
                    ).use { ps ->
                        columnNames.forEachIndexed { index, name ->
                            ps.setString(1, table)
                            ps.setInt(2, index)
                            ps.setString(3, name)
                            ps.setString(4, affinities[index])
                            ps.addBatch()
                        }
                        ps.executeBatch()
                    }

                    if (columnNames.isNotEmpty()) {
                        val columnsSql = columnNames.joinToString(",") { quoteIdent(it) }
                        val placeholders = columnNames.joinToString(",") { "?" }
                        val sql = "INSERT INTO ${quoteIdent(table)} ($columnsSql) VALUES ($placeholders)"
                        db.prepareStatement(sql).use { ps ->
                            for (r in 0 until rowsJson.length()) {
                                val row = rowsJson.getJSONArray(r)
                                columnNames.indices.forEach { idx -> setJsonValue(ps, idx + 1, row.opt(idx)) }
                                ps.addBatch()
                                totalRows++
                            }
                            ps.executeBatch()
                        }
                    }
                }

                importPreferences(db, preferences)
                putMeta(db, "backup_created_at", manifest.optString("createdAt", ""))
                putMeta(db, "database_user_version", dbVersion.toString())
                putMeta(db, "source_file", file.absolutePath)
                putMeta(db, "imported_at", LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME))
                db.commit()
                return ImportSummary(names.size, totalRows, manifest.optString("createdAt").takeIf { it.isNotBlank() }, dbVersion)
            } catch (t: Throwable) {
                db.rollback()
                throw t
            } finally {
                db.autoCommit = true
            }
        }
    }

    fun exportBackup(destination: File): ExportSummary {
        destination.parentFile?.mkdirs()
        val databaseJson = JSONObject()
        val tablesJson = JSONObject()
        var totalRows = 0
        val names = tableNames()

        names.forEach { table ->
            val cols = columns(table).map { it.name }
            val tableJson = JSONObject()
            val colArray = JSONArray()
            cols.forEach(colArray::put)
            tableJson.put("columns", colArray)

            val rowsJson = JSONArray()
            rows(table).forEach { row ->
                val jsonRow = JSONArray()
                cols.forEach { column -> jsonRow.put(jdbcValueToJson(row.values[column])) }
                rowsJson.put(jsonRow)
                totalRows++
            }
            tableJson.put("rows", rowsJson)
            tablesJson.put(table, tableJson)
        }
        databaseJson.put("tables", tablesJson)

        val manifest = JSONObject()
            .put("format", BACKUP_FORMAT)
            .put("version", BACKUP_VERSION)
            .put("createdAt", LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME))
            .put("databaseUserVersion", meta("database_user_version")?.toIntOrNull() ?: 0)
            .put("tableCount", names.size)
            .put("rowCount", totalRows)

        val prefs = exportPreferences()
        ZipOutputStream(BufferedOutputStream(destination.outputStream())).use { zip ->
            writeZipText(zip, "manifest.json", manifest.toString(2))
            writeZipText(zip, "database.json", databaseJson.toString())
            writeZipText(zip, "preferences.json", prefs.toString())
        }
        return ExportSummary(names.size, totalRows, destination)
    }

    fun clearDataset() {
        connection().use { db ->
            db.autoCommit = false
            try {
                val existing = tableNames(db)
                db.createStatement().use { st ->
                    existing.forEach { st.execute("DROP TABLE IF EXISTS ${quoteIdent(it)}") }
                    st.execute("DELETE FROM _nb_schema")
                    st.execute("DELETE FROM _nb_preferences")
                    st.execute("DELETE FROM _nb_meta")
                }
                db.commit()
            } catch (t: Throwable) {
                db.rollback()
                throw t
            } finally {
                db.autoCommit = true
            }
        }
    }

    private fun tableNames(db: Connection): List<String> =
        db.prepareStatement("SELECT DISTINCT table_name FROM _nb_schema ORDER BY table_name").use { ps ->
            ps.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.getString(1)) } }
        }

    private fun inferAffinity(rows: JSONArray, column: Int): String {
        var sawInteger = false
        var sawReal = false
        var sawBlob = false
        var sawText = false
        for (r in 0 until rows.length()) {
            val value = rows.getJSONArray(r).opt(column)
            if (value == null || value === JSONObject.NULL) continue
            when (value) {
                is ByteArray -> sawBlob = true
                is JSONObject -> if (value.optString("__type") == "blob") sawBlob = true else sawText = true
                is Float, is Double -> sawReal = true
                is Number -> sawInteger = true
                else -> sawText = true
            }
        }
        return when {
            sawBlob -> "BLOB"
            sawText -> "TEXT"
            sawReal -> "REAL"
            sawInteger -> "INTEGER"
            else -> "TEXT"
        }
    }

    private fun setJsonValue(ps: java.sql.PreparedStatement, index: Int, value: Any?) {
        when {
            value == null || value === JSONObject.NULL -> ps.setObject(index, null)
            value is JSONObject && value.optString("__type") == "blob" ->
                ps.setBytes(index, Base64.getDecoder().decode(value.getString("base64")))
            value is Int -> ps.setLong(index, value.toLong())
            value is Long -> ps.setLong(index, value)
            value is Number -> ps.setDouble(index, value.toDouble())
            else -> ps.setString(index, value.toString())
        }
    }

    private fun jdbcValueToJson(value: Any?): Any = when (value) {
        null -> JSONObject.NULL
        is ByteArray -> JSONObject().put("__type", "blob").put("base64", Base64.getEncoder().encodeToString(value))
        else -> value
    }

    private fun readRows(rs: ResultSet): List<DbRow> {
        val meta = rs.metaData
        val names = (1..meta.columnCount).map { meta.getColumnName(it) }
        return buildList {
            while (rs.next()) {
                val values = linkedMapOf<String, Any?>()
                names.forEachIndexed { index, name ->
                    values[name] = rs.getObject(index + 1)
                }
                add(DbRow(values))
            }
        }
    }

    private fun importPreferences(db: Connection, root: JSONObject) {
        db.prepareStatement(
            "INSERT OR REPLACE INTO _nb_preferences(pref_name,pref_key,encoded_json) VALUES(?,?,?)"
        ).use { ps ->
            val prefNames = root.keys()
            while (prefNames.hasNext()) {
                val prefName = prefNames.next()
                val obj = root.optJSONObject(prefName) ?: continue
                val keys = obj.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    val encoded = obj.optJSONObject(key) ?: continue
                    ps.setString(1, prefName)
                    ps.setString(2, key)
                    ps.setString(3, encoded.toString())
                    ps.addBatch()
                }
            }
            ps.executeBatch()
        }
    }

    private fun exportPreferences(): JSONObject = connection().use { db ->
        val root = JSONObject()
        db.createStatement().use { st ->
            st.executeQuery("SELECT pref_name,pref_key,encoded_json FROM _nb_preferences ORDER BY pref_name,pref_key").use { rs ->
                while (rs.next()) {
                    val prefName = rs.getString(1)
                    val key = rs.getString(2)
                    val encoded = JSONObject(rs.getString(3))
                    val prefObj = root.optJSONObject(prefName) ?: JSONObject().also { root.put(prefName, it) }
                    prefObj.put(key, encoded)
                }
            }
        }
        root
    }

    private fun putMeta(db: Connection, key: String, value: String?) {
        db.prepareStatement("INSERT OR REPLACE INTO _nb_meta(meta_key,meta_value) VALUES(?,?)").use { ps ->
            ps.setString(1, key)
            ps.setString(2, value)
            ps.executeUpdate()
        }
    }

    private fun writeZipText(zip: ZipOutputStream, name: String, text: String) {
        zip.putNextEntry(ZipEntry(name))
        zip.write(text.toByteArray(Charsets.UTF_8))
        zip.closeEntry()
    }

    private fun quoteIdent(name: String): String = "\"" + name.replace("\"", "\"\"") + "\""
}
