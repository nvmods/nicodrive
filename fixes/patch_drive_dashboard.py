#!/usr/bin/env python3
from pathlib import Path
import sys

if len(sys.argv) != 2:
    raise SystemExit("Usage: patch_drive_dashboard.py <project_root>")

root = Path(sys.argv[1])
target = root / "app/src/main/java/com/example/nicobudget/data/model/BudgetViewModel.kt"
if not target.exists():
    raise SystemExit(f"Fichier introuvable: {target}")

text = target.read_text(encoding="utf-8")

anchor = '''    suspend fun getDriveTopProducts(month: String, limit: Int = 15): List<DriveTopProduct> =
        driveOrderDao.getTopProducts(month, limit)

'''
insert = '''    suspend fun getDriveTopProducts(month: String, limit: Int = 15): List<DriveTopProduct> =
        driveOrderDao.getTopProducts(month, limit)

    suspend fun getDriveTopProductsForYear(year: String, limit: Int = 15): List<DriveTopProduct> =
        driveOrderDao.getTopProductsForYear(year, limit)

    suspend fun getDriveTopProductsAll(limit: Int = 15): List<DriveTopProduct> =
        driveOrderDao.getTopProductsAll(limit)

    suspend fun getDriveSectionTotalsForYear(year: String): List<CategoryExpenseTotal> =
        driveOrderDao.getSectionTotalsForYear(year)

    suspend fun getDriveSectionTotalsAll(): List<CategoryExpenseTotal> =
        driveOrderDao.getSectionTotalsAll()

'''

if "getDriveTopProductsForYear" not in text:
    if anchor not in text:
        raise SystemExit("Point insertion méthodes stats Drive introuvable")
    text = text.replace(anchor, insert, 1)

target.write_text(text, encoding="utf-8")
print(f"ViewModel stats Drive étendu : {target}")
