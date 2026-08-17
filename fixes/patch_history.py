#!/usr/bin/env python3
from pathlib import Path
import sys

if len(sys.argv) != 2:
    raise SystemExit("Usage: patch_history.py <project_root>")

root = Path(sys.argv[1])
screen = root / "app/src/main/java/com/example/nicobudget/ui/DriveScreen.kt"
if not screen.exists():
    raise SystemExit(f"Fichier introuvable: {screen}")

text = screen.read_text(encoding="utf-8")

state_anchor = "    var leclercRetryUrl by remember { mutableStateOf<String?>(null) }\n"
if "var showLeclercHistory by remember" not in text:
    if state_anchor not in text:
        raise SystemExit("Etat leclercRetryUrl introuvable")
    text = text.replace(
        state_anchor,
        state_anchor + "    var showLeclercHistory by remember { mutableStateOf(false) }\n",
        1,
    )

config_marker = "    if (showConfig) {\n"
history_dialog = '''    if (showLeclercHistory) {
        LeclercHistoryDialog(
            initialUrl = "https://fd6-espace-client.leclercdrive.fr/drive/magasin-123311-123311-saint-medard-en-jalles/mes-commandes.aspx",
            viewModel = viewModel,
            onDismiss = { showLeclercHistory = false }
        )
    }

'''
if "LeclercHistoryDialog(" not in text:
    if config_marker not in text:
        raise SystemExit("Point insertion dialogue historique introuvable")
    text = text.replace(config_marker, history_dialog + config_marker, 1)

if 'Text("Synchroniser l\'historique E.Leclerc")' not in text:
    retry_anchor = "\n        if (leclercRetryUrl != null) {\n"
    surface_anchor = "\n        item {\n            Surface("
    import_pos = text.find('Text("Importer des PDF manuellement")')
    if import_pos < 0:
        raise SystemExit("Bouton import manuel introuvable")
    insert_pos = text.find(retry_anchor, import_pos)
    if insert_pos < 0:
        insert_pos = text.find(surface_anchor, import_pos)
    if insert_pos < 0:
        raise SystemExit("Point insertion bouton historique introuvable")

    history_button = '''

        item {
            OutlinedButton(
                onClick = { showLeclercHistory = true },
                enabled = !syncing,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Synchroniser l'historique E.Leclerc")
            }
        }
'''
    text = text[:insert_pos] + history_button + text[insert_pos:]

screen.write_text(text, encoding="utf-8")
print(f"Historique batch intégré dans {screen}")
