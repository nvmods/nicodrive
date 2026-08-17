#!/usr/bin/env python3
from pathlib import Path
import sys

if len(sys.argv) != 2:
    raise SystemExit("Usage: patch_advanced_stats.py <project_root>")

root = Path(sys.argv[1])
target = root / "app/src/main/java/com/example/nicobudget/ui/DriveStatsScreen.kt"
if not target.exists():
    raise SystemExit(f"Fichier introuvable: {target}")

text = target.read_text(encoding="utf-8")

# Graphe de prix dans le dialogue d'évolution produit.
old = '''                    val maxTotal = visibleEvolution.maxOf { it.total }
                    Column {
                        visibleEvolution.forEach { e ->
'''
new = '''                    val maxTotal = visibleEvolution.maxOf { it.total }
                    Column {
                        ProductPriceEvolutionChart(visibleEvolution)
                        visibleEvolution.forEach { e ->
'''
if "ProductPriceEvolutionChart(visibleEvolution)" not in text:
    if old not in text:
        raise SystemExit("Point insertion évolution prix produit introuvable")
    text = text.replace(old, new, 1)

# Tendances globales/annuelles + comparatif de deux mois, juste avant les totaux annuels.
marker = "        // ---------------- Totaux annuels en mode global ----------------\n"
block = '''        // ---------------- Tendances et comparaison ----------------
        DriveTrendCharts(
            allMonthly = allMonthly,
            selectedScope = selectedScope
        )

        DriveMonthComparison(
            months = months,
            allMonthly = allMonthly,
            viewModel = viewModel
        )

'''
if "DriveMonthComparison(" not in text:
    if marker not in text:
        raise SystemExit("Point insertion tendances/comparatif introuvable")
    text = text.replace(marker, block + marker, 1)

target.write_text(text, encoding="utf-8")
print(f"Stats avancées Drive intégrées : {target}")
