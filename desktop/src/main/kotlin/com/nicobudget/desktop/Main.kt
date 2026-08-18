package com.nicobudget.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import java.io.File
import javax.swing.JFileChooser
import javax.swing.filechooser.FileNameExtensionFilter

internal enum class Section(val label: String, val glyph: String) {
    DASHBOARD("Tableau de bord", "⌂"),
    EXPENSES("Dépenses", "€"),
    BUDGET("Budget & récurrents", "¤"),
    CLOSURES("Clôtures", "✓"),
    ARCHIVES("Archives", "▣"),
    STATS("Stats & analyses", "▥"),
    DRIVE("Leclerc Drive", "▤"),
    MENUS("Menus & courses", "☷"),
    EXPORTS("Exports", "⇩"),
    DATA("Données & échanges", "⇄"),
    DIAGNOSTICS("Diagnostics", "⚙"),
    SYNC("Synchronisation", "↔")
}

internal class AppModel {
    var section by mutableStateOf(Section.DASHBOARD)
    var revision by mutableIntStateOf(0)
    var message by mutableStateOf<String?>(null)
    var error by mutableStateOf(false)

    fun refresh(message: String? = null) {
        revision++
        if (message != null) {
            this.message = message
            error = false
        }
    }

    fun fail(text: String) {
        message = text
        error = true
    }
}

fun main() = application {
    val state = rememberWindowState(width = 1480.dp, height = 920.dp)
    Window(
        onCloseRequest = ::exitApplication,
        title = "NicoBudget Desktop",
        state = state
    ) {
        MaterialTheme(colorScheme = lightColorScheme()) {
            val model = remember { AppModel() }
            NicoBudgetDesktop(model)
        }
    }
}

@Composable
private fun NicoBudgetDesktop(model: AppModel) {
    val hasData = remember(model.revision) { DesktopStore.hasDataset() }

    Row(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Surface(
            modifier = Modifier.fillMaxHeight().width(245.dp),
            tonalElevation = 2.dp,
            shadowElevation = 2.dp
        ) {
            Column(
                Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                Text("NicoBudget", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text("Desktop 0.3", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))

                Section.entries.forEach { item ->
                    NavigationDrawerItem(
                        selected = model.section == item,
                        onClick = { model.section = item },
                        icon = { Text(item.glyph, style = MaterialTheme.typography.titleSmall) },
                        label = { Text(item.label, style = MaterialTheme.typography.bodyMedium) },
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                Spacer(Modifier.weight(1f))
                HorizontalDivider()
                Spacer(Modifier.height(5.dp))
                Text(
                    if (hasData) "Base PC chargée" else "Aucune donnée importée",
                    fontWeight = FontWeight.SemiBold,
                    style = MaterialTheme.typography.bodySmall
                )
                OutlinedButton(
                    onClick = {
                        val file = chooseBackupToOpen()
                        if (file != null) {
                            runCatching { DesktopStore.importBackup(file) }
                                .onSuccess { summary -> model.refresh("Backup importé : ${summary.tables} tables, ${summary.rows} lignes.") }
                                .onFailure { model.fail("Import impossible : ${it.message}") }
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Importer .nbbackup") }
            }
        }

        Column(Modifier.fillMaxSize()) {
            model.message?.let { text ->
                Surface(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 10.dp),
                    color = if (model.error) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.primaryContainer,
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(text, modifier = Modifier.weight(1f), color = if (model.error) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onPrimaryContainer)
                        TextButton(onClick = { model.message = null }) { Text("Fermer") }
                    }
                }
            }

            Box(Modifier.fillMaxSize()) {
                if (!hasData && model.section !in setOf(Section.DATA, Section.EXPORTS, Section.DIAGNOSTICS, Section.SYNC)) {
                    EmptyDesktopState(
                        onImport = {
                            val file = chooseBackupToOpen()
                            if (file != null) {
                                runCatching { DesktopStore.importBackup(file) }
                                    .onSuccess { summary -> model.refresh("Backup importé : ${summary.rows} lignes.") }
                                    .onFailure { model.fail("Import impossible : ${it.message}") }
                            }
                        }
                    )
                } else {
                    when (model.section) {
                        Section.DASHBOARD -> DashboardV03Screen(model)
                        Section.EXPENSES -> ExpensesParityScreen(model)
                        Section.BUDGET -> BudgetManagementScreen(model)
                        Section.CLOSURES -> ClosuresV03Screen(model)
                        Section.ARCHIVES -> ArchivesParityScreen(model)
                        Section.STATS -> AnalyticsV03Screen(model)
                        Section.DRIVE -> DriveV03Screen(model)
                        Section.MENUS -> MenusV03Screen(model)
                        Section.EXPORTS -> ExportsV03Screen(model)
                        Section.DATA -> DataV03Screen(model)
                        Section.DIAGNOSTICS -> DiagnosticsV03Screen(model)
                        Section.SYNC -> SyncDesktopScreen()
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyDesktopState(onImport: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Card(Modifier.widthIn(max = 560.dp).padding(24.dp)) {
            Column(
                Modifier.padding(28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Text("Bienvenue dans NicoBudget Desktop", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text(
                    "Importe un fichier .nbbackup NicoBudget. Le PC crée sa base locale puis toutes les actions métier fonctionnent hors ligne.",
                    style = MaterialTheme.typography.bodyLarge
                )
                Button(onClick = onImport) { Text("Choisir un backup NicoBudget") }
                Text(
                    "La synchronisation automatique sera choisie et ajoutée dans une étape séparée.",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

internal fun chooseBackupToOpen(): File? {
    val chooser = JFileChooser().apply {
        dialogTitle = "Importer une sauvegarde NicoBudget"
        fileFilter = FileNameExtensionFilter("Sauvegarde NicoBudget (*.nbbackup)", "nbbackup")
    }
    return if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) chooser.selectedFile else null
}

internal fun chooseBackupToSave(): File? {
    val chooser = JFileChooser().apply {
        dialogTitle = "Exporter une sauvegarde NicoBudget"
        fileFilter = FileNameExtensionFilter("Sauvegarde NicoBudget (*.nbbackup)", "nbbackup")
        selectedFile = File("NicoBudget_backup_PC.nbbackup")
    }
    if (chooser.showSaveDialog(null) != JFileChooser.APPROVE_OPTION) return null
    val selected = chooser.selectedFile
    return if (selected.extension.equals("nbbackup", ignoreCase = true)) selected else File(selected.parentFile, selected.name + ".nbbackup")
}
