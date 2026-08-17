#!/usr/bin/env python3
from pathlib import Path
import sys

if len(sys.argv) != 2:
    raise SystemExit("Usage: patch_drive_budget.py <project_root>")

root = Path(sys.argv[1])
target = root / "app/src/main/java/com/example/nicobudget/data/model/BudgetViewModel.kt"
if not target.exists():
    raise SystemExit(f"Fichier introuvable: {target}")

text = target.read_text(encoding="utf-8")

old_init = '''    init {
        refreshBudget()
        calculateCurrentWeekBudget()
        loadExpensesByCategory()
    }
'''
new_init = '''    init {
        refreshBudget()
        calculateCurrentWeekBudget()
        loadExpensesByCategory()

        // Les anciennes versions créaient une dépense courante pour chaque bon
        // Drive historique importé. On réconcilie au démarrage : seuls les bons
        // appartenant au cycle budgétaire actif restent dans la table expenses.
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                DriveImporter.reconcileCurrentBudget(getApplication())
            }
            refreshBudget()
            calculateCurrentWeekBudget()
            loadExpensesByCategory()
        }
    }
'''

if "DriveImporter.reconcileCurrentBudget" not in text:
    if old_init not in text:
        raise SystemExit("Bloc init BudgetViewModel introuvable")
    text = text.replace(old_init, new_init, 1)

target.write_text(text, encoding="utf-8")
print(f"Réconciliation budget Drive ajoutée : {target}")
