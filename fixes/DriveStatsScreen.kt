package com.example.nicobudget.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.Storefront
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.nicobudget.data.model.*
import com.example.nicobudget.ui.components.*
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * Tableau de bord Leclerc Drive.
 *
 * La portée est sélectionnable : historique global, année complète ou mois.
 * Les agrégats produits/rayons sont calculés directement en base sur la portée
 * choisie ; les totaux mensuels restent basés sur le montant réellement payé.
 */
@Composable
fun DriveStatsScreen(viewModel: BudgetViewModel) {
    val scope = rememberCoroutineScope()

    var months by remember { mutableStateOf(emptyList<String>()) }
    var allMonthly by remember { mutableStateOf(emptyList<DriveMonthlyTotal>()) }
    var selectedScope by remember { mutableStateOf("ALL") }
    var topProducts by remember { mutableStateOf(emptyList<DriveTopProduct>()) }
    var sections by remember { mutableStateOf(emptyList<CategoryExpenseTotal>()) }
    var evolutionOf by remember { mutableStateOf<String?>(null) }
    var evolution by remember { mutableStateOf(emptyList<DriveProductStat>()) }
    var byQuantity by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(true) }

    val years = remember(months) {
        months.mapNotNull { it.takeIf { value -> value.length >= 4 }?.take(4) }
            .distinct()
            .sortedDescending()
    }

    fun loadScope(key: String) {
        selectedScope = key
        loading = true
        scope.launch {
            try {
                when {
                    key == "ALL" -> {
                        topProducts = viewModel.getDriveTopProductsAll(10000)
                        sections = viewModel.getDriveSectionTotalsAll()
                    }
                    key.length == 4 -> {
                        topProducts = viewModel.getDriveTopProductsForYear(key, 10000)
                        sections = viewModel.getDriveSectionTotalsForYear(key)
                    }
                    else -> {
                        topProducts = viewModel.getDriveTopProducts(key, 10000)
                        sections = viewModel.getDriveSectionTotals(key)
                    }
                }
            } finally {
                loading = false
            }
        }
    }

    LaunchedEffect(Unit) {
        months = viewModel.getDriveMonths()
        allMonthly = viewModel.getDriveMonthlyTotals()
        try {
            topProducts = viewModel.getDriveTopProductsAll(10000)
            sections = viewModel.getDriveSectionTotalsAll()
        } finally {
            loading = false
        }
    }

    val periodMonthly = remember(allMonthly, selectedScope) {
        when {
            selectedScope == "ALL" -> allMonthly
            selectedScope.length == 4 -> allMonthly.filter { it.month.startsWith(selectedScope) }
            else -> allMonthly.filter { it.month == selectedScope }
        }
    }

    val periodLabel = when {
        selectedScope == "ALL" -> "Historique global"
        selectedScope.length == 4 -> "Année $selectedScope"
        else -> selectedScope
    }

    val activeYear = when {
        selectedScope == "ALL" -> null
        selectedScope.length >= 4 -> selectedScope.take(4)
        else -> null
    }

    val monthsForActiveYear = remember(months, activeYear) {
        if (activeYear == null) emptyList()
        else months.filter { it.startsWith(activeYear) }
    }

    // ---------------- Dialogue d'évolution d'un produit ----------------
    evolutionOf?.let { label ->
        val visibleEvolution = evolution.filter { e ->
            when {
                selectedScope == "ALL" -> true
                selectedScope.length == 4 -> e.month.startsWith(selectedScope)
                else -> e.month == selectedScope
            }
        }

        AlertDialog(
            onDismissRequest = { evolutionOf = null },
            title = {
                Column {
                    Text(label, style = MaterialTheme.typography.titleMedium)
                    Text(
                        periodLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            text = {
                if (visibleEvolution.isEmpty()) {
                    Text("Pas d'historique pour ce produit sur cette période.")
                } else {
                    val maxTotal = visibleEvolution.maxOf { it.total }
                    Column {
                        visibleEvolution.forEach { e ->
                            Column(Modifier.padding(vertical = 4.dp)) {
                                Row(
                                    Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(e.month)
                                    Text(
                                        "x%s — %s".format(
                                            formatQuantity(e.quantity),
                                            e.total.eur()
                                        ),
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }
                                LinearProgressIndicator(
                                    progress = {
                                        if (maxTotal > 0) (e.total / maxTotal).toFloat() else 0f
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                    color = MaterialTheme.colorScheme.secondary,
                                    trackColor = MaterialTheme.colorScheme.surfaceVariant
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { evolutionOf = null }) { Text("Fermer") }
            }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        SectionHeader(Icons.Default.BarChart, "Stats Leclerc Drive")

        if (months.isEmpty()) {
            EmptyHint("Aucune commande importée pour l'instant.")
            return@Column
        }

        // ---------------- Portée globale / année ----------------
        Text(
            "Période",
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold
        )
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                FilterChip(
                    selected = selectedScope == "ALL",
                    onClick = { loadScope("ALL") },
                    label = { Text("Global") }
                )
            }
            items(years) { year ->
                FilterChip(
                    selected = selectedScope == year,
                    onClick = { loadScope(year) },
                    label = { Text(year) }
                )
            }
        }

        // Une fois une année choisie, on garde le détail mensuel disponible.
        activeYear?.let { year ->
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item {
                    FilterChip(
                        selected = selectedScope == year,
                        onClick = { loadScope(year) },
                        label = { Text("Année entière") }
                    )
                }
                items(monthsForActiveYear) { month ->
                    FilterChip(
                        selected = selectedScope == month,
                        onClick = { loadScope(month) },
                        label = { Text(month.substringAfter('-')) }
                    )
                }
            }
        }

        if (loading) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }

        // ---------------- Synthèse de la période ----------------
        val orderCount = periodMonthly.sumOf { it.orderCount }
        val paidTotal = periodMonthly.sumOf { it.total }
        val savings = periodMonthly.sumOf { it.savings }
        val ticket = periodMonthly.sumOf { it.ticketLeclerc }
        val advantages = savings + ticket
        val lineTotal = periodMonthly.sumOf { it.lineTotal }
        val gap = paidTotal - lineTotal
        val averageMonth = if (periodMonthly.isNotEmpty()) paidTotal / periodMonthly.size else 0.0
        val averageOrder = if (orderCount > 0) paidTotal / orderCount else 0.0

        SectionCard(Icons.Default.BarChart, "Synthèse — $periodLabel") {
            StatLine("Commandes", orderCount.toString())
            StatLine("Total payé", paidTotal.eur(), strong = true)
            StatLine("Panier moyen", averageOrder.eur())
            if (periodMonthly.size > 1) {
                StatLine("Moyenne par mois", averageMonth.eur())
            }
            if (savings > 0.0) StatLine("Économies immédiates", savings.eur())
            if (ticket > 0.0) StatLine("Ticket E.Leclerc gagné", ticket.eur())
            if (advantages > 0.0) StatLine("Avantages totaux", advantages.eur(), strong = true)
            StatLine("Lignes produits reconnues", lineTotal.eur())
            if (abs(gap) >= 0.01) {
                StatLine("Écart total / lignes", gap.eur())
            }
        }

        // ---------------- Totaux annuels en mode global ----------------
        if (selectedScope == "ALL") {
            val yearly = periodMonthly
                .groupBy { it.month.take(4) }
                .mapValues { (_, values) ->
                    Triple(
                        values.sumOf { it.orderCount },
                        values.sumOf { it.total },
                        values.sumOf { it.savings + it.ticketLeclerc }
                    )
                }
                .toList()
                .sortedByDescending { it.first }
            val maxYearTotal = yearly.maxOfOrNull { it.second.second } ?: 0.0

            SectionCard(Icons.Default.BarChart, "Totaux par année") {
                yearly.forEach { (year, data) ->
                    val (count, total, adv) = data
                    Column(Modifier.padding(vertical = 5.dp)) {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column {
                                Text(year, fontWeight = FontWeight.SemiBold)
                                Text(
                                    "$count commande(s) · ${adv.eur()} d'avantages",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Text(total.eur(), fontWeight = FontWeight.Bold)
                        }
                        Spacer(Modifier.height(3.dp))
                        LinearProgressIndicator(
                            progress = {
                                if (maxYearTotal > 0) (total / maxYearTotal).toFloat() else 0f
                            },
                            modifier = Modifier.fillMaxWidth(),
                            color = MaterialTheme.colorScheme.primary,
                            trackColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                    }
                }
            }
        }

        // ---------------- Top 10 produits ----------------
        SectionCard(
            Icons.Default.EmojiEvents,
            "Top 10 produits — $periodLabel",
            trailing = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (byQuantity) "Quantité" else "Dépense",
                        style = MaterialTheme.typography.labelMedium
                    )
                    Spacer(Modifier.width(6.dp))
                    Switch(
                        checked = byQuantity,
                        onCheckedChange = { byQuantity = it }
                    )
                }
            }
        ) {
            if (topProducts.isEmpty()) {
                EmptyHint("Aucun produit sur cette période.")
            } else {
                val ranked = (
                    if (byQuantity) topProducts.sortedByDescending { it.quantity }
                    else topProducts.sortedByDescending { it.total }
                ).take(10)

                val maxValue = if (byQuantity)
                    ranked.maxOfOrNull { it.quantity } ?: 0.0
                else
                    ranked.maxOfOrNull { it.total } ?: 0.0

                Text(
                    "Classement calculé sur toutes les lignes reconnues de la période. " +
                        "Tape un produit pour voir son évolution.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(6.dp))

                ranked.forEachIndexed { index, p ->
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .clickable {
                                evolutionOf = p.label
                                scope.launch {
                                    evolution = viewModel.getDriveProductEvolution(p.label)
                                }
                            }
                            .padding(vertical = 5.dp)
                    ) {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                "${index + 1}. ${p.label}",
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1,
                                modifier = Modifier.weight(1f)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                if (byQuantity)
                                    "x${formatQuantity(p.quantity)} · ${p.total.eur()}"
                                else
                                    "${p.total.eur()} · x${formatQuantity(p.quantity)}",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                        Text(
                            "${p.orders} commande(s)",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(3.dp))
                        LinearProgressIndicator(
                            progress = {
                                val value = if (byQuantity) p.quantity else p.total
                                if (maxValue > 0) (value / maxValue).toFloat() else 0f
                            },
                            modifier = Modifier.fillMaxWidth(),
                            color = MaterialTheme.colorScheme.secondary,
                            trackColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                    }
                }
            }
        }

        // ---------------- Répartition par rayon ----------------
        SectionCard(Icons.Default.Storefront, "Répartition par rayon — $periodLabel") {
            if (sections.isEmpty()) {
                EmptyHint("Aucune donnée de rayon sur cette période.")
            } else {
                val parsedTotal = sections.sumOf { it.total }

                Text(
                    "Lignes ventilées : ${lineTotal.eur()} · Total payé : ${paidTotal.eur()}",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    "Les pourcentages portent sur les lignes produits reconnues.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (abs(gap) >= 0.01) {
                    Text(
                        "Écart total/lignes : ${gap.eur()} (remises globales, frais ou lignes non reconnues)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.secondary
                    )
                }
                Spacer(Modifier.height(6.dp))

                sections.forEach { section ->
                    val percent = if (parsedTotal > 0) section.total / parsedTotal * 100.0 else 0.0
                    Column(Modifier.padding(vertical = 5.dp)) {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                section.category,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "%s (%.1f %%)".format(section.total.eur(), percent),
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                        Spacer(Modifier.height(3.dp))
                        LinearProgressIndicator(
                            progress = {
                                if (parsedTotal > 0) (section.total / parsedTotal).toFloat() else 0f
                            },
                            modifier = Modifier.fillMaxWidth(),
                            color = MaterialTheme.colorScheme.tertiary,
                            trackColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                    }
                }
            }
        }

        // ---------------- Historique mensuel complet ----------------
        if (periodMonthly.isNotEmpty()) {
            val maxMonthTotal = periodMonthly.maxOfOrNull { it.total } ?: 0.0
            SectionCard(Icons.Default.BarChart, "Dépenses par mois — $periodLabel") {
                Text(
                    if (selectedScope == "ALL")
                        "Tous les mois disponibles dans l'historique importé."
                    else
                        "Détail mensuel de la période sélectionnée.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(6.dp))

                periodMonthly.forEach { month ->
                    val adv = month.savings + month.ticketLeclerc
                    Column(Modifier.padding(vertical = 5.dp)) {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column {
                                Text(month.month, fontWeight = FontWeight.SemiBold)
                                Text(
                                    "${month.orderCount} commande(s) · panier moyen ${
                                        if (month.orderCount > 0) (month.total / month.orderCount).eur() else 0.0.eur()
                                    }",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                if (adv > 0.0) {
                                    Text(
                                        "Avantages : ${adv.eur()}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.secondary
                                    )
                                }
                            }
                            Text(month.total.eur(), fontWeight = FontWeight.Bold)
                        }
                        Spacer(Modifier.height(3.dp))
                        LinearProgressIndicator(
                            progress = {
                                if (maxMonthTotal > 0) (month.total / maxMonthTotal).toFloat() else 0f
                            },
                            modifier = Modifier.fillMaxWidth(),
                            color = MaterialTheme.colorScheme.primary,
                            trackColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StatLine(label: String, value: String, strong: Boolean = false) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (strong) FontWeight.Bold else FontWeight.SemiBold
        )
    }
}

private fun formatQuantity(value: Double): String =
    if (value % 1.0 == 0.0) value.toInt().toString()
    else "%.3f".format(value).trimEnd('0').trimEnd(',').trimEnd('.')
