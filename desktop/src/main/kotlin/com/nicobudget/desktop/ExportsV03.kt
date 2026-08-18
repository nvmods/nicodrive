package com.nicobudget.desktop

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.io.File
import java.sql.DriverManager
import java.time.LocalDate
import javax.swing.JFileChooser
import javax.swing.filechooser.FileNameExtensionFilter

@Composable
internal fun ExportsV03Screen(model: AppModel) {
    var xlsxRunning by remember { mutableStateOf(false) }
    val tables = remember(model.revision) { DesktopStore.tableNames().map { it to DesktopStore.rowCount(it) } }

    Column(
        Modifier.fillMaxSize().padding(22.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text("Exports", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text(
            "Exports lisibles pour analyse et sauvegarde complète réinjectable dans NicoBudget.",
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                Text("Excel complet (.xlsx)", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(
                    "Un onglet Index puis un onglet par table : budget, dépenses, archives, catégories, commandes Drive et lignes produits. Le format est identique dans son principe à l'export Excel Android.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Button(
                    enabled = !xlsxRunning && tables.isNotEmpty(),
                    onClick = {
                        chooseXlsxV03()?.let { file ->
                            xlsxRunning = true
                            runCatching { DesktopXlsxExporter.export(file) }
                                .onSuccess { result -> model.refresh("Excel créé : ${result.tables} table(s), ${result.rows} ligne(s) dans ${result.file.name}.") }
                                .onFailure { model.fail("Export Excel impossible : ${it.message}") }
                            xlsxRunning = false
                        }
                    }
                ) { Text(if (xlsxRunning) "Export en cours…" else "Exporter toutes les données (.xlsx)") }
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Backup NicoBudget (.nbbackup)", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text("Copie complète de la base et des préférences, destinée à être réimportée sur Android ou sur un autre PC.")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        chooseBackupToOpen()?.let { file ->
                            runCatching { DesktopStore.importBackup(file) }
                                .onSuccess { model.refresh("Backup importé : ${it.tables} tables, ${it.rows} lignes.") }
                                .onFailure { model.fail("Import impossible : ${it.message}") }
                        }
                    }) { Text("Importer") }
                    OutlinedButton(enabled = tables.isNotEmpty(), onClick = {
                        chooseBackupToSave()?.let { file ->
                            runCatching { DesktopStore.exportBackup(file) }
                                .onSuccess { model.refresh("Backup créé : ${it.rows} lignes dans ${it.file.name}.") }
                                .onFailure { model.fail("Export impossible : ${it.message}") }
                        }
                    }) { Text("Exporter") }
                }
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Contenu exportable", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                tables.forEach { (name, count) ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(name)
                        Text("$count ligne(s)", fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}

@Composable
internal fun DiagnosticsV03Screen(model: AppModel) {
    var integrity by remember(model.revision) { mutableStateOf<String?>(null) }
    val tables = remember(model.revision) { DesktopStore.tableNames().map { it to DesktopStore.rowCount(it) } }

    Column(
        Modifier.fillMaxSize().padding(22.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text("Diagnostics", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text("Contrôles locaux de la base Windows et export d'un rapport technique.", color = MaterialTheme.colorScheme.onSurfaceVariant)

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                Text("Base SQLite", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                DiagnosticKV("Fichier", DesktopStore.databaseFile.absolutePath)
                DiagnosticKV("Tables", tables.size.toString())
                DiagnosticKV("Lignes", tables.sumOf { it.second }.toString())
                DiagnosticKV("Backup source", DesktopStore.meta("backup_created_at") ?: "—")
                DiagnosticKV("Import PC", DesktopStore.meta("imported_at") ?: "—")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        integrity = runCatching { DesktopDiagnosticsV03.integrityCheck() }.getOrElse { "ERREUR : ${it.message}" }
                    }) { Text("Vérifier l'intégrité") }
                    OutlinedButton(onClick = {
                        chooseDiagnosticV03()?.let { file ->
                            runCatching { DesktopDiagnosticsV03.writeReport(file) }
                                .onSuccess { model.refresh("Rapport diagnostic créé : ${file.name}.") }
                                .onFailure { model.fail("Diagnostic impossible : ${it.message}") }
                        }
                    }) { Text("Exporter diagnostic") }
                }
                integrity?.let { Text("Résultat : $it", fontWeight = FontWeight.SemiBold, color = if (it.equals("ok", true)) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error) }
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text("Cohérence fonctionnelle", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                val currentExpenseIds = DesktopStore.rowsIfExistsDiagnostic("expenses").mapNotNull { it.long("id") }.toSet()
                val driveLinks = DesktopStore.rowsIfExistsDiagnostic("drive_orders").mapNotNull { it.long("expenseId", "expense_id") }
                val broken = driveLinks.count { it !in currentExpenseIds }
                DiagnosticKV("Liaisons Drive → dépenses actives", driveLinks.size.toString())
                DiagnosticKV("Liaisons orphelines", broken.toString())
                DiagnosticKV("Commandes Drive", DesktopStore.rowCountSafeDiagnostic("drive_orders").toString())
                DiagnosticKV("Lignes produits", DesktopStore.rowCountSafeDiagnostic("drive_order_lines").toString())
                if (broken > 0) Text("Des commandes pointent vers une dépense absente de la table active. Une clôture ou un ancien import peut nécessiter une réconciliation.", color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

private object DesktopDiagnosticsV03 {
    fun integrityCheck(): String = DriverManager.getConnection("jdbc:sqlite:${DesktopStore.databaseFile.absolutePath}").use { db ->
        db.createStatement().use { st -> st.executeQuery("PRAGMA integrity_check").use { rs -> if (rs.next()) rs.getString(1) else "aucun résultat" } }
    }

    fun writeReport(file: File) {
        file.parentFile?.mkdirs()
        val tables = DesktopStore.tableNames().map { it to DesktopStore.rowCount(it) }
        file.writeText(buildString {
            appendLine("NicoBudget Desktop - diagnostic")
            appendLine("Date: ${LocalDate.now()}")
            appendLine("Base: ${DesktopStore.databaseFile.absolutePath}")
            appendLine("Integrity: ${integrityCheck()}")
            appendLine("Backup source: ${DesktopStore.meta("backup_created_at") ?: "-"}")
            appendLine("Imported at: ${DesktopStore.meta("imported_at") ?: "-"}")
            appendLine()
            appendLine("Tables:")
            tables.forEach { appendLine("- ${it.first}: ${it.second}") }
        }, Charsets.UTF_8)
    }
}

private fun chooseXlsxV03(): File? {
    val chooser = JFileChooser().apply {
        dialogTitle = "Exporter toutes les données NicoBudget"
        fileFilter = FileNameExtensionFilter("Excel (*.xlsx)", "xlsx")
        selectedFile = File("NicoBudget_export_${LocalDate.now()}.xlsx")
    }
    if (chooser.showSaveDialog(null) != JFileChooser.APPROVE_OPTION) return null
    val f = chooser.selectedFile
    return if (f.extension.equals("xlsx", true)) f else File(f.parentFile, f.name + ".xlsx")
}

private fun chooseDiagnosticV03(): File? {
    val chooser = JFileChooser().apply {
        dialogTitle = "Exporter le diagnostic NicoBudget"
        fileFilter = FileNameExtensionFilter("Texte (*.txt)", "txt")
        selectedFile = File("NicoBudget_diagnostic_${LocalDate.now()}.txt")
    }
    if (chooser.showSaveDialog(null) != JFileChooser.APPROVE_OPTION) return null
    val f = chooser.selectedFile
    return if (f.extension.equals("txt", true)) f else File(f.parentFile, f.name + ".txt")
}

@Composable
private fun DiagnosticKV(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(0.35f))
        Text(value, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(0.65f))
    }
}

private fun DesktopStore.rowsIfExistsDiagnostic(table: String): List<DbRow> = if (tableExists(table)) rows(table) else emptyList()
private fun DesktopStore.rowCountSafeDiagnostic(table: String): Int = if (tableExists(table)) rowCount(table) else 0
