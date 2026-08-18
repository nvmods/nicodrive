#!/usr/bin/env python3
from pathlib import Path
import sys

if len(sys.argv) != 2:
    raise SystemExit("Usage: patch_budget_cycle_metadata.py <project_root>")

root = Path(sys.argv[1])
target = root / "app/src/main/java/com/example/nicobudget/data/model/BudgetViewModel.kt"
if not target.exists():
    raise SystemExit(f"Fichier introuvable: {target}")

text = target.read_text(encoding="utf-8")

# 1) Toutes les recherches de budget doivent utiliser la clé du cycle qui commence
# le 27, et non le mois civil courant.
old_current = '''    private fun getCurrentMonthYear(): String {
        return LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM"))
    }
'''
new_current = '''    private fun currentBudgetCycleStart(today: LocalDate = LocalDate.now()): LocalDate =
        if (today.dayOfMonth >= 27) today.withDayOfMonth(27)
        else today.minusMonths(1).withDayOfMonth(27)

    private fun getCurrentMonthYear(): String {
        return currentBudgetCycleStart()
            .format(DateTimeFormatter.ofPattern("yyyy-MM"))
    }

    private suspend fun repairCurrentBudgetCycleMetadata() {
        val budget = monthlyBudgetDao.getBudgetById() ?: return
        val cycleStart = currentBudgetCycleStart()
        val cycleEnd = cycleStart.plusMonths(1)
        val cycleKey = cycleStart.format(DateTimeFormatter.ofPattern("yyyy-MM"))

        // On ne change pas silencieusement de cycle ici : la réinitialisation du
        // budget reste une action explicite. En revanche, si le record représente
        // déjà le cycle courant, ses dates doivent être exactes.
        if (
            budget.monthYear == cycleKey &&
            (budget.startDate != cycleStart.toString() || budget.endDate != cycleEnd.toString())
        ) {
            monthlyBudgetDao.upsertBudget(
                budget.copy(
                    title = "Budget $cycleKey",
                    startDate = cycleStart.toString(),
                    endDate = cycleEnd.toString()
                )
            )
        }
    }
'''
if old_current in text:
    text = text.replace(old_current, new_current, 1)
elif "private fun currentBudgetCycleStart" not in text:
    raise SystemExit("getCurrentMonthYear introuvable")

# 2) Le calcul du nombre de semaines doit utiliser le cycle réellement actif.
text = text.replace(
'''            val today = LocalDate.now()
            val startDate = today.withDayOfMonth(27)
            val endDate = startDate.plusMonths(1)
''',
'''            val today = LocalDate.now()
            val startDate = currentBudgetCycleStart(today)
            val endDate = startDate.plusMonths(1)
''',
1,
)

# 3) Lors d'une réinitialisation du cycle, actualiser aussi les métadonnées d'un
# record existant. L'ancien code ne mettait à jour que les montants/semaine.
old_reset = '''            val existingBudget = monthlyBudgetDao.getBudgetBlocking(currentMonthYear)

            val updatedBudget = existingBudget?.copy(
                monthlyIncome = totalIncome,
                disposableLeftover = newDisposableLeftover,
                currentWeekIndex = 1,
                totalWeeks = totalWeeks
            ) ?: run {
                // Cycle budgétaire du 27 au 27 (cf. principes de fonctionnement §6).
                val today = LocalDate.now()
                val cycleStart =
                    if (today.dayOfMonth >= 27) today.withDayOfMonth(27)
                    else today.minusMonths(1).withDayOfMonth(27)
                val cycleEnd = cycleStart.plusMonths(1)
                MonthlyBudgetEntity(
                    title = "Budget $currentMonthYear",
                    monthYear = currentMonthYear,
                    monthlyIncome = totalIncome,
                    disposableLeftover = newDisposableLeftover,
                    currentWeekIndex = 1,
                    totalWeeks = totalWeeks,
                    startDate = cycleStart.toString(),
                    endDate = cycleEnd.toString()
                )
            }
'''
new_reset = '''            val cycleStart = currentBudgetCycleStart()
            val cycleEnd = cycleStart.plusMonths(1)
            val existingBudget = monthlyBudgetDao.getBudgetBlocking(currentMonthYear)

            val updatedBudget = existingBudget?.copy(
                title = "Budget $currentMonthYear",
                monthYear = currentMonthYear,
                monthlyIncome = totalIncome,
                disposableLeftover = newDisposableLeftover,
                currentWeekIndex = 1,
                totalWeeks = totalWeeks,
                startDate = cycleStart.toString(),
                endDate = cycleEnd.toString()
            ) ?: MonthlyBudgetEntity(
                title = "Budget $currentMonthYear",
                monthYear = currentMonthYear,
                monthlyIncome = totalIncome,
                disposableLeftover = newDisposableLeftover,
                currentWeekIndex = 1,
                totalWeeks = totalWeeks,
                startDate = cycleStart.toString(),
                endDate = cycleEnd.toString()
            )
'''
if old_reset in text:
    text = text.replace(old_reset, new_reset, 1)
elif "val cycleStart = currentBudgetCycleStart()" not in text:
    raise SystemExit("Bloc de création/réinitialisation MonthlyBudget introuvable")

# 4) Répare automatiquement l'ancienne ligne incohérente au prochain démarrage,
# avant le recalcul/rattachement des dépenses Drive.
old_reconcile = '''            withContext(Dispatchers.IO) {
                DriveImporter.reconcileCurrentBudget(getApplication())
            }
'''
new_reconcile = '''            withContext(Dispatchers.IO) {
                repairCurrentBudgetCycleMetadata()
                DriveImporter.reconcileCurrentBudget(getApplication())
            }
'''
if old_reconcile in text:
    text = text.replace(old_reconcile, new_reconcile, 1)
elif "repairCurrentBudgetCycleMetadata()" not in text:
    raise SystemExit("Bloc de réconciliation init introuvable")

required = [
    "currentBudgetCycleStart",
    "repairCurrentBudgetCycleMetadata",
    "val startDate = currentBudgetCycleStart(today)",
    "startDate = cycleStart.toString()",
]
missing = [m for m in required if m not in text]
if missing:
    raise SystemExit("Patch cycle budget incomplet: " + ", ".join(missing))

target.write_text(text, encoding="utf-8")
print(f"Cycle budget 27->27 corrigé à la source dans {target}")
