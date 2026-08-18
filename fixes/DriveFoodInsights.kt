package com.example.nicobudget.ui

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Storefront
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.nicobudget.data.model.*
import com.example.nicobudget.ui.components.EmptyHint
import com.example.nicobudget.ui.components.SectionCard
import com.example.nicobudget.ui.components.eur
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val FOOD_OVERRIDE_PREFS = "drive_food_family_overrides"

private data class ClassifiedFoodLine(
    val line: DriveFoodAnalysisLine,
    val key: String,
    val family: DriveFoodFamily
)

/** Résultat déjà agrégé pour une des trois lectures alimentaires. */
private data class PreparedFoodView(
    val visibleOrders: Int,
    val visibleSpend: Double,
    val visibleProducts: Int,
    val familySummaries: List<DriveFoodFamilySummary>
)

/**
 * Les 5 000+ lignes Drive sont classifiées une seule fois hors du thread Compose.
 * Les trois vues ne font ensuite qu'un accès à cette structure déjà préparée.
 */
private data class PreparedFoodAnalysis(
    val allPeriodOrders: Int,
    val views: Map<DriveFoodView, PreparedFoodView>
)

/**
 * Petit cache mémoire des agrégats d'affichage.
 * Il ne contient pas les lignes brutes et est invalidé si le nombre de lignes,
 * la période ou les corrections manuelles changent.
 */
private object FoodAnalysisMemoryCache {
    private const val MAX_ENTRIES = 8
    private val entries = LinkedHashMap<String, PreparedFoodAnalysis>(MAX_ENTRIES, 0.75f, true)

    @Synchronized
    fun get(key: String): PreparedFoodAnalysis? = entries[key]

    @Synchronized
    fun put(key: String, value: PreparedFoodAnalysis) {
        entries[key] = value
        while (entries.size > MAX_ENTRIES) {
            val eldest = entries.entries.firstOrNull()?.key ?: break
            entries.remove(eldest)
        }
    }
}

@Composable
fun DriveFoodHabits(
    viewModel: BudgetViewModel,
    selectedScope: String,
    periodLabel: String
) {
    val context = LocalContext.current
    val prefs = remember {
        context.getSharedPreferences(FOOD_OVERRIDE_PREFS, Context.MODE_PRIVATE)
    }

    var lines by remember { mutableStateOf<List<DriveFoodAnalysisLine>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var preparing by remember { mutableStateOf(false) }
    var prepared by remember { mutableStateOf<PreparedFoodAnalysis?>(null) }
    var foodView by remember { mutableStateOf(DriveFoodView.MAIN_DISHES) }
    var selectedFamily by remember { mutableStateOf<DriveFoodFamily?>(null) }
    var editingProduct by remember { mutableStateOf<DriveFoodProductSummary?>(null) }
    var overrideRevision by remember { mutableIntStateOf(0) }

    LaunchedEffect(Unit) {
        try {
            lines = viewModel.getDriveFoodAnalysisLines()
        } finally {
            loading = false
        }
    }

    LaunchedEffect(lines, selectedScope, overrideRevision) {
        if (lines.isEmpty()) {
            prepared = null
            preparing = false
            return@LaunchedEffect
        }

        // Snapshot unique des corrections : on évite 5 000 accès SharedPreferences.
        val overrides = prefs.all.mapNotNull { (key, value) ->
            val family = (value as? String)?.let {
                runCatching { DriveFoodFamily.valueOf(it) }.getOrNull()
            }
            family?.let { key to it }
        }.toMap()
        val overrideSignature = overrides.entries
            .sortedBy { it.key }
            .fold(17) { acc, entry -> 31 * acc + entry.hashCode() }
        val cacheKey = "${lines.size}|$selectedScope|$overrideSignature"

        FoodAnalysisMemoryCache.get(cacheKey)?.let { cached ->
            prepared = cached
            preparing = false
            return@LaunchedEffect
        }

        preparing = true
        val result = withContext(Dispatchers.Default) {
            prepareFoodAnalysis(lines, selectedScope, overrides)
        }
        FoodAnalysisMemoryCache.put(cacheKey, result)
        prepared = result
        preparing = false
    }

    val currentView = prepared?.views?.get(foodView)
    val allPeriodOrders = prepared?.allPeriodOrders ?: 0
    val visibleOrders = currentView?.visibleOrders ?: 0
    val visibleSpend = currentView?.visibleSpend ?: 0.0
    val visibleProducts = currentView?.visibleProducts ?: 0
    val familySummaries = currentView?.familySummaries.orEmpty()

    SectionCard(
        Icons.Default.Storefront,
        "Habitudes alimentaires — $periodLabel"
    ) {
        Text(
            "Lecture par rôle alimentaire : les références sont regroupées en familles pour éviter " +
                "que les produits de fond de panier dominent toute l'analyse.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(8.dp))

        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(DriveFoodView.entries) { view ->
                FilterChip(
                    selected = foodView == view,
                    onClick = { foodView = view },
                    label = { Text(view.label) }
                )
            }
        }

        Spacer(Modifier.height(8.dp))
        Text(
            when (foodView) {
                DriveFoodView.ALL_FOOD ->
                    "Tout ce qui est consommable, hors hygiène, entretien, animalerie, bébé et maison."
                DriveFoodView.MEALS ->
                    "Exclut aussi boissons, viennoiseries/petit-déjeuner, desserts, apéritif/snacking et condiments."
                DriveFoodView.MAIN_DISHES ->
                    "Garde le cœur des repas : viandes/poissons, légumes, féculents, œufs, pizzas, plats préparés et repas complets."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(Modifier.height(10.dp))
        if (loading) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            Text(
                "Lecture de l'historique Drive…",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            return@SectionCard
        }
        if (prepared == null && preparing) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            Text(
                "Préparation des statistiques alimentaires…",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            return@SectionCard
        }
        if (familySummaries.isEmpty()) {
            EmptyHint("Aucune donnée alimentaire reconnue pour cette période.")
            return@SectionCard
        }

        if (preparing) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            Spacer(Modifier.height(4.dp))
        }

        Text(
            "$visibleOrders / $allPeriodOrders commande(s) contiennent au moins un élément de cette vue · " +
                "${visibleSpend.eur()} · $visibleProducts produit(s)",
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.SemiBold
        )
        Text(
            "La fréquence d'une famille compte chaque commande une seule fois, même si elle contient plusieurs produits de cette famille.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(Modifier.height(8.dp))
        val maxOrders = familySummaries.maxOfOrNull { it.orders } ?: 1

        familySummaries.forEachIndexed { index, family ->
            val orderPercent = if (allPeriodOrders > 0) {
                family.orders * 100.0 / allPeriodOrders
            } else 0.0
            val spendPercent = if (visibleSpend > 0.0) {
                family.total * 100.0 / visibleSpend
            } else 0.0

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { selectedFamily = family.family }
                    .padding(vertical = 6.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        "${index + 1}. ${family.family.label}",
                        modifier = Modifier.weight(1f),
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("${family.orders} cmd", fontWeight = FontWeight.Bold)
                }
                Text(
                    "%.1f %% des commandes · %s (%.1f %% des dépenses de la vue) · %d réf.".format(
                        orderPercent,
                        family.total.eur(),
                        spendPercent,
                        family.products.size
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(3.dp))
                LinearProgressIndicator(
                    progress = { family.orders.toFloat() / maxOrders.toFloat() },
                    modifier = Modifier.fillMaxWidth(),
                    trackColor = MaterialTheme.colorScheme.surfaceVariant
                )
            }
        }

        Spacer(Modifier.height(6.dp))
        Text(
            "Tape une famille pour voir ses produits. Une référence mal classée peut être corrigée manuellement ; la correction est mémorisée sur ce téléphone.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }

    selectedFamily?.let { familyKey ->
        val current = familySummaries.firstOrNull { it.family == familyKey }
        if (current == null) {
            selectedFamily = null
        } else {
            AlertDialog(
                onDismissRequest = { selectedFamily = null },
                title = {
                    Column {
                        Text(current.family.label)
                        Text(
                            "$periodLabel · ${current.orders} commande(s) · ${current.total.eur()}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                text = {
                    LazyColumn(Modifier.heightIn(max = 560.dp)) {
                        items(current.products, key = { it.key }) { product ->
                            val manuallyOverridden = prefs.contains(product.key)
                            Column(Modifier.padding(vertical = 5.dp)) {
                                Text(product.label, fontWeight = FontWeight.SemiBold)
                                Text(
                                    "${product.orders} commande(s) · x${foodQuantity(product.quantity)} · ${product.total.eur()}" +
                                        if (manuallyOverridden) " · corrigé manuellement" else "",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                TextButton(
                                    onClick = { editingProduct = product },
                                    contentPadding = PaddingValues(0.dp)
                                ) {
                                    Text("Classer ce produit…")
                                }
                                HorizontalDivider()
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { selectedFamily = null }) { Text("Fermer") }
                }
            )
        }
    }

    editingProduct?.let { product ->
        AlertDialog(
            onDismissRequest = { editingProduct = null },
            title = { Text("Classer « ${product.label} »") },
            text = {
                Column(
                    Modifier
                        .heightIn(max = 560.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    Text(
                        "La classification automatique actuelle est « ${product.family.label} ». " +
                            "Choisis une famille pour mémoriser une correction.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = {
                            prefs.edit().remove(product.key).apply()
                            overrideRevision++
                            editingProduct = null
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Revenir au classement automatique")
                    }
                    Spacer(Modifier.height(6.dp))
                    DriveFoodFamily.entries
                        .sortedBy { it.label }
                        .forEach { family ->
                            TextButton(
                                onClick = {
                                    prefs.edit().putString(product.key, family.name).apply()
                                    overrideRevision++
                                    editingProduct = null
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(family.label)
                            }
                        }
                }
            },
            confirmButton = {
                TextButton(onClick = { editingProduct = null }) { Text("Annuler") }
            }
        )
    }
}

private fun SharedPreferences.familyOverride(key: String): DriveFoodFamily? =
    getString(key, null)?.let { stored ->
        runCatching { DriveFoodFamily.valueOf(stored) }.getOrNull()
    }

private fun prepareFoodAnalysis(
    allLines: List<DriveFoodAnalysisLine>,
    selectedScope: String,
    overrides: Map<String, DriveFoodFamily>
): PreparedFoodAnalysis {
    val periodLines = allLines.filter { line ->
        when {
            selectedScope == "ALL" -> true
            selectedScope.length == 4 -> line.month.startsWith(selectedScope)
            else -> line.month == selectedScope
        }
    }

    val classified = periodLines.map { line ->
        val key = DriveProductNormalizer.key(line.label)
        ClassifiedFoodLine(
            line = line,
            key = key,
            family = DriveFoodClassifier.classify(line, overrides[key])
        )
    }

    val allPeriodOrders = periodLines.asSequence()
        .map { it.orderRowId }
        .distinct()
        .count()

    // Les familles et produits sont agrégés une seule fois. Une vue ne fait ensuite
    // que filtrer les familles déjà préparées.
    val allFamilySummaries = buildFamilySummaries(classified)

    val views = DriveFoodView.entries.associateWith { view ->
        val visible = classified.filter { it.family.includedIn(view) }
        PreparedFoodView(
            visibleOrders = visible.asSequence().map { it.line.orderRowId }.distinct().count(),
            visibleSpend = visible.sumOf { it.line.total },
            visibleProducts = visible.asSequence().map { it.key }.distinct().count(),
            familySummaries = allFamilySummaries.filter { it.family.includedIn(view) }
        )
    }

    return PreparedFoodAnalysis(
        allPeriodOrders = allPeriodOrders,
        views = views
    )
}

private fun buildFamilySummaries(
    lines: List<ClassifiedFoodLine>
): List<DriveFoodFamilySummary> {
    val productSummaries = lines
        .groupBy { it.key }
        .map { (key, rows) ->
            val label = rows.groupingBy { it.line.label }.eachCount()
                .maxByOrNull { it.value }?.key ?: rows.first().line.label
            val section = rows.groupingBy { it.line.section }.eachCount()
                .maxByOrNull { it.value }?.key ?: rows.first().line.section
            DriveFoodProductSummary(
                key = key,
                label = label,
                section = section,
                family = rows.first().family,
                orders = rows.asSequence().map { it.line.orderRowId }.distinct().count(),
                quantity = rows.sumOf { it.line.quantity },
                total = rows.sumOf { it.line.total }
            )
        }

    return lines.groupBy { it.family }
        .map { (family, familyLines) ->
            DriveFoodFamilySummary(
                family = family,
                orders = familyLines.asSequence().map { it.line.orderRowId }.distinct().count(),
                quantity = familyLines.sumOf { it.line.quantity },
                total = familyLines.sumOf { it.line.total },
                products = productSummaries
                    .filter { it.family == family }
                    .sortedWith(
                        compareByDescending<DriveFoodProductSummary> { it.orders }
                            .thenByDescending { it.total }
                    )
            )
        }
        .sortedWith(
            compareByDescending<DriveFoodFamilySummary> { it.orders }
                .thenByDescending { it.total }
        )
}

private fun foodQuantity(value: Double): String =
    if (value % 1.0 == 0.0) value.toInt().toString()
    else "%.3f".format(value).trimEnd('0').trimEnd(',').trimEnd('.')
