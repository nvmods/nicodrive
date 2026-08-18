#!/usr/bin/env python3
from pathlib import Path
import sys

if len(sys.argv) != 2:
    raise SystemExit("Usage: patch_xlsx_export.py <project_root>")

root = Path(sys.argv[1])
target = root / "app/src/main/java/com/example/nicobudget/ui/DriveStatsScreen.kt"
if not target.exists():
    raise SystemExit(f"Fichier introuvable: {target}")

text = target.read_text(encoding="utf-8")

imports = {
    "import androidx.activity.compose.rememberLauncherForActivityResult\n": "import androidx.compose.foundation.clickable\n",
    "import androidx.activity.result.contract.ActivityResultContracts\n": "import androidx.activity.compose.rememberLauncherForActivityResult\n",
    "import androidx.compose.ui.platform.LocalContext\n": "import androidx.compose.ui.Modifier\n",
    "import com.example.nicobudget.export.DataXlsxExporter\n": "import com.example.nicobudget.data.model.*\n",
    "import kotlinx.coroutines.Dispatchers\n": "import kotlinx.coroutines.launch\n",
    "import kotlinx.coroutines.withContext\n": "import kotlinx.coroutines.Dispatchers\n",
    "import java.time.LocalDate\n": "import java.time.YearMonth\n",
}

for line, after in imports.items():
    if line not in text:
        if after not in text:
            raise SystemExit(f"Import anchor introuvable: {after.strip()}")
        text = text.replace(after, after + line, 1)

state_anchor = "    val scope = rememberCoroutineScope()\n"
state_block = '''    val scope = rememberCoroutineScope()
    val exportContext = LocalContext.current
    var exportRunning by remember { mutableStateOf(false) }
    var exportStatus by remember { mutableStateOf<String?>(null) }
    val xlsxExportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument(
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        )
    ) { uri ->
        if (uri != null) {
            exportRunning = true
            exportStatus = "Export Excel en cours…"
            scope.launch {
                try {
                    val result = withContext(Dispatchers.IO) {
                        DataXlsxExporter.export(exportContext, uri)
                    }
                    exportStatus = "Export terminé : ${result.rows} ligne(s), ${result.dataSheets} table(s), ${result.sheets} onglet(s)."
                } catch (e: Exception) {
                    exportStatus = "Échec de l'export : ${e.message ?: "erreur inconnue"}"
                } finally {
                    exportRunning = false
                }
            }
        }
    }
'''

if "val xlsxExportLauncher = rememberLauncherForActivityResult" not in text:
    if state_anchor not in text:
        raise SystemExit("Point insertion export state introuvable")
    text = text.replace(state_anchor, state_block, 1)

header_anchor = '        SectionHeader(Icons.Default.BarChart, "Stats Leclerc Drive")\n'
export_ui = '''        SectionHeader(Icons.Default.BarChart, "Stats Leclerc Drive")

        SectionCard(Icons.Default.BarChart, "Export des données") {
            Text(
                "Crée un fichier Excel .xlsx avec toutes les tables de données locales de NicoBudget : " +
                    "budget, dépenses, commandes Drive et lignes produits. Les préférences et identifiants ne sont pas exportés.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))
            Button(
                enabled = !exportRunning,
                onClick = {
                    exportStatus = null
                    xlsxExportLauncher.launch("NicoBudget_export_${LocalDate.now()}.xlsx")
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (exportRunning) "Export en cours…" else "Exporter toutes les données (.xlsx)")
            }
            if (exportRunning) {
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            exportStatus?.let { message ->
                Spacer(Modifier.height(6.dp))
                Text(
                    message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary
                )
            }
        }
'''

if "Exporter toutes les données (.xlsx)" not in text:
    if header_anchor not in text:
        raise SystemExit("Point insertion UI export introuvable")
    text = text.replace(header_anchor, export_ui, 1)

target.write_text(text, encoding="utf-8")
print(f"Export XLSX intégré dans {target}")
