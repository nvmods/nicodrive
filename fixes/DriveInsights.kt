package com.example.nicobudget.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Storefront
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.nicobudget.data.model.BudgetViewModel
import com.example.nicobudget.data.model.DriveMonthlyTotal
import com.example.nicobudget.data.model.DriveProductMonthlyStat
import com.example.nicobudget.data.model.DriveSectionMonthlyStat
import com.example.nicobudget.ui.components.SectionCard
import com.example.nicobudget.ui.components.eur
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.min

/**
 * Dernière couche d'analyse historique Drive :
 * - évolution annuelle ;
 * - moyenne glissante 3/6/12 mois ;
 * - inflation personnelle à panier comparable ;
 * - saisonnalité des rayons ;
 * - détection de hausses de prix inhabituelles.
 *
 * Les calculs restent locaux et reposent uniquement sur les bons déjà importés.
 */
@Composable
fun DriveHistoricalInsights(
    allMonthly: List<DriveMonthlyTotal>,
    selectedScope: String,
    viewModel: BudgetViewModel
) {
    var productMonthly by remember { mutableStateOf(emptyList<DriveProductMonthlyStat>()) }
    var sectionMonthly by remember { mutableStateOf(emptyList<DriveSectionMonthlyStat>()) }
    var loading by remember { mutableStateOf(true) }
    var movingWindow by remember { mutableStateOf(3) }
    var seasonalSection by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        loading = true
        try {
            val data = withContext(Dispatchers.IO) {
                viewModel.getDriveProductMonthlyStatsAll() to
                    viewModel.getDriveSectionMonthlyStatsAll()
            }
            productMonthly = data.first
            sectionMonthly = data.second
        } finally {
            loading = false
        }
    }

    if (loading) {
        SectionCard(Icons.Default.BarChart, "Analyses historiques") {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            Spacer(Modifier.height(6.dp))
            Text(
                "Calcul des tendances sur l'historique Drive…",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        return
    }

    val scopedMonthly = remember(allMonthly, selectedScope) {
        allMonthly.filter {
            when {
                selectedScope == "ALL" -> true
                selectedScope.length == 4 -> it.month.startsWith(selectedScope)
                else -> it.month == selectedScope
            }
        }.sortedBy { it.month }
    }

    // ---------------------------------------------------------------------
    // Évolution annuelle
    // ---------------------------------------------------------------------
    if (selectedScope == "ALL") {
        val annual = remember(allMonthly) {
            allMonthly.groupBy { it.month.take(4) }
                .map { (year, rows) ->
                    val count = rows.sumOf { it.orderCount }
                    AnnualPoint(
                        year = year,
                        total = rows.sumOf { it.total },
                        averageBasket = if (count > 0) rows.sumOf { it.total } / count else 0.0,
                        orders = count
                    )
                }
                .sortedBy { it.year }
        }

        if (annual.size >= 2) {
            SectionCard(Icons.Default.BarChart, "Évolution annuelle") {
                Text(
                    "Dépense totale par année",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                InsightLineChart(
                    points = annual.map { it.year to it.total },
                    valueFormatter = { it.eur() },
                    modifier = Modifier.fillMaxWidth().height(145.dp)
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    "Panier moyen annuel",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                InsightLineChart(
                    points = annual.map { it.year to it.averageBasket },
                    valueFormatter = { it.eur() },
                    modifier = Modifier.fillMaxWidth().height(125.dp)
                )
                Spacer(Modifier.height(6.dp))
                annual.takeLast(3).reversed().forEach { row ->
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 2.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("${row.year} · ${row.orders} cmd")
                        Text(row.total.eur(), fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }

    // ---------------------------------------------------------------------
    // Moyennes glissantes
    // ---------------------------------------------------------------------
    if (scopedMonthly.size >= 3) {
        val possibleWindows = listOf(3, 6, 12).filter { it <= scopedMonthly.size }
        if (movingWindow !in possibleWindows) movingWindow = possibleWindows.first()
        val raw = scopedMonthly.map { it.total }
        val rolling = rollingAverage(raw, movingWindow)

        SectionCard(Icons.Default.BarChart, "Tendance lissée") {
            Text(
                "La moyenne glissante permet de voir la tendance réelle malgré les gros/petits mois.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(6.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(possibleWindows) { window ->
                    FilterChip(
                        selected = movingWindow == window,
                        onClick = { movingWindow = window },
                        label = { Text("${window} mois") }
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            DualLineChart(
                labels = scopedMonthly.map { it.month },
                first = raw.map { it },
                second = rolling,
                firstLabel = "Dépense mensuelle",
                secondLabel = "Moyenne ${movingWindow}m",
                valueFormatter = { it.eur() },
                modifier = Modifier.fillMaxWidth().height(165.dp)
            )
            rolling.lastOrNull()?.let { latest ->
                Spacer(Modifier.height(4.dp))
                Text(
                    "Tendance actuelle : ${latest.eur()} / mois sur ${movingWindow} mois",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }

    // ---------------------------------------------------------------------
    // Inflation du panier personnel à produits comparables, année sur année.
    // ---------------------------------------------------------------------
    val inflationPoints = remember(productMonthly, allMonthly, selectedScope) {
        comparableInflation(productMonthly, allMonthly)
            .filter {
                when {
                    selectedScope == "ALL" -> true
                    selectedScope.length == 4 -> it.month.startsWith(selectedScope)
                    else -> it.month == selectedScope
                }
            }
    }

    if (inflationPoints.isNotEmpty()) {
        SectionCard(Icons.Default.BarChart, "Inflation de ton panier comparable") {
            Text(
                "Compare chaque mois au même mois de l'année précédente uniquement sur les produits achetés dans les deux périodes. " +
                    "Les quantités communes servent de pondération : c'est un indice personnel, pas l'inflation officielle.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))
            val latest = inflationPoints.last()
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("${latest.month} vs N-1", fontWeight = FontWeight.SemiBold)
                Text(
                    "%+.1f %%".format(latest.percent),
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            Text(
                "${latest.comparableProducts} produits comparables",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (inflationPoints.size >= 2) {
                Spacer(Modifier.height(8.dp))
                InsightLineChart(
                    points = inflationPoints.map { it.month to it.percent },
                    valueFormatter = { "%+.1f %%".format(it) },
                    modifier = Modifier.fillMaxWidth().height(145.dp)
                )
            }
        }
    }

    // ---------------------------------------------------------------------
    // Saisonnalité par rayon : pertinente surtout sur plusieurs années.
    // ---------------------------------------------------------------------
    if (selectedScope == "ALL" && sectionMonthly.isNotEmpty()) {
        val topSections = remember(sectionMonthly) {
            sectionMonthly.groupBy { it.category }
                .mapValues { (_, rows) -> rows.sumOf { it.total } }
                .entries.sortedByDescending { it.value }
                .take(8)
                .map { it.key }
        }
        if (seasonalSection == null || seasonalSection !in topSections) {
            seasonalSection = topSections.firstOrNull()
        }

        seasonalSection?.let { section ->
            val seasonal = seasonalityForSection(section, sectionMonthly, allMonthly)
            if (seasonal.any { it.second > 0.0 }) {
                val peak = seasonal.maxByOrNull { it.second }
                val low = seasonal.filter { it.second > 0.0 }.minByOrNull { it.second }

                SectionCard(Icons.Default.Storefront, "Saisonnalité par rayon") {
                    Text(
                        "Dépense mensuelle moyenne pour un même rayon, regroupée par mois de l'année.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(6.dp))
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(topSections) { item ->
                            FilterChip(
                                selected = seasonalSection == item,
                                onClick = { seasonalSection = item },
                                label = { Text(item, maxLines = 1) }
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    InsightBarChart(
                        points = seasonal,
                        valueFormatter = { it.eur() },
                        modifier = Modifier.fillMaxWidth().height(160.dp)
                    )
                    if (peak != null) {
                        Text(
                            "Pic habituel : ${peak.first} (${peak.second.eur()} en moyenne)" +
                                if (low != null) " · creux : ${low.first} (${low.second.eur()})" else "",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        }
    }

    // ---------------------------------------------------------------------
    // Hausses de prix à surveiller.
    // ---------------------------------------------------------------------
    val targetMonth = remember(allMonthly, selectedScope) {
        when {
            selectedScope == "ALL" -> allMonthly.maxOfOrNull { it.month }
            selectedScope.length == 4 -> allMonthly
                .filter { it.month.startsWith(selectedScope) }
                .maxOfOrNull { it.month }
            else -> selectedScope
        }
    }
    val priceAlerts = remember(productMonthly, targetMonth) {
        targetMonth?.let { priceIncreaseAlerts(productMonthly, it) }.orEmpty()
    }

    if (targetMonth != null) {
        SectionCard(Icons.Default.BarChart, "Hausses de prix à surveiller — $targetMonth") {
            Text(
                "Prix moyen du mois comparé aux 6 observations précédentes du même produit. " +
                    "On exige au moins 3 mois de référence et une hausse de 10 %.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(6.dp))
            if (priceAlerts.isEmpty()) {
                Text("Aucune hausse significative détectée avec assez d'historique.")
            } else {
                priceAlerts.take(10).forEach { alert ->
                    Column(Modifier.padding(vertical = 5.dp)) {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                alert.label,
                                modifier = Modifier.weight(1f),
                                maxLines = 1
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "+%.1f %%".format(alert.percent),
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.secondary
                            )
                        }
                        Text(
                            "${alert.baseline.eur()} → ${alert.current.eur()} / unité estimée · ${alert.referenceMonths} mois de référence",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Text(
                    "À interpréter avec prudence pour les produits au poids, lots ou changements de conditionnement.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

private data class AnnualPoint(
    val year: String,
    val total: Double,
    val averageBasket: Double,
    val orders: Int
)

private data class InflationPoint(
    val month: String,
    val percent: Double,
    val comparableProducts: Int
)

private data class PriceAlert(
    val label: String,
    val baseline: Double,
    val current: Double,
    val percent: Double,
    val referenceMonths: Int
)

private fun rollingAverage(values: List<Double>, window: Int): List<Double?> =
    values.indices.map { index ->
        if (index + 1 < window) null
        else values.subList(index + 1 - window, index + 1).average()
    }

private fun comparableInflation(
    products: List<DriveProductMonthlyStat>,
    monthly: List<DriveMonthlyTotal>
): List<InflationPoint> {
    val byMonth = products.groupBy { it.month }
    val available = monthly.map { it.month }.toSet()
    val result = mutableListOf<InflationPoint>()

    available.sorted().forEach { currentMonth ->
        val year = currentMonth.take(4).toIntOrNull() ?: return@forEach
        val monthPart = currentMonth.substringAfter('-', "")
        val previousMonth = "%04d-%s".format(year - 1, monthPart)
        if (previousMonth !in available) return@forEach

        val previous = byMonth[previousMonth].orEmpty()
            .filter { it.quantity > 0.0 && it.total > 0.0 }
            .associateBy { it.label }
        val current = byMonth[currentMonth].orEmpty()
            .filter { it.quantity > 0.0 && it.total > 0.0 }
            .associateBy { it.label }
        val common = previous.keys.intersect(current.keys)

        var previousCost = 0.0
        var currentCost = 0.0
        var count = 0
        common.forEach { label ->
            val old = previous.getValue(label)
            val now = current.getValue(label)
            val oldUnit = old.total / old.quantity
            val newUnit = now.total / now.quantity
            val weight = min(old.quantity, now.quantity)
            if (oldUnit > 0.0 && newUnit > 0.0 && weight > 0.0) {
                previousCost += oldUnit * weight
                currentCost += newUnit * weight
                count++
            }
        }

        if (count >= 3 && previousCost > 0.0) {
            result += InflationPoint(
                month = currentMonth,
                percent = (currentCost / previousCost - 1.0) * 100.0,
                comparableProducts = count
            )
        }
    }
    return result
}

private fun seasonalityForSection(
    section: String,
    rows: List<DriveSectionMonthlyStat>,
    monthly: List<DriveMonthlyTotal>
): List<Pair<String, Double>> {
    val labels = listOf("Jan", "Fév", "Mar", "Avr", "Mai", "Juin", "Juil", "Août", "Sep", "Oct", "Nov", "Déc")
    val monthsAvailable = monthly.map { it.month }.toSet()
    val sectionByMonth = rows.filter { it.category == section }.associateBy { it.month }

    return (1..12).map { monthNumber ->
        val suffix = "-%02d".format(monthNumber)
        val periods = monthsAvailable.filter { it.endsWith(suffix) }
        val average = if (periods.isEmpty()) 0.0
        else periods.sumOf { sectionByMonth[it]?.total ?: 0.0 } / periods.size
        labels[monthNumber - 1] to average
    }
}

private fun priceIncreaseAlerts(
    rows: List<DriveProductMonthlyStat>,
    targetMonth: String
): List<PriceAlert> {
    val byLabel = rows.groupBy { it.label }
    val alerts = mutableListOf<PriceAlert>()

    byLabel.forEach { (label, history) ->
        val current = history.firstOrNull { it.month == targetMonth } ?: return@forEach
        if (current.quantity <= 0.0 || current.total <= 0.0) return@forEach

        val previous = history
            .filter { it.month < targetMonth && it.quantity > 0.0 && it.total > 0.0 }
            .sortedByDescending { it.month }
            .take(6)
        if (previous.size < 3) return@forEach

        val baselineQty = previous.sumOf { it.quantity }
        val baselineTotal = previous.sumOf { it.total }
        if (baselineQty <= 0.0 || baselineTotal <= 0.0) return@forEach

        val baseline = baselineTotal / baselineQty
        val currentPrice = current.total / current.quantity
        if (baseline <= 0.0) return@forEach
        val percent = (currentPrice / baseline - 1.0) * 100.0
        if (percent >= 10.0) {
            alerts += PriceAlert(label, baseline, currentPrice, percent, previous.size)
        }
    }

    return alerts.sortedByDescending { it.percent }
}

@Composable
private fun InsightLineChart(
    points: List<Pair<String, Double>>,
    valueFormatter: (Double) -> String,
    modifier: Modifier = Modifier
) {
    if (points.isEmpty()) return
    val primary = MaterialTheme.colorScheme.primary
    val grid = MaterialTheme.colorScheme.outlineVariant
    val max = points.maxOf { it.second }
    val min = points.minOf { it.second }
    val range = (max - min).takeIf { abs(it) > 0.000001 } ?: 1.0

    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Min ${valueFormatter(min)}", style = MaterialTheme.typography.labelSmall)
            Text("Max ${valueFormatter(max)}", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold)
        }
        Canvas(modifier = modifier) {
            val left = 8.dp.toPx()
            val right = size.width - 8.dp.toPx()
            val top = 8.dp.toPx()
            val bottom = size.height - 8.dp.toPx()
            val width = (right - left).coerceAtLeast(1f)
            val height = (bottom - top).coerceAtLeast(1f)
            repeat(4) { i ->
                val y = top + height * i / 3f
                drawLine(grid, Offset(left, y), Offset(right, y), 1.dp.toPx())
            }
            fun pos(i: Int): Offset {
                val x = if (points.size == 1) left else left + width * i / points.lastIndex.toFloat()
                val norm = ((points[i].second - min) / range).toFloat()
                return Offset(x, bottom - norm * height)
            }
            for (i in 0 until points.lastIndex) {
                drawLine(primary, pos(i), pos(i + 1), 3.dp.toPx(), cap = StrokeCap.Round)
            }
            points.indices.forEach { drawCircle(primary, 3.3.dp.toPx(), pos(it)) }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(points.first().first, style = MaterialTheme.typography.labelSmall)
            Text(points.last().first, style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun DualLineChart(
    labels: List<String>,
    first: List<Double?>,
    second: List<Double?>,
    firstLabel: String,
    secondLabel: String,
    valueFormatter: (Double) -> String,
    modifier: Modifier = Modifier
) {
    if (labels.isEmpty()) return
    val primary = MaterialTheme.colorScheme.primary
    val secondary = MaterialTheme.colorScheme.secondary
    val grid = MaterialTheme.colorScheme.outlineVariant
    val values = (first + second).filterNotNull()
    if (values.isEmpty()) return
    val max = values.maxOrNull() ?: 0.0
    val min = values.minOrNull() ?: 0.0
    val range = (max - min).takeIf { abs(it) > 0.000001 } ?: 1.0

    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(firstLabel, color = primary, style = MaterialTheme.typography.labelSmall)
            Text(secondLabel, color = secondary, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold)
        }
        Text(
            "${valueFormatter(min)} → ${valueFormatter(max)}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Canvas(modifier = modifier) {
            val left = 8.dp.toPx()
            val right = size.width - 8.dp.toPx()
            val top = 8.dp.toPx()
            val bottom = size.height - 8.dp.toPx()
            val width = (right - left).coerceAtLeast(1f)
            val height = (bottom - top).coerceAtLeast(1f)
            repeat(4) { i ->
                val y = top + height * i / 3f
                drawLine(grid, Offset(left, y), Offset(right, y), 1.dp.toPx())
            }
            fun pos(i: Int, value: Double): Offset {
                val x = if (labels.size == 1) left else left + width * i / labels.lastIndex.toFloat()
                val norm = ((value - min) / range).toFloat()
                return Offset(x, bottom - norm * height)
            }
            fun drawSeries(series: List<Double?>, color: Color, stroke: Float) {
                var previousIndex: Int? = null
                var previousValue: Double? = null
                series.forEachIndexed { index, value ->
                    if (value == null) {
                        previousIndex = null
                        previousValue = null
                    } else {
                        if (previousIndex != null && previousValue != null) {
                            drawLine(
                                color,
                                pos(previousIndex!!, previousValue!!),
                                pos(index, value),
                                stroke,
                                cap = StrokeCap.Round
                            )
                        }
                        drawCircle(color, 2.8.dp.toPx(), pos(index, value))
                        previousIndex = index
                        previousValue = value
                    }
                }
            }
            drawSeries(first, primary, 2.dp.toPx())
            drawSeries(second, secondary, 3.5.dp.toPx())
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(labels.first(), style = MaterialTheme.typography.labelSmall)
            Text(labels.last(), style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun InsightBarChart(
    points: List<Pair<String, Double>>,
    valueFormatter: (Double) -> String,
    modifier: Modifier = Modifier
) {
    val primary = MaterialTheme.colorScheme.primary
    val grid = MaterialTheme.colorScheme.outlineVariant
    val max = points.maxOfOrNull { it.second } ?: 0.0
    Column {
        Text(
            "Max ${valueFormatter(max)}",
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold
        )
        Canvas(modifier = modifier) {
            val left = 4.dp.toPx()
            val right = size.width - 4.dp.toPx()
            val top = 8.dp.toPx()
            val bottom = size.height - 8.dp.toPx()
            val width = (right - left).coerceAtLeast(1f)
            val height = (bottom - top).coerceAtLeast(1f)
            repeat(4) { i ->
                val y = top + height * i / 3f
                drawLine(grid, Offset(left, y), Offset(right, y), 1.dp.toPx())
            }
            val cell = width / points.size.coerceAtLeast(1)
            val barWidth = cell * 0.58f
            points.forEachIndexed { index, (_, value) ->
                val ratio = if (max > 0.0) (value / max).toFloat() else 0f
                val x = left + cell * index + (cell - barWidth) / 2f
                val y = bottom - height * ratio
                drawLine(
                    primary,
                    Offset(x + barWidth / 2f, bottom),
                    Offset(x + barWidth / 2f, y),
                    barWidth,
                    cap = StrokeCap.Round
                )
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Jan", style = MaterialTheme.typography.labelSmall)
            Text("Juin", style = MaterialTheme.typography.labelSmall)
            Text("Déc", style = MaterialTheme.typography.labelSmall)
        }
    }
}
