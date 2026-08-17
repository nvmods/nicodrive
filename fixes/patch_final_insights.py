#!/usr/bin/env python3
from pathlib import Path
import sys

if len(sys.argv) != 2:
    raise SystemExit("Usage: patch_final_insights.py <project_root>")

root = Path(sys.argv[1])
vm = root / "app/src/main/java/com/example/nicobudget/data/model/BudgetViewModel.kt"
stats = root / "app/src/main/java/com/example/nicobudget/ui/DriveStatsScreen.kt"

for target in (vm, stats):
    if not target.exists():
        raise SystemExit(f"Fichier introuvable: {target}")

# ---------------------------------------------------------------------------
# ViewModel : expose les deux agrégats historiques en une requête chacun.
# ---------------------------------------------------------------------------
text = vm.read_text(encoding="utf-8")
anchor = '''    suspend fun getDriveSectionTotalsAll(): List<CategoryExpenseTotal> =
        driveOrderDao.getSectionTotalsAll()

'''
insert = '''    suspend fun getDriveSectionTotalsAll(): List<CategoryExpenseTotal> =
        driveOrderDao.getSectionTotalsAll()

    suspend fun getDriveProductMonthlyStatsAll(): List<DriveProductMonthlyStat> =
        driveOrderDao.getProductMonthlyStatsAll()

    suspend fun getDriveSectionMonthlyStatsAll(): List<DriveSectionMonthlyStat> =
        driveOrderDao.getSectionMonthlyStatsAll()

'''
if "getDriveProductMonthlyStatsAll" not in text:
    if anchor not in text:
        raise SystemExit("Point insertion insights dans BudgetViewModel introuvable")
    text = text.replace(anchor, insert, 1)
vm.write_text(text, encoding="utf-8")

# ---------------------------------------------------------------------------
# Écran stats : les nouvelles analyses viennent après les graphes/comparatifs b98.
# ---------------------------------------------------------------------------
text = stats.read_text(encoding="utf-8")
anchor = '''        DriveMonthComparison(
            months = months,
            allMonthly = allMonthly,
            viewModel = viewModel
        )

'''
block = '''        DriveMonthComparison(
            months = months,
            allMonthly = allMonthly,
            viewModel = viewModel
        )

        DriveHistoricalInsights(
            allMonthly = allMonthly,
            selectedScope = selectedScope,
            viewModel = viewModel
        )

'''
if "DriveHistoricalInsights(" not in text:
    if anchor not in text:
        raise SystemExit("Point insertion DriveHistoricalInsights introuvable")
    text = text.replace(anchor, block, 1)
stats.write_text(text, encoding="utf-8")

print(f"Insights historiques ajoutés dans {vm} et {stats}")
