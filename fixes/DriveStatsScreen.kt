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
 * Statistiques Leclerc Drive.
 *
 * Corrections b72 :
 * - le classement "Quantité" ne trie plus uniquement les 15 produits qui
 *   avaient déjà été sélectionnés par montant ; on charge tous les produits
 *   du mois, puis on calcule réellement le Top 10 selon le critère choisi ;
 * - le Top est explicitement limité à 10 produits ;
 * - la répartition par rayon est calculée sur les lignes produits reconnues,
 *   et l'écran affiche séparément le total réellement payé et l'écart éventuel
 *   entre ce total et la somme des lignes. On n'attribue donc plus implicitement
 *   un écart global à un rayon au hasard.
 */
@Composable
fun DriveStatsScreen(viewModel: BudgetViewModel) {
    val scope = rememberCoroutineScope()

    var months by remember { mutableStateOf(emptyList<String>()) }
    var selectedMonth by remember { mutableStateOf<String?>(null) }
    var topProducts by remember { mutableStateOf(emptyList<DriveTopProduct>()) }
    var sections by remember { mutableStateOf(emptyList<CategoryExpenseTotal>()) }
    var monthlySummary by remember { mutableStateOf<DriveMonthlyTotal?>(null) }
    var evolutionOf by remember { mutableStateOf<String?>(null) }
    var evolution by remember { mutableStateOf(emptyList<DriveProductStat>()) }
    var byQuantity by remember { mutableStateOf(false) }

    fun loadMonth(month: String) {
        scope.launch {
            // Le DAO classe par montant avant LIMIT. Pour que le mode Quantité
            // soit exact, on récupère volontairement tous les produits du mois
            // puis on applique le classement final côté UI.
            topProducts = viewModel.getDriveTopProducts(month, 10000)
            sections = viewModel.getDriveSectionTotals(month)
            monthlySummary = viewModel.getDriveMonthlyTotals()
                .firstOrNull { it.month == month }
        }
    }

    LaunchedEffect(Unit) {
        months = viewModel.getDriveMonths()
        months.firstOrNull()?.let {
            selectedMonth = it
            loadMonth(it)
        }
    }

    // ---------------- Dialogue d'évolution d'un produit ----------------
    evolutionOf?.let { label ->
        AlertDialog(
            onDismissRequest = { evolutionOf = null },
            title = {
                Text(label, style = MaterialTheme.typography.titleMedium)
            },
            text = {
                if (evolution.isEmpty()) {
                    Text("Pas d'historique pour ce produit.")
                } else {
                    val maxTotal = evolution.maxOf { it.total }
                    Column {
                        evolution.forEach { e ->
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
                                        if (maxTotal > 0)
                                            (e.total / maxTotal).toFloat() else 0f
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

        // ---------------- Sélecteur de mois ----------------
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(months) { month ->
                FilterChip(
                    selected = month == selectedMonth,
                    onClick = {
                        selectedMonth = month
                        loadMonth(month)
                    },
                    label = { Text(month) }
                )
            }
        }

        // ---------------- Top 10 produits ----------------
        SectionCard(
            Icons.Default.EmojiEvents,
            "Top 10 produits",
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
                EmptyHint("Aucun produit sur ce mois.")
            } else {
                val ranked = (
                    if (byQuantity)
                        topProducts.sortedByDescending { it.quantity }
                    else
                        topProducts.sortedByDescending { it.total }
                ).take(10)

                val maxValue = if (byQuantity)
                    ranked.maxOfOrNull { it.quantity } ?: 0.0
                else
                    ranked.maxOfOrNull { it.total } ?: 0.0

                Text(
                    "Classement calculé sur toutes les lignes produits reconnues du mois. " +
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
                        if (p.orders > 1) {
                            Text(
                                "${p.orders} commandes",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Spacer(Modifier.height(3.dp))
                        LinearProgressIndicator(
                            progress = {
                                val v = if (byQuantity) p.quantity else p.total
                                if (maxValue > 0) (v / maxValue).toFloat() else 0f
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
        SectionCard(Icons.Default.Storefront, "Répartition par rayon") {
            if (sections.isEmpty()) {
                EmptyHint("Aucune donnée de rayon sur ce mois.")
            } else {
                val parsedLineTotal = sections.sumOf { it.total }
                val paidTotal = monthlySummary?.total ?: parsedLineTotal
                val storedLineTotal = monthlySummary?.lineTotal ?: parsedLineTotal
                val gap = paidTotal - storedLineTotal

                Text(
                    "Lignes ventilées : ${storedLineTotal.eur()} · Total payé : ${paidTotal.eur()}",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    "Les pourcentages ci-dessous portent sur les lignes produits reconnues, " +
                        "afin de ne pas fausser les rayons avec un écart global.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (abs(gap) >= 0.01) {
                    Text(
                        "Écart total/lignes : ${gap.eur()} " +
                            "(remises globales, frais ou lignes non reconnues)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.secondary
                    )
                }
                Spacer(Modifier.height(6.dp))

                sections.forEach { s ->
                    val percent = if (parsedLineTotal > 0)
                        s.total / parsedLineTotal * 100.0 else 0.0
                    Column(Modifier.padding(vertical = 5.dp)) {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                s.category,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "%s (%.1f %%)".format(s.total.eur(), percent),
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                        Spacer(Modifier.height(3.dp))
                        LinearProgressIndicator(
                            progress = {
                                if (parsedLineTotal > 0)
                                    (s.total / parsedLineTotal).toFloat() else 0f
                            },
                            modifier = Modifier.fillMaxWidth(),
                            color = MaterialTheme.colorScheme.tertiary,
                            trackColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                    }
                }
            }
        }
    }
}

private fun formatQuantity(value: Double): String =
    if (value % 1.0 == 0.0) value.toInt().toString()
    else "%.3f".format(value).trimEnd('0').trimEnd(',').trimEnd('.')
