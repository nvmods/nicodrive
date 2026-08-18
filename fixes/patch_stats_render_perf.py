#!/usr/bin/env python3
from pathlib import Path
import sys

if len(sys.argv) != 2:
    raise SystemExit("Usage: patch_stats_render_perf.py <project_root>")

root = Path(sys.argv[1])
stats = root / "app/src/main/java/com/example/nicobudget/ui/DriveStatsScreen.kt"
if not stats.exists():
    raise SystemExit(f"Fichier introuvable: {stats}")

text = stats.read_text(encoding="utf-8")

# ---------------------------------------------------------------------------
# 1. Une seule famille de stats est composée à la fois.
# Le gros Column historique construisait jusque-là toutes les sections, y compris
# celles situées plusieurs écrans plus bas.
# ---------------------------------------------------------------------------
state_anchor = '    var loading by remember { mutableStateOf(true) }\n'
state_new = '''    var loading by remember { mutableStateOf(true) }
    var statsView by remember { mutableStateOf("ESSENTIAL") }
'''
if "var statsView by remember" not in text:
    if state_anchor not in text:
        raise SystemExit("État loading DriveStatsScreen introuvable")
    text = text.replace(state_anchor, state_new, 1)

header_anchor = '        SectionHeader(Icons.Default.BarChart, "Stats Leclerc Drive")\n'
header_new = '''        SectionHeader(Icons.Default.BarChart, "Stats Leclerc Drive")

        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                FilterChip(
                    selected = statsView == "ESSENTIAL",
                    onClick = { statsView = "ESSENTIAL" },
                    label = { Text("Essentiel") }
                )
            }
            item {
                FilterChip(
                    selected = statsView == "FOOD",
                    onClick = { statsView = "FOOD" },
                    label = { Text("Alimentation") }
                )
            }
            item {
                FilterChip(
                    selected = statsView == "TRENDS",
                    onClick = { statsView = "TRENDS" },
                    label = { Text("Tendances") }
                )
            }
            item {
                FilterChip(
                    selected = statsView == "HISTORY",
                    onClick = { statsView = "HISTORY" },
                    label = { Text("Historique") }
                )
            }
        }
        Text(
            when (statsView) {
                "FOOD" -> "Habitudes alimentaires et familles de repas."
                "TRENDS" -> "Graphiques, comparaisons et analyses historiques avancées."
                "HISTORY" -> "Totaux annuels et détail des mois importés."
                else -> "Synthèse, produits les plus fréquents et répartition des dépenses."
            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
'''
if 'label = { Text("Essentiel") }' not in text:
    if header_anchor not in text:
        raise SystemExit("Header Stats Leclerc Drive introuvable")
    text = text.replace(header_anchor, header_new, 1)

# Helper : insère une condition autour d'un segment délimité par deux commentaires.
def wrap_segment(start_marker: str, end_marker: str, condition: str, tag: str):
    global text
    guard = f'// PERF-PANEL-{tag}'
    if guard in text:
        return
    start = text.find(start_marker)
    end = text.find(end_marker, start + len(start_marker)) if start >= 0 else -1
    if start < 0 or end < 0:
        raise SystemExit(f"Segment stats introuvable: {tag}")
    body_start = start + len(start_marker)
    body = text[body_start:end]
    wrapped = start_marker + f'        {guard}\n        if ({condition}) {{\n' + body + '        }\n\n'
    text = text[:start] + wrapped + text[end:]

# Tendances + comparateur + insights b103/b105 : uniquement dans l'onglet Tendances.
wrap_segment(
    '        // ---------------- Tendances et comparaison ----------------\n',
    '        // ---------------- Totaux annuels en mode global ----------------\n',
    'statsView == "TRENDS"',
    'TRENDS'
)

# Totaux annuels : dans Historique uniquement. Ils ne sont pas utiles pour le rendu
# initial et contiennent plusieurs barres de progression.
wrap_segment(
    '        // ---------------- Totaux annuels en mode global ----------------\n',
    '        // ---------------- Habitudes alimentaires ----------------\n',
    'statsView == "HISTORY"',
    'ANNUAL'
)

# Analyse des 5k+ lignes alimentaires : ne doit exister dans la composition que
# lorsque l'utilisateur ouvre explicitement Alimentation.
wrap_segment(
    '        // ---------------- Habitudes alimentaires ----------------\n',
    '        // ---------------- Top 10 produits ----------------\n',
    'statsView == "FOOD"',
    'FOOD'
)

# Top produits et rayons = vue Essentiel.
wrap_segment(
    '        // ---------------- Top 10 produits ----------------\n',
    '        // ---------------- Répartition par rayon ----------------\n',
    'statsView == "ESSENTIAL"',
    'TOP'
)
wrap_segment(
    '        // ---------------- Répartition par rayon ----------------\n',
    '        // ---------------- Historique mensuel complet ----------------\n',
    'statsView == "ESSENTIAL"',
    'SECTIONS'
)

# Historique mensuel : dernier bloc du gros Column. On le rend uniquement sur demande.
history_marker = '        // ---------------- Historique mensuel complet ----------------\n'
history_guard = '// PERF-PANEL-HISTORY'
if history_guard not in text:
    start = text.find(history_marker)
    tail_marker = '\n    }\n}\n\n@Composable\nprivate fun StatLine'
    tail = text.rfind(tail_marker)
    if start < 0 or tail < 0 or tail <= start:
        raise SystemExit("Bloc historique mensuel final introuvable")
    body_start = start + len(history_marker)
    body = text[body_start:tail]
    wrapped = history_marker + f'        {history_guard}\n        if (statsView == "HISTORY") {{\n' + body + '        }\n'
    text = text[:start] + wrapped + text[tail:]

# ---------------------------------------------------------------------------
# 2. La synthèse reste commune aux 4 vues, mais évite les tris complets inutiles
# lorsque l'utilisateur n'est pas sur Essentiel : le Top 10 n'est plus composé.
# ---------------------------------------------------------------------------

stats.write_text(text, encoding="utf-8")
print(f"Panneaux de stats performants appliqués : {stats}")
print("- Essentiel : synthèse + Top produits + rayons")
print("- Alimentation : classification alimentaire seulement")
print("- Tendances : graphes/comparaisons/insights seulement")
print("- Historique : totaux annuels + mois seulement")
