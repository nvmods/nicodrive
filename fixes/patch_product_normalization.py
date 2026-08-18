#!/usr/bin/env python3
from pathlib import Path
import sys

if len(sys.argv) != 2:
    raise SystemExit("Usage: patch_product_normalization.py <project_root>")

root = Path(sys.argv[1])
vm = root / "app/src/main/java/com/example/nicobudget/data/model/BudgetViewModel.kt"
stats = root / "app/src/main/java/com/example/nicobudget/ui/DriveStatsScreen.kt"
src = Path(__file__).with_name("DriveProductNormalizer.kt")
dst = root / "app/src/main/java/com/example/nicobudget/data/model/DriveProductNormalizer.kt"

for path in (vm, stats, src):
    if not path.exists():
        raise SystemExit(f"Fichier introuvable: {path}")

dst.write_text(src.read_text(encoding="utf-8"), encoding="utf-8")
text = vm.read_text(encoding="utf-8")

replacements = {
'''    suspend fun getDriveTopProducts(month: String, limit: Int = 15): List<DriveTopProduct> =
        driveOrderDao.getTopProducts(month, limit)
''': '''    suspend fun getDriveTopProducts(month: String, limit: Int = 15): List<DriveTopProduct> =
        DriveProductNormalizer.mergeTopProducts(
            driveOrderDao.getTopProducts(month, 10000)
        ).take(limit)
''',
'''    suspend fun getDriveTopProductsForYear(year: String, limit: Int = 15): List<DriveTopProduct> =
        driveOrderDao.getTopProductsForYear(year, limit)
''': '''    suspend fun getDriveTopProductsForYear(year: String, limit: Int = 15): List<DriveTopProduct> =
        DriveProductNormalizer.mergeTopProducts(
            driveOrderDao.getTopProductsForYear(year, 10000)
        ).take(limit)
''',
'''    suspend fun getDriveTopProductsAll(limit: Int = 15): List<DriveTopProduct> =
        driveOrderDao.getTopProductsAll(limit)
''': '''    suspend fun getDriveTopProductsAll(limit: Int = 15): List<DriveTopProduct> =
        DriveProductNormalizer.mergeTopProducts(
            driveOrderDao.getTopProductsAll(10000)
        ).take(limit)
''',
'''    suspend fun getDriveProductMonthlyStatsAll(): List<DriveProductMonthlyStat> =
        driveOrderDao.getProductMonthlyStatsAll()
''': '''    suspend fun getDriveProductMonthlyStatsAll(): List<DriveProductMonthlyStat> =
        DriveProductNormalizer.mergeMonthly(driveOrderDao.getProductMonthlyStatsAll())
''',
'''    suspend fun getDriveProductEvolution(label: String): List<DriveProductStat> =
        driveOrderDao.getProductEvolution(label)
''': '''    suspend fun getDriveProductEvolution(label: String): List<DriveProductStat> =
        DriveProductNormalizer.evolutionFor(label, driveOrderDao.getProductMonthlyStatsAll())
''',
}

for old, new in replacements.items():
    if old in text:
        text = text.replace(old, new, 1)
    elif new.strip() not in text:
        raise SystemExit("Bloc BudgetViewModel introuvable pour normalisation produit:\n" + old)

vm.write_text(text, encoding="utf-8")

# Information discrète dans l'écran : les formats/poids restent volontairement distincts.
text = stats.read_text(encoding="utf-8")
old_note = '''                    "Classement calculé sur toutes les lignes reconnues de la période. " +
                        "Tape un produit pour voir son évolution.",
'''
new_note = '''                    "Classement calculé sur toutes les lignes reconnues. Les variantes mineures " +
                        "de libellé sont regroupées, mais les formats/poids différents restent séparés. " +
                        "Tape un produit pour voir son évolution.",
'''
if old_note in text:
    text = text.replace(old_note, new_note, 1)
stats.write_text(text, encoding="utf-8")

print(f"Normalisation produits Drive appliquée dans {vm}")
print(f"Normaliseur copié vers {dst}")
