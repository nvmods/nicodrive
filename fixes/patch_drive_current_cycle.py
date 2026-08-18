#!/usr/bin/env python3
from pathlib import Path
import sys

if len(sys.argv) != 2:
    raise SystemExit("Usage: patch_drive_current_cycle.py <project_root>")

root = Path(sys.argv[1])
target = root / "app/src/main/java/com/example/nicobudget/drive/DriveImporter.kt"
if not target.exists():
    raise SystemExit(f"Fichier introuvable: {target}")

text = target.read_text(encoding="utf-8")
old = '''    private fun belongsToActiveBudgetCycle(
        orderDate: String,
        budget: MonthlyBudgetEntity?
    ): Boolean {
        if (budget == null) return false
        return try {
            val date = LocalDate.parse(orderDate)
            val start = LocalDate.parse(budget.startDate)
            val end = LocalDate.parse(budget.endDate)
            val today = LocalDate.now()

            // On ne rattache une commande au budget que si ce budget est bien
            // le cycle actif aujourd'hui et si la commande se situe dans ce cycle.
            val budgetIsActive = !today.isBefore(start) && today.isBefore(end)
            budgetIsActive && !date.isBefore(start) && date.isBefore(end)
        } catch (_: Exception) {
            false
        }
    }
'''
new = '''    private fun belongsToActiveBudgetCycle(
        orderDate: String,
        budget: MonthlyBudgetEntity?
    ): Boolean {
        // NicoBudget fonctionne sur un cycle budgétaire du 27 au 27. Ne pas se
        // fier ici au record MonthlyBudget renvoyé par getBudgetById() : suivant
        // le moment du mois / une ancienne remise à zéro, ses dates peuvent ne
        // pas être le meilleur marqueur du cycle réellement affiché à l'écran.
        // On applique exactement la même règle calendaire que resetBudget().
        if (budget == null) return false
        return try {
            val date = LocalDate.parse(orderDate)
            val today = LocalDate.now()
            val cycleStart =
                if (today.dayOfMonth >= 27) today.withDayOfMonth(27)
                else today.minusMonths(1).withDayOfMonth(27)
            val cycleEnd = cycleStart.plusMonths(1)

            !date.isBefore(cycleStart) && date.isBefore(cycleEnd)
        } catch (_: Exception) {
            false
        }
    }
'''

if old not in text:
    if "val cycleStart =" in text and "today.dayOfMonth >= 27" in text:
        print("Cycle Drive 27->27 déjà appliqué")
    else:
        raise SystemExit("Fonction belongsToActiveBudgetCycle introuvable")
else:
    text = text.replace(old, new, 1)

target.write_text(text, encoding="utf-8")
print(f"Cycle budget Drive aligné sur 27->27 dans {target}")
