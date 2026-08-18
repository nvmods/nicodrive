package com.example.nicobudget.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.SharedPreferences
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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

private const val MENU_PREFS_V3 = "drive_menu_planner_v2"
private const val MENU_FAMILY_PREFS_V3 = "drive_food_family_overrides"
private const val SLOT_COUNT = 14

private enum class MenuPlanModeV3(val label: String) {
    HABITS("Habitudes"),
    VARIED("Varié"),
    ECONOMICAL("Économique"),
    QUICK("Rapide")
}

private enum class MealMoment(val label: String) {
    LUNCH("Midi"),
    DINNER("Soir")
}

private data class MenuNeedV3(
    val label: String,
    val family: DriveFoodFamily,
    val keywords: List<String> = emptyList(),
    val unitsForTwo: Double = 1.0
)

private data class MenuRecipeV3(
    val id: String,
    val name: String,
    val primary: DriveFoodFamily,
    val quick: Boolean = false,
    val needs: List<MenuNeedV3>
)

private data class ResolvedRecipeV3(
    val source: MenuRecipeV3,
    val name: String,
    val needs: List<MenuNeedV3>,
    val substitutions: List<String>
) {
    val id: String get() = source.id
    val primary: DriveFoodFamily get() = source.primary
    val quick: Boolean get() = source.quick
}

private data class MenuCatalogProductV3(
    val key: String,
    val label: String,
    val family: DriveFoodFamily,
    val orders: Int,
    val lastDate: String,
    val recentUnitPrice: Double
)

private data class MenuShoppingItemV3(
    val key: String,
    val label: String,
    val family: DriveFoodFamily,
    val quantity: Int,
    val unitPrice: Double?,
    val generic: Boolean
) {
    val total: Double? get() = unitPrice?.times(quantity)
}

private data class IngredientRuleV3(
    val id: String,
    val label: String,
    val aliases: List<String>,
    val alternatives: List<MenuNeedV3>
)

@Composable
fun DriveMenuPlannerDialog(
    lines: List<DriveFoodAnalysisLine>,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences(MENU_PREFS_V3, Context.MODE_PRIVATE) }
    val familyPrefs = remember { context.getSharedPreferences(MENU_FAMILY_PREFS_V3, Context.MODE_PRIVATE) }
    val recipes = remember { menuRecipesV3() }
    val rules = remember { ingredientRulesV3() }
    val catalog = remember(lines) { buildMenuCatalogV3(lines, familyPrefs) }
    val affinity = remember(lines) { buildFamilyAffinityV3(lines, familyPrefs) }

    val oldDinnerDefault = remember { prefs.getInt("servings", 2).coerceIn(1, 8) }

    var mode by remember {
        mutableStateOf(
            prefs.getString("mode_v3", prefs.getString("mode", null))
                ?.let { runCatching { MenuPlanModeV3.valueOf(it) }.getOrNull() }
                ?: MenuPlanModeV3.VARIED
        )
    }
    var planIds by remember { mutableStateOf(loadPlanIdsV3(prefs, recipes)) }
    var servings by remember { mutableStateOf(loadServingsV3(prefs, oldDinnerDefault)) }
    var locked by remember { mutableStateOf(loadLockedV3(prefs)) }
    var excluded by remember { mutableStateOf(loadExcludedV3(prefs)) }
    var showFoodPrefs by remember { mutableStateOf(false) }
    var draftExcluded by remember { mutableStateOf(excluded) }
    var confirmedMessage by remember { mutableStateOf<String?>(null) }
    val checked = remember { mutableStateMapOf<String, Boolean>() }

    val recentIds = remember {
        prefs.getString("recent", "").orEmpty().split('|').filter { it.isNotBlank() }
    }

    fun regenerateAll() {
        planIds = generateMenuPlanV3(
            recipes = recipes,
            existing = planIds,
            locked = locked,
            mode = mode,
            affinity = affinity,
            catalog = catalog,
            servings = servings,
            excluded = excluded,
            rules = rules,
            recentIds = recentIds
        )
        confirmedMessage = null
        checked.clear()
    }

    fun regenerateOne(index: Int) {
        planIds = regenerateMenuSlotV3(
            index = index,
            current = planIds,
            recipes = recipes,
            mode = mode,
            affinity = affinity,
            catalog = catalog,
            servings = servings,
            excluded = excluded,
            rules = rules,
            recentIds = recentIds
        )
        confirmedMessage = null
        checked.clear()
    }

    fun setServings(index: Int, value: Int) {
        servings = servings.toMutableList().also { list ->
            while (list.size < SLOT_COUNT) list.add(if (list.size % 2 == 0) 1 else oldDinnerDefault)
            list[index] = value.coerceIn(0, 8)
        }.toList()
        if (value == 0) locked = locked - index
        confirmedMessage = null
        checked.clear()
    }

    LaunchedEffect(catalog.size) {
        if (catalog.isNotEmpty() && planIds.size != SLOT_COUNT) regenerateAll()
    }

    LaunchedEffect(planIds, locked, mode, servings, excluded) {
        prefs.edit()
            .putString("plan_v3", planIds.joinToString("|"))
            .putString("locked_v3", locked.sorted().joinToString(","))
            .putString("mode_v3", mode.name)
            .putString("servings_v3", servings.joinToString(","))
            .putStringSet("excluded_v3", HashSet(excluded))
            .apply()
    }

    val resolvedSlots = remember(planIds, servings, excluded, rules) {
        (0 until SLOT_COUNT).map { index ->
            if (servings.getOrElse(index) { 0 } <= 0) null
            else planIds.getOrNull(index)
                ?.let { id -> recipes.firstOrNull { it.id == id } }
                ?.let { recipe -> resolveRecipeV3(recipe, excluded, rules) }
        }
    }

    val shopping = remember(planIds, servings, mode, catalog, excluded) {
        val meals = resolvedSlots.mapIndexedNotNull { index, recipe ->
            recipe?.let { it to servings.getOrElse(index) { 0 } }
        }
        buildShoppingListV3(meals, catalog, mode)
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
                            "7 jours · midi + soir · quantités adaptées aux convives",
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
                        items(MenuPlanModeV3.entries) { item ->
                            FilterChip(
                                selected = mode == item,
                                onClick = { mode = item },
                                label = { Text(item.label) }
                            )
                        }
                    }
                    Text(
                        when (mode) {
                            MenuPlanModeV3.HABITS -> "Privilégie ce que vous achetez régulièrement."
                            MenuPlanModeV3.VARIED -> "Varie protéines et accompagnements, tout en restant proche de vos habitudes."
                            MenuPlanModeV3.ECONOMICAL -> "Favorise les recettes composées de références récemment peu coûteuses."
                            MenuPlanModeV3.QUICK -> "Favorise les repas simples ; le midi à 1 ou 2 personnes reçoit aussi un bonus rapide."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    OutlinedButton(
                        onClick = {
                            draftExcluded = excluded
                            showFoodPrefs = true
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            if (excluded.isEmpty()) "Incompatibilités / aliments à éviter"
                            else "Incompatibilités (${excluded.size})"
                        )
                    }
                    if (excluded.isNotEmpty()) {
                        Text(
                            "Les ingrédients exclus sont remplacés automatiquement par un accompagnement compatible quand la recette le permet.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Button(
                        onClick = { regenerateAll() },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = catalog.isNotEmpty()
                    ) {
                        Text(if (locked.isEmpty()) "Générer midi + soir" else "Régénérer les repas non verrouillés")
                    }

                    Text(
                        "Chaque créneau a son nombre de convives : 0 = aucun repas prévu, jusqu'à 8 personnes. Par défaut : midi 1, soir $oldDinnerDefault.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    if (catalog.isEmpty()) {
                        Text("Pas assez d'historique Drive pour générer les menus.")
                    } else if (planIds.size == SLOT_COUNT) {
                        Text("Menus proposés", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)

                        (0 until 7).forEach { dayOffset ->
                            val day = LocalDate.now().plusDays(dayOffset.toLong())
                            Text(
                                day.format(formatter).replaceFirstChar { it.uppercase() },
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold
                            )

                            MealMoment.entries.forEach { moment ->
                                val index = dayOffset * 2 + if (moment == MealMoment.LUNCH) 0 else 1
                                val people = servings.getOrElse(index) { if (moment == MealMoment.LUNCH) 1 else oldDinnerDefault }
                                val resolved = resolvedSlots.getOrNull(index)
                                val estimate = resolved?.let { estimateRecipeCostV3(it, people, catalog, mode) }

                                Surface(
                                    modifier = Modifier.fillMaxWidth(),
                                    tonalElevation = 1.dp,
                                    shape = MaterialTheme.shapes.medium
                                ) {
                                    Column(Modifier.padding(12.dp)) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                moment.label,
                                                fontWeight = FontWeight.Bold,
                                                modifier = Modifier.weight(1f)
                                            )
                                            TextButton(onClick = { setServings(index, people - 1) }) { Text("−") }
                                            Text(
                                                if (people == 0) "aucun" else "$people pers",
                                                style = MaterialTheme.typography.labelMedium,
                                                fontWeight = FontWeight.SemiBold
                                            )
                                            TextButton(onClick = { setServings(index, people + 1) }) { Text("+") }
                                        }

                                        if (people == 0) {
                                            Text(
                                                "Pas de menu prévu pour ce créneau.",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        } else if (resolved != null) {
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Column(Modifier.weight(1f)) {
                                                    Text(resolved.name, fontWeight = FontWeight.SemiBold)
                                                    Text(
                                                        buildString {
                                                            append(resolved.primary.label)
                                                            if (resolved.quick) append(" · rapide")
                                                            if (estimate != null) append(" · part estimée ~${estimate.eur()}")
                                                        },
                                                        style = MaterialTheme.typography.labelSmall,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                                    )
                                                    if (resolved.substitutions.isNotEmpty()) {
                                                        Text(
                                                            "Adapté : ${resolved.substitutions.joinToString(" · ")}",
                                                            style = MaterialTheme.typography.labelSmall,
                                                            color = MaterialTheme.colorScheme.primary
                                                        )
                                                    }
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
                                        } else {
                                            Text("Aucune recette compatible : régénère ce créneau.")
                                        }
                                    }
                                }
                            }
                        }

                        OutlinedButton(
                            onClick = {
                                val activeIds = planIds.filterIndexed { index, _ -> servings.getOrElse(index) { 0 } > 0 }
                                val old = prefs.getString("recent", "").orEmpty()
                                    .split('|').filter { it.isNotBlank() }
                                val merged = (activeIds + old).distinct().take(40)
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
                            "Les besoins de tous les midis et soirs actifs sont cumulés avant l'arrondi aux conditionnements. Les prix utilisent la médiane des 3 derniers prix observés.",
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
                                    Text(item.label, fontWeight = if (isChecked) FontWeight.Normal else FontWeight.SemiBold)
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
                                        item.total?.let { append(" · ${it.eur()}") } ?: append(" · ?")
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

    if (showFoodPrefs) {
        AlertDialog(
            onDismissRequest = { showFoodPrefs = false },
            title = { Text("Aliments à éviter") },
            text = {
                Column(
                    Modifier
                        .heightIn(max = 520.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    Text(
                        "Coche uniquement les ingrédients qui posent problème. Le générateur les remplacera dans les recettes par une alternative compatible.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(Modifier.height(8.dp))
                    rules.forEach { rule ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = rule.id in draftExcluded,
                                onCheckedChange = { checkedValue ->
                                    draftExcluded = if (checkedValue) draftExcluded + rule.id else draftExcluded - rule.id
                                }
                            )
                            Column {
                                Text(rule.label, fontWeight = FontWeight.SemiBold)
                                Text(
                                    "Alternatives : ${rule.alternatives.joinToString { it.label }}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        excluded = draftExcluded
                        showFoodPrefs = false
                        locked = emptySet()
                        regenerateAll()
                    }
                ) { Text("Appliquer") }
            },
            dismissButton = {
                TextButton(onClick = { showFoodPrefs = false }) { Text("Annuler") }
            }
        )
    }
}

private fun defaultUnitsForTwoV3(family: DriveFoodFamily): Double = when (family) {
    DriveFoodFamily.POULTRY,
    DriveFoodFamily.BEEF,
    DriveFoodFamily.PORK,
    DriveFoodFamily.FISH -> 0.65
    DriveFoodFamily.EGGS -> 0.50
    DriveFoodFamily.POTATOES -> 0.45
    DriveFoodFamily.STARCHES -> 0.30
    DriveFoodFamily.VEGETABLES -> 0.45
    DriveFoodFamily.PIZZA_QUICHE,
    DriveFoodFamily.READY_MEALS,
    DriveFoodFamily.SANDWICH_SALAD -> 1.00
    DriveFoodFamily.BREAD -> 0.50
    DriveFoodFamily.DAIRY_CHEESE -> 0.25
    DriveFoodFamily.CONDIMENTS -> 0.10
    DriveFoodFamily.OTHER_MEAL -> 0.30
    DriveFoodFamily.OTHER_CORE -> 0.50
    else -> 0.50
}

private fun ingredientRulesV3(): List<IngredientRuleV3> {
    fun a(label: String, family: DriveFoodFamily, vararg keywords: String) =
        MenuNeedV3(label, family, keywords.toList(), defaultUnitsForTwoV3(family))

    return listOf(
        IngredientRuleV3("courgettes", "Courgettes", listOf("courgette"), listOf(
            a("Haricots verts", DriveFoodFamily.VEGETABLES, "haricot vert"),
            a("Carottes", DriveFoodFamily.VEGETABLES, "carotte"),
            a("Salade", DriveFoodFamily.VEGETABLES, "laitue", "salade")
        )),
        IngredientRuleV3("brocoli", "Brocoli", listOf("brocoli"), listOf(
            a("Haricots verts", DriveFoodFamily.VEGETABLES, "haricot vert"),
            a("Carottes", DriveFoodFamily.VEGETABLES, "carotte"),
            a("Courgettes", DriveFoodFamily.VEGETABLES, "courgette")
        )),
        IngredientRuleV3("haricots", "Haricots verts", listOf("haricot vert"), listOf(
            a("Courgettes", DriveFoodFamily.VEGETABLES, "courgette"),
            a("Carottes", DriveFoodFamily.VEGETABLES, "carotte"),
            a("Salade", DriveFoodFamily.VEGETABLES, "laitue", "salade")
        )),
        IngredientRuleV3("carottes", "Carottes", listOf("carotte"), listOf(
            a("Courgettes", DriveFoodFamily.VEGETABLES, "courgette"),
            a("Haricots verts", DriveFoodFamily.VEGETABLES, "haricot vert"),
            a("Salade", DriveFoodFamily.VEGETABLES, "laitue", "salade")
        )),
        IngredientRuleV3("salade", "Salade", listOf("salade", "laitue"), listOf(
            a("Haricots verts", DriveFoodFamily.VEGETABLES, "haricot vert"),
            a("Carottes", DriveFoodFamily.VEGETABLES, "carotte"),
            a("Courgettes", DriveFoodFamily.VEGETABLES, "courgette")
        )),
        IngredientRuleV3("lentilles", "Lentilles", listOf("lentille"), listOf(
            a("Riz", DriveFoodFamily.STARCHES, "riz"),
            a("Pommes de terre", DriveFoodFamily.POTATOES, "pomme de terre", "pommes de terre")
        )),
        IngredientRuleV3("frites", "Frites", listOf("frites"), listOf(
            a("Pommes de terre", DriveFoodFamily.POTATOES, "pomme de terre", "pommes de terre"),
            a("Riz", DriveFoodFamily.STARCHES, "riz"),
            a("Pâtes", DriveFoodFamily.STARCHES, "pates", "spaghetti", "macaroni")
        )),
        IngredientRuleV3("riz", "Riz", listOf("riz"), listOf(
            a("Pâtes", DriveFoodFamily.STARCHES, "pates", "spaghetti", "macaroni"),
            a("Pommes de terre", DriveFoodFamily.POTATOES, "pomme de terre", "pommes de terre")
        )),
        IngredientRuleV3("tomate", "Tomate / sauce tomate", listOf("tomate"), listOf(
            a("Courgettes", DriveFoodFamily.VEGETABLES, "courgette"),
            a("Carottes", DriveFoodFamily.VEGETABLES, "carotte")
        ))
    )
}

private fun resolveRecipeV3(
    recipe: MenuRecipeV3,
    excluded: Set<String>,
    rules: List<IngredientRuleV3>
): ResolvedRecipeV3? {
    var name = recipe.name
    val needs = mutableListOf<MenuNeedV3>()
    val substitutions = mutableListOf<String>()

    recipe.needs.forEachIndexed { position, need ->
        val rule = rules.firstOrNull { it.id in excluded && needMatchesRuleV3(need, it) }
        if (rule == null) {
            needs += need
        } else {
            val available = rule.alternatives.filter { alt ->
                rules.none { other -> other.id in excluded && needMatchesRuleV3(alt, other) }
            }
            if (available.isEmpty()) return null
            val altIndex = stableIndexV3("${recipe.id}:${rule.id}:$position", available.size)
            val replacementBase = available[altIndex]
            val replacement = replacementBase.copy(unitsForTwo = need.unitsForTwo)
            needs += replacement
            name = name.replace(need.label, replacement.label, ignoreCase = true)
            substitutions += "${need.label} → ${replacement.label}"
        }
    }
    return ResolvedRecipeV3(recipe, name, needs, substitutions)
}

private fun needMatchesRuleV3(need: MenuNeedV3, rule: IngredientRuleV3): Boolean {
    val needKey = DriveProductNormalizer.key(need.label)
    return rule.aliases.any { alias ->
        val aliasKey = DriveProductNormalizer.key(alias)
        needKey.contains(aliasKey) || aliasKey.contains(needKey)
    }
}

private fun stableIndexV3(value: String, size: Int): Int {
    if (size <= 1) return 0
    var acc = 7
    value.forEach { ch -> acc = (acc * 31 + ch.code) and 0x7fffffff }
    return acc % size
}

private fun buildMenuCatalogV3(
    lines: List<DriveFoodAnalysisLine>,
    familyPrefs: SharedPreferences
): List<MenuCatalogProductV3> {
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
        MenuCatalogProductV3(
            key = key,
            label = label,
            family = group.first().family,
            orders = group.asSequence().map { it.line.orderRowId }.distinct().count(),
            lastDate = group.maxOf { it.line.date },
            recentUnitPrice = medianV3(observations)
        )
    }.filter { it.recentUnitPrice > 0.0 }
}

private fun buildFamilyAffinityV3(
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

private fun chooseProductV3(
    need: MenuNeedV3,
    catalog: List<MenuCatalogProductV3>,
    mode: MenuPlanModeV3
): MenuCatalogProductV3? {
    val normalizedKeywords = need.keywords.map { DriveProductNormalizer.key(it) }
    val candidates = catalog.filter { product ->
        product.family == need.family &&
            (normalizedKeywords.isEmpty() || normalizedKeywords.any { product.key.contains(it) })
    }
    if (candidates.isEmpty()) return null

    return when (mode) {
        MenuPlanModeV3.ECONOMICAL -> candidates.sortedWith(
            compareBy<MenuCatalogProductV3> { it.recentUnitPrice }
                .thenByDescending { it.orders }
                .thenByDescending { it.lastDate }
        ).first()
        else -> candidates.sortedWith(
            compareByDescending<MenuCatalogProductV3> { it.orders }
                .thenByDescending { it.lastDate }
                .thenBy { it.recentUnitPrice }
        ).first()
    }
}

private fun estimateRecipeCostV3(
    recipe: ResolvedRecipeV3,
    servings: Int,
    catalog: List<MenuCatalogProductV3>,
    mode: MenuPlanModeV3
): Double? {
    if (servings <= 0) return null
    var known = 0.0
    var found = 0
    recipe.needs.forEach { need ->
        val product = chooseProductV3(need, catalog, mode) ?: return@forEach
        val q = need.unitsForTwo * servings / 2.0
        known += product.recentUnitPrice * q
        found++
    }
    return known.takeIf { found > 0 }
}

private fun generateMenuPlanV3(
    recipes: List<MenuRecipeV3>,
    existing: List<String>,
    locked: Set<Int>,
    mode: MenuPlanModeV3,
    affinity: Map<DriveFoodFamily, Double>,
    catalog: List<MenuCatalogProductV3>,
    servings: List<Int>,
    excluded: Set<String>,
    rules: List<IngredientRuleV3>,
    recentIds: List<String>
): List<String> {
    val result = MutableList(SLOT_COUNT) { index -> existing.getOrNull(index).orEmpty() }
    val used = mutableListOf<ResolvedRecipeV3>()
    val rng = Random.Default

    for (index in 0 until SLOT_COUNT) {
        if (servings.getOrElse(index) { 0 } <= 0) continue
        val existingRecipe = recipes.firstOrNull { it.id == result[index] }
        if (index in locked && existingRecipe != null) {
            resolveRecipeV3(existingRecipe, excluded, rules)?.let { used += it }
            continue
        }

        val moment = if (index % 2 == 0) MealMoment.LUNCH else MealMoment.DINNER
        val candidates = recipes.mapNotNull { recipe ->
            resolveRecipeV3(recipe, excluded, rules)?.let { resolved ->
                resolved to recipeScoreV3(
                    recipe = resolved,
                    used = used,
                    mode = mode,
                    affinity = affinity,
                    catalog = catalog,
                    servings = servings.getOrElse(index) { 1 },
                    recentIds = recentIds,
                    moment = moment,
                    rng = rng
                )
            }
        }
        val chosen = candidates.maxByOrNull { it.second }?.first ?: continue
        result[index] = chosen.id
        used += chosen
    }
    return result
}

private fun regenerateMenuSlotV3(
    index: Int,
    current: List<String>,
    recipes: List<MenuRecipeV3>,
    mode: MenuPlanModeV3,
    affinity: Map<DriveFoodFamily, Double>,
    catalog: List<MenuCatalogProductV3>,
    servings: List<Int>,
    excluded: Set<String>,
    rules: List<IngredientRuleV3>,
    recentIds: List<String>
): List<String> {
    if (index !in 0 until SLOT_COUNT || servings.getOrElse(index) { 0 } <= 0) return current
    val used = current.mapIndexedNotNull { slot, id ->
        if (slot == index || servings.getOrElse(slot) { 0 } <= 0) null
        else recipes.firstOrNull { it.id == id }?.let { resolveRecipeV3(it, excluded, rules) }
    }
    val currentId = current.getOrNull(index)
    val moment = if (index % 2 == 0) MealMoment.LUNCH else MealMoment.DINNER
    val rng = Random.Default
    val chosen = recipes
        .filter { it.id != currentId }
        .mapNotNull { recipe ->
            resolveRecipeV3(recipe, excluded, rules)?.let { resolved ->
                resolved to recipeScoreV3(
                    resolved,
                    used,
                    mode,
                    affinity,
                    catalog,
                    servings.getOrElse(index) { 1 },
                    recentIds,
                    moment,
                    rng
                )
            }
        }
        .maxByOrNull { it.second }?.first ?: return current

    return current.toMutableList().also {
        while (it.size < SLOT_COUNT) it.add("")
        it[index] = chosen.id
    }
}

private fun recipeScoreV3(
    recipe: ResolvedRecipeV3,
    used: List<ResolvedRecipeV3>,
    mode: MenuPlanModeV3,
    affinity: Map<DriveFoodFamily, Double>,
    catalog: List<MenuCatalogProductV3>,
    servings: Int,
    recentIds: List<String>,
    moment: MealMoment,
    rng: Random
): Double {
    if (used.any { it.id == recipe.id }) return -1000.0
    val familyAffinity = affinity[recipe.primary] ?: 0.0
    val repeatedFamily = used.count { it.primary == recipe.primary }
    val adjacentSame = used.lastOrNull()?.primary == recipe.primary
    val usedNeedKeys = used.flatMap { r -> r.needs.map { DriveProductNormalizer.key(it.label) } }
    val repeatedIngredients = recipe.needs.sumOf { need ->
        usedNeedKeys.count { it == DriveProductNormalizer.key(need.label) }
    }
    val starchFamilies = setOf(DriveFoodFamily.POTATOES, DriveFoodFamily.STARCHES)
    val usedStarches = used.flatMap { r -> r.needs.map { it.family }.filter { it in starchFamilies } }
    val repeatedStarch = recipe.needs.filter { it.family in starchFamilies }
        .sumOf { need -> usedStarches.count { it == need.family } }
    val cost = estimateRecipeCostV3(recipe, servings, catalog, mode) ?: 12.0

    var score = when (mode) {
        MenuPlanModeV3.HABITS -> familyAffinity * 18.0 - repeatedFamily * 3.5 - repeatedIngredients * 1.2
        MenuPlanModeV3.VARIED -> familyAffinity * 8.0 - repeatedFamily * 8.0 - repeatedIngredients * 4.0 - repeatedStarch * 2.5
        MenuPlanModeV3.ECONOMICAL -> familyAffinity * 7.0 - cost * 0.65 - repeatedFamily * 5.0 - repeatedIngredients * 1.8
        MenuPlanModeV3.QUICK -> familyAffinity * 6.0 + (if (recipe.quick) 12.0 else -3.0) - repeatedFamily * 5.0 - repeatedIngredients * 2.0
    }
    if (moment == MealMoment.LUNCH && servings <= 2 && recipe.quick) score += 4.0
    if (adjacentSame) score -= 8.0
    if (recipe.id in recentIds) score -= 7.0
    score += rng.nextDouble(0.0, 3.5)
    return score
}

private fun buildShoppingListV3(
    meals: List<Pair<ResolvedRecipeV3, Int>>,
    catalog: List<MenuCatalogProductV3>,
    mode: MenuPlanModeV3
): List<MenuShoppingItemV3> {
    data class MutableItem(
        val key: String,
        val label: String,
        val family: DriveFoodFamily,
        var quantity: Double,
        val unitPrice: Double?,
        val generic: Boolean
    )

    val map = linkedMapOf<String, MutableItem>()
    meals.forEach { (recipe, people) ->
        if (people <= 0) return@forEach
        recipe.needs.forEach { need ->
            val fraction = need.unitsForTwo * people / 2.0
            val product = chooseProductV3(need, catalog, mode)
            val key = product?.key ?: "generic:${need.family.name}:${DriveProductNormalizer.key(need.label)}"
            val existing = map[key]
            if (existing != null) {
                existing.quantity += fraction
            } else {
                map[key] = MutableItem(
                    key = key,
                    label = product?.label ?: need.label,
                    family = need.family,
                    quantity = fraction,
                    unitPrice = product?.recentUnitPrice,
                    generic = product == null
                )
            }
        }
    }

    return map.values.map { item ->
        val packs = ceil(item.quantity).toInt().coerceAtLeast(1)
        MenuShoppingItemV3(item.key, item.label, item.family, packs, item.unitPrice, item.generic)
    }.sortedWith(compareBy<MenuShoppingItemV3> { it.family.label }.thenBy { it.label })
}

private fun loadPlanIdsV3(prefs: SharedPreferences, recipes: List<MenuRecipeV3>): List<String> {
    val valid = recipes.map { it.id }.toSet()
    val stored = prefs.getString("plan_v3", "").orEmpty().split('|')
    return stored.takeIf { it.size == SLOT_COUNT && it.all { id -> id.isBlank() || id in valid } } ?: emptyList()
}

private fun loadServingsV3(prefs: SharedPreferences, dinnerDefault: Int): List<Int> {
    val stored = prefs.getString("servings_v3", "").orEmpty().split(',').mapNotNull { it.toIntOrNull() }
    if (stored.size == SLOT_COUNT) return stored.map { it.coerceIn(0, 8) }
    return List(SLOT_COUNT) { index -> if (index % 2 == 0) 1 else dinnerDefault }
}

private fun loadLockedV3(prefs: SharedPreferences): Set<Int> =
    prefs.getString("locked_v3", "").orEmpty().split(',').mapNotNull { it.toIntOrNull() }
        .filter { it in 0 until SLOT_COUNT }.toSet()

private fun loadExcludedV3(prefs: SharedPreferences): Set<String> =
    prefs.getStringSet("excluded_v3", emptySet())?.toSet().orEmpty()

private fun medianV3(values: List<Double>): Double {
    if (values.isEmpty()) return 0.0
    val sorted = values.sorted()
    return if (sorted.size % 2 == 1) sorted[sorted.size / 2]
    else (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2]) / 2.0
}

private fun menuRecipesV3(): List<MenuRecipeV3> {
    fun n(label: String, family: DriveFoodFamily, vararg keywords: String, units: Double = -1.0) =
        MenuNeedV3(label, family, keywords.toList(), if (units > 0.0) units else defaultUnitsForTwoV3(family))

    return listOf(
        MenuRecipeV3("poulet_haricots_pdt", "Poulet, haricots verts & pommes de terre", DriveFoodFamily.POULTRY,
            needs = listOf(n("Poulet", DriveFoodFamily.POULTRY, "poulet"), n("Haricots verts", DriveFoodFamily.VEGETABLES, "haricot vert"), n("Pommes de terre", DriveFoodFamily.POTATOES, "pomme de terre", "pommes de terre"))),
        MenuRecipeV3("poulet_riz_courgettes", "Poulet, riz & courgettes", DriveFoodFamily.POULTRY,
            needs = listOf(n("Poulet", DriveFoodFamily.POULTRY, "poulet"), n("Riz", DriveFoodFamily.STARCHES, "riz"), n("Courgettes", DriveFoodFamily.VEGETABLES, "courgette"))),
        MenuRecipeV3("cordon_puree_carottes", "Cordon bleu, purée & carottes", DriveFoodFamily.POULTRY, true,
            listOf(n("Cordon bleu", DriveFoodFamily.POULTRY, "cordon bleu"), n("Pommes de terre", DriveFoodFamily.POTATOES, "pomme de terre", "puree"), n("Carottes", DriveFoodFamily.VEGETABLES, "carotte"))),
        MenuRecipeV3("nuggets_frites_haricots", "Nuggets, frites & haricots verts", DriveFoodFamily.POULTRY, true,
            listOf(n("Nuggets", DriveFoodFamily.POULTRY, "nugget"), n("Frites", DriveFoodFamily.POTATOES, "frites"), n("Haricots verts", DriveFoodFamily.VEGETABLES, "haricot vert"))),
        MenuRecipeV3("poulet_pates_brocoli", "Poulet, pâtes & brocoli", DriveFoodFamily.POULTRY,
            needs = listOf(n("Poulet", DriveFoodFamily.POULTRY, "poulet"), n("Pâtes", DriveFoodFamily.STARCHES, "pates", "macaroni"), n("Brocoli", DriveFoodFamily.VEGETABLES, "brocoli"))),

        MenuRecipeV3("steak_haricots_pdt", "Steak haché, haricots verts & pommes de terre", DriveFoodFamily.BEEF,
            needs = listOf(n("Steak haché", DriveFoodFamily.BEEF, "steak hache"), n("Haricots verts", DriveFoodFamily.VEGETABLES, "haricot vert"), n("Pommes de terre", DriveFoodFamily.POTATOES, "pomme de terre"))),
        MenuRecipeV3("burger_frites_salade", "Burgers maison, frites & salade", DriveFoodFamily.BEEF,
            needs = listOf(n("Steak haché", DriveFoodFamily.BEEF, "steak hache"), n("Pain burger", DriveFoodFamily.BREAD, "pain burger"), n("Fromage", DriveFoodFamily.DAIRY_CHEESE, "fromage", "emmental"), n("Frites", DriveFoodFamily.POTATOES, "frites"), n("Salade", DriveFoodFamily.VEGETABLES, "laitue", "salade"))),
        MenuRecipeV3("boulettes_spaghetti", "Boulettes de bœuf & spaghetti", DriveFoodFamily.BEEF,
            needs = listOf(n("Boulettes de bœuf", DriveFoodFamily.BEEF, "boulette", "boeuf", "viande hachee"), n("Spaghetti", DriveFoodFamily.STARCHES, "spaghetti"), n("Sauce tomate", DriveFoodFamily.CONDIMENTS, "tomate", "sauce tomate"))),
        MenuRecipeV3("bolognaise", "Spaghetti bolognaise", DriveFoodFamily.BEEF,
            needs = listOf(n("Bœuf haché", DriveFoodFamily.BEEF, "boeuf", "viande hachee", "steak hache"), n("Spaghetti", DriveFoodFamily.STARCHES, "spaghetti"), n("Sauce tomate", DriveFoodFamily.CONDIMENTS, "tomate", "sauce tomate"))),
        MenuRecipeV3("boeuf_riz_carottes", "Bœuf, riz & carottes", DriveFoodFamily.BEEF,
            needs = listOf(n("Bœuf", DriveFoodFamily.BEEF, "boeuf", "steak"), n("Riz", DriveFoodFamily.STARCHES, "riz"), n("Carottes", DriveFoodFamily.VEGETABLES, "carotte"))),

        MenuRecipeV3("saucisses_lentilles", "Saucisses & lentilles", DriveFoodFamily.PORK,
            needs = listOf(n("Saucisses", DriveFoodFamily.PORK, "saucisse", "chipolata"), n("Lentilles", DriveFoodFamily.OTHER_MEAL, "lentille"), n("Carottes", DriveFoodFamily.VEGETABLES, "carotte"))),
        MenuRecipeV3("chipolatas_frites_courgettes", "Chipolatas, frites & courgettes", DriveFoodFamily.PORK,
            needs = listOf(n("Chipolatas", DriveFoodFamily.PORK, "chipolata"), n("Frites", DriveFoodFamily.POTATOES, "frites"), n("Courgettes", DriveFoodFamily.VEGETABLES, "courgette"))),
        MenuRecipeV3("jambon_coquillettes", "Jambon, coquillettes & fromage", DriveFoodFamily.PORK, true,
            listOf(n("Jambon", DriveFoodFamily.PORK, "jambon"), n("Coquillettes", DriveFoodFamily.STARCHES, "coquillette", "macaroni", "pates"), n("Fromage", DriveFoodFamily.DAIRY_CHEESE, "emmental", "fromage"))),
        MenuRecipeV3("porc_pdt_carottes", "Porc, pommes de terre & carottes", DriveFoodFamily.PORK,
            needs = listOf(n("Porc", DriveFoodFamily.PORK, "porc", "roti de porc"), n("Pommes de terre", DriveFoodFamily.POTATOES, "pomme de terre"), n("Carottes", DriveFoodFamily.VEGETABLES, "carotte"))),
        MenuRecipeV3("croque_salade", "Croque-monsieur & salade", DriveFoodFamily.PORK, true,
            listOf(n("Jambon", DriveFoodFamily.PORK, "jambon"), n("Pain", DriveFoodFamily.BREAD, "pain de mie"), n("Fromage", DriveFoodFamily.DAIRY_CHEESE, "emmental", "fromage"), n("Salade", DriveFoodFamily.VEGETABLES, "salade", "laitue"))),

        MenuRecipeV3("saumon_riz_brocoli", "Saumon, riz & brocoli", DriveFoodFamily.FISH,
            needs = listOf(n("Saumon", DriveFoodFamily.FISH, "saumon"), n("Riz", DriveFoodFamily.STARCHES, "riz"), n("Brocoli", DriveFoodFamily.VEGETABLES, "brocoli"))),
        MenuRecipeV3("colin_puree_haricots", "Colin, purée & haricots verts", DriveFoodFamily.FISH,
            needs = listOf(n("Colin", DriveFoodFamily.FISH, "colin"), n("Pommes de terre", DriveFoodFamily.POTATOES, "pomme de terre", "puree"), n("Haricots verts", DriveFoodFamily.VEGETABLES, "haricot vert"))),
        MenuRecipeV3("thon_pates_tomate", "Pâtes au thon & tomate", DriveFoodFamily.FISH, true,
            listOf(n("Thon", DriveFoodFamily.FISH, "thon"), n("Pâtes", DriveFoodFamily.STARCHES, "pates", "macaroni", "spaghetti"), n("Sauce tomate", DriveFoodFamily.CONDIMENTS, "tomate", "sauce tomate"))),
        MenuRecipeV3("poisson_frites_legumes", "Poisson, frites & légumes", DriveFoodFamily.FISH, true,
            listOf(n("Poisson", DriveFoodFamily.FISH, "poisson", "colin", "cabillaud"), n("Frites", DriveFoodFamily.POTATOES, "frites"), n("Légumes", DriveFoodFamily.VEGETABLES))),
        MenuRecipeV3("surimi_riz_salade", "Salade de riz au surimi", DriveFoodFamily.FISH, true,
            listOf(n("Surimi", DriveFoodFamily.FISH, "surimi"), n("Riz", DriveFoodFamily.STARCHES, "riz"), n("Salade", DriveFoodFamily.VEGETABLES, "salade", "laitue"))),

        MenuRecipeV3("omelette_jambon_salade", "Omelette jambon-fromage & salade", DriveFoodFamily.EGGS, true,
            listOf(n("Œufs", DriveFoodFamily.EGGS, "oeuf"), n("Jambon", DriveFoodFamily.PORK, "jambon"), n("Fromage", DriveFoodFamily.DAIRY_CHEESE, "emmental", "fromage"), n("Salade", DriveFoodFamily.VEGETABLES, "salade", "laitue"))),
        MenuRecipeV3("oeufs_pdt_haricots", "Œufs, pommes de terre & haricots verts", DriveFoodFamily.EGGS, true,
            listOf(n("Œufs", DriveFoodFamily.EGGS, "oeuf"), n("Pommes de terre", DriveFoodFamily.POTATOES, "pomme de terre"), n("Haricots verts", DriveFoodFamily.VEGETABLES, "haricot vert"))),
        MenuRecipeV3("omelette_legumes", "Omelette aux légumes", DriveFoodFamily.EGGS, true,
            listOf(n("Œufs", DriveFoodFamily.EGGS, "oeuf"), n("Légumes", DriveFoodFamily.VEGETABLES), n("Fromage", DriveFoodFamily.DAIRY_CHEESE, "emmental", "fromage"))),

        MenuRecipeV3("pizza_salade", "Pizza & salade", DriveFoodFamily.PIZZA_QUICHE, true,
            listOf(n("Pizza", DriveFoodFamily.PIZZA_QUICHE, "pizza"), n("Salade", DriveFoodFamily.VEGETABLES, "salade", "laitue"))),
        MenuRecipeV3("quiche_salade", "Quiche & salade", DriveFoodFamily.PIZZA_QUICHE, true,
            listOf(n("Quiche", DriveFoodFamily.PIZZA_QUICHE, "quiche"), n("Salade", DriveFoodFamily.VEGETABLES, "salade", "laitue"))),
        MenuRecipeV3("tarte_poireaux", "Tarte aux poireaux & salade", DriveFoodFamily.PIZZA_QUICHE, true,
            listOf(n("Tarte aux poireaux", DriveFoodFamily.PIZZA_QUICHE, "tarte", "poireaux"), n("Salade", DriveFoodFamily.VEGETABLES, "salade", "laitue"))),

        MenuRecipeV3("lasagnes_salade", "Lasagnes & salade", DriveFoodFamily.READY_MEALS, true,
            listOf(n("Lasagnes", DriveFoodFamily.READY_MEALS, "lasagne"), n("Salade", DriveFoodFamily.VEGETABLES, "salade", "laitue"))),
        MenuRecipeV3("ravioli_legumes", "Ravioli & légumes", DriveFoodFamily.READY_MEALS, true,
            listOf(n("Ravioli", DriveFoodFamily.READY_MEALS, "ravioli"), n("Légumes", DriveFoodFamily.VEGETABLES))),
        MenuRecipeV3("gratin_salade", "Gratin & salade", DriveFoodFamily.READY_MEALS, true,
            listOf(n("Gratin", DriveFoodFamily.READY_MEALS, "gratin"), n("Salade", DriveFoodFamily.VEGETABLES, "salade", "laitue"))),
        MenuRecipeV3("paella_salade", "Paella & salade", DriveFoodFamily.READY_MEALS, true,
            listOf(n("Paella", DriveFoodFamily.READY_MEALS, "paella"), n("Salade", DriveFoodFamily.VEGETABLES, "salade", "laitue"))),
        MenuRecipeV3("couscous_legumes", "Couscous & légumes", DriveFoodFamily.READY_MEALS, true,
            listOf(n("Couscous", DriveFoodFamily.READY_MEALS, "couscous"), n("Légumes", DriveFoodFamily.VEGETABLES))),

        MenuRecipeV3("gnocchi_tomate_fromage", "Gnocchi tomate-fromage", DriveFoodFamily.STARCHES, true,
            listOf(n("Gnocchi", DriveFoodFamily.STARCHES, "gnocchi"), n("Sauce tomate", DriveFoodFamily.CONDIMENTS, "tomate", "sauce tomate"), n("Fromage", DriveFoodFamily.DAIRY_CHEESE, "emmental", "fromage"))),
        MenuRecipeV3("pates_fromage_legumes", "Pâtes, fromage & légumes", DriveFoodFamily.STARCHES, true,
            listOf(n("Pâtes", DriveFoodFamily.STARCHES, "pates", "macaroni"), n("Fromage", DriveFoodFamily.DAIRY_CHEESE, "emmental", "fromage"), n("Légumes", DriveFoodFamily.VEGETABLES)))
    )
}
