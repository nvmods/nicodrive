#!/usr/bin/env python3
from pathlib import Path
import sys

if len(sys.argv) != 2:
    raise SystemExit("Usage: patch_food_habits.py <project_root>")

root = Path(sys.argv[1])
dao = root / "app/src/main/java/com/example/nicobudget/data/db/DriveOrderDao.kt"
vm = root / "app/src/main/java/com/example/nicobudget/data/model/BudgetViewModel.kt"
stats = root / "app/src/main/java/com/example/nicobudget/ui/DriveStatsScreen.kt"
models_src = Path(__file__).with_name("DriveFoodModels.kt")
ui_src = Path(__file__).with_name("DriveFoodInsights.kt")
models_dst = root / "app/src/main/java/com/example/nicobudget/data/model/DriveFoodModels.kt"
ui_dst = root / "app/src/main/java/com/example/nicobudget/ui/DriveFoodInsights.kt"

for path in (dao, vm, stats, models_src, ui_src):
    if not path.exists():
        raise SystemExit(f"Fichier introuvable: {path}")

models_dst.write_text(models_src.read_text(encoding="utf-8"), encoding="utf-8")
ui_dst.write_text(ui_src.read_text(encoding="utf-8"), encoding="utf-8")

# ---------------------------------------------------------------------------
# DAO : on remonte les lignes avec orderRowId + mois. 5-6k lignes restent très
# légères et permettent surtout de compter une famille une seule fois par commande.
# ---------------------------------------------------------------------------
text = dao.read_text(encoding="utf-8")
if "getFoodAnalysisLines" not in text:
    insert = '''

    /** Lignes brutes destinées à l'analyse des habitudes alimentaires. */
    @Query(
        """
        SELECT l.orderId AS orderRowId,
               substr(o.date, 1, 7) AS month,
               COALESCE(l.section, 'Sans rayon') AS section,
               l.label AS label,
               l.quantity AS quantity,
               l.total AS total
        FROM drive_order_lines l
        JOIN drive_orders o ON o.id = l.orderId
        ORDER BY o.date ASC, l.id ASC
        """
    )
    suspend fun getFoodAnalysisLines(): List<DriveFoodAnalysisLine>
'''
    pos = text.rfind("\n}")
    if pos < 0:
        raise SystemExit("Fin de DriveOrderDao introuvable")
    text = text[:pos] + insert + text[pos:]
    dao.write_text(text, encoding="utf-8")

# ---------------------------------------------------------------------------
# ViewModel : expose les lignes à l'écran de stats.
# ---------------------------------------------------------------------------
text = vm.read_text(encoding="utf-8")
if "getDriveFoodAnalysisLines" not in text:
    anchor = '''    suspend fun getArchivedExpenses(): List<ExpenseArchiveEntity> {
'''
    block = '''    suspend fun getDriveFoodAnalysisLines(): List<DriveFoodAnalysisLine> =
        driveOrderDao.getFoodAnalysisLines()

    suspend fun getArchivedExpenses(): List<ExpenseArchiveEntity> {
'''
    if anchor not in text:
        raise SystemExit("Point insertion getDriveFoodAnalysisLines introuvable")
    text = text.replace(anchor, block, 1)
    vm.write_text(text, encoding="utf-8")

# ---------------------------------------------------------------------------
# Stats : la lecture alimentaire vient avant le Top produits brut. On conserve
# celui-ci pour l'analyse financière, mais la vue repas devient la lecture métier.
# ---------------------------------------------------------------------------
text = stats.read_text(encoding="utf-8")
if "DriveFoodHabits(" not in text:
    anchor = '''        // ---------------- Top 10 produits ----------------
'''
    block = '''        // ---------------- Habitudes alimentaires ----------------
        DriveFoodHabits(
            viewModel = viewModel,
            selectedScope = selectedScope,
            periodLabel = periodLabel
        )

        // ---------------- Top 10 produits ----------------
'''
    if anchor not in text:
        raise SystemExit("Point insertion habitudes alimentaires introuvable")
    text = text.replace(anchor, block, 1)
    stats.write_text(text, encoding="utf-8")

print(f"Analyse habitudes alimentaires intégrée : {ui_dst}")
print(f"Modèle/classificateur copié : {models_dst}")
