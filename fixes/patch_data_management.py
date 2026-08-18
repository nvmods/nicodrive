#!/usr/bin/env python3
from pathlib import Path
import shutil
import sys

if len(sys.argv) != 2:
    raise SystemExit("Usage: patch_data_management.py <project_root>")

root = Path(sys.argv[1])
fixes = Path(__file__).resolve().parent
source = fixes / "DataManagementScreen.kt"
target = root / "app/src/main/java/com/example/nicobudget/ui/DataManagementScreen.kt"
main = root / "app/src/main/java/com/example/nicobudget/MainActivity.kt"

if not source.exists():
    raise SystemExit(f"Source introuvable: {source}")
if not main.exists():
    raise SystemExit(f"MainActivity introuvable: {main}")

target.parent.mkdir(parents=True, exist_ok=True)
shutil.copy2(source, target)

text = main.read_text(encoding="utf-8")

if 'navController.navigate("datamanagement")' not in text:
    anchor = '''                            DrawerItem(Icons.Default.BarChart, "Stats budget") {
                                navController.navigate("budgetstats") { launchSingleTop = true }
                                scope.launch { drawerState.close() }
                            }
'''
    new = anchor + '''                            DrawerItem(Icons.Default.BarChart, "Données & sauvegarde") {
                                navController.navigate("datamanagement") { launchSingleTop = true }
                                scope.launch { drawerState.close() }
                            }
'''
    if anchor not in text:
        raise SystemExit("Entrée Stats budget du drawer introuvable")
    text = text.replace(anchor, new, 1)

if 'composable("datamanagement")' not in text:
    anchor = '''                                composable("budgetstats") { BudgetStatsScreen() }
'''
    new = anchor + '''                                composable("datamanagement") { DataManagementScreen() }
'''
    if anchor not in text:
        raise SystemExit("Route budgetstats introuvable")
    text = text.replace(anchor, new, 1)

main.write_text(text, encoding="utf-8")
print("Gestion des données installée :")
print("- suppression unitaire des dépenses archivées")
print("- export .nbbackup de toutes les tables applicatives")
print("- restauration transactionnelle des tables compatibles")
print("- sauvegarde des profils menus et classifications alimentaires")
print("- exclusion volontaire des sessions/cookies et secrets d'authentification")
