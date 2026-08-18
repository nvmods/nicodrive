#!/usr/bin/env python3
from pathlib import Path
import sys

if len(sys.argv) != 2:
    raise SystemExit("Usage: patch_history_budget_reconcile.py <project_root>")

root = Path(sys.argv[1])
target = root / "app/src/main/java/com/example/nicobudget/ui/LeclercHistoryDialog.kt"
if not target.exists():
    raise SystemExit(f"Fichier introuvable: {target}")

text = target.read_text(encoding="utf-8")

old = '''                viewModel.refreshBudget()
                viewModel.calculateCurrentWeekBudget()
                viewModel.loadExpensesByCategory()
                status = "Batch multi-Drive terminé : $imported importée(s), $duplicates doublon(s), $failed échec(s)."
'''
new = '''                // Le batch sert aussi de solution de secours aux mails. Une
                // commande du cycle courant doit donc impacter le budget exactement
                // comme un import mail ou manuel. La réconciliation rattache aussi
                // une commande déjà présente mais importée par une ancienne build
                // avec expenseId = null.
                withContext(Dispatchers.IO) {
                    DriveImporter.reconcileCurrentBudget(view.context.applicationContext)
                }
                viewModel.refreshBudget()
                viewModel.calculateCurrentWeekBudget()
                viewModel.loadExpensesByCategory()
                status = "Batch multi-Drive terminé : $imported importée(s), $duplicates doublon(s), $failed échec(s)."
'''

if "Le batch sert aussi de solution de secours aux mails" not in text:
    if old not in text:
        raise SystemExit("Bloc fin batch historique introuvable")
    text = text.replace(old, new, 1)

target.write_text(text, encoding="utf-8")
print(f"Réconciliation budget après batch ajoutée dans {target}")
