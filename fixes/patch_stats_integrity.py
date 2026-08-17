#!/usr/bin/env python3
from pathlib import Path
import sys

if len(sys.argv) != 2:
    raise SystemExit("Usage: patch_stats_integrity.py <project_root>")

root = Path(sys.argv[1])
stats = root / "app/src/main/java/com/example/nicobudget/ui/DriveStatsScreen.kt"
advanced = root / "app/src/main/java/com/example/nicobudget/ui/DriveAdvancedStats.kt"
insights = root / "app/src/main/java/com/example/nicobudget/ui/DriveInsights.kt"

for target in (stats, advanced, insights):
    if not target.exists():
        raise SystemExit(f"Fichier introuvable: {target}")

# ---------------------------------------------------------------------------
# DriveStatsScreen : distinguer données réelles à date et périodes complètes.
# ---------------------------------------------------------------------------
text = stats.read_text(encoding="utf-8")
if "import java.time.YearMonth" not in text:
    text = text.replace("import kotlin.math.abs\n", "import kotlin.math.abs\nimport java.time.YearMonth\n", 1)

anchor = "    val scope = rememberCoroutineScope()\n"
insert = '''    val scope = rememberCoroutineScope()
    val currentMonth = remember { YearMonth.now().toString() }
    val currentYear = remember(currentMonth) { currentMonth.take(4) }
'''
if "val currentMonth = remember { YearMonth.now().toString() }" not in text:
    if anchor not in text:
        raise SystemExit("Point insertion currentMonth DriveStatsScreen introuvable")
    text = text.replace(anchor, insert, 1)

old = '''    val periodLabel = when {
        selectedScope == "ALL" -> "Historique global"
        selectedScope.length == 4 -> "Année $selectedScope"
        else -> selectedScope
    }
'''
new = '''    val periodLabel = when {
        selectedScope == "ALL" -> "Historique global"
        selectedScope == currentYear -> "Année $selectedScope (en cours)"
        selectedScope.length == 4 -> "Année $selectedScope"
        selectedScope == currentMonth -> "$selectedScope (en cours)"
        else -> selectedScope
    }
'''
if old in text:
    text = text.replace(old, new, 1)

old = '''        val gap = paidTotal - lineTotal
        val averageMonth = if (periodMonthly.isNotEmpty()) paidTotal / periodMonthly.size else 0.0
        val averageOrder = if (orderCount > 0) paidTotal / orderCount else 0.0

        SectionCard(Icons.Default.BarChart, "Synthèse — $periodLabel") {
            StatLine("Commandes", orderCount.toString())
            StatLine("Total payé", paidTotal.eur(), strong = true)
            StatLine("Panier moyen", averageOrder.eur())
            if (periodMonthly.size > 1) {
                StatLine("Moyenne par mois", averageMonth.eur())
            }
'''
new = '''        val gap = paidTotal - lineTotal
        // Le mois courant est réel mais incomplet : il ne doit pas tirer vers le bas
        // une moyenne mensuelle censée représenter un mois entier.
        val completedPeriodMonthly = periodMonthly.filter { it.month < currentMonth }
        val averageMonth = if (completedPeriodMonthly.isNotEmpty())
            completedPeriodMonthly.sumOf { it.total } / completedPeriodMonthly.size else 0.0
        val averageOrder = if (orderCount > 0) paidTotal / orderCount else 0.0
        val periodIsOpen = selectedScope == currentYear || selectedScope == currentMonth

        SectionCard(Icons.Default.BarChart, "Synthèse — $periodLabel") {
            StatLine("Commandes", orderCount.toString())
            StatLine(if (periodIsOpen) "Total payé à date" else "Total payé", paidTotal.eur(), strong = true)
            StatLine("Panier moyen", averageOrder.eur())
            if (completedPeriodMonthly.size > 1) {
                StatLine("Moyenne par mois complet", averageMonth.eur())
            }
'''
if old not in text:
    raise SystemExit("Bloc synthèse DriveStatsScreen introuvable")
text = text.replace(old, new, 1)

text = text.replace(
    '            SectionCard(Icons.Default.BarChart, "Totaux par année") {\n',
    '''            SectionCard(Icons.Default.BarChart, "Totaux enregistrés par année") {
                Text(
                    "Les montants ci-dessous sont les montants réellement enregistrés. " +
                        "L'année en cours est partielle et ne doit pas être comparée directement à une année terminée.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(6.dp))
''',
    1
)
text = text.replace(
    '                                Text(year, fontWeight = FontWeight.SemiBold)\n',
    '                                Text(if (year == currentYear) "$year (en cours)" else year, fontWeight = FontWeight.SemiBold)\n',
    1
)

stats.write_text(text, encoding="utf-8")

# ---------------------------------------------------------------------------
# DriveAdvancedStats : exclure le mois courant des tendances/comparaisons.
# ---------------------------------------------------------------------------
text = advanced.read_text(encoding="utf-8")
if "import java.time.YearMonth" not in text:
    text = text.replace("import kotlin.math.abs\n", "import kotlin.math.abs\nimport java.time.YearMonth\n", 1)

old = '''    val visible = remember(allMonthly, selectedScope) {
        allMonthly.filter {
            when {
                selectedScope == "ALL" -> true
                selectedScope.length == 4 -> it.month.startsWith(selectedScope)
                else -> it.month == selectedScope
            }
        }.sortedBy { it.month }
    }
'''
new = '''    val currentMonth = remember { YearMonth.now().toString() }
    val visible = remember(allMonthly, selectedScope, currentMonth) {
        allMonthly.filter {
            it.month < currentMonth && when {
                selectedScope == "ALL" -> true
                selectedScope.length == 4 -> it.month.startsWith(selectedScope)
                else -> it.month == selectedScope
            }
        }.sortedBy { it.month }
    }
'''
if old not in text:
    raise SystemExit("Bloc visible DriveTrendCharts introuvable")
text = text.replace(old, new, 1)

# Les graphes de dépenses/panier sont des grandeurs absolues : base zéro pour
# éviter d'exagérer visuellement de petites variations.
text = text.replace(
    '''        SimpleLineChart(
            points = spend,
            valueFormatter = { it.eur() },
            modifier = Modifier
''',
    '''        SimpleLineChart(
            points = spend,
            valueFormatter = { it.eur() },
            zeroBaseline = true,
            modifier = Modifier
''', 1)
text = text.replace(
    '''        SimpleLineChart(
            points = baskets,
            valueFormatter = { it.eur() },
            modifier = Modifier
''',
    '''        SimpleLineChart(
            points = baskets,
            valueFormatter = { it.eur() },
            zeroBaseline = true,
            modifier = Modifier
''', 1)

old = '''private fun SimpleLineChart(
    points: List<Pair<String, Double>>,
    valueFormatter: (Double) -> String,
    modifier: Modifier = Modifier
) {
'''
new = '''private fun SimpleLineChart(
    points: List<Pair<String, Double>>,
    valueFormatter: (Double) -> String,
    modifier: Modifier = Modifier,
    zeroBaseline: Boolean = false
) {
'''
if old not in text:
    raise SystemExit("Signature SimpleLineChart introuvable")
text = text.replace(old, new, 1)

old = '''    val max = points.maxOf { it.second }
    val min = points.minOf { it.second }
    val range = (max - min).takeIf { abs(it) > 0.000001 } ?: 1.0
'''
new = '''    val max = points.maxOf { it.second }
    val rawMin = points.minOf { it.second }
    val min = if (zeroBaseline && rawMin >= 0.0) 0.0 else rawMin
    val range = (max - min).takeIf { abs(it) > 0.000001 } ?: 1.0
'''
if old not in text:
    raise SystemExit("Échelle SimpleLineChart introuvable")
text = text.replace(old, new, 1)

old = '''    if (months.size < 2) return

    var open by remember { mutableStateOf(false) }
'''
new = '''    val currentMonth = remember { YearMonth.now().toString() }
    // Comparer un mois partiel à un mois complet donne des écarts artificiels.
    val comparableMonths = remember(months, currentMonth) {
        months.filter { it < currentMonth }
    }
    if (comparableMonths.size < 2) return

    var open by remember { mutableStateOf(false) }
'''
if old not in text:
    raise SystemExit("Début DriveMonthComparison introuvable")
text = text.replace(old, new, 1)

text = text.replace(
    '            "Compare dépenses, panier moyen, commandes, avantages, rayons et produits entre deux périodes.",\n',
    '            "Compare deux mois terminés : le mois en cours est volontairement exclu pour éviter un faux écart.",\n',
    1
)
text = text.replace(
    '    var monthA by remember { mutableStateOf(months.getOrElse(1) { months.first() }) }\n    var monthB by remember { mutableStateOf(months.first()) }\n',
    '    var monthA by remember { mutableStateOf(comparableMonths.getOrElse(1) { comparableMonths.first() }) }\n    var monthB by remember { mutableStateOf(comparableMonths.first()) }\n',
    1
)
text = text.replace('MonthChoiceRow(months, monthA)', 'MonthChoiceRow(comparableMonths, monthA)', 1)
text = text.replace('MonthChoiceRow(months, monthB)', 'MonthChoiceRow(comparableMonths, monthB)', 1)
advanced.write_text(text, encoding="utf-8")

# ---------------------------------------------------------------------------
# DriveInsights : comparaison annuelle à période égale, mois courant exclu des
# moyennes glissantes, inflation et saisonnalité.
# ---------------------------------------------------------------------------
text = insights.read_text(encoding="utf-8")
if "import java.time.LocalDate" not in text:
    text = text.replace("import kotlin.math.min\n", "import kotlin.math.min\nimport java.time.LocalDate\nimport java.time.YearMonth\n", 1)

state_anchor = '    var seasonalSection by remember { mutableStateOf<String?>(null) }\n'
state_insert = '''    var seasonalSection by remember { mutableStateOf<String?>(null) }
    val today = remember { LocalDate.now() }
    val currentMonth = remember(today) { YearMonth.from(today).toString() }
    val completeMonthly = remember(allMonthly, currentMonth) {
        allMonthly.filter { it.month < currentMonth }
    }
'''
if "val completeMonthly = remember(allMonthly, currentMonth)" not in text:
    if state_anchor not in text:
        raise SystemExit("Point insertion dates DriveInsights introuvable")
    text = text.replace(state_anchor, state_insert, 1)

old = '''    val scopedMonthly = remember(allMonthly, selectedScope) {
        allMonthly.filter {
            when {
                selectedScope == "ALL" -> true
                selectedScope.length == 4 -> it.month.startsWith(selectedScope)
                else -> it.month == selectedScope
            }
        }.sortedBy { it.month }
    }
'''
new = '''    val scopedMonthly = remember(completeMonthly, selectedScope) {
        completeMonthly.filter {
            when {
                selectedScope == "ALL" -> true
                selectedScope.length == 4 -> it.month.startsWith(selectedScope)
                else -> it.month == selectedScope
            }
        }.sortedBy { it.month }
    }
'''
if old not in text:
    raise SystemExit("scopedMonthly DriveInsights introuvable")
text = text.replace(old, new, 1)

start = text.find("    // ---------------------------------------------------------------------\n    // Évolution annuelle\n")
end = text.find("    // ---------------------------------------------------------------------\n    // Moyennes glissantes\n", start)
if start < 0 or end < 0:
    raise SystemExit("Bloc évolution annuelle introuvable")
annual_block = '''    // ---------------------------------------------------------------------
    // Évolution annuelle COMPARABLE : mêmes mois terminés pour chaque année.
    // Une année en cours ne doit jamais être opposée directement à 12 mois clos.
    // ---------------------------------------------------------------------
    if (selectedScope == "ALL") {
        val byYear = remember(completeMonthly) {
            completeMonthly.groupBy { it.month.take(4) }
        }
        val commonMonths = remember(byYear) {
            val sets = byYear.values.map { rows ->
                rows.mapNotNull { it.month.substringAfter('-').toIntOrNull() }.toSet()
            }
            if (sets.isEmpty()) emptySet()
            else sets.drop(1).fold(sets.first()) { acc, set -> acc.intersect(set) }
        }
        val annual = remember(byYear, commonMonths) {
            byYear.map { (year, rows) ->
                val comparableRows = rows.filter {
                    it.month.substringAfter('-').toIntOrNull() in commonMonths
                }
                val count = comparableRows.sumOf { it.orderCount }
                AnnualPoint(
                    year = year,
                    total = comparableRows.sumOf { it.total },
                    averageBasket = if (count > 0) comparableRows.sumOf { it.total } / count else 0.0,
                    orders = count
                )
            }.filter { it.orders > 0 }.sortedBy { it.year }
        }

        if (annual.size >= 2 && commonMonths.isNotEmpty()) {
            val monthNames = listOf("jan", "fév", "mar", "avr", "mai", "juin", "juil", "août", "sep", "oct", "nov", "déc")
            val sortedMonths = commonMonths.sorted()
            val periodText = if (sortedMonths.size == 1) monthNames[sortedMonths.first() - 1]
                else "${monthNames[sortedMonths.first() - 1]}–${monthNames[sortedMonths.last() - 1]}"

            SectionCard(Icons.Default.BarChart, "Évolution annuelle comparable") {
                Text(
                    "Comparaison sur les mêmes mois terminés ($periodText). Le mois en cours est exclu.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "Dépense cumulée comparable",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                InsightLineChart(
                    points = annual.map { it.year to it.total },
                    valueFormatter = { it.eur() },
                    zeroBaseline = true,
                    modifier = Modifier.fillMaxWidth().height(145.dp)
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    "Panier moyen sur la même période",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                InsightLineChart(
                    points = annual.map { it.year to it.averageBasket },
                    valueFormatter = { it.eur() },
                    zeroBaseline = true,
                    modifier = Modifier.fillMaxWidth().height(125.dp)
                )
                Spacer(Modifier.height(6.dp))
                annual.takeLast(3).reversed().forEach { row ->
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 2.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("${row.year} · ${row.orders} cmd ($periodText)")
                        Text(row.total.eur(), fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }

'''
text = text[:start] + annual_block + text[end:]

text = text.replace(
    '        comparableInflation(productMonthly, allMonthly)\n',
    '        comparableInflation(productMonthly, completeMonthly)\n',
    1
)
text = text.replace(
    '    if (selectedScope == "ALL" && sectionMonthly.isNotEmpty()) {\n',
    '    if (selectedScope == "ALL" && sectionMonthly.isNotEmpty() && completeMonthly.isNotEmpty()) {\n',
    1
)
text = text.replace(
    '            val seasonal = seasonalityForSection(section, sectionMonthly, allMonthly)\n',
    '            val seasonal = seasonalityForSection(section, sectionMonthly, completeMonthly)\n',
    1
)

# Signaler explicitement que l'alerte de prix du mois courant est provisoire.
needle = '''        SectionCard(Icons.Default.BarChart, "Hausses de prix à surveiller — $targetMonth") {
            Text(
'''
replacement = '''        SectionCard(Icons.Default.BarChart, "Hausses de prix à surveiller — $targetMonth") {
            if (targetMonth == currentMonth) {
                Text(
                    "Mois en cours : ces alertes sont provisoires et peuvent évoluer avec les prochains achats.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.secondary
                )
                Spacer(Modifier.height(4.dp))
            }
            Text(
'''
if needle not in text:
    raise SystemExit("Bloc alertes prix introuvable")
text = text.replace(needle, replacement, 1)

# Base zéro optionnelle pour les valeurs absolues ; l'inflation conserve son
# échelle naturelle car elle peut être négative.
old = '''private fun InsightLineChart(
    points: List<Pair<String, Double>>,
    valueFormatter: (Double) -> String,
    modifier: Modifier = Modifier
) {
'''
new = '''private fun InsightLineChart(
    points: List<Pair<String, Double>>,
    valueFormatter: (Double) -> String,
    modifier: Modifier = Modifier,
    zeroBaseline: Boolean = false
) {
'''
if old not in text:
    raise SystemExit("Signature InsightLineChart introuvable")
text = text.replace(old, new, 1)
old = '''    val max = points.maxOf { it.second }
    val min = points.minOf { it.second }
    val range = (max - min).takeIf { abs(it) > 0.000001 } ?: 1.0
'''
new = '''    val max = points.maxOf { it.second }
    val rawMin = points.minOf { it.second }
    val min = if (zeroBaseline && rawMin >= 0.0) 0.0 else rawMin
    val range = (max - min).takeIf { abs(it) > 0.000001 } ?: 1.0
'''
# Il y a une occurrence dans InsightLineChart ; remplacement limité à une.
if old not in text:
    raise SystemExit("Échelle InsightLineChart introuvable")
text = text.replace(old, new, 1)

# DualLineChart ne contient que des dépenses mensuelles : base zéro.
old = '''    val max = values.maxOrNull() ?: 0.0
    val min = values.minOrNull() ?: 0.0
    val range = (max - min).takeIf { abs(it) > 0.000001 } ?: 1.0
'''
new = '''    val max = values.maxOrNull() ?: 0.0
    val rawMin = values.minOrNull() ?: 0.0
    val min = if (rawMin >= 0.0) 0.0 else rawMin
    val range = (max - min).takeIf { abs(it) > 0.000001 } ?: 1.0
'''
if old not in text:
    raise SystemExit("Échelle DualLineChart introuvable")
text = text.replace(old, new, 1)

insights.write_text(text, encoding="utf-8")
print("Audit statistiques appliqué : périodes complètes, comparaison annuelle équitable et graphes à base zéro.")
