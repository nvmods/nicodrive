package com.example.nicobudget.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.CompareArrows
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.nicobudget.data.model.BudgetViewModel
import com.example.nicobudget.data.model.CategoryExpenseTotal
import com.example.nicobudget.data.model.DriveMonthlyTotal
import com.example.nicobudget.data.model.DriveProductStat
import com.example.nicobudget.data.model.DriveTopProduct
import com.example.nicobudget.ui.components.SectionCard
import com.example.nicobudget.ui.components.eur
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs

/** Graphiques de tendance sans dépendance externe. */
@Composable
fun DriveTrendCharts(
    allMonthly: List<DriveMonthlyTotal>,
    selectedScope: String
) {
    val visible = remember(allMonthly, selectedScope) {
        allMonthly.filter {
            when {
                selectedScope == "ALL" -> true
                selectedScope.length == 4 -> it.month.startsWith(selectedScope)
                else -> it.month == selectedScope
            }
        }.sortedBy { it.month }
    }

    if (visible.size < 2) return

    val spend = visible.map { it.month to it.total }
    val baskets = visible.map {
        it.month to if (it.orderCount > 0) it.total / it.orderCount else 0.0
    }

    SectionCard(Icons.Default.BarChart, "Évolution dans le temps") {
        Text(
            "Dépenses mensuelles",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold
        )
        SimpleLineChart(
            points = spend,
            valueFormatter = { it.eur() },
            modifier = Modifier
                .fillMaxWidth()
                .height(150.dp)
        )

        Spacer(Modifier.height(14.dp))
        Text(
            "Panier moyen",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold
        )
        SimpleLineChart(
            points = baskets,
            valueFormatter = { it.eur() },
            modifier = Modifier
                .fillMaxWidth()
                .height(150.dp)
        )
    }
}

/**
 * Graphe de prix moyen par unité pour le produit sélectionné.
 * total / quantité est volontairement présenté comme une estimation : selon
 * le PDF, certaines quantités peuvent être pondérales ou correspondre à des lots.
 */
@Composable
fun ProductPriceEvolutionChart(stats: List<DriveProductStat>) {
    val points = remember(stats) {
        stats.filter { it.quantity > 0.0 }
            .map { it.month to (it.total / it.quantity) }
            .sortedBy { it.first }
    }
    if (points.size < 2) return

    Text(
        "Prix moyen / unité estimé",
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold
    )
    SimpleLineChart(
        points = points,
        valueFormatter = { it.eur() },
        modifier = Modifier
            .fillMaxWidth()
            .height(140.dp)
    )
    Text(
        "Calcul : dépense du produit ÷ quantité reconnue dans les bons.",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Spacer(Modifier.height(10.dp))
}

@Composable
private fun SimpleLineChart(
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
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                "Min ${valueFormatter(min)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                "Max ${valueFormatter(max)}",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold
            )
        }
        Spacer(Modifier.height(4.dp))
        Canvas(modifier = modifier) {
            val left = 8.dp.toPx()
            val right = size.width - 8.dp.toPx()
            val top = 8.dp.toPx()
            val bottom = size.height - 8.dp.toPx()
            val chartW = (right - left).coerceAtLeast(1f)
            val chartH = (bottom - top).coerceAtLeast(1f)

            for (i in 0..3) {
                val y = top + chartH * i / 3f
                drawLine(
                    color = grid,
                    start = Offset(left, y),
                    end = Offset(right, y),
                    strokeWidth = 1.dp.toPx()
                )
            }

            fun pointAt(index: Int): Offset {
                val x = if (points.size == 1) left else
                    left + chartW * index.toFloat() / (points.size - 1).toFloat()
                val normalized = ((points[index].second - min) / range).toFloat()
                val y = bottom - normalized * chartH
                return Offset(x, y)
            }

            for (i in 0 until points.lastIndex) {
                drawLine(
                    color = primary,
                    start = pointAt(i),
                    end = pointAt(i + 1),
                    strokeWidth = 3.dp.toPx(),
                    cap = StrokeCap.Round
                )
            }
            points.indices.forEach { i ->
                drawCircle(primary, radius = 3.5.dp.toPx(), center = pointAt(i))
            }
        }
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(points.first().first, style = MaterialTheme.typography.labelSmall)
            Text(points.last().first, style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
fun DriveMonthComparison(
    months: List<String>,
    allMonthly: List<DriveMonthlyTotal>,
    viewModel: BudgetViewModel
) {
    if (months.size < 2) return

    var open by remember { mutableStateOf(false) }

    SectionCard(Icons.Default.CompareArrows, "Comparer deux mois") {
        Text(
            "Compare dépenses, panier moyen, commandes, avantages, rayons et produits entre deux périodes.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(8.dp))
        OutlinedButton(
            onClick = { open = true },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Ouvrir le comparatif mensuel")
        }
    }

    if (!open) return

    var monthA by remember { mutableStateOf(months.getOrElse(1) { months.first() }) }
    var monthB by remember { mutableStateOf(months.first()) }
    var productsA by remember { mutableStateOf<List<DriveTopProduct>>(emptyList()) }
    var productsB by remember { mutableStateOf<List<DriveTopProduct>>(emptyList()) }
    var sectionsA by remember { mutableStateOf<List<CategoryExpenseTotal>>(emptyList()) }
    var sectionsB by remember { mutableStateOf<List<CategoryExpenseTotal>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }

    LaunchedEffect(monthA, monthB) {
        loading = true
        try {
            val data = withContext(Dispatchers.IO) {
                listOf(
                    viewModel.getDriveTopProducts(monthA, 10000),
                    viewModel.getDriveTopProducts(monthB, 10000)
                ) to listOf(
                    viewModel.getDriveSectionTotals(monthA),
                    viewModel.getDriveSectionTotals(monthB)
                )
            }
            productsA = data.first[0]
            productsB = data.first[1]
            sectionsA = data.second[0]
            sectionsB = data.second[1]
        } finally {
            loading = false
        }
    }

    val a = allMonthly.firstOrNull { it.month == monthA }
    val b = allMonthly.firstOrNull { it.month == monthB }

    AlertDialog(
        onDismissRequest = { open = false },
        title = { Text("Comparatif $monthA / $monthB") },
        text = {
            Column(
                Modifier
                    .heightIn(max = 620.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Text("Mois de référence", fontWeight = FontWeight.SemiBold)
                MonthChoiceRow(months, monthA) { monthA = it }
                Spacer(Modifier.height(8.dp))
                Text("Mois comparé", fontWeight = FontWeight.SemiBold)
                MonthChoiceRow(months, monthB) { monthB = it }
                Spacer(Modifier.height(12.dp))

                if (loading) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }

                if (a != null && b != null) {
                    ComparisonMetric("Dépenses", a.total, b.total, true)
                    ComparisonMetric(
                        "Panier moyen",
                        if (a.orderCount > 0) a.total / a.orderCount else 0.0,
                        if (b.orderCount > 0) b.total / b.orderCount else 0.0,
                        true
                    )
                    ComparisonMetric("Commandes", a.orderCount.toDouble(), b.orderCount.toDouble(), false)
                    ComparisonMetric(
                        "Avantages",
                        a.savings + a.ticketLeclerc,
                        b.savings + b.ticketLeclerc,
                        true
                    )

                    Spacer(Modifier.height(14.dp))
                    Text("Rayons qui évoluent le plus", fontWeight = FontWeight.Bold)
                    val mapA = sectionsA.associateBy { it.category }
                    val mapB = sectionsB.associateBy { it.category }
                    (mapA.keys + mapB.keys)
                        .map { category ->
                            val va = mapA[category]?.total ?: 0.0
                            val vb = mapB[category]?.total ?: 0.0
                            Triple(category, va, vb)
                        }
                        .sortedByDescending { abs(it.third - it.second) }
                        .take(8)
                        .forEach { (name, va, vb) ->
                            DeltaLine(name, va, vb, true)
                        }

                    Spacer(Modifier.height(14.dp))
                    Text("Produits dont la fréquence change le plus", fontWeight = FontWeight.Bold)
                    val prodA = productsA.associateBy { it.label }
                    val prodB = productsB.associateBy { it.label }
                    (prodA.keys + prodB.keys)
                        .map { label ->
                            val va = prodA[label]?.orders ?: 0
                            val vb = prodB[label]?.orders ?: 0
                            Triple(label, va, vb)
                        }
                        .sortedByDescending { abs(it.third - it.second) }
                        .take(10)
                        .forEach { (name, va, vb) ->
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 3.dp),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(name, modifier = Modifier.weight(1f), maxLines = 1)
                                Spacer(Modifier.width(8.dp))
                                Text("$va → $vb achats", fontWeight = FontWeight.SemiBold)
                            }
                        }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { open = false }) { Text("Fermer") }
        }
    )
}

@Composable
private fun MonthChoiceRow(
    months: List<String>,
    selected: String,
    onSelect: (String) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        months.chunked(4).forEach { rowMonths ->
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                rowMonths.forEach { month ->
                    FilterChip(
                        selected = selected == month,
                        onClick = { onSelect(month) },
                        label = { Text(month.substring(2)) }
                    )
                }
            }
        }
    }
}

@Composable
private fun ComparisonMetric(
    label: String,
    old: Double,
    new: Double,
    money: Boolean
) {
    val delta = new - old
    val percent = if (abs(old) > 0.000001) delta / old * 100.0 else null
    val oldText = if (money) old.eur() else "%.0f".format(old)
    val newText = if (money) new.eur() else "%.0f".format(new)

    Column(Modifier.padding(vertical = 5.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label)
            Text("$oldText → $newText", fontWeight = FontWeight.Bold)
        }
        Text(
            if (percent != null) "%+.1f %%".format(percent) else "Nouvelle valeur",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.secondary
        )
    }
}

@Composable
private fun DeltaLine(label: String, old: Double, new: Double, money: Boolean) {
    val left = if (money) old.eur() else old.toString()
    val right = if (money) new.eur() else new.toString()
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, modifier = Modifier.weight(1f), maxLines = 1)
        Spacer(Modifier.width(8.dp))
        Text("$left → $right", fontWeight = FontWeight.SemiBold)
    }
}
