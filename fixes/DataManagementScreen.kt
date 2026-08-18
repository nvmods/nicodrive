package com.example.nicobudget.ui

import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.nicobudget.data.db.AppDatabase
import com.example.nicobudget.ui.components.eur
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

private const val BACKUP_FORMAT = "NicoBudgetBackup"
private const val BACKUP_VERSION = 1
private val BACKUP_PREFS = listOf("drive_menu_planner_v2", "drive_food_family_overrides")

private data class ArchivedExpenseRow(
    val rowId: Long,
    val date: String,
    val category: String,
    val amount: Double,
    val description: String?
)

private data class RestoreSummary(
    val tables: Int,
    val rows: Int,
    val preferences: Int
)

@Composable
fun DataManagementScreen() {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    var archived by remember { mutableStateOf<List<ArchivedExpenseRow>>(emptyList()) }
    var loadingArchive by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var deleteTarget by remember { mutableStateOf<ArchivedExpenseRow?>(null) }
    var restoreUri by remember { mutableStateOf<Uri?>(null) }

    suspend fun reloadArchive() {
        loadingArchive = true
        archived = runCatching { loadArchivedExpenses(context) }.getOrElse {
            status = "Impossible de lire les dépenses archivées : ${it.message}"
            emptyList()
        }
        loadingArchive = false
    }

    LaunchedEffect(Unit) { reloadArchive() }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip")
    ) { uri ->
        if (uri != null) {
            scope.launch {
                busy = true
                status = null
                status = runCatching {
                    val summary = exportNicoBudgetBackup(context, uri)
                    "Sauvegarde créée : ${summary.first} table(s), ${summary.second} ligne(s)."
                }.getOrElse { "Échec de la sauvegarde : ${it.message}" }
                busy = false
            }
        }
    }

    val restoreLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) restoreUri = uri
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .navigationBarsPadding()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Données & sauvegarde", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text(
            "Nettoyage des archives et sauvegarde complète des données NicoBudget.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Sauvegarde / restauration", fontWeight = FontWeight.SemiBold)
                Text(
                    "Le fichier .nbbackup contient les tables budgétaires, l'historique Drive et les réglages de menus. " +
                        "Les sessions/cookies Leclerc et secrets d'authentification ne sont pas exportés.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Button(
                    onClick = {
                        val stamp = java.time.LocalDate.now().toString()
                        exportLauncher.launch("NicoBudget_backup_$stamp.nbbackup")
                    },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Save, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Exporter une sauvegarde")
                }
                OutlinedButton(
                    onClick = { restoreLauncher.launch(arrayOf("application/zip", "application/octet-stream", "*/*")) },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Restaurer une sauvegarde")
                }
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                status?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (it.startsWith("Échec") || it.startsWith("Impossible")) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Dépenses archivées", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    "${archived.size} dépense(s) · suppression unitaire uniquement",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            TextButton(onClick = { scope.launch { reloadArchive() } }, enabled = !busy) { Text("Actualiser") }
        }

        if (loadingArchive) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
        } else if (archived.isEmpty()) {
            Text("Aucune dépense archivée.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                contentPadding = PaddingValues(bottom = 20.dp)
            ) {
                items(archived, key = { it.rowId }) { row ->
                    Card(Modifier.fillMaxWidth()) {
                        Row(
                            Modifier.fillMaxWidth().padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(row.category, fontWeight = FontWeight.SemiBold)
                                val detail = buildString {
                                    append(row.date)
                                    row.description?.takeIf { it.isNotBlank() }?.let { append(" · ").append(it) }
                                }
                                Text(detail, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Spacer(Modifier.width(8.dp))
                            Text(row.amount.eur(), fontWeight = FontWeight.Bold)
                            IconButton(onClick = { deleteTarget = row }, enabled = !busy) {
                                Icon(Icons.Default.Delete, contentDescription = "Supprimer")
                            }
                        }
                    }
                }
            }
        }
    }

    deleteTarget?.let { row ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Supprimer cette dépense archivée ?") },
            text = {
                Text("${row.date} · ${row.category} · ${row.amount.eur()}\n\nCette suppression est définitive et ne touche pas aux autres archives.")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        deleteTarget = null
                        scope.launch {
                            busy = true
                            status = runCatching {
                                deleteArchivedExpense(context, row.rowId)
                                reloadArchive()
                                "Dépense archivée supprimée."
                            }.getOrElse { "Échec de la suppression : ${it.message}" }
                            busy = false
                        }
                    }
                ) { Text("Supprimer") }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("Annuler") } }
        )
    }

    restoreUri?.let { uri ->
        AlertDialog(
            onDismissRequest = { restoreUri = null },
            title = { Text("Restaurer cette sauvegarde ?") },
            text = {
                Text(
                    "Les tables présentes dans la sauvegarde remplaceront leurs données actuelles. " +
                        "Les sessions Leclerc et secrets locaux ne seront pas modifiés.\n\n" +
                        "Il est conseillé d'exporter une sauvegarde actuelle avant de continuer."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        restoreUri = null
                        scope.launch {
                            busy = true
                            status = null
                            status = runCatching {
                                val summary = restoreNicoBudgetBackup(context, uri)
                                reloadArchive()
                                "Restauration terminée : ${summary.tables} table(s), ${summary.rows} ligne(s), " +
                                    "${summary.preferences} réglage(s). Ferme puis rouvre NicoBudget pour recharger tous les écrans."
                            }.getOrElse { "Échec de la restauration : ${it.message}" }
                            busy = false
                        }
                    }
                ) { Text("Restaurer") }
            },
            dismissButton = { TextButton(onClick = { restoreUri = null }) { Text("Annuler") } }
        )
    }
}

private suspend fun loadArchivedExpenses(context: Context): List<ArchivedExpenseRow> = withContext(Dispatchers.IO) {
    val sql = AppDatabase.getDatabase(context).openHelper.readableDatabase
    val result = mutableListOf<ArchivedExpenseRow>()
    sql.query("SELECT rowid AS __rowid__, * FROM expense_archive ORDER BY date DESC, rowid DESC").use { c ->
        val rowIdx = c.getColumnIndex("__rowid__")
        val dateIdx = c.getColumnIndex("date")
        val catIdx = c.getColumnIndex("category")
        val amountIdx = c.getColumnIndex("amount")
        val descIdx = listOf("description", "label", "name", "note", "title")
            .map { c.getColumnIndex(it) }.firstOrNull { it >= 0 } ?: -1
        while (c.moveToNext()) {
            result += ArchivedExpenseRow(
                rowId = if (rowIdx >= 0) c.getLong(rowIdx) else c.position.toLong(),
                date = if (dateIdx >= 0) c.getString(dateIdx).orEmpty() else "",
                category = if (catIdx >= 0) c.getString(catIdx)?.ifBlank { "Sans catégorie" } ?: "Sans catégorie" else "Sans catégorie",
                amount = if (amountIdx >= 0) c.getDouble(amountIdx) else 0.0,
                description = if (descIdx >= 0 && !c.isNull(descIdx)) c.getString(descIdx) else null
            )
        }
    }
    result
}

private suspend fun deleteArchivedExpense(context: Context, rowId: Long) = withContext(Dispatchers.IO) {
    val sql = AppDatabase.getDatabase(context).openHelper.writableDatabase
    sql.execSQL("DELETE FROM expense_archive WHERE rowid = ?", arrayOf(rowId))
}

private suspend fun exportNicoBudgetBackup(context: Context, uri: Uri): Pair<Int, Int> = withContext(Dispatchers.IO) {
    val db = AppDatabase.getDatabase(context)
    val sql = db.openHelper.writableDatabase
    runCatching { sql.query("PRAGMA wal_checkpoint(FULL)").close() }

    val databaseJson = JSONObject()
    val tablesJson = JSONObject()
    var totalRows = 0
    val tables = applicationTables(sql)

    tables.forEach { table ->
        val tableJson = JSONObject()
        val rowsJson = JSONArray()
        sql.query("SELECT * FROM ${quoteIdent(table)}").use { c ->
            val columns = JSONArray()
            c.columnNames.forEach { columns.put(it) }
            tableJson.put("columns", columns)
            while (c.moveToNext()) {
                val row = JSONArray()
                for (i in 0 until c.columnCount) row.put(cursorValueForJson(c, i))
                rowsJson.put(row)
                totalRows++
            }
        }
        tableJson.put("rows", rowsJson)
        tablesJson.put(table, tableJson)
    }
    databaseJson.put("tables", tablesJson)

    val userVersion = sql.query("PRAGMA user_version").use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }
    val manifest = JSONObject()
        .put("format", BACKUP_FORMAT)
        .put("version", BACKUP_VERSION)
        .put("createdAt", LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME))
        .put("databaseUserVersion", userVersion)
        .put("tableCount", tables.size)
        .put("rowCount", totalRows)

    val prefsJson = exportPreferences(context)
    context.contentResolver.openOutputStream(uri)?.use { raw ->
        ZipOutputStream(BufferedOutputStream(raw)).use { zip ->
            writeZipText(zip, "manifest.json", manifest.toString(2))
            writeZipText(zip, "database.json", databaseJson.toString())
            writeZipText(zip, "preferences.json", prefsJson.toString())
        }
    } ?: error("Impossible d'ouvrir le fichier de destination")

    tables.size to totalRows
}

private suspend fun restoreNicoBudgetBackup(context: Context, uri: Uri): RestoreSummary = withContext(Dispatchers.IO) {
    val entries = mutableMapOf<String, String>()
    context.contentResolver.openInputStream(uri)?.use { raw ->
        ZipInputStream(BufferedInputStream(raw)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (!entry.isDirectory && entry.name in setOf("manifest.json", "database.json", "preferences.json")) {
                    entries[entry.name] = zip.readBytes().toString(Charsets.UTF_8)
                }
                zip.closeEntry()
            }
        }
    } ?: error("Impossible d'ouvrir la sauvegarde")

    val manifest = JSONObject(entries["manifest.json"] ?: error("manifest.json absent"))
    require(manifest.optString("format") == BACKUP_FORMAT) { "Ce fichier n'est pas une sauvegarde NicoBudget" }
    val version = manifest.optInt("version", 0)
    require(version in 1..BACKUP_VERSION) { "Version de sauvegarde non supportée : $version" }

    val databaseJson = JSONObject(entries["database.json"] ?: error("database.json absent"))
    val backupTables = databaseJson.getJSONObject("tables")
    val db = AppDatabase.getDatabase(context)
    val sql = db.openHelper.writableDatabase
    val currentTables = applicationTables(sql).toSet()
    var restoredTables = 0
    var restoredRows = 0

    runCatching { sql.execSQL("PRAGMA foreign_keys=OFF") }
    sql.beginTransaction()
    try {
        val tableNames = backupTables.keys()
        while (tableNames.hasNext()) {
            val table = tableNames.next()
            if (table !in currentTables) continue
            val tableJson = backupTables.getJSONObject(table)
            val backupColumnsJson = tableJson.getJSONArray("columns")
            val backupColumns = (0 until backupColumnsJson.length()).map { backupColumnsJson.getString(it) }
            val currentColumns = tableColumns(sql, table).toSet()
            val selected = backupColumns.mapIndexedNotNull { index, name -> if (name in currentColumns) index to name else null }
            if (selected.isEmpty()) continue

            sql.execSQL("DELETE FROM ${quoteIdent(table)}")
            val columnSql = selected.joinToString(",") { quoteIdent(it.second) }
            val placeholders = selected.joinToString(",") { "?" }
            val insertSql = "INSERT OR REPLACE INTO ${quoteIdent(table)} ($columnSql) VALUES ($placeholders)"
            val rows = tableJson.getJSONArray("rows")
            for (r in 0 until rows.length()) {
                val row = rows.getJSONArray(r)
                val args = selected.map { (index, _) -> jsonValueForSql(row.opt(index)) }.toTypedArray()
                sql.execSQL(insertSql, args)
                restoredRows++
            }
            restoredTables++
        }
        sql.setTransactionSuccessful()
    } finally {
        sql.endTransaction()
        runCatching { sql.execSQL("PRAGMA foreign_keys=ON") }
    }

    val restoredPrefs = entries["preferences.json"]?.let { restorePreferences(context, JSONObject(it)) } ?: 0
    RestoreSummary(restoredTables, restoredRows, restoredPrefs)
}

private fun applicationTables(sql: androidx.sqlite.db.SupportSQLiteDatabase): List<String> {
    val result = mutableListOf<String>()
    sql.query(
        "SELECT name FROM sqlite_master WHERE type='table' " +
            "AND name NOT LIKE 'sqlite_%' AND name NOT IN ('room_master_table','android_metadata') ORDER BY name"
    ).use { c -> while (c.moveToNext()) result += c.getString(0) }
    return result
}

private fun tableColumns(sql: androidx.sqlite.db.SupportSQLiteDatabase, table: String): List<String> {
    val result = mutableListOf<String>()
    sql.query("PRAGMA table_info(${quoteIdent(table)})").use { c ->
        val idx = c.getColumnIndex("name")
        while (c.moveToNext()) if (idx >= 0) result += c.getString(idx)
    }
    return result
}

private fun cursorValueForJson(c: Cursor, index: Int): Any = when (c.getType(index)) {
    Cursor.FIELD_TYPE_NULL -> JSONObject.NULL
    Cursor.FIELD_TYPE_INTEGER -> c.getLong(index)
    Cursor.FIELD_TYPE_FLOAT -> c.getDouble(index)
    Cursor.FIELD_TYPE_STRING -> c.getString(index)
    Cursor.FIELD_TYPE_BLOB -> JSONObject()
        .put("__type", "blob")
        .put("base64", Base64.encodeToString(c.getBlob(index), Base64.NO_WRAP))
    else -> JSONObject.NULL
}

private fun jsonValueForSql(value: Any?): Any? {
    if (value == null || value === JSONObject.NULL) return null
    if (value is JSONObject && value.optString("__type") == "blob") {
        return Base64.decode(value.getString("base64"), Base64.DEFAULT)
    }
    return value
}

private fun exportPreferences(context: Context): JSONObject {
    val root = JSONObject()
    BACKUP_PREFS.forEach { prefName ->
        val prefObj = JSONObject()
        context.getSharedPreferences(prefName, Context.MODE_PRIVATE).all.forEach { (key, value) ->
            prefObj.put(key, encodePreferenceValue(value))
        }
        root.put(prefName, prefObj)
    }
    return root
}

private fun encodePreferenceValue(value: Any?): JSONObject {
    val out = JSONObject()
    when (value) {
        is String -> out.put("type", "string").put("value", value)
        is Boolean -> out.put("type", "boolean").put("value", value)
        is Int -> out.put("type", "int").put("value", value)
        is Long -> out.put("type", "long").put("value", value)
        is Float -> out.put("type", "float").put("value", value.toDouble())
        is Set<*> -> {
            val array = JSONArray()
            value.filterIsInstance<String>().sorted().forEach { array.put(it) }
            out.put("type", "stringSet").put("value", array)
        }
        else -> out.put("type", "string").put("value", value?.toString().orEmpty())
    }
    return out
}

private fun restorePreferences(context: Context, root: JSONObject): Int {
    var count = 0
    BACKUP_PREFS.forEach { prefName ->
        if (!root.has(prefName)) return@forEach
        val obj = root.getJSONObject(prefName)
        val editor = context.getSharedPreferences(prefName, Context.MODE_PRIVATE).edit().clear()
        val keys = obj.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val encoded = obj.getJSONObject(key)
            when (encoded.getString("type")) {
                "string" -> editor.putString(key, encoded.optString("value", ""))
                "boolean" -> editor.putBoolean(key, encoded.optBoolean("value"))
                "int" -> editor.putInt(key, encoded.optInt("value"))
                "long" -> editor.putLong(key, encoded.optLong("value"))
                "float" -> editor.putFloat(key, encoded.optDouble("value").toFloat())
                "stringSet" -> {
                    val array = encoded.getJSONArray("value")
                    editor.putStringSet(key, (0 until array.length()).map { array.getString(it) }.toSet())
                }
            }
            count++
        }
        editor.apply()
    }
    return count
}

private fun quoteIdent(name: String): String = "\"" + name.replace("\"", "\"\"") + "\""

private fun writeZipText(zip: ZipOutputStream, name: String, content: String) {
    zip.putNextEntry(ZipEntry(name))
    zip.write(content.toByteArray(Charsets.UTF_8))
    zip.closeEntry()
}
