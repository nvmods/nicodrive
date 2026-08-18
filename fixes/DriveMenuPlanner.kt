package com.example.nicobudget.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.SharedPreferences
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.nicobudget.data.model.*
import com.example.nicobudget.ui.components.eur
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.ceil
import kotlin.random.Random

private const val MENU_PREFS = "drive_menu_planner_v2"
private const val MENU_FAMILY_PREFS = "drive_food_family_overrides"

private enum class MenuPlanMode(val label: String) {
    HABITS("Habitudes"),
    VARIED("Varié"),
    ECONOMICAL("Économique"),
    QUICK("Rapide")
}

private data class MenuNeed(
    val label: String,
    val family: DriveFoodFamily,
    val keywords: List<String> = emptyList(),
    val unitsForTwo: Double = 1.0
)

private data class MenuRecipe(
    val id: String,
    val name: String,
    val primary: DriveFoodFamily,
    val quick: Boolean = false,
    val needs: List<MenuNeed>
)

private data class MenuCatalogProduct(
    val key: String,
    val label: String,
    val family: DriveFoodFamily,
    val orders: Int,
    val lastDate: String,
    val recentUnitPrice: Double
)

private data class MenuShoppingItem(
    val key: String,
    val label: String,
    val family: DriveFoodFamily,
    val quantity: Int,
    val unitPrice: Double?,
    val generic: Boolean
) {
    val total: Double? get() = unitPrice?.times(quantity)
}

@Composable
fun DriveMenuPlannerDialog(
    lines: List<DriveFoodAnalysisLine>,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences(MENU_PREFS, Context.MODE_PRIVATE) }
    val familyPrefs = remember { context.getSharedPreferences(MENU_FAMILY_PREFS, Context.MODE_PRIVATE) }
    val recipes = remember { menuRecipes() }
    val catalog = remember(lines) { buildMenuCatalog(lines, familyPrefs) }
    val affinity = remember(lines) { buildFamilyAffinity(lines, familyPrefs) }

    var mode by remember {
        mutableStateOf(
            prefs.getString("mode", null)?.let { runCatching { MenuPlanMode.valueOf(it) }.getOrNull() }
                ?: MenuPlanMode.VARIED
        )
    }
    var servings by remember { mutableIntStateOf(prefs.getInt("servings", 2).coerceIn(1, 6)) }
    var planIds by remember { mutableStateOf(loadPlanIds(prefs, recipes)) }
    var locked by remember { mutableStateOf(loadLocked(prefs)) }
    var confirmedMessage by remember { mutableStateOf<String?>(null) }
    val checked = remember { mutableStateMapOf<String, Boolean>() }

    val recentIds = remember { prefs.getString("recent", "").orEmpty().split('|').filter { it.isNotBlank() } }

    fun regenerateAll() {
        planIds = generateMenuPlan(
            recipes = recipes,
            existing = planIds,
            locked = locked,
            mode = mode,
            affinity = affinity,
            catalog = catalog,
            servings = servings,
            recentIds = recentIds
        )
        confirmedMessage = null
        checked.clear()
    }

    fun regenerateOne(index: Int) {
        planIds = regenerateMenuSlot(
            index = index,
            current = planIds,
            recipes = recipes,
            mode = mode,
            affinity = affinity,
            catalog = catalog,
            servings = servings,
            recentIds = recentIds
        )
        confirmedMessage = null
        checked.clear()
    }

    LaunchedEffect(catalog.size) {
        if (catalog.isNotEmpty() && planIds.size != 7) regenerateAll()
    }

    LaunchedEffect(planIds, locked, mode, servings) {
        prefs.edit()
            .putString("plan", planIds.joinToString("|"))
            .putString("locked", locked.sorted().joinToString(","))
            .putString("mode", mode.name)
            .putInt("servings", servings)
            .apply()
    }

    val plan = planIds.mapNotNull { id -> recipes.firstOrNull { it.id == id } }
    val shopping = remember(planIds, servings, mode, catalog) {
        buildShoppingList(plan, servings, catalog, mode)
    }
    val knownTotal = shopping.mapNotNull { it.total }.sum()
    val unknownCount = shopping.count { it.unitPrice == null }
    val formatter = remember { DateTimeFormatter.ofPattern("EEE dd/MM", Locale.FRANCE) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .padding(16.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Menus & courses", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Text(
                            "7 prochains dîners · basé sur ton historique Drive",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    TextButton(onClick = onDismiss) { Text("Fermer") }
                }

                Spacer(Modifier.height(8.dp))

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text("Style de semaine", fontWeight = FontWeight.SemiBold)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(MenuPlanMode.entries) { item ->
                            FilterChip(
                                selected = mode == item,
                                onClick = { mode = item },
                                label = { Text(item.label) }
                            )
                        }
                    }
                    Text(
                        when (mode) {
                            MenuPlanMode.HABITS -> "Privilégie les familles que vous achetez le plus souvent."
                            MenuPlanMode.VARIED -> "Reste proche de vos goûts mais pénalise fortement les répétitions."
                            MenuPlanMode.ECONOMICAL -> "Favorise les recettes dont les références habituelles ont un coût récent faible."
                            MenuPlanMode.QUICK -> "Favorise les repas simples ou rapides tout en gardant de la variété."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Personnes", fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.width(10.dp))
                        (1..6).forEach { count ->
                            FilterChip(
                                selected = servings == count,
                                onClick = { servings = count },
                                label = { Text(count.toString()) },
                                modifier = Modifier.padding(end = 4.dp)
                            )
                        }
                    }

                    Button(
                        onClick = { regenerateAll() },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = catalog.isNotEmpty()
                    ) {
                        Text(if (locked.isEmpty()) "Générer une nouvelle semaine" else "Régénérer les repas non verrouillés")
                    }

                    if (catalog.isEmpty()) {
                        Text("Pas assez d'historique Drive pour générer les menus.")
                    } else if (plan.size == 7) {
                        Text("Menus proposés", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)

                        plan.forEachIndexed { index, recipe ->
                            val day = LocalDate.now().plusDays(index.toLong())
                            val estimate = estimateRecipeCost(recipe, servings, catalog, mode)
                            Surface(
                                modifier = Modifier.fillMaxWidth(),
                                tonalElevation = 1.dp,
                                shape = MaterialTheme.shapes.medium
                            ) {
                                Column(Modifier.padding(12.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(Modifier.weight(1f)) {
                                            Text(
                                                day.format(formatter).replaceFirstChar { it.uppercase() },
                                                style = MaterialTheme.typography.labelMedium,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                            Text(recipe.name, fontWeight = FontWeight.SemiBold)
                                            Text(
                                                buildString {
                                                    append(recipe.primary.label)
                                                    if (recipe.quick) append(" · rapide")
                                                    if (estimate != null) append(" · ~${estimate.eur()}")
                                                },
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                        TextButton(
                                            onClick = {
                                                locked = if (index in locked) locked - index else locked + index
                                            }
                                        ) {
                                            Text(if (index in locked) "🔒" else "🔓")
                                        }
                                        TextButton(
                                            onClick = { regenerateOne(index) },
                                            enabled = index !in locked
                                        ) { Text("↻") }
                                    }
                                }
                            }
                        }

                        OutlinedButton(
                            onClick = {
                                val old = prefs.getString("recent", "").orEmpty()
                                    .split('|').filter { it.isNotBlank() }
                                val merged = (planIds + old).distinct().take(21)
                                prefs.edit().putString("recent", merged.joinToString("|")).apply()
                                confirmedMessage = "Semaine mémorisée : ces repas seront moins prioritaires à la prochaine génération."
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("Valider cette semaine") }

                        confirmedMessage?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                        }

                        HorizontalDivider()
                        Text("Liste de courses estimée", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text(
                            "Les références sont choisies parmi celles réellement achetées. Le prix unitaire est la médiane des 3 derniers prix observés. " +
                                "Les quantités sont des unités/conditionnements habituels estimés, pas des grammages de recette.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        shopping.forEach { item ->
                            val isChecked = checked[item.key] == true
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Checkbox(
                                    checked = isChecked,
                                    onCheckedChange = { checked[item.key] = it }
                                )
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        item.label,
                                        fontWeight = if (isChecked) FontWeight.Normal else FontWeight.SemiBold
                                    )
                                    Text(
                                        if (item.generic) "${item.family.label} · référence non retrouvée"
                                        else item.family.label,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    buildString {
                                        append("x${item.quantity}")
                                        item.total?.let { append(" · ${it.eur()}") }
                                            ?: append(" · ?")
                                    },
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }

                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            tonalElevation = 2.dp,
                            shape = MaterialTheme.shapes.medium
                        ) {
                            Row(
                                Modifier.padding(12.dp).fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column {
                                    Text("Coût Drive estimé", fontWeight = FontWeight.SemiBold)
                                    if (unknownCount > 0) {
                                        Text(
                                            "$unknownCount ingrédient(s) sans prix connu",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                                Text("~${knownTotal.eur()}", fontWeight = FontWeight.Bold)
                            }
                        }

                        OutlinedButton(
                            onClick = {
                                val text = shopping.joinToString("\n") { item ->
                                    "☐ x${item.quantity} ${item.label}" +
                                        (item.total?.let { " — ${it.eur()}" } ?: "")
                                } + "\n\nTotal connu estimé : ${knownTotal.eur()}"
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                clipboard.setPrimaryClip(ClipData.newPlainText("Liste de courses NicoBudget", text))
                                confirmedMessage = "Liste de courses copiée."
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("Copier la liste de courses") }
                    }
                }
            }
        }
    }
}

private fun buildMenuCatalog(
    lines: List<DriveFoodAnalysisLine>,
    familyPrefs: SharedPreferences
): List<MenuCatalogProduct> {
    data class Classified(val line: DriveFoodAnalysisLine, val family: DriveFoodFamily, val key: String)

    val rows = lines.mapNotNull { line ->
        val key = DriveProductNormalizer.key(line.label)
        val override = familyPrefs.getString(key, null)?.let {
            runCatching { DriveFoodFamily.valueOf(it) }.getOrNull()
        }
        val family = DriveFoodClassifier.classify(line, override)
        if (family == DriveFoodFamily.NON_FOOD) null else Classified(line, family, key)
    }

    return rows.groupBy { it.key }.map { (key, group) ->
        val label = group.groupingBy { it.line.label }.eachCount().maxByOrNull { it.value }?.key
            ?: group.first().line.label
        val observations = group
            .sortedByDescending { it.line.date }
            .mapNotNull { row ->
                val p = when {
                    row.line.unitPrice > 0.0 -> row.line.unitPrice
                    row.line.quantity > 0.0 -> row.line.total / row.line.quantity
                    else -> 0.0
                }
                p.takeIf { it > 0.0 }
            }
            .take(3)
        MenuCatalogProduct(
            key = key,
            label = label,
            family = group.first().family,
            orders = group.asSequence().map { it.line.orderRowId }.distinct().count(),
            lastDate = group.maxOf { it.line.date },
            recentUnitPrice = median(observations)
        )
    }.filter { it.recentUnitPrice > 0.0 }
}

private fun buildFamilyAffinity(
    lines: List<DriveFoodAnalysisLine>,
    familyPrefs: SharedPreferences
): Map<DriveFoodFamily, Double> {
    val totalOrders = lines.asSequence().map { it.orderRowId }.distinct().count().coerceAtLeast(1)
    return lines.groupBy { line ->
        val key = DriveProductNormalizer.key(line.label)
        val override = familyPrefs.getString(key, null)?.let {
            runCatching { DriveFoodFamily.valueOf(it) }.getOrNull()
        }
        DriveFoodClassifier.classify(line, override)
    }.mapValues { (_, rows) ->
        rows.asSequence().map { it.orderRowId }.distinct().count().toDouble() / totalOrders.toDouble()
    }
}

private fun chooseProduct(
    need: MenuNeed,
    catalog: List<MenuCatalogProduct>,
    mode: MenuPlanMode
): MenuCatalogProduct? {
    val normalizedKeywords = need.keywords.map { DriveProductNormalizer.key(it) }
    val candidates = catalog.filter { p ->
        p.family == need.family &&
            (normalizedKeywords.isEmpty() || normalizedKeywords.any { p.key.contains(it) })
    }
    if (candidates.isEmpty()) return null

    return when (mode) {
        MenuPlanMode.ECONOMICAL -> candidates
            .sortedWith(
                compareBy<MenuCatalogProduct> { it.recentUnitPrice }
                    .thenByDescending { it.orders }
                    .thenByDescending { it.lastDate }
            ).first()
        else -> candidates
            .sortedWith(
                compareByDescending<MenuCatalogProduct> { it.orders }
                    .thenByDescending { it.lastDate }
                    .thenBy { it.recentUnitPrice }
            ).first()
    }
}

private fun estimateRecipeCost(
    recipe: MenuRecipe,
    servings: Int,
    catalog: List<MenuCatalogProduct>,
    mode: MenuPlanMode
): Double? {
    var known = 0.0
    var found = 0
    recipe.needs.forEach { need ->
        val product = chooseProduct(need, catalog, mode) ?: return@forEach
        val q = ceil(need.unitsForTwo * servings.coerceAtLeast(1) / 2.0).toInt().coerceAtLeast(1)
        known += product.recentUnitPrice * q
        found++
    }
    return known.takeIf { found > 0 }
}

private fun generateMenuPlan(
    recipes: List<MenuRecipe>,
    existing: List<String>,
    locked: Set<Int>,
    mode: MenuPlanMode,
    affinity: Map<DriveFoodFamily, Double>,
    catalog: List<MenuCatalogProduct>,
    servings: Int,
    recentIds: List<String>
): List<String> {
    val rng = Random(System.nanoTime())
    val result = MutableList(7) { "" }

    for (i in 0 until 7) {
        if (i in locked && i < existing.size && recipes.any { it.id == existing[i] }) {
            result[i] = existing[i]
            continue
        }
        val used = result.filter { it.isNotBlank() }.mapNotNull { id -> recipes.firstOrNull { it.id == id } }
        val best = recipes.maxByOrNull { recipe ->
            menuRecipeScore(recipe, used, mode, affinity, catalog, servings, recentIds, rng)
        } ?: recipes.first()
        result[i] = best.id
    }
    return result
}

private fun regenerateMenuSlot(
    index: Int,
    current: List<String>,
    recipes: List<MenuRecipe>,
    mode: MenuPlanMode,
    affinity: Map<DriveFoodFamily, Double>,
    catalog: List<MenuCatalogProduct>,
    servings: Int,
    recentIds: List<String>
): List<String> {
    if (current.size != 7) return current
    val rng = Random(System.nanoTime())
    val used = current.mapIndexedNotNull { i, id ->
        if (i == index) null else recipes.firstOrNull { it.id == id }
    }
    val previousFamily = current.getOrNull(index - 1)?.let { id -> recipes.firstOrNull { it.id == id }?.primary }
    val nextFamily = current.getOrNull(index + 1)?.let { id -> recipes.firstOrNull { it.id == id }?.primary }
    val oldId = current[index]

    val best = recipes
        .filter { it.id != oldId }
        .maxByOrNull { recipe ->
            var score = menuRecipeScore(recipe, used, mode, affinity, catalog, servings, recentIds, rng)
            if (recipe.primary == previousFamily) score -= 8.0
            if (recipe.primary == nextFamily) score -= 8.0
            score
        } ?: return current

    return current.toMutableList().also { it[index] = best.id }
}

private fun menuRecipeScore(
    recipe: MenuRecipe,
    used: List<MenuRecipe>,
    mode: MenuPlanMode,
    affinity: Map<DriveFoodFamily, Double>,
    catalog: List<MenuCatalogProduct>,
    servings: Int,
    recentIds: List<String>,
    rng: Random
): Double {
    if (used.any { it.id == recipe.id }) return -1000.0
    val familyAffinity = affinity[recipe.primary] ?: 0.0
    val repeatedFamily = used.count { it.primary == recipe.primary }
    val adjacentSame = used.lastOrNull()?.primary == recipe.primary
    val cost = estimateRecipeCost(recipe, servings, catalog, mode) ?: 12.0

    var score = when (mode) {
        MenuPlanMode.HABITS -> familyAffinity * 18.0 - repeatedFamily * 4.0
        MenuPlanMode.VARIED -> familyAffinity * 8.0 - repeatedFamily * 12.0
        MenuPlanMode.ECONOMICAL -> familyAffinity * 7.0 - cost * 0.65 - repeatedFamily * 8.0
        MenuPlanMode.QUICK -> familyAffinity * 6.0 + if (recipe.quick) 12.0 else -3.0 - repeatedFamily * 8.0
    }
    if (adjacentSame) score -= 10.0
    if (recipe.id in recentIds) score -= 7.0
    score += rng.nextDouble(0.0, 4.0)
    return score
}

private fun buildShoppingList(
    plan: List<MenuRecipe>,
    servings: Int,
    catalog: List<MenuCatalogProduct>,
    mode: MenuPlanMode
): List<MenuShoppingItem> {
    data class MutableItem(
        val key: String,
        val label: String,
        val family: DriveFoodFamily,
        var quantity: Int,
        val unitPrice: Double?,
        val generic: Boolean
    )

    val map = linkedMapOf<String, MutableItem>()
    plan.forEach { recipe ->
        recipe.needs.forEach { need ->
            val quantity = ceil(need.unitsForTwo * servings.coerceAtLeast(1) / 2.0)
                .toInt().coerceAtLeast(1)
            val product = chooseProduct(need, catalog, mode)
            val key = product?.key ?: "generic:${need.family.name}:${need.label}"
            val existing = map[key]
            if (existing != null) {
                existing.quantity += quantity
            } else {
                map[key] = MutableItem(
                    key = key,
                    label = product?.label ?: need.label,
                    family = need.family,
                    quantity = quantity,
                    unitPrice = product?.recentUnitPrice,
                    generic = product == null
                )
            }
        }
    }

    return map.values.map {
        MenuShoppingItem(it.key, it.label, it.family, it.quantity, it.unitPrice, it.generic)
    }.sortedWith(compareBy<MenuShoppingItem> { it.family.label }.thenBy { it.label })
}

private fun loadPlanIds(prefs: SharedPreferences, recipes: List<MenuRecipe>): List<String> {
    val valid = recipes.map { it.id }.toSet()
    val ids = prefs.getString("plan", "").orEmpty().split('|').filter { it in valid }
    return ids.takeIf { it.size == 7 } ?: emptyList()
}

private fun loadLocked(prefs: SharedPreferences): Set<Int> =
    prefs.getString("locked", "").orEmpty()
        .split(',')
        .mapNotNull { it.toIntOrNull() }
        .filter { it in 0..6 }
        .toSet()

private fun median(values: List<Double>): Double {
    if (values.isEmpty()) return 0.0
    val sorted = values.sorted()
    return if (sorted.size % 2 == 1) sorted[sorted.size / 2]
    else (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2]) / 2.0
}

private fun menuRecipes(): List<MenuRecipe> {
    fun n(label: String, family: DriveFoodFamily, vararg keywords: String, units: Double = 1.0) =
        MenuNeed(label, family, keywords.toList(), units)

    return listOf(
        MenuRecipe("poulet_haricots_pdt", "Poulet, haricots verts & pommes de terre", DriveFoodFamily.POULTRY,
            needs = listOf(n("Poulet", DriveFoodFamily.POULTRY, "poulet"), n("Haricots verts", DriveFoodFamily.VEGETABLES, "haricot vert"), n("Pommes de terre", DriveFoodFamily.POTATOES, "pomme de terre", "pommes de terre"))),
        MenuRecipe("poulet_riz_courgettes", "Poulet, riz & courgettes", DriveFoodFamily.POULTRY,
            needs = listOf(n("Poulet", DriveFoodFamily.POULTRY, "poulet"), n("Riz", DriveFoodFamily.STARCHES, "riz"), n("Courgettes", DriveFoodFamily.VEGETABLES, "courgette"))),
        MenuRecipe("poulet_pates_champignons", "Poulet, pâtes & champignons", DriveFoodFamily.POULTRY,
            needs = listOf(n("Poulet", DriveFoodFamily.POULTRY, "poulet"), n("Pâtes", DriveFoodFamily.STARCHES, "pates", "torsade", "macaroni"), n("Champignons", DriveFoodFamily.VEGETABLES, "champignon"))),
        MenuRecipe("cordon_frites_salade", "Cordon bleu, frites & salade", DriveFoodFamily.POULTRY, true,
            listOf(n("Cordon bleu", DriveFoodFamily.POULTRY, "cordon bleu"), n("Frites", DriveFoodFamily.POTATOES, "frites"), n("Salade", DriveFoodFamily.VEGETABLES, "laitue", "salade"))),
        MenuRecipe("nuggets_frites_haricots", "Nuggets, frites & haricots verts", DriveFoodFamily.POULTRY, true,
            listOf(n("Nuggets", DriveFoodFamily.POULTRY, "nugget"), n("Frites", DriveFoodFamily.POTATOES, "frites"), n("Haricots verts", DriveFoodFamily.VEGETABLES, "haricot vert"))),
        MenuRecipe("steak_haricots_pdt", "Steak haché, haricots verts & pommes de terre", DriveFoodFamily.BEEF,
            needs = listOf(n("Steak haché", DriveFoodFamily.BEEF, "steak hache", "boeuf"), n("Haricots verts", DriveFoodFamily.VEGETABLES, "haricot vert"), n("Pommes de terre", DriveFoodFamily.POTATOES, "pomme de terre", "pommes de terre"))),
        MenuRecipe("steak_pates_tomate", "Steak haché & pâtes à la tomate", DriveFoodFamily.BEEF,
            needs = listOf(n("Steak haché", DriveFoodFamily.BEEF, "steak hache", "boeuf"), n("Pâtes", DriveFoodFamily.STARCHES, "spaghetti", "pates", "macaroni"), n("Sauce tomate", DriveFoodFamily.CONDIMENTS, "tomate", "sauce tomate"))),
        MenuRecipe("burger_frites_salade", "Burgers maison, frites & salade", DriveFoodFamily.BEEF,
            needs = listOf(n("Steak haché", DriveFoodFamily.BEEF, "steak hache"), n("Pain burger", DriveFoodFamily.BREAD, "pain burger"), n("Fromage", DriveFoodFamily.DAIRY_CHEESE, "fromage", "emmental"), n("Frites", DriveFoodFamily.POTATOES, "frites"), n("Salade", DriveFoodFamily.VEGETABLES, "laitue", "salade"))),
        MenuRecipe("boulettes_spaghetti", "Boulettes de bœuf & spaghetti", DriveFoodFamily.BEEF,
            needs = listOf(n("Boulettes de bœuf", DriveFoodFamily.BEEF, "boulette", "boeuf", "viande hachee"), n("Spaghetti", DriveFoodFamily.STARCHES, "spaghetti"), n("Sauce tomate", DriveFoodFamily.CONDIMENTS, "tomate", "sauce tomate"))),
        MenuRecipe("saucisses_lentilles", "Saucisses & lentilles", DriveFoodFamily.PORK,
            needs = listOf(n("Saucisses", DriveFoodFamily.PORK, "saucisse", "chipolata"), n("Lentilles", DriveFoodFamily.OTHER_MEAL, "lentille"), n("Carottes", DriveFoodFamily.VEGETABLES, "carotte"))),
        MenuRecipe("chipolatas_frites_courgettes", "Chipolatas, frites & courgettes", DriveFoodFamily.PORK,
            needs = listOf(n("Chipolatas", DriveFoodFamily.PORK, "chipolata"), n("Frites", DriveFoodFamily.POTATOES, "frites"), n("Courgettes", DriveFoodFamily.VEGETABLES, "courgette"))),
        MenuRecipe("jambon_coquillettes", "Jambon, coquillettes & fromage", DriveFoodFamily.PORK, true,
            listOf(n("Jambon", DriveFoodFamily.PORK, "jambon"), n("Coquillettes", DriveFoodFamily.STARCHES, "coquillette", "macaroni", "pates"), n("Fromage", DriveFoodFamily.DAIRY_CHEESE, "emmental", "fromage"))),
        MenuRecipe("porc_pdt_carottes", "Porc, pommes de terre & carottes", DriveFoodFamily.PORK,
            needs = listOf(n("Porc", DriveFoodFamily.PORK, "porc", "roti de porc"), n("Pommes de terre", DriveFoodFamily.POTATOES, "pomme de terre", "pommes de terre"), n("Carottes", DriveFoodFamily.VEGETABLES, "carotte"))),
        MenuRecipe("saumon_riz_brocoli", "Saumon, riz & brocoli", DriveFoodFamily.FISH,
            needs = listOf(n("Saumon", DriveFoodFamily.FISH, "saumon"), n("Riz", DriveFoodFamily.STARCHES, "riz"), n("Brocoli", DriveFoodFamily.VEGETABLES, "brocoli"))),
        MenuRecipe("colin_puree_haricots", "Colin, purée & haricots verts", DriveFoodFamily.FISH,
            needs = listOf(n("Colin", DriveFoodFamily.FISH, "colin"), n("Purée / pommes de terre", DriveFoodFamily.POTATOES, "pomme de terre", "puree"), n("Haricots verts", DriveFoodFamily.VEGETABLES, "haricot vert"))),
        MenuRecipe("thon_pates_tomate", "Pâtes au thon & tomate", DriveFoodFamily.FISH, true,
            listOf(n("Thon", DriveFoodFamily.FISH, "thon"), n("Pâtes", DriveFoodFamily.STARCHES, "pates", "spaghetti", "macaroni"), n("Tomate", DriveFoodFamily.CONDIMENTS, "tomate", "sauce tomate"))),
        MenuRecipe("poisson_frites_pois", "Poisson, frites & petits pois", DriveFoodFamily.FISH, true,
            listOf(n("Poisson", DriveFoodFamily.FISH, "poisson", "colin"), n("Frites", DriveFoodFamily.POTATOES, "frites"), n("Petits pois", DriveFoodFamily.VEGETABLES, "petit pois"))),
        MenuRecipe("omelette_salade_pdt", "Omelette, salade & pommes de terre", DriveFoodFamily.EGGS, true,
            listOf(n("Œufs", DriveFoodFamily.EGGS, "oeuf"), n("Salade", DriveFoodFamily.VEGETABLES, "laitue", "salade"), n("Pommes de terre", DriveFoodFamily.POTATOES, "pomme de terre", "pommes de terre"))),
        MenuRecipe("omelette_jambon_fromage", "Omelette jambon-fromage & salade", DriveFoodFamily.EGGS, true,
            listOf(n("Œufs", DriveFoodFamily.EGGS, "oeuf"), n("Jambon", DriveFoodFamily.PORK, "jambon"), n("Fromage", DriveFoodFamily.DAIRY_CHEESE, "fromage", "emmental"), n("Salade", DriveFoodFamily.VEGETABLES, "laitue", "salade"))),
        MenuRecipe("pizza_salade", "Pizza & salade", DriveFoodFamily.PIZZA_QUICHE, true,
            listOf(n("Pizza", DriveFoodFamily.PIZZA_QUICHE, "pizza"), n("Salade", DriveFoodFamily.VEGETABLES, "laitue", "salade"))),
        MenuRecipe("quiche_salade", "Quiche & salade", DriveFoodFamily.PIZZA_QUICHE, true,
            listOf(n("Quiche", DriveFoodFamily.PIZZA_QUICHE, "quiche"), n("Salade", DriveFoodFamily.VEGETABLES, "laitue", "salade"))),
        MenuRecipe("lasagnes_salade", "Lasagnes & salade", DriveFoodFamily.READY_MEALS, true,
            listOf(n("Lasagnes", DriveFoodFamily.READY_MEALS, "lasagne"), n("Salade", DriveFoodFamily.VEGETABLES, "laitue", "salade"))),
        MenuRecipe("ravioli_legumes", "Ravioli & légumes", DriveFoodFamily.READY_MEALS, true,
            listOf(n("Ravioli", DriveFoodFamily.READY_MEALS, "ravioli"), n("Légumes", DriveFoodFamily.VEGETABLES, "haricot vert", "courgette", "carotte"))),
        MenuRecipe("cannelloni_salade", "Cannelloni & salade", DriveFoodFamily.READY_MEALS, true,
            listOf(n("Cannelloni", DriveFoodFamily.READY_MEALS, "cannelloni"), n("Salade", DriveFoodFamily.VEGETABLES, "laitue", "salade"))),
        MenuRecipe("paella_salade", "Paella & salade", DriveFoodFamily.READY_MEALS, true,
            listOf(n("Paella", DriveFoodFamily.READY_MEALS, "paella"), n("Salade", DriveFoodFamily.VEGETABLES, "laitue", "salade"))),
        MenuRecipe("croque_salade", "Croque-monsieur & salade", DriveFoodFamily.READY_MEALS, true,
            listOf(n("Croque-monsieur", DriveFoodFamily.READY_MEALS, "croque monsieur"), n("Salade", DriveFoodFamily.VEGETABLES, "laitue", "salade"))),
        MenuRecipe("gnocchi_tomate_fromage", "Gnocchi tomate-fromage", DriveFoodFamily.STARCHES, true,
            listOf(n("Gnocchi", DriveFoodFamily.STARCHES, "gnocchi"), n("Sauce tomate", DriveFoodFamily.CONDIMENTS, "tomate", "sauce tomate"), n("Fromage", DriveFoodFamily.DAIRY_CHEESE, "fromage", "emmental"))),
        MenuRecipe("gratin_salade", "Gratin & salade", DriveFoodFamily.READY_MEALS, true,
            listOf(n("Gratin", DriveFoodFamily.READY_MEALS, "gratin"), n("Salade", DriveFoodFamily.VEGETABLES, "laitue", "salade")))
    )
}
